/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.samza.test.processor;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import kafka.admin.AdminUtils;
import kafka.utils.TestUtils;
import org.I0Itec.zkclient.ZkClient;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.samza.SamzaException;
import org.apache.samza.application.StreamApplication;
import org.apache.samza.config.ApplicationConfig;
import org.apache.samza.config.Config;
import org.apache.samza.config.JobConfig;
import org.apache.samza.config.JobCoordinatorConfig;
import org.apache.samza.config.MapConfig;
import org.apache.samza.config.TaskConfig;
import org.apache.samza.config.TaskConfigJava;
import org.apache.samza.config.ZkConfig;
import org.apache.samza.container.TaskName;
import org.apache.samza.job.ApplicationStatus;
import org.apache.samza.job.model.JobModel;
import org.apache.samza.job.model.TaskModel;
import org.apache.samza.runtime.LocalApplicationRunner;
import org.apache.samza.test.StandaloneIntegrationTestHarness;
import org.apache.samza.test.StandaloneTestUtils;
import org.apache.samza.test.processor.TestStreamApplication.StreamApplicationCallback;
import org.apache.samza.test.processor.TestStreamApplication.TestKafkaEvent;
import org.apache.samza.util.NoOpMetricsRegistry;
import org.apache.samza.zk.ZkJobCoordinatorFactory;
import org.apache.samza.zk.ZkKeyBuilder;
import org.apache.samza.zk.ZkUtils;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for {@link LocalApplicationRunner}.
 *
 * Brings up embedded ZooKeeper, Kafka broker and launches multiple {@link StreamApplication} through
 * {@link LocalApplicationRunner} to verify the guarantees made in stand alone execution environment.
 */
