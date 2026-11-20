package net.knightsandkings.knk.paper.siege.points;

import net.knightsandkings.knk.core.siege.economy.SiegePointsEconomy.Credit;
import net.knightsandkings.knk.core.siege.economy.SiegePointsSource;
import net.knightsandkings.knk.core.telemetry.TelemetryEvent;
import net.knightsandkings.knk.core.telemetry.TelemetryEventNames;
import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import net.knightsandkings.knk.paper.telemetry.TelemetryEmitter;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Siege points in the diagnostic telemetry for the balance runs (siege shop P27, #651): one
 * {@code siege.points_earned} event per kill, assist, death, capture, trickle or notable event. Gate damage and
 * capture progress pay small amounts many times a second, so they are summed per player and source and sent at most
 * once a minute (and at the match end), with the number of credits in {@code count}; {@code factor} is the kill-streak multiplier ("multiplier" would trip the
 * API catalog's forbidden "ip" key check). Main thread only.
 */
public final class SiegePointsTelemetry {

    /** Summed sources go out at most this often per player and source. */
    static final long AGGREGATE_MILLIS = 60_000L;

    private static final Set<SiegePointsSource> AGGREGATED = EnumSet.of(SiegePointsSource.GATE_DAMAGE,
            SiegePointsSource.CAPTURE_PROGRESS);

    private record Key(int lobbyId, UUID player, SiegePointsSource source) { }

    private static final class Sum {
        final long since;
        long amount;
        int count;

        Sum(long since) {
            this.since = since;
        }
    }

    private final TelemetryEmitter emitter;
    private final LongSupplier clock;
    private final Map<Key, Sum> sums = new LinkedHashMap<>();

    public SiegePointsTelemetry(TelemetryEmitter emitter) {
        this(emitter, System::currentTimeMillis);
    }

    SiegePointsTelemetry(TelemetryEmitter emitter, LongSupplier clock) {
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void earned(SiegeLobbyRuntime lobby, Credit credit) {
        if (!AGGREGATED.contains(credit.source())) {
            emit(lobby.id(), credit.player(), credit.source(), credit.amount(), 1, credit.multiplier());
            return;
        }
        long now = clock.getAsLong();
        Sum sum = sums.computeIfAbsent(new Key(lobby.id(), credit.player(), credit.source()), k -> new Sum(now));
        sum.amount += credit.amount();
        sum.count++;
        if (now - sum.since >= AGGREGATE_MILLIS) flushOne(new Key(lobby.id(), credit.player(), credit.source()));
    }

    /** The lobby's match ended: its summed credits go out. */
    public void flush(SiegeLobbyRuntime lobby) {
        for (Key key : new ArrayList<>(sums.keySet())) {
            if (key.lobbyId() == lobby.id()) flushOne(key);
        }
    }

    private void flushOne(Key key) {
        Sum sum = sums.remove(key);
        if (sum != null && sum.amount > 0) emit(key.lobbyId(), key.player(), key.source(), sum.amount, sum.count, 1.0);
    }

    private void emit(int lobbyId, UUID player, SiegePointsSource source, long amount, int count, double multiplier) {
        emitter.event(TelemetryEventNames.SIEGE_POINTS_EARNED)
                .player(player)
                .outcome(TelemetryEvent.Outcome.INFO)
                .object("siege_lobby", lobbyId)
                .put("lobbyId", lobbyId)
                .put("source", source.code())
                .put("amount", amount)
                .put("count", count)
                .put("factor", Math.round(multiplier * 100) / 100.0)
                .emit();
    }
}
