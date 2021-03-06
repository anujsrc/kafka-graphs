/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.kgraph.pregel;


import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.nodes.GroupMember;
import org.apache.curator.framework.recipes.shared.SharedValue;
import org.apache.curator.utils.ZKPaths;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Transformer;
import org.apache.kafka.streams.kstream.ValueTransformerWithKey;
import org.apache.kafka.streams.processor.Processor;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.internals.AbstractTask;
import org.apache.kafka.streams.processor.internals.ProcessorContextImpl;
import org.apache.kafka.streams.processor.internals.StreamTask;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kgraph.EdgeWithValue;
import io.kgraph.GraphAlgorithmState;
import io.kgraph.GraphSerialized;
import io.kgraph.VertexWithValue;
import io.kgraph.pregel.PregelState.Stage;
import io.kgraph.utils.ClientUtils;
import io.kgraph.utils.KryoSerde;
import io.kgraph.utils.KryoSerializer;
import io.vavr.Tuple2;
import io.vavr.Tuple3;
import io.vavr.Tuple4;

public class PregelComputation<K, VV, EV, Message> {
    private static final Logger log = LoggerFactory.getLogger(PregelComputation.class);

    private final String hostAndPort;
    private final String applicationId;
    private final String bootstrapServers;
    private final CuratorFramework curator;

    private final String verticesTopic;
    private KTable<K, VV> vertices;

    private final String edgesGroupedBySourceTopic;
    private KTable<K, Iterable<EdgeWithValue<K, EV>>> edgesGroupedBySource;

    private final String solutionSetTopic;
    private final String solutionSetStore;
    private KTable<K, VV> solutionSet;

    private final String workSetTopic;
    private KStream<K, Tuple3<Integer, K, Message>> workSet;

    private final int numPartitions;

    private final GraphSerialized<K, VV, EV> serialized;

    private final Optional<Message> initialMessage;
    private final ComputeFunction<K, VV, EV, Message> computeFunction;

    private Properties streamsConfig;

    private volatile int maxIterations = Integer.MAX_VALUE;
    private volatile CompletableFuture<KTable<K, VV>> futureResult;

    private final String edgesStoreName;
    private final String verticesStoreName;
    private final String localworkSetStoreName;
    private final String localSolutionSetStoreName;

    private final Map<Integer, Map<Integer, Set<K>>> activeVertices = new ConcurrentHashMap<>();

    public PregelComputation(
        String hostAndPort,
        String applicationId,
        String bootstrapServers,
        CuratorFramework curator,
        String verticesTopic,
        String edgesGroupedBySourceTopic,
        GraphSerialized<K, VV, EV> serialized,
        String solutionSetTopic,
        String solutionSetStore,
        String workSetTopic,
        int numPartitions,
        Optional<Message> initialMessage,
        ComputeFunction<K, VV, EV, Message> cf
    ) {

        this.hostAndPort = hostAndPort;
        this.applicationId = applicationId;
        this.bootstrapServers = bootstrapServers;
        this.curator = curator;
        this.verticesTopic = verticesTopic;
        this.edgesGroupedBySourceTopic = edgesGroupedBySourceTopic;
        this.solutionSetStore = solutionSetStore;
        this.solutionSetTopic = solutionSetTopic;
        this.workSetTopic = workSetTopic;
        this.numPartitions = numPartitions;
        this.serialized = serialized;
        this.initialMessage = initialMessage;
        this.computeFunction = cf;

        this.edgesStoreName = "edgesStore-" + applicationId;
        this.verticesStoreName = "verticesStore-" + applicationId;
        this.localworkSetStoreName = "localworkSetStore-" + applicationId;
        this.localSolutionSetStoreName = "localSolutionSetStore-" + applicationId;
    }

    public KTable<K, VV> vertices() {
        return vertices;
    }

    public KTable<K, Iterable<EdgeWithValue<K, EV>>> edgesGroupedBySource() {
        return edgesGroupedBySource;
    }

    public KTable<K, VV> result() {
        return solutionSet;
    }