public class TestZkLocalApplicationRunner extends StandaloneIntegrationTestHarness {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestZkLocalApplicationRunner.class);

  private static final int NUM_KAFKA_EVENTS = 300;
  private static final int ZK_CONNECTION_TIMEOUT_MS = 5000;
  private static final int ZK_SESSION_TIMEOUT_MS = 10000;
  private static final String TEST_SYSTEM = "TestSystemName";
  private static final String TEST_SSP_GROUPER_FACTORY = "org.apache.samza.container.grouper.stream.GroupByPartitionFactory";
  private static final String TEST_TASK_GROUPER_FACTORY = "org.apache.samza.container.grouper.task.GroupByContainerIdsFactory";
  private static final String TEST_JOB_COORDINATOR_FACTORY = "org.apache.samza.zk.ZkJobCoordinatorFactory";
  private static final String TEST_SYSTEM_FACTORY = "org.apache.samza.system.kafka.KafkaSystemFactory";
  private static final String TASK_SHUTDOWN_MS = "2000";
  private static final String JOB_DEBOUNCE_TIME_MS = "2000";
  private static final String BARRIER_TIMEOUT_MS = "2000";
  private static final String[] PROCESSOR_IDS = new String[] {"0000000000", "0000000001", "0000000002"};

  private String inputKafkaTopic;
  private String outputKafkaTopic;
  private String inputSinglePartitionKafkaTopic;
  private String outputSinglePartitionKafkaTopic;
  private ZkUtils zkUtils;
  private ApplicationConfig applicationConfig1;
  private ApplicationConfig applicationConfig2;
  private ApplicationConfig applicationConfig3;
  private String testStreamAppName;
  private String testStreamAppId;

  @Rule
  public Timeout testTimeOutInMillis = new Timeout(150000);

  @Rule
  public final ExpectedException expectedException = ExpectedException.none();

  @Override
  public void setUp() {
    super.setUp();
    String uniqueTestId = UUID.randomUUID().toString();
    testStreamAppName = String.format("test-app-name-%s", uniqueTestId);
    testStreamAppId = String.format("test-app-id-%s", uniqueTestId);
    inputKafkaTopic = String.format("test-input-topic-%s", uniqueTestId);
    outputKafkaTopic = String.format("test-output-topic-%s", uniqueTestId);
    inputSinglePartitionKafkaTopic = String.format("test-input-single-partition-topic-%s", uniqueTestId);
    outputSinglePartitionKafkaTopic = String.format("test-output-single-partition-topic-%s", uniqueTestId);

    // Set up stream application config map with the given testStreamAppName, testStreamAppId and test kafka system
    // TODO: processorId should typically come up from a processorID generator as processor.id will be deprecated in 0.14.0+
    Map<String, String> configMap =
        buildStreamApplicationConfigMap(TEST_SYSTEM, inputKafkaTopic, testStreamAppName, testStreamAppId);
    configMap.put(JobConfig.PROCESSOR_ID(), PROCESSOR_IDS[0]);
    applicationConfig1 = new ApplicationConfig(new MapConfig(configMap));
    configMap.put(JobConfig.PROCESSOR_ID(), PROCESSOR_IDS[1]);
    applicationConfig2 = new ApplicationConfig(new MapConfig(configMap));
    configMap.put(JobConfig.PROCESSOR_ID(), PROCESSOR_IDS[2]);
    applicationConfig3 = new ApplicationConfig(new MapConfig(configMap));

    ZkClient zkClient = new ZkClient(zkConnect());
    ZkKeyBuilder zkKeyBuilder = new ZkKeyBuilder(ZkJobCoordinatorFactory.getJobCoordinationZkPath(applicationConfig1));
    zkUtils = new ZkUtils(zkKeyBuilder, zkClient, ZK_CONNECTION_TIMEOUT_MS, ZK_SESSION_TIMEOUT_MS, new NoOpMetricsRegistry());
    zkUtils.connect();

    for (String kafkaTopic : ImmutableList.of(inputKafkaTopic, outputKafkaTopic)) {
      LOGGER.info("Creating kafka topic: {}.", kafkaTopic);
      TestUtils.createTopic(zkUtils(), kafkaTopic, 5, 1, servers(), new Properties());
      if (AdminUtils.topicExists(zkUtils(), kafkaTopic)) {
        LOGGER.info("Topic: {} was created", kafkaTopic);
      } else {
        Assert.fail(String.format("Unable to create kafka topic: %s.", kafkaTopic));
      }
    }
    for (String kafkaTopic : ImmutableList.of(inputSinglePartitionKafkaTopic, outputSinglePartitionKafkaTopic)) {
      LOGGER.info("Creating kafka topic: {}.", kafkaTopic);
      TestUtils.createTopic(zkUtils(), kafkaTopic, 1, 1, servers(), new Properties());
      if (AdminUtils.topicExists(zkUtils(), kafkaTopic)) {
        LOGGER.info("Topic: {} was created", kafkaTopic);
      } else {
        Assert.fail(String.format("Unable to create kafka topic: %s.", kafkaTopic));
      }
    }
  }

  public void tearDown() {
    for (String kafkaTopic : ImmutableList.of(inputKafkaTopic, outputKafkaTopic)) {
      LOGGER.info("Deleting kafka topic: {}.", kafkaTopic);
      AdminUtils.deleteTopic(zkUtils(), kafkaTopic);
    }
    for (String kafkaTopic : ImmutableList.of(inputSinglePartitionKafkaTopic, outputSinglePartitionKafkaTopic)) {
      LOGGER.info("Deleting kafka topic: {}.", kafkaTopic);
      AdminUtils.deleteTopic(zkUtils(), kafkaTopic);
    }
    zkUtils.close();
    super.tearDown();
  }

  private void publishKafkaEvents(String topic, int startIndex, int endIndex, String streamProcessorId) {
    KafkaProducer producer = getKafkaProducer();
    for (int eventIndex = startIndex; eventIndex < endIndex; eventIndex++) {
      try {
        LOGGER.info("Publish kafka event with index : {} for stream processor: {}.", eventIndex, streamProcessorId);
        producer.send(new ProducerRecord(topic, new TestKafkaEvent(streamProcessorId, String.valueOf(eventIndex)).toString().getBytes()));
      } catch (Exception  e) {
        LOGGER.error("Publishing to kafka topic: {} resulted in exception: {}.", new Object[]{topic, e});
        throw new SamzaException(e);
      }
    }
  }

  private Map<String, String> buildStreamApplicationConfigMap(String systemName, String inputTopic,
      String appName, String appId) {
    Map<String, String> samzaContainerConfig = ImmutableMap.<String, String>builder()
        .put(ZkConfig.ZK_CONSENSUS_TIMEOUT_MS, BARRIER_TIMEOUT_MS)
        .put(TaskConfig.INPUT_STREAMS(), inputTopic)
        .put(JobConfig.JOB_DEFAULT_SYSTEM(), systemName)
        .put(TaskConfig.IGNORED_EXCEPTIONS(), "*")
        .put(ZkConfig.ZK_CONNECT, zkConnect())
        .put(JobConfig.SSP_GROUPER_FACTORY(), TEST_SSP_GROUPER_FACTORY)
        .put(TaskConfig.GROUPER_FACTORY(), TEST_TASK_GROUPER_FACTORY)
        .put(JobCoordinatorConfig.JOB_COORDINATOR_FACTORY, TEST_JOB_COORDINATOR_FACTORY)
        .put(ApplicationConfig.APP_NAME, appName)
        .put(ApplicationConfig.APP_ID, appId)
        .put(String.format("systems.%s.samza.factory", systemName), TEST_SYSTEM_FACTORY)
        .put(JobConfig.JOB_NAME(), appName)
        .put(JobConfig.JOB_ID(), appId)
        .put(TaskConfigJava.TASK_SHUTDOWN_MS, TASK_SHUTDOWN_MS)
        .put(TaskConfig.DROP_PRODUCER_ERROR(), "true")
        .put(JobConfig.JOB_DEBOUNCE_TIME_MS(), JOB_DEBOUNCE_TIME_MS)
        .build();
    Map<String, String> applicationConfig = Maps.newHashMap(samzaContainerConfig);

    applicationConfig.putAll(StandaloneTestUtils.getKafkaSystemConfigs(systemName, bootstrapServers(), zkConnect(), null, StandaloneTestUtils.SerdeAlias.STRING, true));
    return applicationConfig;
  }

  /**
   * sspGrouper is set to GroupBySystemStreamPartitionFactory.
   * Run a stream application(streamApp1) consuming messages from input topic(effectively one container).
   *
   * In the callback triggered by streamApp1 after processing a message, bring up an another stream application(streamApp2).
   *
   * Assertions:
   *           A) JobModel generated before and after the addition of streamApp2 should be equal.
   *           B) Second stream application(streamApp2) should not join the group and process any message.
   */

  @Test
  public void shouldStopNewProcessorsJoiningGroupWhenNumContainersIsGreaterThanNumTasks() throws InterruptedException {
    // Set up kafka topics.
    publishKafkaEvents(inputSinglePartitionKafkaTopic, 0, NUM_KAFKA_EVENTS * 2, PROCESSOR_IDS[0]);

    // Configuration, verification variables
    MapConfig testConfig = new MapConfig(ImmutableMap.of(JobConfig.SSP_GROUPER_FACTORY(),
        "org.apache.samza.container.grouper.stream.GroupBySystemStreamPartitionFactory", JobConfig.JOB_DEBOUNCE_TIME_MS(), "10"));
    // Declared as final array to update it from streamApplication callback(Variable should be declared final to access in lambda block).
    final JobModel[] previousJobModel = new JobModel[1];
    final String[] previousJobModelVersion = new String[1];
    AtomicBoolean hasSecondProcessorJoined = new AtomicBoolean(false);
    final CountDownLatch secondProcessorRegistered = new CountDownLatch(1);

    zkUtils.subscribeToProcessorChange((parentPath, currentChilds) -> {
        // When streamApp2 with id: PROCESSOR_IDS[1] is registered, start processing message in streamApp1.
        if (currentChilds.contains(PROCESSOR_IDS[1])) {
          secondProcessorRegistered.countDown();
        }
      });

    // Set up stream app 2.
    CountDownLatch processedMessagesLatch = new CountDownLatch(NUM_KAFKA_EVENTS);
    Config testAppConfig2 = new MapConfig(applicationConfig2, testConfig);
    LocalApplicationRunner localApplicationRunner2 = new LocalApplicationRunner(testAppConfig2);
    StreamApplication streamApp2 = TestStreamApplication.getInstance(inputSinglePartitionKafkaTopic, outputSinglePartitionKafkaTopic,
        processedMessagesLatch, null, null, testAppConfig2);

    // Callback handler for streamApp1.
    StreamApplicationCallback streamApplicationCallback = message -> {
      if (hasSecondProcessorJoined.compareAndSet(false, true)) {
        previousJobModelVersion[0] = zkUtils.getJobModelVersion();
        previousJobModel[0] = zkUtils.getJobModel(previousJobModelVersion[0]);
        localApplicationRunner2.run(streamApp2);
        try {
          // Wait for streamApp2 to register with zookeeper.
          secondProcessorRegistered.await();
        } catch (InterruptedException e) {
        }
      }
    };

    CountDownLatch kafkaEventsConsumedLatch = new CountDownLatch(NUM_KAFKA_EVENTS * 2);

    // Set up stream app 1.
    Config testAppConfig1 = new MapConfig(applicationConfig1, testConfig);
    LocalApplicationRunner localApplicationRunner1 = new LocalApplicationRunner(testAppConfig1);
    StreamApplication streamApp1 = TestStreamApplication.getInstance(inputSinglePartitionKafkaTopic, outputSinglePartitionKafkaTopic,
        null, streamApplicationCallback, kafkaEventsConsumedLatch, testAppConfig1);
    localApplicationRunner1.run(streamApp1);

    kafkaEventsConsumedLatch.await();

    String currentJobModelVersion = zkUtils.getJobModelVersion();
    JobModel updatedJobModel = zkUtils.getJobModel(currentJobModelVersion);

    // Job model before and after the addition of second stream processor should be the same.
    assertEquals(previousJobModel[0], updatedJobModel);
    assertEquals(new MapConfig(), updatedJobModel.getConfig());
    // TODO: After SAMZA-1364 add assertion for localApplicationRunner2.status(streamApp)
    // ProcessedMessagesLatch shouldn't have changed. Should retain it's initial value.
    assertEquals(NUM_KAFKA_EVENTS, processedMessagesLatch.getCount());

    // TODO: re-enable the following kill and waitForFinish calls after fixing SAMZA-1665
    // localApplicationRunner1.kill(streamApp1);
    // localApplicationRunner2.kill(streamApp2);

    // localApplicationRunner1.waitForFinish();
    // localApplicationRunner2.waitForFinish();
  }

  /**
   * sspGrouper is set to AllSspToSingleTaskGrouperFactory (All ssps from input kafka topic are mapped to a single task per container).
   * AllSspToSingleTaskGrouperFactory should be used only with high-level consumers which do the partition management
   * by themselves. Using the factory with the consumers that do not do the partition management will result in
   * each processor/task consuming all the messages from all the partitions.
   * Run a stream application(streamApp1) consuming messages from input topic(effectively one container).
   *
   * In the callback triggered by streamApp1 after processing a message, bring up an another stream application(streamApp2).
   *
   * Assertions:
   *           A) JobModel generated before and after the addition of streamApp2 should not be equal.
   *           B) Second stream application(streamApp2) should join the group and process all the messages.
   */

  @Test
  public void shouldUpdateJobModelWhenNewProcessorJoiningGroupUsingAllSspToSingleTaskGrouperFactory() throws InterruptedException {
    // Set up kafka topics.
    publishKafkaEvents(inputKafkaTopic, 0, NUM_KAFKA_EVENTS * 2, PROCESSOR_IDS[0]);

    // Configuration, verification variables
    MapConfig testConfig = new MapConfig(ImmutableMap.of(JobConfig.SSP_GROUPER_FACTORY(), "org.apache.samza.container.grouper.stream.AllSspToSingleTaskGrouperFactory", JobConfig.JOB_DEBOUNCE_TIME_MS(), "10"));
    // Declared as final array to update it from streamApplication callback(Variable should be declared final to access in lambda block).
    final JobModel[] previousJobModel = new JobModel[1];
    final String[] previousJobModelVersion = new String[1];
    AtomicBoolean hasSecondProcessorJoined = new AtomicBoolean(false);
    final CountDownLatch secondProcessorRegistered = new CountDownLatch(1);

    zkUtils.subscribeToProcessorChange((parentPath, currentChilds) -> {
        // When streamApp2 with id: PROCESSOR_IDS[1] is registered, start processing message in streamApp1.
        if (currentChilds.contains(PROCESSOR_IDS[1])) {
          secondProcessorRegistered.countDown();
        }
      });

    // Set up streamApp2.
    CountDownLatch processedMessagesLatch = new CountDownLatch(NUM_KAFKA_EVENTS * 2);
    Config testAppConfig2 = new MapConfig(applicationConfig2, testConfig);
    LocalApplicationRunner localApplicationRunner2 = new LocalApplicationRunner(testAppConfig2);
    StreamApplication streamApp2 = TestStreamApplication.getInstance(inputKafkaTopic, outputKafkaTopic, processedMessagesLatch, null, null, testAppConfig2);

    // Callback handler for streamApp1.
    StreamApplicationCallback streamApplicationCallback = message -> {
      if (hasSecondProcessorJoined.compareAndSet(false, true)) {
        previousJobModelVersion[0] = zkUtils.getJobModelVersion();
        previousJobModel[0] = zkUtils.getJobModel(previousJobModelVersion[0]);
        localApplicationRunner2.run(streamApp2);
        try {
          // Wait for streamApp2 to register with zookeeper.
          secondProcessorRegistered.await();
        } catch (InterruptedException e) {
        }
      }
    };

    // This is the latch for the messages received by streamApp1. Since streamApp1 is run first, it gets one event
    // redelivered due to re-balancing done by Zk after the streamApp2 joins (See the callback above).
    CountDownLatch kafkaEventsConsumedLatch = new CountDownLatch(NUM_KAFKA_EVENTS * 2 + 1);

    // Set up stream app 1.
    Config testAppConfig1 = new MapConfig(applicationConfig1, testConfig);
    LocalApplicationRunner localApplicationRunner1 = new LocalApplicationRunner(testAppConfig1);
    StreamApplication streamApp1 = TestStreamApplication.getInstance(inputKafkaTopic, outputKafkaTopic, null,
        streamApplicationCallback, kafkaEventsConsumedLatch, testAppConfig1);
    localApplicationRunner1.run(streamApp1);

    kafkaEventsConsumedLatch.await();

    String currentJobModelVersion = zkUtils.getJobModelVersion();
    JobModel updatedJobModel = zkUtils.getJobModel(currentJobModelVersion);

    // JobModelVersion check to verify that leader publishes new jobModel.
    assertTrue(Integer.parseInt(previousJobModelVersion[0]) < Integer.parseInt(currentJobModelVersion));

    // Job model before and after the addition of second stream processor should not be the same.
    assertTrue(!previousJobModel[0].equals(updatedJobModel));

    // Task names in the job model should be different but the set of partitions should be the same and each task name
    // should be assigned to a different container.
    assertEquals(new MapConfig(), previousJobModel[0].getConfig());
    assertEquals(previousJobModel[0].getContainers().get(PROCESSOR_IDS[0]).getTasks().size(), 1);
    assertEquals(new MapConfig(), updatedJobModel.getConfig());
    assertEquals(updatedJobModel.getContainers().get(PROCESSOR_IDS[0]).getTasks().size(), 1);
    assertEquals(updatedJobModel.getContainers().get(PROCESSOR_IDS[1]).getTasks().size(), 1);
    Map<TaskName, TaskModel> updatedTaskModelMap1 = updatedJobModel.getContainers().get(PROCESSOR_IDS[0]).getTasks();
    Map<TaskName, TaskModel> updatedTaskModelMap2 = updatedJobModel.getContainers().get(PROCESSOR_IDS[1]).getTasks();
    assertEquals(updatedTaskModelMap1.size(), 1);
    assertEquals(updatedTaskModelMap2.size(), 1);

    TaskModel taskModel1 = updatedTaskModelMap1.values().stream().findFirst().get();
    TaskModel taskModel2 = updatedTaskModelMap2.values().stream().findFirst().get();
    assertEquals(taskModel1.getSystemStreamPartitions(), taskModel2.getSystemStreamPartitions());
    assertTrue(!taskModel1.getTaskName().getTaskName().equals(taskModel2.getTaskName().getTaskName()));

    processedMessagesLatch.await();

    assertEquals(ApplicationStatus.Running, localApplicationRunner2.status(streamApp2));

    // TODO: re-enable the following kill and waitForFinish calls after fixing SAMZA-1665
    // localApplicationRunner1.kill(streamApp1);
    // localApplicationRunner2.kill(streamApp2);

    // localApplicationRunner1.waitForFinish();
    // localApplicationRunner2.waitForFinish();
  }

  @Test
  public void shouldReElectLeaderWhenLeaderDies() throws InterruptedException {
    // Set up kafka topics.
    publishKafkaEvents(inputKafkaTopic, 0, 2 * NUM_KAFKA_EVENTS, PROCESSOR_IDS[0]);

    // Create stream applications.
    CountDownLatch kafkaEventsConsumedLatch = new CountDownLatch(2 * NUM_KAFKA_EVENTS);
    CountDownLatch processedMessagesLatch1 = new CountDownLatch(1);
    CountDownLatch processedMessagesLatch2 = new CountDownLatch(1);
    CountDownLatch processedMessagesLatch3 = new CountDownLatch(1);

    StreamApplication streamApp1 = TestStreamApplication.getInstance(inputKafkaTopic, outputKafkaTopic, processedMessagesLatch1, null, kafkaEventsConsumedLatch, applicationConfig1);
    StreamApplication streamApp2 = TestStreamApplication.getInstance(inputKafkaTopic, outputKafkaTopic, processedMessagesLatch2, null, kafkaEventsConsumedLatch, applicationConfig2);
    StreamApplication streamApp3 = TestStreamApplication.getInstance(inputKafkaTopic, outputKafkaTopic, processedMessagesLatch3, null, kafkaEventsConsumedLatch, applicationConfig3);

    // Create LocalApplicationRunners
    LocalApplicationRunner applicationRunner1 = new LocalApplicationRunner(applicationConfig1);
    LocalApplicationRunner applicationRunner2 = new LocalApplicationRunner(applicationConfig2);

    // Run stream applications.
    applicationRunner1.run(streamApp1);
    applicationRunner2.run(streamApp2);

    // Wait until all processors have processed a message.
    processedMessagesLatch1.await();
    processedMessagesLatch2.await();

    // Verifications before killing the leader.
    String jobModelVersion = zkUtils.getJobModelVersion();
    JobModel jobModel = zkUtils.getJobModel(jobModelVersion);
    assertEquals(2, jobModel.getContainers().size());
    assertEquals(Sets.newHashSet("0000000000", "0000000001"), jobModel.getContainers().keySet());
    assertEquals("1", jobModelVersion);

    List<String> processorIdsFromZK = zkUtils.getActiveProcessorsIDs(Arrays.asList(PROCESSOR_IDS));

    assertEquals(2, processorIdsFromZK.size());
    assertEquals(PROCESSOR_IDS[0], processorIdsFromZK.get(0));

    // Kill the leader. Since streamApp1 is the first to join the cluster, it's the leader.
    applicationRunner1.kill(streamApp1);
    applicationRunner1.waitForFinish();

    // How do you know here that leader has been reelected.

    kafkaEventsConsumedLatch.await();
    publishKafkaEvents(inputKafkaTopic, 0, 2 * NUM_KAFKA_EVENTS, PROCESSOR_IDS[0]);

    LocalApplicationRunner applicationRunner3 = new LocalApplicationRunner(applicationConfig3);
    applicationRunner3.run(streamApp3);
    processedMessagesLatch3.await();

    // Verifications after killing the leader.
    assertEquals(ApplicationStatus.SuccessfulFinish, applicationRunner1.status(streamApp1));
    processorIdsFromZK = zkUtils.getActiveProcessorsIDs(ImmutableList.of(PROCESSOR_IDS[1], PROCESSOR_IDS[2]));
    assertEquals(2, processorIdsFromZK.size());
    assertEquals(PROCESSOR_IDS[1], processorIdsFromZK.get(0));
    jobModelVersion = zkUtils.getJobModelVersion();
    jobModel = zkUtils.getJobModel(jobModelVersion);
    assertEquals(Sets.newHashSet("0000000001", "0000000002"), jobModel.getContainers().keySet());
    assertEquals(2, jobModel.getContainers().size());

    // TODO: re-enable the following kill and waitForFinish calls after fixing SAMZA-1665
    // applicationRunner2.kill(streamApp2);
    // applicationRunner3.kill(streamApp3);

    // applicationRunner2.waitForFinish();
    // applicationRunner3.waitForFinish();
  }

  @Test
  public void shouldFailWhenNewProcessorJoinsWithSameIdAsExistingProcessor() throws InterruptedException {
    // Set up kafka topics.
    publishKafkaEvents(inputKafkaTopic, 0, NUM_KAFKA_EVENTS, PROCESSOR_IDS[0]);

    // Create StreamApplications.
    CountDownLatch kafkaEventsConsumedLatch = new CountDownLatch(NUM_KAFKA_EVENTS);
    CountDownLatch processedMessagesLatch1 = new CountDownLatch(1);
    CountDownLatch processedMessagesLatch2 = new CountDownLatch(1);

    StreamApplication streamApp1 = TestStreamApplication.getInstance(inputKafkaTopic, outputKafkaTopic, processedMessagesLatch1, null, kafkaEventsConsumedLatch, applicationConfig1);
    StreamApplication streamApp2 = TestStreamApplication.getInstance(inputKafkaTopic, outputKafkaTopic, processedMessagesLatch2, null, kafkaEventsConsumedLatch, applicationConfig2);

    // Create LocalApplicationRunners
    LocalApplicationRunner applicationRunner1 = new LocalApplicationRunner(applicationConfig1);
    LocalApplicationRunner applicationRunner2 = new LocalApplicationRunner(applicationConfig2);

    // Run stream applications.
    applicationRunner1.run(streamApp1);
    applicationRunner2.run(streamApp2);

    // Wait for message processing to start in both the processors.
    processedMessagesLatch1.await();
    processedMessagesLatch2.await();

    MapConfig appConfig = new ApplicationConfig(new MapConfig(applicationConfig2, ImmutableMap.of(ZkConfig.ZK_SESSION_TIMEOUT_MS, "10")));
    LocalApplicationRunner applicationRunner3 = new LocalApplicationRunner(appConfig);

    // Create a stream app with same processor id as SP2 and run it. It should fail.
    StreamApplication streamApp3 = TestStreamApplication.getInstance(inputKafkaTopic, outputKafkaTopic, null, null, kafkaEventsConsumedLatch, applicationConfig2);
    // Fail when the duplicate processor joins.
    expectedException.expect(SamzaException.class);
    try {
      applicationRunner3.run(streamApp3);
    } finally {
      // TODO: re-enable the following kill and waitForFinish calls after fixing SAMZA-1665
      // applicationRunner1.kill(streamApp1);
      // applicationRunner2.kill(streamApp2);

      // applicationRunner1.waitForFinish();
      // applicationRunner2.waitForFinish();
    }
  }

  @Test
  public void testRollingUpgradeOfStreamApplicationsShouldGenerateSameJobModel() throws Exception {
    // Set up kafka topics.
    publishKafkaEvents(inputKafkaTopic, 0, NUM_KAFKA_EVENTS, PROCESSOR_IDS[0]);

    Map<String, String> configMap = buildStreamApplicationConfigMap(TEST_SYSTEM, inputKafkaTopic, testStreamAppName, testStreamAppId);

    configMap.put(JobConfig.PROCESSOR_ID(), PROCESSOR_IDS[0]);
    Config applicationConfig1 = new MapConfig(configMap);

    configMap.put(JobConfig.PROCESSOR_ID(), PROCESSOR_IDS[1]);
    Config applicationConfig2 = new MapConfig(configMap);

    LocalApplicationRunner applicationRunner1 = new LocalApplicationRunner(applicationConfig1);
    LocalApplicationRunner applicationRunner2 = new LocalApplicationRunner(applicationConfig2);

    List<TestKafkaEvent> messagesProcessed = new ArrayList<>();
    StreamApplicationCallback streamApplicationCallback = m -> messagesProcessed.add(m);

    // Create StreamApplication from configuration.
    CountDownLatch kafkaEventsConsumedLatch = new CountDownLatch(NUM_KAFKA_EVENTS);
    CountDownLatch processedMessagesLatch1 = new CountDownLatch(1);
    CountDownLatch processedMessagesLatch2 = new CountDownLatch(1);

    StreamApplication streamApp1 = TestStreamApplication.getInstance(inputKafkaTopic, outputKafkaTopic, processedMessagesLatch1, streamApplicationCallback, kafkaEventsConsumedLatch, applicationConfig1);
    StreamApplication streamApp2 = TestStreamApplication.getInstance(inputKafkaTopic, outputKafkaTopic, processedMessagesLatch2, null, kafkaEventsConsumedLatch, applicationConfig2);

    // Run stream application.
    applicationRunner1.run(streamApp1);
    applicationRunner2.run(streamApp2);

    processedMessagesLatch1.await();
    processedMessagesLatch2.await();

    // Read job model before rolling upgrade.
    String jobModelVersion = zkUtils.getJobModelVersion();
    JobModel jobModel = zkUtils.getJobModel(jobModelVersion);

    applicationRunner1.kill(streamApp1);
    applicationRunner1.waitForFinish();

    LocalApplicationRunner applicationRunner4 = new LocalApplicationRunner(applicationConfig1);
    processedMessagesLatch1 = new CountDownLatch(1);
    publishKafkaEvents(inputKafkaTopic, NUM_KAFKA_EVENTS, 2 * NUM_KAFKA_EVENTS, PROCESSOR_IDS[0]);
    streamApp1 = TestStreamApplication.getInstance(inputKafkaTopic, outputKafkaTopic, processedMessagesLatch1, streamApplicationCallback, kafkaEventsConsumedLatch, applicationConfig1);
    applicationRunner4.run(streamApp1);

    processedMessagesLatch1.await();

    // Read new job model after rolling upgrade.
    String newJobModelVersion = zkUtils.getJobModelVersion();
    JobModel newJobModel = zkUtils.getJobModel(newJobModelVersion);

    assertEquals(Integer.parseInt(jobModelVersion) + 1, Integer.parseInt(newJobModelVersion));
    assertEquals(jobModel.getContainers(), newJobModel.getContainers());

    // TODO: re-enable the following kill and waitForFinish calls after fixing SAMZA-1665
    // applicationRunner2.kill(streamApp2);
    // applicationRunner4.kill(streamApp1);

    // applicationRunner2.waitForFinish();
    // applicationRunner4.waitForFinish();
  }

}
