/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.replication;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.opensearch.BaseExceptionsHelper;
import org.opensearch.action.ActionListener;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.Nullable;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.CancellableThreads;
import org.opensearch.common.util.concurrent.ConcurrentCollections;
import org.opensearch.index.shard.IndexEventListener;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.index.shard.IndexShardState;
import org.opensearch.index.shard.ShardId;
import org.opensearch.indices.IndicesService;
import org.opensearch.indices.recovery.FileChunkRequest;
import org.opensearch.indices.recovery.ForceSyncRequest;
import org.opensearch.indices.recovery.RecoverySettings;
import org.opensearch.indices.recovery.RetryableTransportClient;
import org.opensearch.indices.replication.checkpoint.ReplicationCheckpoint;
import org.opensearch.indices.replication.common.ReplicationCollection;
import org.opensearch.indices.replication.common.ReplicationCollection.ReplicationRef;
import org.opensearch.indices.replication.common.ReplicationFailedException;
import org.opensearch.indices.replication.common.ReplicationListener;
import org.opensearch.indices.replication.common.ReplicationState;
import org.opensearch.tasks.Task;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportChannel;
import org.opensearch.transport.TransportRequestHandler;
import org.opensearch.transport.TransportRequestOptions;
import org.opensearch.transport.TransportResponse;
import org.opensearch.transport.TransportService;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.opensearch.indices.replication.SegmentReplicationSourceService.Actions.UPDATE_VISIBLE_CHECKPOINT;

/**
 * Service class that orchestrates replication events on replicas.
 *
 * @opensearch.internal
 */
public class SegmentReplicationTargetService implements IndexEventListener {

    private static final Logger logger = LogManager.getLogger(SegmentReplicationTargetService.class);

    private final ThreadPool threadPool;
    private final RecoverySettings recoverySettings;

    private final ReplicationCollection<SegmentReplicationTarget> onGoingReplications;

    private final Map<ShardId, SegmentReplicationTarget> completedReplications = ConcurrentCollections.newConcurrentMap();

    private final SegmentReplicationSourceFactory sourceFactory;

    protected final Map<ShardId, ReplicationCheckpoint> latestReceivedCheckpoint = ConcurrentCollections.newConcurrentMap();

    private final IndicesService indicesService;
    private final ClusterService clusterService;
    private final TransportService transportService;

    public ReplicationRef<SegmentReplicationTarget> get(long replicationId) {
        return onGoingReplications.get(replicationId);
    }

    /**
     * The internal actions
     *
     * @opensearch.internal
     */
    public static class Actions {
        public static final String FILE_CHUNK = "internal:index/shard/replication/file_chunk";
        public static final String FORCE_SYNC = "internal:index/shard/replication/segments_sync";
    }

    public SegmentReplicationTargetService(
        final ThreadPool threadPool,
        final RecoverySettings recoverySettings,
        final TransportService transportService,
        final SegmentReplicationSourceFactory sourceFactory,
        final IndicesService indicesService,
        final ClusterService clusterService
    ) {
        this(
            threadPool,
            recoverySettings,
            transportService,
            sourceFactory,
            indicesService,
            clusterService,
            new ReplicationCollection<>(logger, threadPool)
        );
    }

    public SegmentReplicationTargetService(
        final ThreadPool threadPool,
        final RecoverySettings recoverySettings,
        final TransportService transportService,
        final SegmentReplicationSourceFactory sourceFactory,
        final IndicesService indicesService,
        final ClusterService clusterService,
        final ReplicationCollection<SegmentReplicationTarget> ongoingSegmentReplications
    ) {
        this.threadPool = threadPool;
        this.recoverySettings = recoverySettings;
        this.onGoingReplications = ongoingSegmentReplications;
        this.sourceFactory = sourceFactory;
        this.indicesService = indicesService;
        this.clusterService = clusterService;
        this.transportService = transportService;

        transportService.registerRequestHandler(
            Actions.FILE_CHUNK,
            ThreadPool.Names.GENERIC,
            FileChunkRequest::new,
            new FileChunkTransportRequestHandler()
        );
        transportService.registerRequestHandler(
            Actions.FORCE_SYNC,
            ThreadPool.Names.GENERIC,
            ForceSyncRequest::new,
            new ForceSyncTransportRequestHandler()
        );
    }

