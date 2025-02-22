/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.replication;

import com.carrotsearch.randomizedtesting.RandomizedTest;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.StandardDirectoryReader;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.BytesRef;
import org.opensearch.action.ActionFuture;
import org.opensearch.action.admin.indices.flush.FlushRequest;
import org.opensearch.action.admin.indices.stats.IndicesStatsRequest;
import org.opensearch.action.admin.indices.stats.IndicesStatsResponse;
import org.opensearch.action.search.CreatePitAction;
import org.opensearch.action.search.CreatePitRequest;
import org.opensearch.action.search.CreatePitResponse;
import org.opensearch.action.search.DeletePitAction;
import org.opensearch.action.search.DeletePitRequest;
import org.opensearch.action.search.PitTestsUtil;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.search.SearchType;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.action.update.UpdateResponse;
import org.opensearch.client.Requests;
import org.opensearch.cluster.health.ClusterHealthStatus;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.routing.ShardRoutingState;
import org.opensearch.cluster.routing.allocation.command.CancelAllocationCommand;
import org.opensearch.common.io.stream.NamedWriteableRegistry;
import org.opensearch.common.lucene.index.OpenSearchDirectoryReader;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.lease.Releasable;
import org.opensearch.common.util.FeatureFlags;
import org.opensearch.index.IndexModule;
import org.opensearch.index.SegmentReplicationPerGroupStats;
import org.opensearch.index.SegmentReplicationPressureService;
import org.opensearch.index.SegmentReplicationShardStats;
import org.opensearch.index.codec.CodecService;
import org.opensearch.index.engine.Engine;
import org.opensearch.index.engine.EngineConfig;
import org.opensearch.index.engine.NRTReplicationReaderManager;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.index.shard.ShardId;
import org.opensearch.indices.recovery.FileChunkRequest;
import org.opensearch.indices.replication.common.ReplicationType;
import org.opensearch.search.SearchService;
import org.opensearch.search.builder.PointInTimeBuilder;
import org.opensearch.search.internal.PitReaderContext;
import org.opensearch.search.sort.SortOrder;
import org.opensearch.node.NodeClosedException;
import org.opensearch.test.BackgroundIndexer;
import org.opensearch.test.InternalTestCluster;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.opensearch.test.transport.MockTransportService;
import org.opensearch.transport.TransportService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static org.opensearch.action.search.PitTestsUtil.assertSegments;
import static org.opensearch.action.search.SearchContextId.decode;
import static org.opensearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.opensearch.index.query.QueryBuilders.matchAllQuery;
import static org.opensearch.index.query.QueryBuilders.matchQuery;
import static org.opensearch.indices.replication.SegmentReplicationTarget.REPLICATION_PREFIX;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAllSuccessful;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertHitCount;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertSearchHits;

@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.TEST, numDataNodes = 0)
public class SegmentReplicationIT extends SegmentReplicationBaseIT {

    public void testPrimaryStopped_ReplicaPromoted() throws Exception {
        final String primary = internalCluster().startNode();
        createIndex(INDEX_NAME);
        ensureYellowAndNoInitializingShards(INDEX_NAME);
        final String replica = internalCluster().startNode();
        ensureGreen(INDEX_NAME);

        client().prepareIndex(INDEX_NAME).setId("1").setSource("foo", "bar").setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get();
        refresh(INDEX_NAME);

        waitForSearchableDocs(1, primary, replica);

        // index another doc but don't refresh, we will ensure this is searchable once replica is promoted.
        client().prepareIndex(INDEX_NAME).setId("2").setSource("bar", "baz").setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get();

        // stop the primary node - we only have one shard on here.
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(primary));
        ensureYellowAndNoInitializingShards(INDEX_NAME);

        final ShardRouting replicaShardRouting = getShardRoutingForNodeName(replica);
        assertNotNull(replicaShardRouting);
        assertTrue(replicaShardRouting + " should be promoted as a primary", replicaShardRouting.primary());
        refresh(INDEX_NAME);
        assertHitCount(client(replica).prepareSearch(INDEX_NAME).setSize(0).setPreference("_only_local").get(), 2);

        // assert we can index into the new primary.
        client().prepareIndex(INDEX_NAME).setId("3").setSource("bar", "baz").setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get();
        assertHitCount(client(replica).prepareSearch(INDEX_NAME).setSize(0).setPreference("_only_local").get(), 3);

