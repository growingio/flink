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

package org.apache.flink.test.checkpointing;

import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.AkkaOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.HighAvailabilityOptions;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.contrib.streaming.state.RocksDBOptions;
import org.apache.flink.contrib.streaming.state.RocksDBStateBackend;
import org.apache.flink.core.fs.Path;
import org.apache.flink.runtime.state.AbstractStateBackend;
import org.apache.flink.runtime.state.filesystem.FsStateBackend;
import org.apache.flink.runtime.state.memory.MemoryStateBackend;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.AssignerWithPunctuatedWatermarks;
import org.apache.flink.streaming.api.functions.timestamps.AscendingTimestampExtractor;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.test.checkpointing.utils.WatermarkCheckpointingSource;
import org.apache.flink.test.checkpointing.utils.WatermarkCheckpointingValidatingSink;
import org.apache.flink.test.util.MiniClusterWithClientResource;
import org.apache.flink.test.util.SuccessException;
import org.apache.flink.util.TestLogger;

import org.apache.curator.test.TestingServer;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.flink.test.checkpointing.WatermarkCheckpointingITCase.StateBackendEnum.ROCKSDB_INCREMENTAL_ZK;
import static org.junit.Assert.fail;

/**
 * This verifies that checkpointing works well with watermark.
 */
@RunWith(Parameterized.class)
public class WatermarkCheckpointingITCase extends TestLogger {

	private static final int MAX_MEM_STATE_SIZE = 20 * 1024 * 1024;
	private static final int PARALLELISM = 4;

	private TestingServer zkServer;

	public MiniClusterWithClientResource miniClusterResource;

	@ClassRule
	public static TemporaryFolder tempFolder = new TemporaryFolder();

	@Rule
	public TestName name = new TestName();

	private AbstractStateBackend stateBackend;

	@Parameterized.Parameter
	public StateBackendEnum stateBackendEnum;

	enum StateBackendEnum {
		MEM, FILE, ROCKSDB_FULLY_ASYNC, ROCKSDB_INCREMENTAL, ROCKSDB_INCREMENTAL_ZK, MEM_ASYNC, FILE_ASYNC
	}

	@Parameterized.Parameters(name = "statebackend type ={0}")
	public static Collection<StateBackendEnum> parameter() {
		return Arrays.asList(StateBackendEnum.values());
	}

	protected StateBackendEnum getStateBackend() {
		return this.stateBackendEnum;
	}

	protected final MiniClusterWithClientResource getMiniClusterResource() {
		return new MiniClusterWithClientResource(
			new MiniClusterResourceConfiguration.Builder()
				.setConfiguration(getConfigurationSafe())
				.setNumberTaskManagers(2)
				.setNumberSlotsPerTaskManager(PARALLELISM / 2)
				.build());
	}

	private Configuration getConfigurationSafe() {
		try {
			return getConfiguration();
		} catch (Exception e) {
			throw new AssertionError("Could not initialize test.", e);
		}
	}

	private Configuration getConfiguration() throws Exception {

		// print a message when starting a test method to avoid Travis' <tt>"Maven produced no
		// output for xxx seconds."</tt> messages
		System.out.println(
			"Starting " + getClass().getCanonicalName() + "#" + name.getMethodName() + ".");

		// Testing HA Scenario / ZKCompletedCheckpointStore with incremental checkpoints
		StateBackendEnum stateBackendEnum = getStateBackend();
		if (ROCKSDB_INCREMENTAL_ZK.equals(stateBackendEnum)) {
			zkServer = new TestingServer();
			zkServer.start();
		}

		Configuration config = createClusterConfig();

		switch (stateBackendEnum) {
			case MEM:
				this.stateBackend = new MemoryStateBackend(MAX_MEM_STATE_SIZE, false);
				break;
			case FILE: {
				String backups = tempFolder.newFolder().getAbsolutePath();
				this.stateBackend = new FsStateBackend("file://" + backups, false);
				break;
			}
			case MEM_ASYNC:
				this.stateBackend = new MemoryStateBackend(MAX_MEM_STATE_SIZE, true);
				break;
			case FILE_ASYNC: {
				String backups = tempFolder.newFolder().getAbsolutePath();
				this.stateBackend = new FsStateBackend("file://" + backups, true);
				break;
			}
			case ROCKSDB_FULLY_ASYNC: {
				setupRocksDB(-1, false);
				break;
			}
			case ROCKSDB_INCREMENTAL:
				// Test RocksDB based timer service as well
				config.setString(
					RocksDBOptions.TIMER_SERVICE_FACTORY,
					RocksDBStateBackend.PriorityQueueStateType.ROCKSDB.toString());
				setupRocksDB(16, true);
				break;
			case ROCKSDB_INCREMENTAL_ZK: {
				setupRocksDB(16, true);
				break;
			}
			default:
				throw new IllegalStateException("No backend selected.");
		}
		return config;
	}

