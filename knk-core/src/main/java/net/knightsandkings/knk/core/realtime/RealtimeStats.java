package net.knightsandkings.knk.core.realtime;

import net.knightsandkings.knk.core.metrics.MetricsCollector;
import net.knightsandkings.knk.core.metrics.PrometheusWriter;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters of the realtime connection (DESIGN §4.4 "Metrics"): state, connects, reconnect attempts, disconnects,
 * messages by type, resyncs and sequence gaps. Always counted (cheap); exported on the KNG-232 {@code /metrics}
 * endpoint when {@code metrics.enabled} is on, and summarised by {@code /knk health}. Thread-safe.
 */
public final class RealtimeStats implements MetricsCollector {

    private final AtomicReference<RealtimeState> state = new AtomicReference<>(RealtimeState.DISABLED);
    private final LongAdder connects = new LongAdder();
    private final LongAdder attempts = new LongAdder();
    private final LongAdder disconnects = new LongAdder();
    private final LongAdder resyncs = new LongAdder();
    private final LongAdder gaps = new LongAdder();
    private final LongAdder sendFailures = new LongAdder();
    private final Map<String, LongAdder> messages = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> connectedSince = new AtomicReference<>();
    private final AtomicLong lastSeq = new AtomicLong();

    public void state(RealtimeState newState, Instant now) {
        RealtimeState previous = state.getAndSet(newState);
        if (newState == RealtimeState.CONNECTED && previous != RealtimeState.CONNECTED) {
            connects.increment();
            connectedSince.set(now);
        } else if (previous == RealtimeState.CONNECTED && newState != RealtimeState.CONNECTED) {
            disconnects.increment();
            connectedSince.set(null);
        }
    }

    public RealtimeState state() {
        return state.get();
    }

    public void attempt() {
        attempts.increment();
    }

    public void message(String type, long seq) {
        messages.computeIfAbsent(type == null ? "?" : type, k -> new LongAdder()).increment();
        lastSeq.set(seq);
    }

    public void resync() {
        resyncs.increment();
    }

    public void gap() {
        gaps.increment();
    }

    public void sendFailure() {
        sendFailures.increment();
    }

    public long connects() {
        return connects.sum();
    }

    public long attempts() {
        return attempts.sum();
    }

    public long disconnects() {
        return disconnects.sum();
    }

    public long resyncs() {
        return resyncs.sum();
    }

    public long gaps() {
        return gaps.sum();
    }

    public long sendFailures() {
        return sendFailures.sum();
    }

    public long lastSeq() {
        return lastSeq.get();
    }

    public Instant connectedSince() {
        return connectedSince.get();
    }

    /** Messages received per type, sorted by type. */
    public Map<String, Long> messagesByType() {
        Map<String, Long> out = new TreeMap<>();
        messages.forEach((type, count) -> out.put(type, count.sum()));
        return out;
    }

    public long messagesTotal() {
        return messages.values().stream().mapToLong(LongAdder::sum).sum();
    }

    @Override
    public void collect(PrometheusWriter out) {
        RealtimeState current = state.get();
        for (RealtimeState s : RealtimeState.values()) {
            out.gauge("knk_realtime_state", "Realtime hub connection state (1 = current).", s == current ? 1 : 0,
                "state", s.name().toLowerCase(java.util.Locale.ROOT));
        }
        out.counter("knk_realtime_connects_total", "Successful connects to the API's game hub.", connects.sum());
        out.counter("knk_realtime_connect_attempts_total", "Connect attempts to the API's game hub.", attempts.sum());
        out.counter("knk_realtime_disconnects_total", "Connections to the API's game hub that dropped or were closed.", disconnects.sum());
        out.counter("knk_realtime_resyncs_total", "Full resyncs run after a (re)connect or a sequence gap.", resyncs.sum());
        out.counter("knk_realtime_gaps_total", "Sequence gaps seen in hub messages.", gaps.sum());
        out.counter("knk_realtime_send_failures_total", "Client-to-hub invocations that could not be sent.", sendFailures.sum());
        messagesByType().forEach((type, count) ->
            out.counter("knk_realtime_messages_total", "Hub messages received by type.", count, "type", type));
    }
}