    /**
     * Cancel any replications on this node for a replica that is about to be closed.
     */
    @Override
    public void beforeIndexShardClosed(ShardId shardId, @Nullable IndexShard indexShard, Settings indexSettings) {
        if (indexShard != null && indexShard.indexSettings().isSegRepEnabled()) {
            onGoingReplications.cancelForShard(shardId, "shard closed");
            latestReceivedCheckpoint.remove(shardId);
        }
    }

    /**
     * Replay any received checkpoint while replica was recovering.  This does not need to happen
     * for primary relocations because they recover from translog.
     */
    @Override
    public void afterIndexShardStarted(IndexShard indexShard) {
        if (indexShard.indexSettings().isSegRepEnabled() && indexShard.routingEntry().primary() == false) {
            processLatestReceivedCheckpoint(indexShard, Thread.currentThread());
        }
    }

    /**
     * Cancel any replications on this node for a replica that has just been promoted as the new primary.
     */
    @Override
    public void shardRoutingChanged(IndexShard indexShard, @Nullable ShardRouting oldRouting, ShardRouting newRouting) {
        if (oldRouting != null && indexShard.indexSettings().isSegRepEnabled() && oldRouting.primary() == false && newRouting.primary()) {
            onGoingReplications.cancelForShard(indexShard.shardId(), "shard has been promoted to primary");
            latestReceivedCheckpoint.remove(indexShard.shardId());
        }
    }

    /**
     * returns SegmentReplicationState of on-going segment replication events.
     */
    @Nullable
    public SegmentReplicationState getOngoingEventSegmentReplicationState(ShardId shardId) {
        return Optional.ofNullable(onGoingReplications.getOngoingReplicationTarget(shardId))
            .map(SegmentReplicationTarget::state)
            .orElse(null);
    }

    /**
     * returns SegmentReplicationState of latest completed segment replication events.
     */
    @Nullable
    public SegmentReplicationState getlatestCompletedEventSegmentReplicationState(ShardId shardId) {
        return Optional.ofNullable(completedReplications.get(shardId)).map(SegmentReplicationTarget::state).orElse(null);
    }

    /**
     * returns SegmentReplicationState of on-going if present or completed segment replication events.
     */
    @Nullable
    public SegmentReplicationState getSegmentReplicationState(ShardId shardId) {
        return Optional.ofNullable(getOngoingEventSegmentReplicationState(shardId))
            .orElseGet(() -> getlatestCompletedEventSegmentReplicationState(shardId));
    }

