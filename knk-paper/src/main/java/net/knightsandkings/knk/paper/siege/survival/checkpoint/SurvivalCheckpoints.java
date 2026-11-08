package net.knightsandkings.knk.paper.siege.survival.checkpoint;

import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MatchCheckpointDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.PlayerCheckpointDto;
import net.knightsandkings.knk.api.impl.SiegeSurvivalMatchApiImpl;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.MemberView;
import net.knightsandkings.knk.core.survival.SurvivalSubstate;
import net.knightsandkings.knk.core.survival.checkpoint.CheckpointCadence;
import net.knightsandkings.knk.core.survival.end.EndConditionSnapshot;
import net.knightsandkings.knk.core.survival.wave.RoundSpawnQueue;
import net.knightsandkings.knk.paper.siege.survival.MatchHook;
import net.knightsandkings.knk.paper.siege.survival.SurvivalMatch;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Writes the running match's checkpoint to the API (DESIGN §10 runtime/checkpoint record; link S8b, S8a contract
 * {@code PUT …/checkpoint}): at round start and end, on every objective capture, death and ghost release, every
 * {@code checkpoint-interval-seconds}, and once right before the result. Off the main thread, latest-wins, one request
 * in flight at a time (a change during a send is sent afterwards with the state of that moment), never blocking the
 * match; failures are logged once a minute. Door repairs stay empty until S6. The match id comes from the lobby once
 * {@code createMatch} answered; without it the checkpoint is skipped (and counted).
 */
public final class SurvivalCheckpoints implements MatchHook {

    private final SurvivalMatch match;
    private final SiegeSurvivalMatchApiImpl api;
    private final Supplier<OptionalLong> apiMatchId;
    private final Function<UUID, OptionalInt> userIdOf;
    private final Function<UUID, OptionalInt> kitOf;
    private final Function<UUID, Optional<String>> pendingRollOf;
    private final CheckpointCadence cadence;
    private final Executor mainThread;
    private final Logger logger;

    private volatile int revision;
    private volatile OffsetDateTime lastStoredAt;
    private int sent;
    private int skippedWithoutId;
    private String lastTrigger = "-";
    private boolean ended;

    public SurvivalCheckpoints(SurvivalMatch match, SiegeSurvivalMatchApiImpl api, Supplier<OptionalLong> apiMatchId,
                               Function<UUID, OptionalInt> userIdOf, Function<UUID, OptionalInt> kitOf,
                               Function<UUID, Optional<String>> pendingRollOf, long intervalMillis, Executor mainThread, Logger logger) {
        this.match = match;
        this.api = api;
        this.apiMatchId = apiMatchId;
        this.userIdOf = userIdOf;
        this.kitOf = kitOf == null ? id -> OptionalInt.empty() : kitOf;
        this.pendingRollOf = pendingRollOf == null ? id -> Optional.empty() : pendingRollOf;
        this.cadence = new CheckpointCadence(intervalMillis);
        this.mainThread = mainThread == null ? Runnable::run : mainThread;
        this.logger = logger;
    }

    /** The API's revision of the last stored checkpoint (0 before the first answer). */
    public int revision() {
        return revision;
    }

    public int sent() {
        return sent;
    }

    public int failures() {
        return cadence.failures();
    }

    // ---- triggers ----

    @Override
    public void ticked(long nowMillis) {
        if (!ended && cadence.intervalDue(nowMillis)) request("interval", nowMillis);
    }

    @Override
    public void roundStarted(int round, Optional<String> specialProfileId) {
        request("round " + round + " start", match.now());
    }

    @Override
    public void roundCompleted(int round) {
        request("round " + round + " end", match.now());
    }

    @Override
    public void objectiveCaptured(int objectiveId) {
        request("objective " + objectiveId, match.now());
    }

    @Override
    public void playerBecameGhost(UUID playerId) {
        request("death", match.now());
    }

    @Override
    public void ghostsReleased(List<UUID> playerIds) {
        request("respawn", match.now());
    }

    @Override
    public void memberLeft(UUID playerId) {
        request("leave", match.now());
    }

    /** The last checkpoint, right before the result is posted (the match is in ENDING). */
    public void requestFinal() {
        request("final", match.now());
        ended = true;
    }

    @Override
    public void ended() {
        ended = true;
    }

    // ---- sending ----

    /** A checkpoint is wanted; sends now or after the one in flight (latest wins). Main thread. */
    void request(String trigger, long nowMillis) {
        if (ended && !"final".equals(trigger)) return;
        lastTrigger = trigger;
        if (cadence.request(nowMillis)) send(trigger, nowMillis);
    }

