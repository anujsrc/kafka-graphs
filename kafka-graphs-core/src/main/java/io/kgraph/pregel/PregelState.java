package io.kgraph.pregel;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import io.kgraph.GraphAlgorithmState;
import io.kgraph.GraphAlgorithmState.State;

public class PregelState {

    private final State state;
    private final int superstep;
    private final Stage stage;
    private final long startTime;
    private final long endTime;

    public enum Stage {
        RECEIVE(0),
        SEND(1);

        private static final Map<Integer, Stage> lookup = new HashMap<>();

        static {
            for (Stage s : EnumSet.allOf(Stage.class)) {
                lookup.put(s.code(), s);
            }
        }

        private final int code;

        Stage(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static Stage get(int code) {
            return lookup.get(code);
        }
    }

    public PregelState(GraphAlgorithmState.State state, int superstep, Stage stage) {
        this.state = state;
        this.superstep = superstep;
        this.stage = stage;
        this.startTime = state == State.RUNNING ? System.currentTimeMillis() : 0L;
        this.endTime = 0L;
    }

    protected PregelState(GraphAlgorithmState.State state, int superstep, Stage stage,
                          long startTime, long endTime) {
        this.state = state;
        this.superstep = superstep;
        this.stage = stage;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public PregelState next() {
        switch (stage) {
            case RECEIVE:
                return new PregelState(state, superstep, Stage.SEND, startTime, endTime);
            case SEND:
                return new PregelState(state, superstep + 1, Stage.RECEIVE, startTime, endTime);
            default:
                throw new IllegalArgumentException("Invalid stage");
        }
    }

    public PregelState complete() {
        return new PregelState(State.COMPLETED, superstep, stage, startTime, System.currentTimeMillis());
    }

    public int superstep() {
        return superstep;
    }

    public Stage stage() {
        return stage;
    }

    public GraphAlgorithmState.State state() {
        return state;
    }

    protected long startTime() {
        return startTime;
    }

    protected long endTime() {
        return endTime;
    }

    public long runningTime() {
        switch (state) {
            case CREATED:
                return 0L;
            case RUNNING:
                return System.currentTimeMillis() - startTime;
            default:
                return endTime - startTime;
        }
    }

    public static PregelState fromBytes(byte[] bytes) {
        return new PregelStateDeserializer().deserialize(null, bytes);
    }

    public byte[] toBytes() {
        return new PregelStateSerializer().serialize(null, this);
    }

    @Override
    public String toString() {
        return "Superstep{" +
            "state=" + state +
            ", superstep=" + superstep +
            ", stage=" + stage +
            '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PregelState pregelState = (PregelState) o;
        return superstep == pregelState.superstep &&
            state == pregelState.state &&
            stage == pregelState.stage;
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, superstep, stage);
    }
}