    /**
     * Invoked when a new checkpoint is received from a primary shard.
     * It checks if a new checkpoint should be processed or not and starts replication if needed.
     *
     * @param receivedCheckpoint received checkpoint that is checked for processing
     * @param replicaShard       replica shard on which checkpoint is received
     */
    public synchronized void onNewCheckpoint(final ReplicationCheckpoint receivedCheckpoint, final IndexShard replicaShard) {
        logger.trace(() -> new ParameterizedMessage("Replica received new replication checkpoint from primary [{}]", receivedCheckpoint));
        // if the shard is in any state
        if (replicaShard.state().equals(IndexShardState.CLOSED)) {
            // ignore if shard is closed
            logger.trace(() -> "Ignoring checkpoint, Shard is closed");
            return;
        }
        updateLatestReceivedCheckpoint(receivedCheckpoint, replicaShard);
        // Checks if replica shard is in the correct STARTED state to process checkpoints (avoids parallel replication events taking place)
        // This check ensures we do not try to process a received checkpoint while the shard is still recovering, yet we stored the latest
        // checkpoint to be replayed once the shard is Active.
        if (replicaShard.state().equals(IndexShardState.STARTED) == true) {
            // Checks if received checkpoint is already present and ahead then it replaces old received checkpoint
            SegmentReplicationTarget ongoingReplicationTarget = onGoingReplications.getOngoingReplicationTarget(replicaShard.shardId());
            if (ongoingReplicationTarget != null) {
                if (ongoingReplicationTarget.getCheckpoint().getPrimaryTerm() < receivedCheckpoint.getPrimaryTerm()) {
                    logger.trace(
                        "Cancelling ongoing replication from old primary with primary term {}",
                        ongoingReplicationTarget.getCheckpoint().getPrimaryTerm()
                    );
                    onGoingReplications.cancel(ongoingReplicationTarget.getId(), "Cancelling stuck target after new primary");
                    completedReplications.put(replicaShard.shardId(), ongoingReplicationTarget);
                } else {
                    logger.trace(
                        () -> new ParameterizedMessage(
                            "Ignoring new replication checkpoint - shard is currently replicating to checkpoint {}",
                            replicaShard.getLatestReplicationCheckpoint()
                        )
                    );
                    return;
                }
            }
            final Thread thread = Thread.currentThread();
            if (replicaShard.shouldProcessCheckpoint(receivedCheckpoint)) {
                startReplication(replicaShard, new SegmentReplicationListener() {
                    @Override
                    public void onReplicationDone(SegmentReplicationState state) {
                        logger.trace(
                            () -> new ParameterizedMessage(
                                "[shardId {}] [replication id {}] Replication complete to {}, timing data: {}",
                                replicaShard.shardId().getId(),
                                state.getReplicationId(),
                                replicaShard.getLatestReplicationCheckpoint(),
                                state.getTimingData()
                            )
                        );

                        // update visible checkpoint to primary
                        updateVisibleCheckpoint(state.getReplicationId(), replicaShard);

                        // if we received a checkpoint during the copy event that is ahead of this
                        // try and process it.
                        processLatestReceivedCheckpoint(replicaShard, thread);
                    }

                    @Override
                    public void onReplicationFailure(
                        SegmentReplicationState state,
                        ReplicationFailedException e,
                        boolean sendShardFailure
                    ) {
                        logger.trace(
                            () -> new ParameterizedMessage(
                                "[shardId {}] [replication id {}] Replication failed, timing data: {}",
                                replicaShard.shardId().getId(),
                                state.getReplicationId(),
                                state.getTimingData()
                            )
                        );
                        if (sendShardFailure == true) {
                            logger.error("replication failure", e);
                            replicaShard.failShard("replication failure", e);
                        }
                    }
                });

            }
        } else {
            logger.trace(
                () -> new ParameterizedMessage("Ignoring checkpoint, shard not started {} {}", receivedCheckpoint, replicaShard.state())
            );
        }
    }

    protected void updateVisibleCheckpoint(long replicationId, IndexShard replicaShard) {
        ShardRouting primaryShard = clusterService.state().routingTable().shardRoutingTable(replicaShard.shardId()).primaryShard();

        final UpdateVisibleCheckpointRequest request = new UpdateVisibleCheckpointRequest(
            replicationId,
            replicaShard.routingEntry().allocationId().getId(),
            primaryShard.shardId(),
            getPrimaryNode(primaryShard),
            replicaShard.getLatestReplicationCheckpoint()
        );

        final TransportRequestOptions options = TransportRequestOptions.builder()
            .withTimeout(recoverySettings.internalActionTimeout())
            .build();
        logger.debug("Updating replication checkpoint to {}", request.getCheckpoint());
        RetryableTransportClient transportClient = new RetryableTransportClient(
            transportService,
            getPrimaryNode(primaryShard),
            recoverySettings.internalActionRetryTimeout(),
            logger
        );
        final ActionListener<Void> listener = new ActionListener<>() {
            @Override
            public void onResponse(Void unused) {
                logger.debug(
                    "Successfully updated replication checkpoint {} for replica {}",
                    replicaShard.shardId(),
                    request.getCheckpoint()
                );
            }

            @Override
            public void onFailure(Exception e) {
                logger.error(
                    "Failed to update visible checkpoint for replica {}, {}: {}",
                    replicaShard.shardId(),
                    request.getCheckpoint(),
                    e
                );
            }
        };

        transportClient.executeRetryableAction(
            UPDATE_VISIBLE_CHECKPOINT,
            request,
            options,
            ActionListener.map(listener, r -> null),
            in -> TransportResponse.Empty.INSTANCE
        );
    }