    private void send(String trigger, long nowMillis) {
        OptionalLong id = apiMatchId.get();
        if (id.isEmpty()) {
            skippedWithoutId++;
            if (skippedWithoutId == 1 && logger != null) {
                logger.info("[Survival] Lobby " + match.lobby().key() + ": checkpoint skipped (" + trigger + "): the API match id is not known yet");
            }
            afterSend(nowMillis, false, "no match id");
            return;
        }
        MatchCheckpointDto body;
        try {
            body = capture(nowMillis);
        } catch (RuntimeException e) {
            afterSend(nowMillis, false, "capture failed: " + e);
            return;
        }
        CompletableFuture<MatchCheckpointDto> call;
        try {
            call = api.putCheckpoint(id.getAsLong(), body);
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        if (call == null) call = CompletableFuture.completedFuture(null);
        sent++;
        call.whenComplete((stored, error) -> mainThread.execute(() -> {
            if (error == null && stored != null) {
                if (stored.revision() != null) revision = stored.revision();
                lastStoredAt = stored.updatedAt() == null ? body.capturedAt() : stored.updatedAt();
                afterSend(match.now(), true, null);
            } else {
                Throwable t = error;
                while (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) t = t.getCause();
                afterSend(match.now(), false, error == null ? "404 (unknown match)" : String.valueOf(t));
            }
        }));
    }

    private void afterSend(long nowMillis, boolean ok, String failure) {
        if (!ok && cadence.failed(nowMillis) && logger != null) {
            logger.warning("[Survival] Lobby " + match.lobby().key() + ": checkpoint failed (" + lastTrigger + "): " + failure
                + (cadence.suppressedFailures() > 0 ? "" : "") + "; further failures are logged once a minute (" + cadence.failures() + " so far)");
        }
        if (cadence.sendCompleted()) send(lastTrigger, nowMillis);
    }

    /** The match as it stands (main thread). */
    MatchCheckpointDto capture(long nowMillis) {
        RoundSpawnQueue queue = match.rounds().substate() == SurvivalSubstate.WAVE_ACTIVE ? match.rounds().currentQueue().orElse(null) : null;
        EndConditionSnapshot snapshot = match.snapshot();
        List<Integer> npcHeld = new ArrayList<>();
        snapshot.objectives().forEach(o -> {
            if (o.npcHeld()) parseInt(o.id()).ifPresent(npcHeld::add);
        });
        List<Integer> zones = new ArrayList<>();
        for (String zoneId : match.frontier().activeZoneIds()) parseInt(zoneId).ifPresent(zones::add);
        List<PlayerCheckpointDto> players = new ArrayList<>();
        for (UUID id : match.siege().roster().playerIds()) {
            OptionalInt userId = userIdOf.apply(id);
            if (userId.isEmpty()) continue;
            OptionalInt kit = kitOf.apply(id);
            players.add(new PlayerCheckpointDto(userId.getAsInt(), match.ghosts().isGhost(id), kit.isPresent() ? kit.getAsInt() : null,
                match.economy().balance(id), match.economy().kills(id),
                match.siege().roster().member(id).map(MemberView::deaths).orElse(0), pendingRollOf.apply(id).orElse(null)));
        }
        return new MatchCheckpointDto(
            (int) apiMatchId.get().orElse(0),
            match.config().scenarioId(),
            match.config().difficulty() == null ? null : match.config().difficulty().id(),
            Instant.ofEpochMilli(nowMillis).atOffset(ZoneOffset.UTC),
            match.rounds().substate().name(),
            match.record().reached(),
            match.record().completed(),
            match.specialProfileKey().orElse(null),
            queue == null ? 0 : queue.aliveCount(),
            queue == null ? 0 : queue.queuedCount(),
            npcHeld,
            zones,
            List.of(),
            players,
            null, null);
    }

    private static OptionalInt parseInt(String value) {
        try {
            return OptionalInt.of(Integer.parseInt(value.trim()));
        } catch (RuntimeException e) {
            return OptionalInt.empty();
        }
    }

    @Override
    public List<String> statusLines() {
        return List.of("Checkpoint: revision " + revision + ", sent " + sent + ", failures " + cadence.failures()
            + (skippedWithoutId > 0 ? ", skipped without match id " + skippedWithoutId : "")
            + ", interval " + (cadence.intervalMillis() <= 0 ? "off" : (cadence.intervalMillis() / 1000) + " s")
            + ", last trigger " + lastTrigger + (lastStoredAt == null ? "" : ", stored at " + lastStoredAt)
            + (cadence.inFlight() ? ", in flight" : ""));
    }
}
