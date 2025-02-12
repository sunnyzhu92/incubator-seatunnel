/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.engine.e2e;

import static com.google.common.base.Preconditions.checkArgument;

import org.apache.seatunnel.common.config.Common;
import org.apache.seatunnel.common.config.DeployMode;
import org.apache.seatunnel.common.constants.JobMode;
import org.apache.seatunnel.common.utils.FileUtils;
import org.apache.seatunnel.engine.client.SeaTunnelClient;
import org.apache.seatunnel.engine.client.job.ClientJobProxy;
import org.apache.seatunnel.engine.client.job.JobExecutionEnvironment;
import org.apache.seatunnel.engine.common.config.ConfigProvider;
import org.apache.seatunnel.engine.common.config.JobConfig;
import org.apache.seatunnel.engine.common.config.SeaTunnelConfig;
import org.apache.seatunnel.engine.core.job.JobStatus;
import org.apache.seatunnel.engine.server.SeaTunnelServerStarter;

import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.Config;
import com.hazelcast.instance.impl.HazelcastInstanceImpl;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.org.apache.commons.lang3.tuple.ImmutablePair;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Cluster fault tolerance test. Test the job recovery capability and data consistency assurance capability in case of cluster node failure
 */
@Slf4j
public class ClusterFaultToleranceIT {

    public static final String DYNAMIC_TEST_CASE_NAME = "dynamic_test_case_name";

    public static final String DYNAMIC_JOB_MODE = "dynamic_job_mode";

    public static final String DYNAMIC_TEST_ROW_NUM_PER_PARALLELISM = "dynamic_test_row_num_per_parallelism";

    public static final String DYNAMIC_TEST_PARALLELISM = "dynamic_test_parallelism";