    private DiscoveryNode getPrimaryNode(ShardRouting primaryShard) {
        return clusterService.state().nodes().get(primaryShard.currentNodeId());
    }

    // visible to tests
    protected boolean processLatestReceivedCheckpoint(IndexShard replicaShard, Thread thread) {
        final ReplicationCheckpoint latestPublishedCheckpoint = latestReceivedCheckpoint.get(replicaShard.shardId());
        if (latestPublishedCheckpoint != null && latestPublishedCheckpoint.isAheadOf(replicaShard.getLatestReplicationCheckpoint())) {
            Runnable runnable = () -> onNewCheckpoint(latestReceivedCheckpoint.get(replicaShard.shardId()), replicaShard);
            // Checks if we are using same thread and forks if necessary.
            if (thread == Thread.currentThread()) {
                threadPool.generic().execute(runnable);
            } else {
                runnable.run();
            }
            return true;
        }
        return false;
    }

    // visible to tests
    protected void updateLatestReceivedCheckpoint(ReplicationCheckpoint receivedCheckpoint, IndexShard replicaShard) {
        if (latestReceivedCheckpoint.get(replicaShard.shardId()) != null) {
            if (receivedCheckpoint.isAheadOf(latestReceivedCheckpoint.get(replicaShard.shardId()))) {
                latestReceivedCheckpoint.replace(replicaShard.shardId(), receivedCheckpoint);
            }
        } else {
            latestReceivedCheckpoint.put(replicaShard.shardId(), receivedCheckpoint);
        }
    }

    public SegmentReplicationTarget startReplication(final IndexShard indexShard, final SegmentReplicationListener listener) {
        final SegmentReplicationTarget target = new SegmentReplicationTarget(indexShard, sourceFactory.get(indexShard), listener);
        startReplication(target);
        return target;
    }

    // pkg-private for integration tests
    void startReplication(final SegmentReplicationTarget target) {
        final long replicationId = onGoingReplications.start(target, recoverySettings.activityTimeout());
        threadPool.generic().execute(new ReplicationRunner(replicationId));
    }

    /**
     * Listener that runs on changes in Replication state
     *
     * @opensearch.internal
     */
    public interface SegmentReplicationListener extends ReplicationListener {

        @Override
        default void onDone(ReplicationState state) {
            onReplicationDone((SegmentReplicationState) state);
        }

        @Override
        default void onFailure(ReplicationState state, ReplicationFailedException e, boolean sendShardFailure) {
            onReplicationFailure((SegmentReplicationState) state, e, sendShardFailure);
        }

        void onReplicationDone(SegmentReplicationState state);

        void onReplicationFailure(SegmentReplicationState state, ReplicationFailedException e, boolean sendShardFailure);
    }

    /**
     * Runnable implementation to trigger a replication event.
     */
    private class ReplicationRunner implements Runnable {

        final long replicationId;

        public ReplicationRunner(long replicationId) {
            this.replicationId = replicationId;
        }

        @Override
        public void run() {
            start(replicationId);
        }
    }