        // start another node, index another doc and replicate.
        String nodeC = internalCluster().startNode();
        ensureGreen(INDEX_NAME);
        client().prepareIndex(INDEX_NAME).setId("4").setSource("baz", "baz").get();
        refresh(INDEX_NAME);
        waitForSearchableDocs(4, nodeC, replica);
        verifyStoreContent();
    }

    public void testRestartPrimary() throws Exception {
        final String primary = internalCluster().startNode();
        createIndex(INDEX_NAME);
        ensureYellowAndNoInitializingShards(INDEX_NAME);
        final String replica = internalCluster().startNode();
        ensureGreen(INDEX_NAME);

        assertEquals(getNodeContainingPrimaryShard().getName(), primary);

        final int initialDocCount = 1;
        client().prepareIndex(INDEX_NAME).setId("1").setSource("foo", "bar").setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get();
        refresh(INDEX_NAME);

        waitForSearchableDocs(initialDocCount, replica, primary);

        internalCluster().restartNode(primary);
        ensureGreen(INDEX_NAME);

        assertEquals(getNodeContainingPrimaryShard().getName(), replica);

        flushAndRefresh(INDEX_NAME);
        waitForSearchableDocs(initialDocCount, replica, primary);
        verifyStoreContent();
    }

    public void testCancelPrimaryAllocation() throws Exception {
        // this test cancels allocation on the primary - promoting the new replica and recreating the former primary as a replica.
        final String primary = internalCluster().startNode();
        createIndex(INDEX_NAME);
        ensureYellowAndNoInitializingShards(INDEX_NAME);
        final String replica = internalCluster().startNode();
        ensureGreen(INDEX_NAME);

        final int initialDocCount = 1;

        client().prepareIndex(INDEX_NAME).setId("1").setSource("foo", "bar").setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get();
        refresh(INDEX_NAME);

        waitForSearchableDocs(initialDocCount, replica, primary);

        final IndexShard indexShard = getIndexShard(primary, INDEX_NAME);
        client().admin()
            .cluster()
            .prepareReroute()
            .add(new CancelAllocationCommand(INDEX_NAME, indexShard.shardId().id(), primary, true))
            .execute()
            .actionGet();
        ensureGreen(INDEX_NAME);

        assertEquals(getNodeContainingPrimaryShard().getName(), replica);

        flushAndRefresh(INDEX_NAME);
        waitForSearchableDocs(initialDocCount, replica, primary);
        verifyStoreContent();
    }

    public void testReplicationAfterPrimaryRefreshAndFlush() throws Exception {
        final String nodeA = internalCluster().startNode();
        final String nodeB = internalCluster().startNode();
        final Settings settings = Settings.builder()
            .put(indexSettings())
            .put(
                EngineConfig.INDEX_CODEC_SETTING.getKey(),
                randomFrom(CodecService.DEFAULT_CODEC, CodecService.BEST_COMPRESSION_CODEC, CodecService.LUCENE_DEFAULT_CODEC)
            )
            .build();
        createIndex(INDEX_NAME, settings);
        ensureGreen(INDEX_NAME);

        final int initialDocCount = scaledRandomIntBetween(0, 200);
        try (
            BackgroundIndexer indexer = new BackgroundIndexer(
                INDEX_NAME,
                "_doc",
                client(),
                -1,
                RandomizedTest.scaledRandomIntBetween(2, 5),
                false,
                random()
            )
        ) {
            indexer.start(initialDocCount);
            waitForDocs(initialDocCount, indexer);
            refresh(INDEX_NAME);
            waitForSearchableDocs(initialDocCount, nodeA, nodeB);

            final int additionalDocCount = scaledRandomIntBetween(0, 200);
            final int expectedHitCount = initialDocCount + additionalDocCount;
            indexer.start(additionalDocCount);
            waitForDocs(expectedHitCount, indexer);

            flushAndRefresh(INDEX_NAME);
            waitForSearchableDocs(expectedHitCount, nodeA, nodeB);

            ensureGreen(INDEX_NAME);
            verifyStoreContent();
        }
    }

    public void testIndexReopenClose() throws Exception {
        final String primary = internalCluster().startNode();
        final String replica = internalCluster().startNode();
        createIndex(INDEX_NAME);
        ensureGreen(INDEX_NAME);

        final int initialDocCount = scaledRandomIntBetween(100, 200);
        try (
            BackgroundIndexer indexer = new BackgroundIndexer(
                INDEX_NAME,
                "_doc",
                client(),
                -1,
                RandomizedTest.scaledRandomIntBetween(2, 5),
                false,
                random()
            )
        ) {
            indexer.start(initialDocCount);
            waitForDocs(initialDocCount, indexer);
            flush(INDEX_NAME);
            waitForSearchableDocs(initialDocCount, primary, replica);
        }
        logger.info("--> Closing the index ");
        client().admin().indices().prepareClose(INDEX_NAME).get();

        logger.info("--> Opening the index");
        client().admin().indices().prepareOpen(INDEX_NAME).get();

        ensureGreen(INDEX_NAME);
        waitForSearchableDocs(initialDocCount, primary, replica);
        verifyStoreContent();
    }

    public void testMultipleShards() throws Exception {
        Settings indexSettings = Settings.builder()
            .put(super.indexSettings())
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 3)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
            .put(IndexModule.INDEX_QUERY_CACHE_ENABLED_SETTING.getKey(), false)
            .put(IndexMetadata.SETTING_REPLICATION_TYPE, ReplicationType.SEGMENT)
            .build();
        final String nodeA = internalCluster().startNode();
        final String nodeB = internalCluster().startNode();
        createIndex(INDEX_NAME, indexSettings);
        ensureGreen(INDEX_NAME);

        final int initialDocCount = scaledRandomIntBetween(1, 200);
        try (
            BackgroundIndexer indexer = new BackgroundIndexer(
                INDEX_NAME,
                "_doc",
                client(),
                -1,
                RandomizedTest.scaledRandomIntBetween(2, 5),
                false,
                random()
            )
        ) {
            indexer.start(initialDocCount);
            waitForDocs(initialDocCount, indexer);
            refresh(INDEX_NAME);
            waitForSearchableDocs(initialDocCount, nodeA, nodeB);

            final int additionalDocCount = scaledRandomIntBetween(0, 200);
            final int expectedHitCount = initialDocCount + additionalDocCount;
            indexer.start(additionalDocCount);
            waitForDocs(expectedHitCount, indexer);

            flushAndRefresh(INDEX_NAME);
            waitForSearchableDocs(expectedHitCount, nodeA, nodeB);

            ensureGreen(INDEX_NAME);
            verifyStoreContent();
        }
    }

    public void testReplicationAfterForceMerge() throws Exception {
        final String nodeA = internalCluster().startNode();
        final String nodeB = internalCluster().startNode();
        createIndex(INDEX_NAME);
        ensureGreen(INDEX_NAME);

        final int initialDocCount = scaledRandomIntBetween(0, 200);
        final int additionalDocCount = scaledRandomIntBetween(0, 200);
        final int expectedHitCount = initialDocCount + additionalDocCount;
        try (
            BackgroundIndexer indexer = new BackgroundIndexer(
                INDEX_NAME,
                "_doc",
                client(),
                -1,
                RandomizedTest.scaledRandomIntBetween(2, 5),
                false,
                random()
            )
        ) {
            indexer.start(initialDocCount);
            waitForDocs(initialDocCount, indexer);

            flush(INDEX_NAME);
            waitForSearchableDocs(initialDocCount, nodeA, nodeB);

            // Index a second set of docs so we can merge into one segment.
            indexer.start(additionalDocCount);
            waitForDocs(expectedHitCount, indexer);
            waitForSearchableDocs(expectedHitCount, nodeA, nodeB);

            // Force a merge here so that the in memory SegmentInfos does not reference old segments on disk.
            client().admin().indices().prepareForceMerge(INDEX_NAME).setMaxNumSegments(1).setFlush(false).get();
            refresh(INDEX_NAME);
            verifyStoreContent();
        }
    }

    /**
     * This test verifies that segment replication does not fail for closed indices
     */
    public void testClosedIndices() {
        internalCluster().startClusterManagerOnlyNode();
        List<String> nodes = new ArrayList<>();
        // start 1st node so that it contains the primary
        nodes.add(internalCluster().startNode());
        createIndex(INDEX_NAME, super.indexSettings());
        ensureYellowAndNoInitializingShards(INDEX_NAME);
        // start 2nd node so that it contains the replica
        nodes.add(internalCluster().startNode());
        ensureGreen(INDEX_NAME);

        logger.info("--> Close index");
        assertAcked(client().admin().indices().prepareClose(INDEX_NAME));

        logger.info("--> waiting for allocation to have shards assigned");
        waitForRelocation(ClusterHealthStatus.GREEN);
    }

    /**
     * This test validates the primary node drop does not result in shard failure on replica.
     * @throws Exception when issue is encountered
     */
    public void testNodeDropWithOngoingReplication() throws Exception {
        internalCluster().startClusterManagerOnlyNode();
        final String primaryNode = internalCluster().startNode();
        createIndex(
            INDEX_NAME,
            Settings.builder()
                .put(indexSettings())
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
                .put("index.refresh_interval", -1)
                .build()
        );
        ensureYellow(INDEX_NAME);
        final String replicaNode = internalCluster().startNode();
        ensureGreen(INDEX_NAME);
        ClusterState state = client().admin().cluster().prepareState().execute().actionGet().getState();
        // Get replica allocation id
        final String replicaAllocationId = state.routingTable()
            .index(INDEX_NAME)
            .shardsWithState(ShardRoutingState.STARTED)
            .stream()
            .filter(routing -> routing.primary() == false)
            .findFirst()
            .get()
            .allocationId()
            .getId();
        DiscoveryNode primaryDiscovery = state.nodes().resolveNode(primaryNode);

        CountDownLatch blockFileCopy = new CountDownLatch(1);
        MockTransportService primaryTransportService = ((MockTransportService) internalCluster().getInstance(
            TransportService.class,
            primaryNode
        ));
        primaryTransportService.addSendBehavior(
            internalCluster().getInstance(TransportService.class, replicaNode),
            (connection, requestId, action, request, options) -> {
                if (action.equals(SegmentReplicationTargetService.Actions.FILE_CHUNK)) {
                    FileChunkRequest req = (FileChunkRequest) request;
                    logger.debug("file chunk [{}] lastChunk: {}", req, req.lastChunk());
                    if (req.name().endsWith("cfs") && req.lastChunk()) {
                        try {
                            blockFileCopy.await();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        throw new NodeClosedException(primaryDiscovery);
                    }
                }
                connection.sendRequest(requestId, action, request, options);
            }
        );
        final int docCount = scaledRandomIntBetween(10, 200);
        for (int i = 0; i < docCount; i++) {
            client().prepareIndex(INDEX_NAME).setId(Integer.toString(i)).setSource("field", "value" + i).execute().get();
        }
        // Refresh, this should trigger round of segment replication
        refresh(INDEX_NAME);
        blockFileCopy.countDown();
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(primaryNode));
        assertBusy(() -> { assertDocCounts(docCount, replicaNode); });
        state = client().admin().cluster().prepareState().execute().actionGet().getState();
        // replica now promoted as primary should have same allocation id
        final String currentAllocationID = state.routingTable()
            .index(INDEX_NAME)
            .shardsWithState(ShardRoutingState.STARTED)
            .stream()
            .filter(routing -> routing.primary())
            .findFirst()
            .get()
            .allocationId()
            .getId();
        assertEquals(currentAllocationID, replicaAllocationId);
    }

    public void testCancellation() throws Exception {
        final String primaryNode = internalCluster().startNode();
        createIndex(INDEX_NAME, Settings.builder().put(indexSettings()).put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1).build());
        ensureYellow(INDEX_NAME);

        final String replicaNode = internalCluster().startNode();

        final SegmentReplicationSourceService segmentReplicationSourceService = internalCluster().getInstance(
            SegmentReplicationSourceService.class,
            primaryNode
        );
        final IndexShard primaryShard = getIndexShard(primaryNode, INDEX_NAME);

        CountDownLatch latch = new CountDownLatch(1);

        MockTransportService mockTransportService = ((MockTransportService) internalCluster().getInstance(
            TransportService.class,
            primaryNode
        ));
        mockTransportService.addSendBehavior(
            internalCluster().getInstance(TransportService.class, replicaNode),
            (connection, requestId, action, request, options) -> {
                if (action.equals(SegmentReplicationTargetService.Actions.FILE_CHUNK)) {
                    FileChunkRequest req = (FileChunkRequest) request;
                    logger.debug("file chunk [{}] lastChunk: {}", req, req.lastChunk());
                    if (req.name().endsWith("cfs") && req.lastChunk()) {
                        try {
                            latch.await();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
                connection.sendRequest(requestId, action, request, options);
            }
        );

        final int docCount = scaledRandomIntBetween(0, 200);
        try (
            BackgroundIndexer indexer = new BackgroundIndexer(
                INDEX_NAME,
                "_doc",
                client(),
                -1,
                RandomizedTest.scaledRandomIntBetween(2, 5),
                false,
                random()
            )
        ) {
            indexer.start(docCount);
            waitForDocs(docCount, indexer);

            flush(INDEX_NAME);
        }
        segmentReplicationSourceService.beforeIndexShardClosed(primaryShard.shardId(), primaryShard, indexSettings());
        latch.countDown();
        assertDocCounts(docCount, primaryNode);
    }

    public void testStartReplicaAfterPrimaryIndexesDocs() throws Exception {
        final String primaryNode = internalCluster().startNode();
        createIndex(INDEX_NAME, Settings.builder().put(indexSettings()).put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0).build());
        ensureGreen(INDEX_NAME);

        // Index a doc to create the first set of segments. _s1.si
        client().prepareIndex(INDEX_NAME).setId("1").setSource("foo", "bar").get();
        // Flush segments to disk and create a new commit point (Primary: segments_3, _s1.si)
        flushAndRefresh(INDEX_NAME);
        assertHitCount(client(primaryNode).prepareSearch(INDEX_NAME).setSize(0).setPreference("_only_local").get(), 1);

        // Index to create another segment
        client().prepareIndex(INDEX_NAME).setId("2").setSource("foo", "bar").get();

        // Force a merge here so that the in memory SegmentInfos does not reference old segments on disk.
        client().admin().indices().prepareForceMerge(INDEX_NAME).setMaxNumSegments(1).setFlush(false).get();
        refresh(INDEX_NAME);

        assertAcked(
            client().admin()
                .indices()
                .prepareUpdateSettings(INDEX_NAME)
                .setSettings(Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1))
        );
        final String replicaNode = internalCluster().startNode();
        ensureGreen(INDEX_NAME);

        assertHitCount(client(primaryNode).prepareSearch(INDEX_NAME).setSize(0).setPreference("_only_local").get(), 2);
        assertHitCount(client(replicaNode).prepareSearch(INDEX_NAME).setSize(0).setPreference("_only_local").get(), 2);

        client().prepareIndex(INDEX_NAME).setId("3").setSource("foo", "bar").get();
        refresh(INDEX_NAME);
        waitForSearchableDocs(3, primaryNode, replicaNode);
        assertHitCount(client(primaryNode).prepareSearch(INDEX_NAME).setSize(0).setPreference("_only_local").get(), 3);
        assertHitCount(client(replicaNode).prepareSearch(INDEX_NAME).setSize(0).setPreference("_only_local").get(), 3);
        verifyStoreContent();
    }

    public void testDeleteOperations() throws Exception {
        final String nodeA = internalCluster().startNode();
        final String nodeB = internalCluster().startNode();

        createIndex(INDEX_NAME);
        ensureGreen(INDEX_NAME);
        final int initialDocCount = scaledRandomIntBetween(0, 200);
        try (
            BackgroundIndexer indexer = new BackgroundIndexer(
                INDEX_NAME,
                "_doc",
                client(),
                -1,
                RandomizedTest.scaledRandomIntBetween(2, 5),
                false,
                random()
            )
        ) {
            indexer.start(initialDocCount);
            waitForDocs(initialDocCount, indexer);
            refresh(INDEX_NAME);
            waitForSearchableDocs(initialDocCount, nodeA, nodeB);

            final int additionalDocCount = scaledRandomIntBetween(0, 200);
            final int expectedHitCount = initialDocCount + additionalDocCount;
            indexer.start(additionalDocCount);
            waitForDocs(expectedHitCount, indexer);
            waitForSearchableDocs(expectedHitCount, nodeA, nodeB);

            ensureGreen(INDEX_NAME);

            Set<String> ids = indexer.getIds();
            String id = ids.toArray()[0].toString();
            client(nodeA).prepareDelete(INDEX_NAME, id).setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get();

            refresh(INDEX_NAME);
            waitForSearchableDocs(expectedHitCount - 1, nodeA, nodeB);
            verifyStoreContent();
        }
    }

    /**
     * This tests that the max seqNo we send to replicas is accurate and that after failover
     * the new primary starts indexing from the correct maxSeqNo and replays the correct count of docs
     * from xlog.
     */
    public void testReplicationPostDeleteAndForceMerge() throws Exception {
        assumeFalse("Skipping the test with Remote store as its flaky.", segmentReplicationWithRemoteEnabled());
        final String primary = internalCluster().startNode();
        createIndex(INDEX_NAME);
        final String replica = internalCluster().startNode();
        ensureGreen(INDEX_NAME);
        final int initialDocCount = scaledRandomIntBetween(10, 200);
        for (int i = 0; i < initialDocCount; i++) {
            client().prepareIndex(INDEX_NAME).setId(String.valueOf(i)).setSource("foo", "bar").get();
        }
        refresh(INDEX_NAME);
        waitForSearchableDocs(initialDocCount, primary, replica);

        final int deletedDocCount = randomIntBetween(10, initialDocCount);
        for (int i = 0; i < deletedDocCount; i++) {
            client(primary).prepareDelete(INDEX_NAME, String.valueOf(i)).setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get();
        }
        client().admin().indices().prepareForceMerge(INDEX_NAME).setMaxNumSegments(1).setFlush(false).get();

        // randomly flush here after the force merge to wipe any old segments.
        if (randomBoolean()) {
            flush(INDEX_NAME);
        }

        final IndexShard primaryShard = getIndexShard(primary, INDEX_NAME);
        final IndexShard replicaShard = getIndexShard(replica, INDEX_NAME);
        assertBusy(
            () -> assertEquals(
                primaryShard.getLatestReplicationCheckpoint().getSegmentInfosVersion(),
                replicaShard.getLatestReplicationCheckpoint().getSegmentInfosVersion()
            )
        );

        // add some docs to the xlog and drop primary.
        final int additionalDocs = randomIntBetween(1, 50);
        for (int i = initialDocCount; i < initialDocCount + additionalDocs; i++) {
            client().prepareIndex(INDEX_NAME).setId(String.valueOf(i)).setSource("foo", "bar").get();
        }
        // Drop the primary and wait until replica is promoted.
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(primary));
        ensureYellowAndNoInitializingShards(INDEX_NAME);

        final ShardRouting replicaShardRouting = getShardRoutingForNodeName(replica);
        assertNotNull(replicaShardRouting);
        assertTrue(replicaShardRouting + " should be promoted as a primary", replicaShardRouting.primary());
        refresh(INDEX_NAME);
        final long expectedHitCount = initialDocCount + additionalDocs - deletedDocCount;
        assertHitCount(client(replica).prepareSearch(INDEX_NAME).setSize(0).setPreference("_only_local").get(), expectedHitCount);

        int expectedMaxSeqNo = initialDocCount + deletedDocCount + additionalDocs - 1;
        assertEquals(expectedMaxSeqNo, replicaShard.seqNoStats().getMaxSeqNo());

        // index another doc.
        client().prepareIndex(INDEX_NAME).setId(String.valueOf(expectedMaxSeqNo + 1)).setSource("another", "doc").get();
        refresh(INDEX_NAME);
        assertHitCount(client(replica).prepareSearch(INDEX_NAME).setSize(0).setPreference("_only_local").get(), expectedHitCount + 1);
    }

    public void testUpdateOperations() throws Exception {
        internalCluster().startClusterManagerOnlyNode();
        final String primary = internalCluster().startDataOnlyNode();
        createIndex(INDEX_NAME);
        ensureYellow(INDEX_NAME);
        final String replica = internalCluster().startDataOnlyNode();
        ensureGreen(INDEX_NAME);

        final int initialDocCount = scaledRandomIntBetween(0, 200);
        try (
            BackgroundIndexer indexer = new BackgroundIndexer(
                INDEX_NAME,
                "_doc",
                client(),
                -1,
                RandomizedTest.scaledRandomIntBetween(2, 5),
                false,
                random()
            )
        ) {
            indexer.start(initialDocCount);
            waitForDocs(initialDocCount, indexer);
            refresh(INDEX_NAME);
            waitForSearchableDocs(initialDocCount, asList(primary, replica));

            final int additionalDocCount = scaledRandomIntBetween(0, 200);
            final int expectedHitCount = initialDocCount + additionalDocCount;
            indexer.start(additionalDocCount);
            waitForDocs(expectedHitCount, indexer);
            waitForSearchableDocs(expectedHitCount, asList(primary, replica));

            Set<String> ids = indexer.getIds();
            String id = ids.toArray()[0].toString();
            UpdateResponse updateResponse = client(primary).prepareUpdate(INDEX_NAME, id)
                .setDoc(Requests.INDEX_CONTENT_TYPE, "foo", "baz")
                .setRefreshPolicy(WriteRequest.RefreshPolicy.WAIT_UNTIL)
                .get();
            assertFalse("request shouldn't have forced a refresh", updateResponse.forcedRefresh());
            assertEquals(2, updateResponse.getVersion());

            refresh(INDEX_NAME);

            verifyStoreContent();
            assertSearchHits(client(primary).prepareSearch(INDEX_NAME).setQuery(matchQuery("foo", "baz")).get(), id);
            assertSearchHits(client(replica).prepareSearch(INDEX_NAME).setQuery(matchQuery("foo", "baz")).get(), id);
        }
    }

    public void testDropPrimaryDuringReplication() throws Exception {
        final int replica_count = 6;
        final Settings settings = Settings.builder()
            .put(indexSettings())
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, replica_count)
            .put(IndexMetadata.SETTING_REPLICATION_TYPE, ReplicationType.SEGMENT)
            .build();
        final String clusterManagerNode = internalCluster().startClusterManagerOnlyNode();
        final String primaryNode = internalCluster().startDataOnlyNode();
        createIndex(INDEX_NAME, settings);
        final List<String> dataNodes = internalCluster().startDataOnlyNodes(6);
        ensureGreen(INDEX_NAME);

        int initialDocCount = scaledRandomIntBetween(100, 200);
        try (
            BackgroundIndexer indexer = new BackgroundIndexer(
                INDEX_NAME,
                "_doc",
                client(),
                -1,
                RandomizedTest.scaledRandomIntBetween(2, 5),
                false,
                random()
            )
        ) {
            indexer.start(initialDocCount);
            waitForDocs(initialDocCount, indexer);
            refresh(INDEX_NAME);
            // don't wait for replication to complete, stop the primary immediately.
            internalCluster().stopRandomNode(InternalTestCluster.nameFilter(primaryNode));
            ensureYellow(INDEX_NAME);

            // start another replica.
            dataNodes.add(internalCluster().startDataOnlyNode());
            ensureGreen(INDEX_NAME);

            // index another doc and refresh - without this the new replica won't catch up.
            String docId = String.valueOf(initialDocCount + 1);
            client().prepareIndex(INDEX_NAME).setId(docId).setSource("foo", "bar").get();

            flushAndRefresh(INDEX_NAME);
            waitForSearchableDocs(initialDocCount + 1, dataNodes);
            verifyStoreContent();
        }
    }

    public void testReplicaHasDiffFilesThanPrimary() throws Exception {
        internalCluster().startClusterManagerOnlyNode();
        final String primaryNode = internalCluster().startNode();
        createIndex(INDEX_NAME, Settings.builder().put(indexSettings()).put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1).build());
        ensureYellow(INDEX_NAME);
        final String replicaNode = internalCluster().startNode();
        ensureGreen(INDEX_NAME);

        final IndexShard replicaShard = getIndexShard(replicaNode, INDEX_NAME);
        IndexWriterConfig iwc = newIndexWriterConfig().setOpenMode(IndexWriterConfig.OpenMode.APPEND);

        // create a doc to index
        int numDocs = 2 + random().nextInt(100);

        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < numDocs; i++) {
            Document doc = new Document();
            doc.add(new StringField("id", "" + i, random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
            doc.add(
                new TextField(
                    "body",
                    TestUtil.randomRealisticUnicodeString(random()),
                    random().nextBoolean() ? Field.Store.YES : Field.Store.NO
                )
            );
            doc.add(new SortedDocValuesField("dv", new BytesRef(TestUtil.randomRealisticUnicodeString(random()))));
            docs.add(doc);
        }
        // create some segments on the replica before copy.
        try (IndexWriter writer = new IndexWriter(replicaShard.store().directory(), iwc)) {
            for (Document d : docs) {
                writer.addDocument(d);
            }
            writer.flush();
            writer.commit();
        }

        final SegmentInfos segmentInfos = SegmentInfos.readLatestCommit(replicaShard.store().directory());
        replicaShard.finalizeReplication(segmentInfos);
        ensureYellow(INDEX_NAME);

        final int docCount = scaledRandomIntBetween(10, 200);
        for (int i = 0; i < docCount; i++) {
            client().prepareIndex(INDEX_NAME).setId(Integer.toString(i)).setSource("field", "value" + i).execute().get();
            // Refresh, this should trigger round of segment replication
            refresh(INDEX_NAME);
        }
        ensureGreen(INDEX_NAME);
        waitForSearchableDocs(docCount, primaryNode, replicaNode);
        verifyStoreContent();
        final IndexShard replicaAfterFailure = getIndexShard(replicaNode, INDEX_NAME);
        assertNotEquals(replicaAfterFailure.routingEntry().allocationId().getId(), replicaShard.routingEntry().allocationId().getId());
    }

    public void testPressureServiceStats() throws Exception {
        final String primaryNode = internalCluster().startNode();
        createIndex(INDEX_NAME);
        final String replicaNode = internalCluster().startNode();
        ensureGreen(INDEX_NAME);

        int initialDocCount = scaledRandomIntBetween(100, 200);
        try (
            BackgroundIndexer indexer = new BackgroundIndexer(
                INDEX_NAME,
                "_doc",
                client(),
                -1,
                RandomizedTest.scaledRandomIntBetween(2, 5),
                false,
                random()
            )
        ) {
            indexer.start(initialDocCount);
            waitForDocs(initialDocCount, indexer);
            refresh(INDEX_NAME);

            SegmentReplicationPressureService pressureService = internalCluster().getInstance(
                SegmentReplicationPressureService.class,
                primaryNode
            );

            final Map<ShardId, SegmentReplicationPerGroupStats> shardStats = pressureService.nodeStats().getShardStats();
            assertEquals(1, shardStats.size());
            final IndexShard primaryShard = getIndexShard(primaryNode, INDEX_NAME);
            IndexShard replica = getIndexShard(replicaNode, INDEX_NAME);
            SegmentReplicationPerGroupStats groupStats = shardStats.get(primaryShard.shardId());
            Set<SegmentReplicationShardStats> replicaStats = groupStats.getReplicaStats();
            assertEquals(1, replicaStats.size());

            // assert replica node returns nothing.
            SegmentReplicationPressureService replicaNode_service = internalCluster().getInstance(
                SegmentReplicationPressureService.class,
                replicaNode
            );
            assertTrue(replicaNode_service.nodeStats().getShardStats().isEmpty());

            // drop the primary, this won't hand off SR state.
            internalCluster().stopRandomNode(InternalTestCluster.nameFilter(primaryNode));
            ensureYellowAndNoInitializingShards(INDEX_NAME);
            replicaNode_service = internalCluster().getInstance(SegmentReplicationPressureService.class, replicaNode);
            replica = getIndexShard(replicaNode, INDEX_NAME);
            assertTrue("replica should be promoted as a primary", replica.routingEntry().primary());
            assertEquals(1, replicaNode_service.nodeStats().getShardStats().size());
            // we don't have a replica assigned yet, so this should be 0.
            assertEquals(0, replicaNode_service.nodeStats().getShardStats().get(primaryShard.shardId()).getReplicaStats().size());

            // start another replica.
            String replicaNode_2 = internalCluster().startNode();
            ensureGreen(INDEX_NAME);
            String docId = String.valueOf(initialDocCount + 1);
            client().prepareIndex(INDEX_NAME).setId(docId).setSource("foo", "bar").get();
            refresh(INDEX_NAME);
            waitForSearchableDocs(initialDocCount + 1, replicaNode_2);

            replicaNode_service = internalCluster().getInstance(SegmentReplicationPressureService.class, replicaNode);
            replica = getIndexShard(replicaNode_2, INDEX_NAME);
            assertEquals(1, replicaNode_service.nodeStats().getShardStats().size());
            replicaStats = replicaNode_service.nodeStats().getShardStats().get(primaryShard.shardId()).getReplicaStats();
            assertEquals(1, replicaStats.size());

            // test a checkpoint without any new segments
            flush(INDEX_NAME);
            assertBusy(() -> {
                final SegmentReplicationPressureService service = internalCluster().getInstance(
                    SegmentReplicationPressureService.class,
                    replicaNode
                );
                assertEquals(1, service.nodeStats().getShardStats().size());
                final Set<SegmentReplicationShardStats> shardStatsSet = service.nodeStats()
                    .getShardStats()
                    .get(primaryShard.shardId())
                    .getReplicaStats();
                assertEquals(1, shardStatsSet.size());
                final SegmentReplicationShardStats stats = shardStatsSet.stream().findFirst().get();
                assertEquals(0, stats.getCheckpointsBehindCount());
            });
        }
    }

    /**
     * Tests a scroll query on the replica
     * @throws Exception when issue is encountered
     */
    public void testScrollCreatedOnReplica() throws Exception {
        assumeFalse("Skipping the test with Remote store as its flaky.", segmentReplicationWithRemoteEnabled());
        // create the cluster with one primary node containing primary shard and replica node containing replica shard
        final String primary = internalCluster().startNode();
        createIndex(INDEX_NAME);
        ensureYellowAndNoInitializingShards(INDEX_NAME);
        final String replica = internalCluster().startNode();
        ensureGreen(INDEX_NAME);

        // index 100 docs
        for (int i = 0; i < 100; i++) {
            client().prepareIndex(INDEX_NAME)
                .setId(String.valueOf(i))
                .setSource(jsonBuilder().startObject().field("field", i).endObject())
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();
            refresh(INDEX_NAME);
        }
        assertBusy(
            () -> assertEquals(
                getIndexShard(primary, INDEX_NAME).getLatestReplicationCheckpoint().getSegmentInfosVersion(),
                getIndexShard(replica, INDEX_NAME).getLatestReplicationCheckpoint().getSegmentInfosVersion()
            )
        );
        final IndexShard replicaShard = getIndexShard(replica, INDEX_NAME);
        final SegmentInfos segmentInfos = replicaShard.getLatestSegmentInfosAndCheckpoint().v1().get();
        final Collection<String> snapshottedSegments = segmentInfos.files(false);
        // opens a scrolled query before a flush is called.
        // this is for testing scroll segment consistency between refresh and flush
        SearchResponse searchResponse = client(replica).prepareSearch()
            .setQuery(matchAllQuery())
            .setIndices(INDEX_NAME)
            .setRequestCache(false)
            .setPreference("_only_local")
            .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
            .addSort("field", SortOrder.ASC)
            .setSize(10)
            .setScroll(TimeValue.timeValueDays(1))
            .get();

        // force call flush
        flush(INDEX_NAME);

        for (int i = 3; i < 50; i++) {
            client().prepareDelete(INDEX_NAME, String.valueOf(i)).setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get();
            refresh(INDEX_NAME);
            if (randomBoolean()) {
                client().admin().indices().prepareForceMerge(INDEX_NAME).setMaxNumSegments(1).setFlush(true).get();
                flush(INDEX_NAME);
            }
        }
        assertBusy(() -> {
            assertEquals(
                getIndexShard(primary, INDEX_NAME).getLatestReplicationCheckpoint().getSegmentInfosVersion(),
                getIndexShard(replica, INDEX_NAME).getLatestReplicationCheckpoint().getSegmentInfosVersion()
            );
        });

        client().admin().indices().prepareForceMerge(INDEX_NAME).setMaxNumSegments(1).setFlush(true).get();
        assertBusy(() -> {
            assertEquals(
                getIndexShard(primary, INDEX_NAME).getLatestReplicationCheckpoint().getSegmentInfosVersion(),
                getIndexShard(replica, INDEX_NAME).getLatestReplicationCheckpoint().getSegmentInfosVersion()
            );
        });
        // Test stats
        logger.info("--> Collect all scroll query hits");
        long scrollHits = 0;
        do {
            scrollHits += searchResponse.getHits().getHits().length;
            searchResponse = client(replica).prepareSearchScroll(searchResponse.getScrollId()).setScroll(TimeValue.timeValueDays(1)).get();
            assertAllSuccessful(searchResponse);
        } while (searchResponse.getHits().getHits().length > 0);

        List<String> currentFiles = List.of(replicaShard.store().directory().listAll());
        assertTrue("Files should be preserved", currentFiles.containsAll(snapshottedSegments));

        client(replica).prepareClearScroll().addScrollId(searchResponse.getScrollId()).get();

        currentFiles = List.of(replicaShard.store().directory().listAll());
        assertFalse("Files should be cleaned up post scroll clear request", currentFiles.containsAll(snapshottedSegments));
        assertEquals(100, scrollHits);
    }

    /**
     * Tests that when scroll query is cleared, it does not delete the temporary replication files, which are part of
     * ongoing round of segment replication
     *
     * @throws Exception when issue is encountered
     */
    public void testScrollWithOngoingSegmentReplication() throws Exception {
        assumeFalse(
            "Skipping the test as its not compatible with segment replication with remote store yet.",
            segmentReplicationWithRemoteEnabled()
        );

        // create the cluster with one primary node containing primary shard and replica node containing replica shard
        final String primary = internalCluster().startNode();
        prepareCreate(
            INDEX_NAME,
            Settings.builder()
                // we want to control refreshes
                .put("index.refresh_interval", -1)
        ).get();
        ensureYellowAndNoInitializingShards(INDEX_NAME);
        final String replica = internalCluster().startNode();
        ensureGreen(INDEX_NAME);

        final int initialDocCount = 10;
        final int finalDocCount = 20;
        for (int i = 0; i < initialDocCount; i++) {
            client().prepareIndex(INDEX_NAME)
                .setId(String.valueOf(i))
                .setSource(jsonBuilder().startObject().field("field", i).endObject())
                .get();
        }
        // catch up replica with primary
        refresh(INDEX_NAME);
        assertBusy(
            () -> assertEquals(
                getIndexShard(primary, INDEX_NAME).getLatestReplicationCheckpoint().getSegmentInfosVersion(),
                getIndexShard(replica, INDEX_NAME).getLatestReplicationCheckpoint().getSegmentInfosVersion()
            )
        );
        logger.info("--> Create scroll query");
        // opens a scrolled query before a flush is called.
        SearchResponse searchResponse = client(replica).prepareSearch()
            .setQuery(matchAllQuery())
            .setIndices(INDEX_NAME)
            .setRequestCache(false)
            .setPreference("_only_local")
            .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
            .addSort("field", SortOrder.ASC)
            .setSize(10)
            .setScroll(TimeValue.timeValueDays(1))
            .get();

        // force call flush
        flush(INDEX_NAME);

        // Index more documents
        for (int i = initialDocCount; i < finalDocCount; i++) {
            client().prepareIndex(INDEX_NAME)
                .setId(String.valueOf(i))
                .setSource(jsonBuilder().startObject().field("field", i).endObject())
                .get();
        }
        // Block file copy operation to ensure replica has few temporary replication files
        CountDownLatch blockFileCopy = new CountDownLatch(1);
        CountDownLatch waitForFileCopy = new CountDownLatch(1);
        MockTransportService primaryTransportService = ((MockTransportService) internalCluster().getInstance(
            TransportService.class,
            primary
        ));
        primaryTransportService.addSendBehavior(
            internalCluster().getInstance(TransportService.class, replica),
            (connection, requestId, action, request, options) -> {
                if (action.equals(SegmentReplicationTargetService.Actions.FILE_CHUNK)) {
                    FileChunkRequest req = (FileChunkRequest) request;
                    logger.debug("file chunk [{}] lastChunk: {}", req, req.lastChunk());
                    if (req.name().endsWith("cfs") && req.lastChunk()) {
                        try {
                            waitForFileCopy.countDown();
                            logger.info("--> Waiting for file copy");
                            blockFileCopy.await();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
                connection.sendRequest(requestId, action, request, options);
            }
        );

        // perform refresh to start round of segment replication
        refresh(INDEX_NAME);

        // wait for segrep to start and copy temporary files
        waitForFileCopy.await();

        final IndexShard replicaShard = getIndexShard(replica, INDEX_NAME);
        // Wait until replica has written a tmp file to disk.
        List<String> temporaryFiles = new ArrayList<>();
        assertBusy(() -> {
            // verify replica contains temporary files
            temporaryFiles.addAll(
                Arrays.stream(replicaShard.store().directory().listAll())
                    .filter(fileName -> fileName.startsWith(REPLICATION_PREFIX))
                    .collect(Collectors.toList())
            );
            logger.info("--> temporaryFiles {}", temporaryFiles);
            assertTrue(temporaryFiles.size() > 0);
        });

        // Clear scroll query, this should clean up files on replica
        client(replica).prepareClearScroll().addScrollId(searchResponse.getScrollId()).get();

        // verify temporary files still exist
        List<String> temporaryFilesPostClear = Arrays.stream(replicaShard.store().directory().listAll())
            .filter(fileName -> fileName.startsWith(REPLICATION_PREFIX))
            .collect(Collectors.toList());
        logger.info("--> temporaryFilesPostClear {}", temporaryFilesPostClear);

        // Unblock segment replication
        blockFileCopy.countDown();

        assertTrue(temporaryFilesPostClear.containsAll(temporaryFiles));

        // wait for replica to catch up and verify doc count
        assertBusy(() -> {
            assertEquals(
                getIndexShard(primary, INDEX_NAME).getLatestReplicationCheckpoint().getSegmentInfosVersion(),
                getIndexShard(replica, INDEX_NAME).getLatestReplicationCheckpoint().getSegmentInfosVersion()
            );
        });
        verifyStoreContent();
        waitForSearchableDocs(finalDocCount, primary, replica);
    }

    public void testPitCreatedOnReplica() throws Exception {
        final String primary = internalCluster().startNode();
        createIndex(INDEX_NAME);
        ensureYellowAndNoInitializingShards(INDEX_NAME);
        final String replica = internalCluster().startNode();
        ensureGreen(INDEX_NAME);
        client().prepareIndex(INDEX_NAME)
            .setId("1")
            .setSource("foo", randomInt())
            .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
            .get();
        refresh(INDEX_NAME);

        client().prepareIndex(INDEX_NAME)
            .setId("2")
            .setSource("foo", randomInt())
            .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
            .get();
        for (int i = 3; i < 100; i++) {
            client().prepareIndex(INDEX_NAME)
                .setId(String.valueOf(i))
                .setSource("foo", randomInt())
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();
            refresh(INDEX_NAME);
        }
        // wait until replication finishes, then make the pit request.
        assertBusy(
            () -> assertEquals(
                getIndexShard(primary, INDEX_NAME).getLatestReplicationCheckpoint().getSegmentInfosVersion(),
                getIndexShard(replica, INDEX_NAME).getLatestReplicationCheckpoint().getSegmentInfosVersion()
            )
        );
        CreatePitRequest request = new CreatePitRequest(TimeValue.timeValueDays(1), false);
        request.setPreference("_only_local");
        request.setIndices(new String[] { INDEX_NAME });
        ActionFuture<CreatePitResponse> execute = client(replica).execute(CreatePitAction.INSTANCE, request);
        CreatePitResponse pitResponse = execute.get();
        SearchResponse searchResponse = client(replica).prepareSearch(INDEX_NAME)
            .setSize(10)
            .setPreference("_only_local")
            .setRequestCache(false)
            .addSort("foo", SortOrder.ASC)
            .searchAfter(new Object[] { 30 })
            .setPointInTime(new PointInTimeBuilder(pitResponse.getId()).setKeepAlive(TimeValue.timeValueDays(1)))
            .get();
        assertEquals(1, searchResponse.getSuccessfulShards());
        assertEquals(1, searchResponse.getTotalShards());
        FlushRequest flushRequest = Requests.flushRequest(INDEX_NAME);
        client().admin().indices().flush(flushRequest).get();
        final IndexShard replicaShard = getIndexShard(replica, INDEX_NAME);

        // fetch the segments snapshotted when the reader context was created.
        Collection<String> snapshottedSegments;
        SearchService searchService = internalCluster().getInstance(SearchService.class, replica);
        NamedWriteableRegistry registry = internalCluster().getInstance(NamedWriteableRegistry.class, replica);
        final PitReaderContext pitReaderContext = searchService.getPitReaderContext(
            decode(registry, pitResponse.getId()).shards().get(replicaShard.routingEntry().shardId()).getSearchContextId()
        );
        try (final Engine.Searcher searcher = pitReaderContext.acquireSearcher("test")) {
            final StandardDirectoryReader standardDirectoryReader = NRTReplicationReaderManager.unwrapStandardReader(
                (OpenSearchDirectoryReader) searcher.getDirectoryReader()
            );
            final SegmentInfos infos = standardDirectoryReader.getSegmentInfos();
            snapshottedSegments = infos.files(false);
        }

        flush(INDEX_NAME);
        for (int i = 101; i < 200; i++) {
            client().prepareIndex(INDEX_NAME)
                .setId(String.valueOf(i))
                .setSource("foo", randomInt())
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();
            refresh(INDEX_NAME);
            if (randomBoolean()) {
                client().admin().indices().prepareForceMerge(INDEX_NAME).setMaxNumSegments(1).setFlush(true).get();
                flush(INDEX_NAME);
            }
        }
        assertBusy(() -> {
            assertEquals(
                getIndexShard(primary, INDEX_NAME).getLatestReplicationCheckpoint().getSegmentInfosVersion(),
                getIndexShard(replica, INDEX_NAME).getLatestReplicationCheckpoint().getSegmentInfosVersion()
            );
        });

        client().admin().indices().prepareForceMerge(INDEX_NAME).setMaxNumSegments(1).setFlush(true).get();
        assertBusy(() -> {
            assertEquals(
                getIndexShard(primary, INDEX_NAME).getLatestReplicationCheckpoint().getSegmentInfosVersion(),
                getIndexShard(replica, INDEX_NAME).getLatestReplicationCheckpoint().getSegmentInfosVersion()
            );
        });
        // Test stats
        IndicesStatsRequest indicesStatsRequest = new IndicesStatsRequest();
        indicesStatsRequest.indices(INDEX_NAME);
        indicesStatsRequest.all();
        IndicesStatsResponse indicesStatsResponse = client().admin().indices().stats(indicesStatsRequest).get();
        long pitCurrent = indicesStatsResponse.getIndex(INDEX_NAME).getTotal().search.getTotal().getPitCurrent();
        long openContexts = indicesStatsResponse.getIndex(INDEX_NAME).getTotal().search.getOpenContexts();
        assertEquals(1, pitCurrent);
        assertEquals(1, openContexts);
        SearchResponse resp = client(replica).prepareSearch(INDEX_NAME)
            .setSize(10)
            .setPreference("_only_local")
            .addSort("foo", SortOrder.ASC)
            .searchAfter(new Object[] { 30 })
            .setPointInTime(new PointInTimeBuilder(pitResponse.getId()).setKeepAlive(TimeValue.timeValueDays(1)))
            .setRequestCache(false)
            .get();
        PitTestsUtil.assertUsingGetAllPits(client(replica), pitResponse.getId(), pitResponse.getCreationTime());
        assertSegments(false, INDEX_NAME, 1, client(replica), pitResponse.getId());

        List<String> currentFiles = List.of(replicaShard.store().directory().listAll());
        assertTrue("Files should be preserved", currentFiles.containsAll(snapshottedSegments));

        // delete the PIT
        DeletePitRequest deletePITRequest = new DeletePitRequest(pitResponse.getId());
        client().execute(DeletePitAction.INSTANCE, deletePITRequest).actionGet();

        currentFiles = List.of(replicaShard.store().directory().listAll());
        assertFalse("Files should be cleaned up", currentFiles.containsAll(snapshottedSegments));
    }

    /**
     * This tests that if a primary receives docs while a replica is performing round of segrep during recovery
     * the replica will catch up to latest checkpoint once recovery completes without requiring an additional primary refresh/flush.
     */
    public void testPrimaryReceivesDocsDuringReplicaRecovery() throws Exception {
        final List<String> nodes = new ArrayList<>();
        final String primaryNode = internalCluster().startNode();
        nodes.add(primaryNode);
        final Settings settings = Settings.builder().put(indexSettings()).put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0).build();
        createIndex(INDEX_NAME, settings);
        ensureGreen(INDEX_NAME);
        // start a replica node, initially will be empty with no shard assignment.
        final String replicaNode = internalCluster().startNode();
        nodes.add(replicaNode);

        // index a doc.
        client().prepareIndex(INDEX_NAME).setId("1").setSource("foo", randomInt()).get();
        refresh(INDEX_NAME);

        CountDownLatch latch = new CountDownLatch(1);
        // block replication
        try (final Releasable ignored = blockReplication(List.of(replicaNode), latch)) {
            // update to add replica, initiating recovery, this will get stuck at last step
            assertAcked(
                client().admin()
                    .indices()
                    .prepareUpdateSettings(INDEX_NAME)
                    .setSettings(Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1))
            );
            ensureYellow(INDEX_NAME);
            // index another doc while blocked, this would not get replicated to replica.
            client().prepareIndex(INDEX_NAME).setId("2").setSource("foo2", randomInt()).get();
            refresh(INDEX_NAME);
        }
        ensureGreen(INDEX_NAME);
        waitForSearchableDocs(2, nodes);
    }

    private boolean segmentReplicationWithRemoteEnabled() {
        return IndexMetadata.INDEX_REMOTE_STORE_ENABLED_SETTING.get(indexSettings()).booleanValue()
            && "true".equalsIgnoreCase(featureFlagSettings().get(FeatureFlags.SEGMENT_REPLICATION_EXPERIMENTAL));
    }
}