    @SuppressWarnings("checkstyle:RegexpSingleline")
    @Test
    @Disabled
    public void testBatchJobRunOkIn2Node() throws ExecutionException, InterruptedException {
        String testCaseName = "testBatchJobRunOkIn2Node";
        String testClusterName = "ClusterFaultToleranceIT_testBatchJobRunOkIn2Node";
        long testRowNumber = 1000;
        int testParallelism = 6;

        HazelcastInstanceImpl node1 = null;
        HazelcastInstanceImpl node2 = null;
        SeaTunnelClient engineClient = null;

        SeaTunnelConfig seaTunnelConfig = ConfigProvider.locateAndGetSeaTunnelConfig();
        seaTunnelConfig.getHazelcastConfig().setClusterName(TestUtils.getClusterName(testClusterName));

        try {
            node1 = SeaTunnelServerStarter.createHazelcastInstance(seaTunnelConfig);

            node2 = SeaTunnelServerStarter.createHazelcastInstance(seaTunnelConfig);

            // waiting all node added to cluster
            HazelcastInstanceImpl finalNode = node1;
            Awaitility.await().atMost(10000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> Assertions.assertEquals(2, finalNode.getCluster().getMembers().size()));

            Common.setDeployMode(DeployMode.CLIENT);
            ImmutablePair<String, String> testResources =
                createTestResources(testCaseName, JobMode.BATCH, testRowNumber, testParallelism);
            JobConfig jobConfig = new JobConfig();
            jobConfig.setName(testCaseName);

            ClientConfig clientConfig = ConfigProvider.locateAndGetClientConfig();
            clientConfig.setClusterName(
                TestUtils.getClusterName(testClusterName));
            engineClient = new SeaTunnelClient(clientConfig);
            JobExecutionEnvironment jobExecutionEnv =
                engineClient.createExecutionContext(testResources.getRight(), jobConfig);
            ClientJobProxy clientJobProxy = jobExecutionEnv.execute();

            CompletableFuture<JobStatus> objectCompletableFuture = CompletableFuture.supplyAsync(clientJobProxy::waitForJobComplete);
            Awaitility.await().atMost(200000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Thread.sleep(2000);
                    System.out.println(FileUtils.getFileLineNumberFromDir(testResources.getLeft()));
                    Assertions.assertTrue(
                        objectCompletableFuture.isDone() && JobStatus.FINISHED.equals(objectCompletableFuture.get()));
                });

            Long fileLineNumberFromDir = FileUtils.getFileLineNumberFromDir(testResources.getLeft());
            Assertions.assertEquals(testRowNumber * testParallelism, fileLineNumberFromDir);
            System.out.println(engineClient.getJobMetrics(clientJobProxy.getJobId()));
        } finally {
            if (engineClient != null) {
                engineClient.shutdown();
            }

            if (node1 != null) {
                node1.shutdown();
            }

            if (node2 != null) {
                node2.shutdown();
            }
        }
    }

    /**
     * Create the test job config file basic on cluster_batch_fake_to_localfile_template.conf
     * It will delete the test sink target path before return the final job config file path
     *
     * @param testCaseName testCaseName
     * @param jobMode      jobMode
     * @param rowNumber    row.num per FakeSource parallelism
     * @param parallelism  FakeSource parallelism
     */
    private ImmutablePair<String, String> createTestResources(@NonNull String testCaseName, @NonNull JobMode jobMode,
                                                              long rowNumber, int parallelism) {
        checkArgument(rowNumber > 0, "rowNumber must greater than 0");
        checkArgument(parallelism > 0, "parallelism must greater than 0");
        Map<String, String> valueMap = new HashMap<>();
        valueMap.put(DYNAMIC_TEST_CASE_NAME, testCaseName);
        valueMap.put(DYNAMIC_JOB_MODE, jobMode.toString());
        valueMap.put(DYNAMIC_TEST_ROW_NUM_PER_PARALLELISM, String.valueOf(rowNumber));
        valueMap.put(DYNAMIC_TEST_PARALLELISM, String.valueOf(parallelism));

        String targetDir = "/tmp/hive/warehouse/" + testCaseName;
        targetDir = targetDir.replace("/", File.separator);

        // clear target dir before test
        FileUtils.createNewDir(targetDir);

        String targetConfigFilePath =
            File.separator + "tmp" + File.separator + "test_conf" + File.separator + testCaseName +
                ".conf";
        TestUtils.createTestConfigFileFromTemplate("cluster_batch_fake_to_localfile_template.conf", valueMap,
            targetConfigFilePath);

        return new ImmutablePair<>(targetDir, targetConfigFilePath);
    }

    @SuppressWarnings("checkstyle:RegexpSingleline")
    @Test
    @Disabled
    public void testStreamJobRunOkIn2Node() throws ExecutionException, InterruptedException {
        String testCaseName = "testStreamJobRunOkIn2Node";
        String testClusterName = "ClusterFaultToleranceIT_testStreamJobRunOkIn2Node";
        long testRowNumber = 1000;
        int testParallelism = 6;
        HazelcastInstanceImpl node1 = null;
        HazelcastInstanceImpl node2 = null;
        SeaTunnelClient engineClient = null;

        SeaTunnelConfig seaTunnelConfig = ConfigProvider.locateAndGetSeaTunnelConfig();
        seaTunnelConfig.getHazelcastConfig().setClusterName(TestUtils.getClusterName(testClusterName));
        try {
            node1 = SeaTunnelServerStarter.createHazelcastInstance(seaTunnelConfig);

            node2 = SeaTunnelServerStarter.createHazelcastInstance(seaTunnelConfig);

            // waiting all node added to cluster
            HazelcastInstanceImpl finalNode = node1;
            Awaitility.await().atMost(10000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> Assertions.assertEquals(2, finalNode.getCluster().getMembers().size()));

            Common.setDeployMode(DeployMode.CLIENT);
            ImmutablePair<String, String> testResources =
                createTestResources(testCaseName, JobMode.STREAMING, testRowNumber, testParallelism);
            JobConfig jobConfig = new JobConfig();
            jobConfig.setName(testCaseName);

            ClientConfig clientConfig = ConfigProvider.locateAndGetClientConfig();
            clientConfig.setClusterName(
                TestUtils.getClusterName(testClusterName));
            engineClient = new SeaTunnelClient(clientConfig);
            JobExecutionEnvironment jobExecutionEnv =
                engineClient.createExecutionContext(testResources.getRight(), jobConfig);
            ClientJobProxy clientJobProxy = jobExecutionEnv.execute();

            CompletableFuture<JobStatus> objectCompletableFuture = CompletableFuture.supplyAsync(clientJobProxy::waitForJobComplete);

            Awaitility.await().atMost(2, TimeUnit.MINUTES)
                .untilAsserted(() -> {
                    Thread.sleep(2000);
                    System.out.println(FileUtils.getFileLineNumberFromDir(testResources.getLeft()));
                    Assertions.assertTrue(JobStatus.RUNNING.equals(clientJobProxy.getJobStatus()) &&
                        testRowNumber * testParallelism == FileUtils.getFileLineNumberFromDir(testResources.getLeft()));
                });

            clientJobProxy.cancelJob();

            Awaitility.await().atMost(200000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> Assertions.assertTrue(
                    objectCompletableFuture.isDone() && JobStatus.CANCELED.equals(objectCompletableFuture.get())));

            Long fileLineNumberFromDir = FileUtils.getFileLineNumberFromDir(testResources.getLeft());
            Assertions.assertEquals(testRowNumber * testParallelism, fileLineNumberFromDir);

        } finally {
            if (engineClient != null) {
                engineClient.shutdown();
            }

            if (node1 != null) {
                node1.shutdown();
            }

            if (node2 != null) {
                node2.shutdown();
            }
        }
    }

    @SuppressWarnings("checkstyle:RegexpSingleline")
    @Test
    @Disabled
    public void testBatchJobRestoreIn2NodeWorkerDown() throws ExecutionException, InterruptedException {
        String testCaseName = "testBatchJobRestoreIn2NodeWorkerDown";
        String testClusterName = "ClusterFaultToleranceIT_testBatchJobRestoreIn2NodeWorkerDown";
        long testRowNumber = 1000;
        int testParallelism = 2;
        HazelcastInstanceImpl node1 = null;
        HazelcastInstanceImpl node2 = null;
        SeaTunnelClient engineClient = null;

        SeaTunnelConfig seaTunnelConfig = ConfigProvider.locateAndGetSeaTunnelConfig();
        seaTunnelConfig.getHazelcastConfig().setClusterName(TestUtils.getClusterName(testClusterName));
        try {
            node1 = SeaTunnelServerStarter.createHazelcastInstance(seaTunnelConfig);

            node2 = SeaTunnelServerStarter.createHazelcastInstance(seaTunnelConfig);

            // waiting all node added to cluster
            HazelcastInstanceImpl finalNode = node1;
            Awaitility.await().atMost(10000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> Assertions.assertEquals(2, finalNode.getCluster().getMembers().size()));

            Common.setDeployMode(DeployMode.CLIENT);
            ImmutablePair<String, String> testResources =
                createTestResources(testCaseName, JobMode.BATCH, testRowNumber, testParallelism);
            JobConfig jobConfig = new JobConfig();
            jobConfig.setName(testCaseName);

            ClientConfig clientConfig = ConfigProvider.locateAndGetClientConfig();
            clientConfig.setClusterName(
                TestUtils.getClusterName(testClusterName));
            engineClient = new SeaTunnelClient(clientConfig);
            JobExecutionEnvironment jobExecutionEnv =
                engineClient.createExecutionContext(testResources.getRight(), jobConfig);
            ClientJobProxy clientJobProxy = jobExecutionEnv.execute();

            CompletableFuture<JobStatus> objectCompletableFuture = CompletableFuture.supplyAsync(clientJobProxy::waitForJobComplete);

            Awaitility.await().atMost(60000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    // Wait some tasks commit finished
                    Thread.sleep(2000);
                    System.out.println(FileUtils.getFileLineNumberFromDir(testResources.getLeft()));
                    Assertions.assertTrue(JobStatus.RUNNING.equals(clientJobProxy.getJobStatus()) &&
                        FileUtils.getFileLineNumberFromDir(testResources.getLeft()) > 1);
                });

            // shutdown on worker node
            node2.shutdown();

            Awaitility.await().atMost(200000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> Assertions.assertTrue(
                    objectCompletableFuture.isDone() && JobStatus.FINISHED.equals(objectCompletableFuture.get())));

            Long fileLineNumberFromDir = FileUtils.getFileLineNumberFromDir(testResources.getLeft());
            Assertions.assertEquals(testRowNumber * testParallelism, fileLineNumberFromDir);

        } finally {
            if (engineClient != null) {
                engineClient.shutdown();
            }

            if (node1 != null) {
                node1.shutdown();
            }

            if (node2 != null) {
                node2.shutdown();
            }
        }
    }

    @SuppressWarnings("checkstyle:RegexpSingleline")
    @Test
    @Disabled
    public void testStreamJobRestoreIn2NodeWorkerDown() throws ExecutionException, InterruptedException {
        String testCaseName = "testStreamJobRestoreIn2NodeWorkerDown";
        String testClusterName = "ClusterFaultToleranceIT_testStreamJobRestoreIn2NodeWorkerDown";
        long testRowNumber = 1000;
        int testParallelism = 6;
        HazelcastInstanceImpl node1 = null;
        HazelcastInstanceImpl node2 = null;
        SeaTunnelClient engineClient = null;

        SeaTunnelConfig seaTunnelConfig = ConfigProvider.locateAndGetSeaTunnelConfig();
        seaTunnelConfig.getHazelcastConfig().setClusterName(TestUtils.getClusterName(testClusterName));
        try {
            node1 = SeaTunnelServerStarter.createHazelcastInstance(seaTunnelConfig);

            node2 = SeaTunnelServerStarter.createHazelcastInstance(seaTunnelConfig);

            // waiting all node added to cluster
            HazelcastInstanceImpl finalNode = node1;
            Awaitility.await().atMost(10000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> Assertions.assertEquals(2, finalNode.getCluster().getMembers().size()));

            Common.setDeployMode(DeployMode.CLIENT);
            ImmutablePair<String, String> testResources =
                createTestResources(testCaseName, JobMode.STREAMING, testRowNumber, testParallelism);
            JobConfig jobConfig = new JobConfig();
            jobConfig.setName(testCaseName);

            ClientConfig clientConfig = ConfigProvider.locateAndGetClientConfig();
            clientConfig.setClusterName(
                TestUtils.getClusterName(testClusterName));
            engineClient = new SeaTunnelClient(clientConfig);
            JobExecutionEnvironment jobExecutionEnv =
                engineClient.createExecutionContext(testResources.getRight(), jobConfig);
            ClientJobProxy clientJobProxy = jobExecutionEnv.execute();

            CompletableFuture<JobStatus> objectCompletableFuture = CompletableFuture.supplyAsync(clientJobProxy::waitForJobComplete);

            Awaitility.await().atMost(60000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    // Wait some tasks commit finished, and we can get rows from the sink target dir
                    Thread.sleep(2000);
                    System.out.println(FileUtils.getFileLineNumberFromDir(testResources.getLeft()));
                    Assertions.assertTrue(JobStatus.RUNNING.equals(clientJobProxy.getJobStatus()) &&
                        FileUtils.getFileLineNumberFromDir(testResources.getLeft()) > 1);
                });

            Thread.sleep(5000);
            // shutdown on worker node
            node2.shutdown();

            Awaitility.await().atMost(360000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    // Wait job write all rows in file
                    Thread.sleep(2000);
                    System.out.println(FileUtils.getFileLineNumberFromDir(testResources.getLeft()));
                    Assertions.assertTrue(JobStatus.RUNNING.equals(clientJobProxy.getJobStatus()) &&
                        testRowNumber * testParallelism == FileUtils.getFileLineNumberFromDir(testResources.getLeft()));
                });

            // sleep 10s and expect the job don't write more rows.
            Thread.sleep(10000);
            clientJobProxy.cancelJob();

            Awaitility.await().atMost(200000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> Assertions.assertTrue(
                    objectCompletableFuture.isDone() && JobStatus.CANCELED.equals(objectCompletableFuture.get())));

            // check the final rows
            Long fileLineNumberFromDir = FileUtils.getFileLineNumberFromDir(testResources.getLeft());
            Assertions.assertEquals(testRowNumber * testParallelism, fileLineNumberFromDir);

        } finally {
            if (engineClient != null) {
                engineClient.shutdown();
            }

            if (node1 != null) {
                node1.shutdown();
            }

            if (node2 != null) {
                node2.shutdown();
            }
        }
    }

    @SuppressWarnings("checkstyle:RegexpSingleline")
    @Test
    @Disabled
    public void testBatchJobRestoreIn2NodeMasterDown() throws ExecutionException, InterruptedException {
        String testCaseName = "testBatchJobRestoreIn2NodeMasterDown";
        String testClusterName = "ClusterFaultToleranceIT_testBatchJobRestoreIn2NodeMasterDown";
        long testRowNumber = 1000;
        int testParallelism = 6;
        HazelcastInstanceImpl node1 = null;
        HazelcastInstanceImpl node2 = null;
        SeaTunnelClient engineClient = null;

        SeaTunnelConfig seaTunnelConfig = ConfigProvider.locateAndGetSeaTunnelConfig();
        seaTunnelConfig.getHazelcastConfig().setClusterName(TestUtils.getClusterName(testClusterName));
        try {
            node1 = SeaTunnelServerStarter.createHazelcastInstance(seaTunnelConfig);

            node2 = SeaTunnelServerStarter.createHazelcastInstance(seaTunnelConfig);

            // waiting all node added to cluster
            HazelcastInstanceImpl finalNode = node1;
            Awaitility.await().atMost(10000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> Assertions.assertEquals(2, finalNode.getCluster().getMembers().size()));

            Common.setDeployMode(DeployMode.CLIENT);
            ImmutablePair<String, String> testResources =
                createTestResources(testCaseName, JobMode.BATCH, testRowNumber, testParallelism);
            JobConfig jobConfig = new JobConfig();
            jobConfig.setName(testCaseName);

            ClientConfig clientConfig = ConfigProvider.locateAndGetClientConfig();
            clientConfig.setClusterName(
                TestUtils.getClusterName(testClusterName));
            engineClient = new SeaTunnelClient(clientConfig);
            JobExecutionEnvironment jobExecutionEnv =
                engineClient.createExecutionContext(testResources.getRight(), jobConfig);
            ClientJobProxy clientJobProxy = jobExecutionEnv.execute();

            CompletableFuture<JobStatus> objectCompletableFuture = CompletableFuture.supplyAsync(clientJobProxy::waitForJobComplete);

            Awaitility.await().atMost(60000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    // Wait some tasks commit finished
                    Thread.sleep(2000);
                    System.out.println(FileUtils.getFileLineNumberFromDir(testResources.getLeft()));
                    Assertions.assertTrue(JobStatus.RUNNING.equals(clientJobProxy.getJobStatus()) &&
                        FileUtils.getFileLineNumberFromDir(testResources.getLeft()) > 1);
                });

            // shutdown master node
            node1.shutdown();

            Awaitility.await().atMost(200000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> Assertions.assertTrue(
                    objectCompletableFuture.isDone() && JobStatus.FINISHED.equals(objectCompletableFuture.get())));

            Long fileLineNumberFromDir = FileUtils.getFileLineNumberFromDir(testResources.getLeft());
            Assertions.assertEquals(testRowNumber * testParallelism, fileLineNumberFromDir);

        } finally {
            if (engineClient != null) {
                engineClient.shutdown();
            }

            if (node1 != null) {
                node1.shutdown();
            }

            if (node2 != null) {
                node2.shutdown();
            }
        }
    }

    @SuppressWarnings("checkstyle:RegexpSingleline")
    @Test
    @Disabled
    public void testStreamJobRestoreIn2NodeMasterDown() throws ExecutionException, InterruptedException {
        String testCaseName = "testStreamJobRestoreIn2NodeMasterDown";
        String testClusterName = "ClusterFaultToleranceIT_testStreamJobRestoreIn2NodeMasterDown";
        long testRowNumber = 1000;
        int testParallelism = 6;
        HazelcastInstanceImpl node1 = null;
        HazelcastInstanceImpl node2 = null;
        SeaTunnelClient engineClient = null;

        SeaTunnelConfig seaTunnelConfig = ConfigProvider.locateAndGetSeaTunnelConfig();
        seaTunnelConfig.getHazelcastConfig().setClusterName(TestUtils.getClusterName(testClusterName));
        try {
            node1 = SeaTunnelServerStarter.createHazelcastInstance(seaTunnelConfig);

            node2 = SeaTunnelServerStarter.createHazelcastInstance(seaTunnelConfig);

            // waiting all node added to cluster
            HazelcastInstanceImpl finalNode = node1;
            Awaitility.await().atMost(10000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> Assertions.assertEquals(2, finalNode.getCluster().getMembers().size()));

            Common.setDeployMode(DeployMode.CLIENT);
            ImmutablePair<String, String> testResources =
                createTestResources(testCaseName, JobMode.STREAMING, testRowNumber, testParallelism);
            JobConfig jobConfig = new JobConfig();
            jobConfig.setName(testCaseName);

            ClientConfig clientConfig = ConfigProvider.locateAndGetClientConfig();
            clientConfig.setClusterName(
                TestUtils.getClusterName(testClusterName));
            engineClient = new SeaTunnelClient(clientConfig);
            JobExecutionEnvironment jobExecutionEnv =
                engineClient.createExecutionContext(testResources.getRight(), jobConfig);
            ClientJobProxy clientJobProxy = jobExecutionEnv.execute();

            CompletableFuture<JobStatus> objectCompletableFuture = CompletableFuture.supplyAsync(clientJobProxy::waitForJobComplete);

            Awaitility.await().atMost(60000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    // Wait some tasks commit finished, and we can get rows from the sink target dir
                    Thread.sleep(2000);
                    System.out.println(FileUtils.getFileLineNumberFromDir(testResources.getLeft()));
                    Assertions.assertTrue(JobStatus.RUNNING.equals(clientJobProxy.getJobStatus()) &&
                        FileUtils.getFileLineNumberFromDir(testResources.getLeft()) > 1);
                });

            // shutdown master node
            node1.shutdown();

            Awaitility.await().atMost(600000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    // Wait job write all rows in file
                    Thread.sleep(2000);
                    System.out.println(FileUtils.getFileLineNumberFromDir(testResources.getLeft()));
                    Assertions.assertTrue(JobStatus.RUNNING.equals(clientJobProxy.getJobStatus()) &&
                        testRowNumber * testParallelism == FileUtils.getFileLineNumberFromDir(testResources.getLeft()));
                });

            // sleep 10s and expect the job don't write more rows.
            Thread.sleep(10000);
            clientJobProxy.cancelJob();

            Awaitility.await().atMost(200000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> Assertions.assertTrue(
                    objectCompletableFuture.isDone() && JobStatus.CANCELED.equals(objectCompletableFuture.get())));

            // check the final rows
            Long fileLineNumberFromDir = FileUtils.getFileLineNumberFromDir(testResources.getLeft());
            Assertions.assertEquals(testRowNumber * testParallelism, fileLineNumberFromDir);

        } finally {
            if (engineClient != null) {
                engineClient.shutdown();
            }

            if (node1 != null) {
                node1.shutdown();
            }

            if (node2 != null) {
                node2.shutdown();
            }
        }
    }

    @SuppressWarnings("checkstyle:RegexpSingleline")
    @Test
    @Disabled
    public void testStreamJobRestoreInAllNodeDown() throws ExecutionException, InterruptedException {
        String testCaseName = "testStreamJobRestoreInAllNodeDown";
        String testClusterName = "ClusterFaultToleranceIT_testStreamJobRestoreInAllNodeDown";
        int testRowNumber = 1000;
        int testParallelism = 6;
        HazelcastInstanceImpl node1 = null;
        HazelcastInstanceImpl node2 = null;
        SeaTunnelClient engineClient = null;

        try {
            String yaml = "hazelcast:\n" +
                "  cluster-name: seatunnel\n" +
                "  network:\n" +
                "    rest-api:\n" +
                "      enabled: true\n" +
                "      endpoint-groups:\n" +
                "        CLUSTER_WRITE:\n" +
                "          enabled: true\n" +
                "    join:\n" +
                "      tcp-ip:\n" +
                "        enabled: true\n" +
                "        member-list:\n" +
                "          - localhost\n" +
                "    port:\n" +
                "      auto-increment: true\n" +
                "      port-count: 100\n" +
                "      port: 5801\n" +
                "  map:\n" +
                "    engine*:\n" +
                "      map-store:\n" +
                "        enabled: true\n" +
                "        initial-mode: EAGER\n" +
                "        factory-class-name: org.apache.seatunnel.engine.server.persistence.FileMapStoreFactory\n" +
                "        properties:\n" +
                "          type: hdfs\n" +
                "          namespace: /tmp/seatunnel/imap\n" +
                "          clusterName: " + testClusterName + "\n" +
                "          fs.defaultFS: file:///\n" +
                "\n" +
                "  properties:\n" +
                "    hazelcast.invocation.max.retry.count: 200\n" +
                "    hazelcast.tcp.join.port.try.count: 30\n" +
                "    hazelcast.invocation.retry.pause.millis: 2000\n" +
                "    hazelcast.slow.operation.detector.stacktrace.logging.enabled: true\n" +
                "    hazelcast.logging.type: log4j2\n";

            Config hazelcastConfig = Config.loadFromString(yaml);
            hazelcastConfig.setClusterName(TestUtils.getClusterName(testClusterName));
            SeaTunnelConfig seaTunnelConfig = ConfigProvider.locateAndGetSeaTunnelConfig();
            seaTunnelConfig.setHazelcastConfig(hazelcastConfig);
            node1 = SeaTunnelServerStarter.createHazelcastInstance(seaTunnelConfig);

            node2 = SeaTunnelServerStarter.createHazelcastInstance(seaTunnelConfig);

            // waiting all node added to cluster
            HazelcastInstanceImpl finalNode = node1;
            Awaitility.await().atMost(10000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> Assertions.assertEquals(2, finalNode.getCluster().getMembers().size()));

            Common.setDeployMode(DeployMode.CLIENT);
            ImmutablePair<String, String> testResources =
                createTestResources(testCaseName, JobMode.STREAMING, testRowNumber, testParallelism);
            JobConfig jobConfig = new JobConfig();
            jobConfig.setName(testCaseName);

            ClientConfig clientConfig = ConfigProvider.locateAndGetClientConfig();
            clientConfig.setClusterName(
                TestUtils.getClusterName(testClusterName));
            engineClient = new SeaTunnelClient(clientConfig);
            JobExecutionEnvironment jobExecutionEnv =
                engineClient.createExecutionContext(testResources.getRight(), jobConfig);
            ClientJobProxy clientJobProxy = jobExecutionEnv.execute();

            CompletableFuture<JobStatus> objectCompletableFuture = CompletableFuture.supplyAsync(clientJobProxy::waitForJobComplete);

            Awaitility.await().atMost(60000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    // Wait some tasks commit finished, and we can get rows from the sink target dir
                    Thread.sleep(2000);
                    System.out.println(FileUtils.getFileLineNumberFromDir(testResources.getLeft()));
                    Assertions.assertTrue(JobStatus.RUNNING.equals(clientJobProxy.getJobStatus()) &&
                        FileUtils.getFileLineNumberFromDir(testResources.getLeft()) > 1);
                });

            Thread.sleep(5000);
            // shutdown all node
            node1.shutdown();
            node2.shutdown();

            Thread.sleep(10000);

            node1 = SeaTunnelServerStarter.createHazelcastInstance(seaTunnelConfig);

            node2 = SeaTunnelServerStarter.createHazelcastInstance(seaTunnelConfig);

            // waiting all node added to cluster
            HazelcastInstanceImpl restoreFinalNode = node1;
            Awaitility.await().atMost(10000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> Assertions.assertEquals(2, restoreFinalNode.getCluster().getMembers().size()));

            Awaitility.await().atMost(360000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    // Wait job write all rows in file
                    Thread.sleep(2000);
                    System.out.println(FileUtils.getFileLineNumberFromDir(testResources.getLeft()));
                    Assertions.assertTrue(JobStatus.RUNNING.equals(clientJobProxy.getJobStatus()) &&
                        testRowNumber * testParallelism == FileUtils.getFileLineNumberFromDir(testResources.getLeft()));
                });

            // sleep 10s and expect the job don't write more rows.
            Thread.sleep(10000);
            clientJobProxy.cancelJob();

            Awaitility.await().atMost(360000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> Assertions.assertTrue(
                    objectCompletableFuture.isDone() && JobStatus.CANCELED.equals(objectCompletableFuture.get())));

            // prove that the task was restarted
            Long fileLineNumberFromDir = FileUtils.getFileLineNumberFromDir(testResources.getLeft());
            Assertions.assertEquals(testRowNumber * testParallelism, fileLineNumberFromDir);

        } finally {
            if (engineClient != null) {
                engineClient.shutdown();
            }

            if (node1 != null) {
                node1.shutdown();
            }

            if (node2 != null) {
                node2.shutdown();
            }
        }
    }
}