    private void start(final long replicationId) {
        try (ReplicationRef<SegmentReplicationTarget> replicationRef = onGoingReplications.get(replicationId)) {
            // This check is for handling edge cases where the reference is removed before the ReplicationRunner is started by the
            // threadpool.
            if (replicationRef == null) {
                return;
            }
            SegmentReplicationTarget target = onGoingReplications.getTarget(replicationId);
            replicationRef.get().startReplication(new ActionListener<>() {
                @Override
                public void onResponse(Void o) {
                    onGoingReplications.markAsDone(replicationId);
                    if (target.state().getIndex().recoveredFileCount() != 0 && target.state().getIndex().recoveredBytes() != 0) {
                        completedReplications.put(target.shardId(), target);
                    }

                }

                @Override
                public void onFailure(Exception e) {
                    Throwable cause = BaseExceptionsHelper.unwrapCause(e);
                    if (cause instanceof CancellableThreads.ExecutionCancelledException) {
                        if (onGoingReplications.getTarget(replicationId) != null) {
                            IndexShard indexShard = onGoingReplications.getTarget(replicationId).indexShard();
                            // if the target still exists in our collection, the primary initiated the cancellation, fail the replication
                            // but do not fail the shard. Cancellations initiated by this node from Index events will be removed with
                            // onGoingReplications.cancel and not appear in the collection when this listener resolves.
                            onGoingReplications.fail(replicationId, new ReplicationFailedException(indexShard, cause), false);
                            completedReplications.put(target.shardId(), target);
                        }
                    } else {
                        onGoingReplications.fail(replicationId, new ReplicationFailedException("Segment Replication failed", e), false);
                    }
                }
            });
        }
    }

    private class FileChunkTransportRequestHandler implements TransportRequestHandler<FileChunkRequest> {

        // How many bytes we've copied since we last called RateLimiter.pause
        final AtomicLong bytesSinceLastPause = new AtomicLong();

        @Override
        public void messageReceived(final FileChunkRequest request, TransportChannel channel, Task task) throws Exception {
            try (ReplicationRef<SegmentReplicationTarget> ref = onGoingReplications.getSafe(request.recoveryId(), request.shardId())) {
                final SegmentReplicationTarget target = ref.get();
                final ActionListener<Void> listener = target.createOrFinishListener(channel, Actions.FILE_CHUNK, request);
                target.handleFileChunk(request, target, bytesSinceLastPause, recoverySettings.rateLimiter(), listener);
            }
        }
    }

    /**
     * Force sync transport handler forces round of segment replication. Caller should verify necessary checks before
     * calling this handler.
     */
    private class ForceSyncTransportRequestHandler implements TransportRequestHandler<ForceSyncRequest> {
        @Override
        public void messageReceived(final ForceSyncRequest request, TransportChannel channel, Task task) throws Exception {
            assert indicesService != null;
            final IndexShard indexShard = indicesService.getShardOrNull(request.getShardId());
            // Proceed with round of segment replication only when it is allowed
            if (indexShard == null || indexShard.getReplicationEngine().isEmpty()) {
                logger.info("Ignore force segment replication sync as it is not allowed");
                channel.sendResponse(TransportResponse.Empty.INSTANCE);
                return;
            }
            startReplication(indexShard, new SegmentReplicationTargetService.SegmentReplicationListener() {
                @Override
                public void onReplicationDone(SegmentReplicationState state) {
                    logger.trace(
                        () -> new ParameterizedMessage(
                            "[shardId {}] [replication id {}] Replication complete to {}, timing data: {}",
                            indexShard.shardId().getId(),
                            state.getReplicationId(),
                            indexShard.getLatestReplicationCheckpoint(),
                            state.getTimingData()
                        )
                    );
                    try {
                        // Promote engine type for primary target
                        if (indexShard.recoveryState().getPrimary() == true) {
                            indexShard.resetToWriteableEngine();
                        } else {
                            // Update the replica's checkpoint on primary's replication tracker.
                            updateVisibleCheckpoint(state.getReplicationId(), indexShard);
                        }
                        channel.sendResponse(TransportResponse.Empty.INSTANCE);
                    } catch (InterruptedException | TimeoutException | IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void onReplicationFailure(SegmentReplicationState state, ReplicationFailedException e, boolean sendShardFailure) {
                    logger.trace(
                        () -> new ParameterizedMessage(
                            "[shardId {}] [replication id {}] Replication failed, timing data: {}",
                            indexShard.shardId().getId(),
                            state.getReplicationId(),
                            state.getTimingData()
                        )
                    );
                    if (sendShardFailure == true) {
                        indexShard.failShard("replication failure", e);
                    }
                    try {
                        channel.sendResponse(e);
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            });
        }
    }

}