    public CompletableFuture<KTable<K, VV>> futureResult() {
        return futureResult;
    }

    public void prepare(StreamsBuilder builder, Properties streamsConfig) {
        this.streamsConfig = streamsConfig;

        final StoreBuilder<KeyValueStore<Integer, Map<K, Map<K, Message>>>> workSetStoreBuilder =
            Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore(localworkSetStoreName),
                Serdes.Integer(), new KryoSerde<>()
            );
        builder.addStateStore(workSetStoreBuilder);

        final StoreBuilder<KeyValueStore<K, Tuple4<Integer, VV, Integer, VV>>> solutionSetStoreBuilder =
            Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore(localSolutionSetStoreName),
                serialized.keySerde(), new KryoSerde<>()
            );
        builder.addStateStore(solutionSetStoreBuilder);

        this.vertices = builder
            .table(
                verticesTopic,
                Materialized.<K, VV, KeyValueStore<Bytes, byte[]>>as(verticesStoreName)
                    .withKeySerde(serialized.keySerde()).withValueSerde(serialized.vertexValueSerde())
            );

        this.edgesGroupedBySource = builder
            .table(
                edgesGroupedBySourceTopic,
                Materialized.<K, Iterable<EdgeWithValue<K, EV>>, KeyValueStore<Bytes, byte[]>>as(edgesStoreName)
                    .withKeySerde(serialized.keySerde()).withValueSerde(new KryoSerde<>())
            );

        this.solutionSet = builder
            .table(solutionSetTopic, Consumed.<K, Tuple4<Integer, VV, Integer, VV>>with(serialized.keySerde(), new KryoSerde<>()))
            .mapValues(v -> v._4, Materialized.as(solutionSetStore));

        // Initalize solution set
        this.vertices
            .toStream()
            .mapValues(v -> new Tuple4<>(-1, v, 0, v))
            .to(solutionSetTopic, Produced.with(serialized.keySerde(), new KryoSerde<>()));

        // Initialize workset
        this.vertices
            .toStream()
            .peek((k, v) -> {
                try {
                    int partition = PregelComputation.vertexToPartition(k, serialized.keySerde().serializer(), numPartitions);
                    ZKUtils.addChild(curator, applicationId, new PregelState(GraphAlgorithmState.State.CREATED, 0, Stage.SEND), "partition-" + partition);
                } catch (Exception e) {
                    throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
                }

            })
            .mapValues((k, v) -> new Tuple3<>(0, k, initialMessage.orElse(null)))
            .peek((k, v) -> log.trace("workset 0 before topic: (" + k + ", " + v + ")"))
            .<K, Tuple3<Integer, K, Message>>to(workSetTopic, Produced.with(serialized.keySerde(), new KryoSerde<>
                ()));

        this.workSet = builder
            .stream(workSetTopic, Consumed.with(serialized.keySerde(), new KryoSerde<Tuple3<Integer, K, Message>>()))
            .peek((k, v) -> log.trace("workset 1 after topic: (" + k + ", " + v + ")"))
            // 0th iteration does not count as it just sets up the initial message
            .filter((K k, Tuple3<Integer, K, Message> v) -> v._1 <= maxIterations);

        KStream<K, Tuple3<Integer, Iterable<EdgeWithValue<K, EV>>, Map<K, Message>>> workSetWithEdges = workSet
            .transform(BarrierSync::new, edgesGroupedBySource.queryableStoreName(), localworkSetStoreName)
            .peek((k, v) -> log.trace("workset 2 after join: (" + k + ", " + v + ")"));

        KStream<K, Tuple3<Integer, Tuple4<Integer, VV, Integer, VV>, Map<K, Message>>> superstepComputation =
            workSetWithEdges
                .transformValues(VertexComputeUdf::new, localSolutionSetStoreName, vertices.queryableStoreName());

        // Compute the solution set delta
        KStream<K, Tuple4<Integer, VV, Integer, VV>> solutionSetDelta = superstepComputation
            .flatMapValues(v -> v._2 != null ? Collections.singletonList(v._2) : Collections.emptyList())
            .peek((k, v) -> log.trace("solution set: (" + k + ", " + v + ")"));

        solutionSetDelta
            .to(solutionSetTopic, Produced.with(serialized.keySerde(), new KryoSerde<>()));

        // Compute the inbox of each vertex for the next step (new workset)
        KStream<K, Tuple2<Integer, Map<K, Message>>> newworkSet = superstepComputation
            .mapValues(v -> new Tuple2<>(v._1, v._3))
            .peek((k, v) -> log.trace("workset new: (" + k + ", " + v + ")"));

        newworkSet.process(SendMessages::new);
    }

    public PregelState run(int maxIterations, CompletableFuture<KTable<K, VV>> futureResult) {
        this.maxIterations = maxIterations;
        this.futureResult = futureResult;

        PregelState pregelState = new PregelState(GraphAlgorithmState.State.RUNNING, 0, Stage.RECEIVE);
        try (SharedValue sharedValue = new SharedValue(curator, ZKPaths.makePath(ZKUtils.PREGEL_PATH + applicationId, ZKUtils.SUPERSTEP), pregelState.toBytes())) {
            sharedValue.start();
            sharedValue.setValue(pregelState.toBytes());
            return pregelState;
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    public PregelState state() {
        PregelState pregelState = new PregelState(GraphAlgorithmState.State.RUNNING, 0, Stage.RECEIVE);
        try (SharedValue sharedValue = new SharedValue(curator, ZKPaths.makePath(ZKUtils.PREGEL_PATH + applicationId, ZKUtils.SUPERSTEP), pregelState.toBytes())) {
            sharedValue.start();
            pregelState = PregelState.fromBytes(sharedValue.getValue());
            return pregelState;
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    private final class BarrierSync
        implements Transformer<K, Tuple3<Integer, K, Message>,
        KeyValue<K, Tuple3<Integer, Iterable<EdgeWithValue<K, EV>>, Map<K, Message>>>> {

        private ProcessorContext context;
        private KeyValueStore<Integer, Map<K, Map<K, Message>>> localworkSetStore;
        private KeyValueStore<K, Iterable<EdgeWithValue<K, EV>>> edgesStore;
        private Consumer<byte[], byte[]> internalConsumer;
        private LeaderLatch leaderLatch;
        private GroupMember group;
        private SharedValue sharedValue;
        private TreeCache treeCache;
        private PregelState pregelState = new PregelState(GraphAlgorithmState.State.CREATED, 0, Stage.RECEIVE);

        private final Map<Integer, Set<K>> forwardedVertices = new HashMap<>();

        @SuppressWarnings("unchecked")
        @Override
        public void init(final ProcessorContext context) {
            try {
                this.context = context;
                this.localworkSetStore = (KeyValueStore<Integer, Map<K, Map<K, Message>>>) context.getStateStore(localworkSetStoreName);
                this.edgesStore = (KeyValueStore<K, Iterable<EdgeWithValue<K, EV>>>) context.getStateStore(edgesGroupedBySource.queryableStoreName());
                this.internalConsumer = internalConsumer(context);

                String threadId = String.valueOf(Thread.currentThread().getId());
                // Worker name needs to be unique to a StreamThread but common to StreamTasks that share a StreamThread
                String workerName = hostAndPort != null ? hostAndPort + "#" + threadId : "local:#" + threadId;
                log.debug("Registering worker {} for application {}", workerName, applicationId);
                group = new GroupMember(curator, ZKPaths.makePath(ZKUtils.PREGEL_PATH + applicationId, ZKUtils.GROUP), workerName);
                group.start();
                leaderLatch = new LeaderLatch(curator, ZKPaths.makePath(ZKUtils.PREGEL_PATH + applicationId, ZKUtils.LEADER));
                leaderLatch.start();
                sharedValue = new SharedValue(curator, ZKPaths.makePath(ZKUtils.PREGEL_PATH + applicationId, ZKUtils.SUPERSTEP), pregelState.toBytes());
                sharedValue.start();

                // TODO make interval configurable
                this.context.schedule(250, PunctuationType.WALL_CLOCK_TIME, (timestamp) -> {
                    try {
                        pregelState = PregelState.fromBytes(sharedValue.getValue());

                        if (pregelState.state() == GraphAlgorithmState.State.CREATED) {
                            return;
                        } else if (pregelState.state() == GraphAlgorithmState.State.COMPLETED) {
                            if (futureResult != null && !futureResult.isDone()) {
                                if (pregelState.superstep() > maxIterations) {
                                    log.info("Pregel computation halted after {} iterations", maxIterations);
                                } else {
                                    log.info("Pregel computation converged after {} iterations", pregelState.superstep());
                                }
                                futureResult.complete(result());
                            }
                            return;
                        }

                        if (leaderLatch.hasLeadership()) {
                            if (treeCache == null) {
                                treeCache = new TreeCache(curator, ZKPaths.makePath(ZKUtils.PREGEL_PATH + applicationId, ZKUtils.BARRIERS));
                                treeCache.start();
                            }

                            if (pregelState.stage() == Stage.RECEIVE) {
                                int groupSize = group.getCurrentMembers().size();
                                PregelState nextPregelState = ZKUtils.maybeCreateReadyToSendNode(curator, applicationId, pregelState, treeCache, groupSize);
                                if (!pregelState.equals(nextPregelState)) {
                                    pregelState = nextPregelState;
                                    sharedValue.setValue(pregelState.toBytes());
                                } else {
                                    log.debug("Not ready to create snd: state {}", pregelState);
                                }
                            }
                            if (pregelState.stage() == Stage.SEND) {
                                PregelState nextPregelState = ZKUtils.maybeCreateReadyToReceiveNode(curator, applicationId, pregelState, treeCache);
                                if (!pregelState.equals(nextPregelState)) {
                                    pregelState = nextPregelState;
                                    sharedValue.setValue(pregelState.toBytes());
                                } else {
                                    log.debug("Not ready to create rcv: state {}", pregelState);
                                }
                            }
                            if (pregelState.superstep() > maxIterations) {
                                pregelState = pregelState.complete();
                                sharedValue.setValue(pregelState.toBytes());
                                return;
                            }
                        }

                        if (pregelState.stage() == Stage.RECEIVE) {
                            if (pregelState.superstep() == 0) {
                                if (!ZKUtils.hasChild(curator, applicationId, pregelState, workerName)) {
                                    Set<TopicPartition> workSetTps = localPartitions(internalConsumer, workSetTopic);
                                    Set<TopicPartition> solutionSetTps = localPartitions(internalConsumer, solutionSetTopic);
                                    if (isTopicSynced(internalConsumer, verticesTopic)
                                        && isTopicSynced(internalConsumer, edgesGroupedBySourceTopic)) {
                                        ZKUtils.addChild(curator, applicationId, pregelState, workerName, CreateMode.EPHEMERAL);
                                        // Ensure vertices and edges are read into tables first
                                        internalConsumer.seekToBeginning(workSetTps);
                                        internalConsumer.resume(workSetTps);
                                        internalConsumer.seekToBeginning(solutionSetTps);
                                        internalConsumer.resume(solutionSetTps);
                                    } else {
                                        internalConsumer.pause(workSetTps);
                                        internalConsumer.pause(solutionSetTps);
                                    }
                                }
                            }
                            if (ZKUtils.isReady(curator, applicationId, pregelState)) {
                                if (!ZKUtils.hasChild(curator, applicationId, pregelState, workerName)) {
                                    // Try to ensure we have all messages; however the consumer may not yet
                                    // be in sync so we do another check in the next stage
                                    if (isTopicSynced(internalConsumer, workSetTopic)) {
                                        ZKUtils.addChild(curator, applicationId, pregelState, workerName, CreateMode.EPHEMERAL);
                                    }
                                }
                            }
                        } else if (pregelState.stage() == Stage.SEND) {
                            if (ZKUtils.isReady(curator, applicationId, pregelState)) {
                                Map<K, Map<K, Message>> messages = localworkSetStore.get(pregelState.superstep());
                                if (hasVerticesToForward(messages)) {
                                    // This check is to ensure we have all messages produced in the last stage;
                                    // we may get new messages as well but that is fine
                                    if (isTopicSynced(internalConsumer, workSetTopic)) {
                                        forwardVertices(context, messages);
                                    }
                                }

                                // clean up previous step
                                activeVertices.remove(pregelState.superstep() - 1);
                                forwardedVertices.remove(pregelState.superstep() - 1);
                                localworkSetStore.delete(pregelState.superstep() - 1);
                            }
                        }
                    } catch (Exception e) {
                        throw toRuntimeException(e);
                    }
                });
            } catch (Exception e) {
                throw toRuntimeException(e);
            }
        }

        private boolean hasVerticesToForward(Map<K, Map<K, Message>> messages) {
            if (messages == null) return false;
            for (Map.Entry<K, Map<K, Message>> entry : messages.entrySet()) {
                Set<K> forwarded = forwardedVertices.get(pregelState.superstep());
                if (forwarded == null || !forwarded.contains(entry.getKey())) {
                    return true;
                }
            }
            return false;
        }

        private void forwardVertices(ProcessorContext context, Map<K, Map<K, Message>> messages) {
            List<Map.Entry<K, Map<K, Message>>> toForward = new ArrayList<>();
            for (Map.Entry<K, Map<K, Message>> entry : messages.entrySet()) {
                Set<K> forwarded = forwardedVertices.computeIfAbsent(pregelState.superstep(), k -> new HashSet<>());
                if (!forwarded.contains(entry.getKey())) {
                    forwarded.add(entry.getKey());
                    activateVertex(entry);
                    toForward.add(entry);
                }
            }
            for (Map.Entry<K, Map<K, Message>> entry : toForward) {
                Iterable<EdgeWithValue<K, EV>> edges = edgesStore.get(entry.getKey());
                context.forward(entry.getKey(), new Tuple3<>(pregelState.superstep(), edges, entry.getValue()));
            }
            context.commit();
        }

        private void activateVertex(Map.Entry<K, Map<K, Message>> entry) {
            int partition = vertexToPartition(entry.getKey(), serialized.keySerde().serializer(), numPartitions);
            Map<Integer, Set<K>> active = activeVertices.computeIfAbsent(
                pregelState.superstep(), k -> new ConcurrentHashMap<>());
            Set<K> vertices = active.computeIfAbsent(partition, k -> ConcurrentHashMap.newKeySet());
            vertices.add(entry.getKey());
            log.debug("vertex {} for part {} for step {} is active", entry.getKey(), partition, pregelState.superstep());
        }

        @Override
        public KeyValue<K, Tuple3<Integer, Iterable<EdgeWithValue<K, EV>>, Map<K, Message>>> transform(
            final K readOnlyKey, final Tuple3<Integer, K, Message> value
        ) {

            Map<K, Map<K, Message>> messages = localworkSetStore.get(value._1);
            if (messages == null) {
                messages = new HashMap<>();
            }
            Map<K, Message> messagesForSuperstep = messages.computeIfAbsent(readOnlyKey, k -> new HashMap<>());
            if (value._3 != null) {
                messagesForSuperstep.put(value._2, value._3);
            }
            localworkSetStore.put(value._1, messages);

            Set<K> forwarded = forwardedVertices.get(value._1);
            if (forwarded != null) {
                forwarded.remove(readOnlyKey);
            }

            return null;
        }

        public KeyValue<K, Tuple3<Integer, Iterable<EdgeWithValue<K, EV>>, Map<K, Message>>> punctuate(long i) {
            return null;
        }

        @Override
        public void close() {
            if (treeCache != null) {
                treeCache.close();
            }
            if (sharedValue != null) {
                try {
                    sharedValue.close();
                } catch (IOException e) {
                    // ignore
                }
            }
            if (leaderLatch != null) {
                try {
                    leaderLatch.close();
                } catch (IOException e) {
                    // ignore
                }
            }
            if (group != null) {
                group.close();
            }
        }
    }

    private final class VertexComputeUdf
        implements ValueTransformerWithKey<K, Tuple3<Integer, Iterable<EdgeWithValue<K, EV>>, Map<K, Message>>,
        Tuple3<Integer, Tuple4<Integer, VV, Integer, VV>, Map<K, Message>>> {

        private KeyValueStore<K, Tuple4<Integer, VV, Integer, VV>> localSolutionSetStore;
        private ReadOnlyKeyValueStore<K, VV> verticesStore;

        @SuppressWarnings("unchecked")
        @Override
        public void init(final ProcessorContext context) {
            this.localSolutionSetStore = (KeyValueStore<K, Tuple4<Integer, VV, Integer, VV>>) context.getStateStore(localSolutionSetStoreName);
            this.verticesStore = (ReadOnlyKeyValueStore<K, VV>) context.getStateStore(vertices.queryableStoreName());
        }

        @Override
        public Tuple3<Integer, Tuple4<Integer, VV, Integer, VV>, Map<K, Message>> transform(
            final K readOnlyKey, final Tuple3<Integer, Iterable<EdgeWithValue<K, EV>>, Map<K, Message>> value
        ) {

            int superstep = value._1;
            Iterable<EdgeWithValue<K, EV>> edgesIter = value._2 != null ? value._2 : Collections.emptyList();
            Tuple4<Integer, VV, Integer, VV> vertex = localSolutionSetStore.get(readOnlyKey);
            if (vertex == null) {
                VV vertexValue = verticesStore.get(readOnlyKey);
                if (vertexValue == null) {
                    log.warn("No vertex value for {}", readOnlyKey);
                }
                vertex = new Tuple4<>(-1, vertexValue, 0, vertexValue);
            }
            Map<K, Message> messages = value._3;
            Tuple3<Integer, Tuple4<Integer, VV, Integer, VV>, Map<K, Message>> result =
                apply(superstep, readOnlyKey, vertex, messages, edgesIter);
            if (result._2 != null) {
                localSolutionSetStore.put(readOnlyKey, result._2);
            }
            return result;
        }

        private Tuple3<Integer, Tuple4<Integer, VV, Integer, VV>, Map<K, Message>> apply(
            int superstep,
            K key,
            Tuple4<Integer, VV, Integer, VV> vertex,
            Map<K, Message> incomingMessages,
            Iterable<EdgeWithValue<K, EV>> edges
        ) {

            // Find the value that applies to this step
            VV oldVertexValue = vertex._3 <= superstep ? vertex._4 : vertex._2;
            ComputeFunction.Callback<K, VV, Message> cb = new ComputeFunction.Callback<>();
            computeFunction.compute(superstep, new VertexWithValue<>(key, oldVertexValue), incomingMessages, edges, cb);
            Tuple4<Integer, VV, Integer, VV> newVertex = cb.newVertexValue != null
                ? new Tuple4<>(superstep, oldVertexValue, superstep + 1, cb.newVertexValue) : null;
            return new Tuple3<>(superstep + 1, newVertex, cb.outgoingMessages);
        }

        @Override
        public void close() {
        }
    }

    private final class SendMessages implements Processor<K, Tuple2<Integer, Map<K, Message>>> {

        private Producer<K, Tuple3<Integer, K, Message>> producer;

        @Override
        public void init(final ProcessorContext context) {
            Properties producerConfig = ClientUtils.producerConfig(
                bootstrapServers, serialized.keySerde().serializer().getClass(), KryoSerializer.class,
                streamsConfig != null ? streamsConfig : new Properties()
            );
            this.producer = new KafkaProducer<>(producerConfig);
        }

        @Override
        public void process(final K readOnlyKey, final Tuple2<Integer, Map<K, Message>> value) {

            try {
                for (Map.Entry<K, Message> entry : value._2.entrySet()) {
                    Tuple3<Integer, K, Message> message = new Tuple3<>(value._1, readOnlyKey, entry.getValue());
                    ProducerRecord<K, Tuple3<Integer, K, Message>> producerRecord =
                        new ProducerRecord<>(workSetTopic, entry.getKey(), message);
                    producer.send(producerRecord, (metadata, error) -> {
                        if (error == null) {
                            try {
                                int p = vertexToPartition(entry.getKey(), serialized.keySerde().serializer(), numPartitions);
                                log.debug("adding vertex {} for partition {}", entry.getKey(), p);
                                ZKUtils.addChild(curator, applicationId, new PregelState(GraphAlgorithmState.State.RUNNING, value._1, Stage.SEND), "partition-" + p);
                            } catch (Exception e) {
                                throw toRuntimeException(e);
                            }
                        }
                    });
                }
                producer.flush();
                deactivateVertex(value._1, readOnlyKey);
            } catch (Exception e) {
                throw toRuntimeException(e);
            }
        }

        private void deactivateVertex(int superstep, K vertex) throws Exception {
            int partition = vertexToPartition(vertex, serialized.keySerde().serializer(), numPartitions);
            Map<Integer, Set<K>> active = activeVertices.get(superstep - 1);
            Set<K> vertices = active.get(partition);
            vertices.remove(vertex);
            log.debug("vertex {} for part {} for step {} is NOT active", vertex, partition, superstep - 1);
            if (vertices.isEmpty()) {
                log.debug("removing vertex {} for partition {}", vertex, partition);
                ZKUtils.removeChild(curator, applicationId, new PregelState(GraphAlgorithmState.State.RUNNING, superstep - 1, Stage.SEND), "partition-" + partition);
            }
        }

        public void punctuate(long i) {
        }

        @Override
        public void close() {
            producer.close();
        }
    }

    private static <K> int vertexToPartition(K vertex, Serializer<K> serializer, int numPartitions) {
        // TODO make configurable, currently this is tied to DefaultStreamPartitioner
        byte[] keyBytes = serializer.serialize(null, vertex);
        int partition = Utils.toPositive(Utils.murmur2(keyBytes)) % numPartitions;
        return partition;
    }

    @SuppressWarnings("unchecked")
    private static Consumer<byte[], byte[]> internalConsumer(ProcessorContext context)
        throws NoSuchFieldException, IllegalAccessException {
        // Consumer is created in a different thread, so can't use ThreadLocal; use reflection instead
        Field taskField = ProcessorContextImpl.class.getDeclaredField("task");
        taskField.setAccessible(true);
        StreamTask streamTask = (StreamTask) taskField.get(context);
        Field consumerField = AbstractTask.class.getDeclaredField("consumer");
        consumerField.setAccessible(true);
        return (Consumer<byte[], byte[]>) consumerField.get(streamTask);
    }

    @SuppressWarnings("unchecked")
    private static boolean isTopicSynced(Consumer<byte[], byte[]> consumer, String topic) {
        Set<TopicPartition> partitions = localPartitions(consumer, topic);
        Map<TopicPartition, Long> offsets = consumer.endOffsets(partitions);
        Map<TopicPartition, Long> positions = positions(consumer, partitions);
        return offsets.equals(positions);
    }

    private static Set<TopicPartition> localPartitions(Consumer<byte[], byte[]> consumer, String topic) {
        Set<TopicPartition> result = new HashSet<>();
        Set<TopicPartition> assignment = consumer.assignment();
        for (TopicPartition tp : assignment) {
            if (tp.topic().equals(topic)) {
                result.add(tp);
            }
        }
        return result;
    }

    private static Map<TopicPartition, Long> positions(Consumer<byte[], byte[]> consumer, Set<TopicPartition> tps) {
        Map<TopicPartition, Long> positions = new HashMap<>();
        for (TopicPartition tp : tps) {
            positions.put(tp, consumer.position(tp));
        }
        return positions;
    }

    private static RuntimeException toRuntimeException(Exception e) {
        return e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
    }
}