	private void setupRocksDB(int fileSizeThreshold, boolean incrementalCheckpoints) throws IOException {
		String rocksDb = tempFolder.newFolder().getAbsolutePath();
		String backups = tempFolder.newFolder().getAbsolutePath();
		// we use the fs backend with small threshold here to test the behaviour with file
		// references, not self contained byte handles
		RocksDBStateBackend rdb =
			new RocksDBStateBackend(
				new FsStateBackend(
					new Path("file://" + backups).toUri(), fileSizeThreshold),
				incrementalCheckpoints);
		rdb.setDbStoragePath(rocksDb);
		this.stateBackend = rdb;
	}

	protected Configuration createClusterConfig() throws IOException {
		TemporaryFolder temporaryFolder = new TemporaryFolder();
		temporaryFolder.create();
		final File haDir = temporaryFolder.newFolder();

		Configuration config = new Configuration();
		config.setString(TaskManagerOptions.MANAGED_MEMORY_SIZE, "48m");
		// the default network buffers size (10% of heap max =~ 150MB) seems to much for this test case
		config.setString(TaskManagerOptions.NETWORK_BUFFERS_MEMORY_MAX, String.valueOf(80L << 20)); // 80 MB
		config.setString(AkkaOptions.FRAMESIZE, String.valueOf(MAX_MEM_STATE_SIZE) + "b");

		if (zkServer != null) {
			config.setString(HighAvailabilityOptions.HA_MODE, "ZOOKEEPER");
			config.setString(HighAvailabilityOptions.HA_ZOOKEEPER_QUORUM, zkServer.getConnectString());
			config.setString(HighAvailabilityOptions.HA_STORAGE_PATH, haDir.toURI().toString());
		}
		return config;
	}

	@Before
	public void setupTestCluster() throws Exception {
		miniClusterResource = getMiniClusterResource();
		miniClusterResource.before();
	}

	@After
	public void stopTestCluster() throws IOException {
		if (miniClusterResource != null) {
			miniClusterResource.after();
			miniClusterResource = null;
		}

		if (zkServer != null) {
			zkServer.stop();
			zkServer = null;
		}

		// Prints a message when finishing a test method to avoid Travis'
		// <tt>"Maven produced no output for xxx seconds."</tt> messages.
		System.out.println(
			"Finished " + getClass().getCanonicalName() + "#" + name.getMethodName() + ".");
	}

	// ------------------------------------------------------------------------

	@Test
	public void testTimestampsAndPeriodicWatermarksOperator() throws Exception {
		int sendCount = 10;
		int upperWatermark = 5;
		int lowerWatermark = 3;

		int expect = 5 * 10 / 2;

		try {
			StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
			env.setParallelism(PARALLELISM);
			env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
			env.enableCheckpointing(100);
			env.setRestartStrategy(RestartStrategies.fixedDelayRestart(1, 0));
			env.getConfig().disableSysoutLogging();
			env.setStateBackend(this.stateBackend);

			env.addSource(new WatermarkCheckpointingSource(upperWatermark, lowerWatermark, sendCount,
				WatermarkCheckpointingSource.PERIOD_MODE)).setParallelism(1)
				.assignTimestampsAndWatermarks(new AscendingTimestampExtractor<Tuple2<Integer, Integer>>() {
					@Override
					public long extractAscendingTimestamp(Tuple2<Integer, Integer> element) {
						return element.f0;
					}
				}).setParallelism(1)
				.windowAll(TumblingEventTimeWindows.of(Time.of(1, MILLISECONDS)))
				.sum(1).setParallelism(1)
				.map((MapFunction<Tuple2<Integer, Integer>, Integer>) value -> value.f1).setParallelism(1)
				.addSink(new WatermarkCheckpointingValidatingSink(expect)).setParallelism(1);

			env.execute();
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
	}

	@Test
	public void testTimestampsAndPunctuatedWatermarksOperator() throws Exception {
		int sendCount = 100;
		int upperWatermark = 10000;
		int lowerWatermark = 1;

		// 10000 + 10001 + ... + 10049
		int expect = 501275;

		try {
			StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
			env.setParallelism(PARALLELISM);
			env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
			env.enableCheckpointing(100);
			env.setRestartStrategy(RestartStrategies.fixedDelayRestart(1, 0));
			env.getConfig().disableSysoutLogging();
			env.setStateBackend(this.stateBackend);

			env.addSource(new WatermarkCheckpointingSource(upperWatermark, lowerWatermark, sendCount,
				WatermarkCheckpointingSource.PUNCTUATE_MODE)).setParallelism(1)
				.assignTimestampsAndWatermarks(new AssignerWithPunctuatedWatermarks<Tuple2<Integer, Integer>>() {
					@Override
					public long extractTimestamp(Tuple2<Integer, Integer> element, long previousElementTimestamp) {
						return element.f1;
					}

					@Nullable
					@Override
					public Watermark checkAndGetNextWatermark(Tuple2<Integer, Integer> lastElement, long extractedTimestamp) {
						return new Watermark(lastElement.f1);
					}
				}).setParallelism(1)
				.windowAll(TumblingEventTimeWindows.of(Time.of(100, MILLISECONDS)))
				.sum(1).setParallelism(1)
				.map((MapFunction<Tuple2<Integer, Integer>, Integer>) value -> value.f1).setParallelism(1)
				.addSink(new WatermarkCheckpointingValidatingSink(expect)).setParallelism(1);

			env.execute();
		} catch (SuccessException ex) {
			// do nothing
		}
	}
}

