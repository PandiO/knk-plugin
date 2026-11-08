package net.knightsandkings.knk.paper.siege.survival.results;

import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MatchResultDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.PlayerResultDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.SettlementDto;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.MemberView;
import net.knightsandkings.knk.core.survival.end.SurvivalEndReason;
import net.knightsandkings.knk.core.survival.result.SurvivalResultRules;
import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import net.knightsandkings.knk.paper.siege.survival.MatchHook;
import net.knightsandkings.knk.paper.siege.survival.SurvivalMatch;
import net.knightsandkings.knk.paper.siege.survival.checkpoint.SurvivalCheckpoints;
import net.knightsandkings.knk.paper.siege.survival.recovery.SurvivalRecoveryStore;
import net.knightsandkings.knk.paper.siege.survival.results.SurvivalResultInput.PlayerResult;
import net.knightsandkings.knk.paper.siege.survival.results.SurvivalResultPoster.Outcome;
import net.knightsandkings.knk.paper.siege.survival.results.SurvivalResults.Leaver;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Phase 5 of a survival match on the plugin side (DESIGN §3 step 7, §9, §10, §11; S32, S61, S62; link S8b): the
 * checkpoint hook per match, the leavers, the recovery marker, and at the end the result - built from the match,
 * persisted, shown to the players instead of the PvP result (flag S5-5), posted to the API and settled there once.
 * Main thread unless stated; the API calls run on the client executor and hop back through {@code mainThread}.
 *
 * <p>Match id: {@link SiegeLobbyRuntime#apiMatchId()} once {@code createMatch} answered. A result whose match id is
 * not known yet waits up to {@link #MATCH_ID_WAIT_MILLIS} for it (polled from the runtime tick) and is dropped with a
 * SEVERE log afterwards (an unrecorded round has no row to post to; flag S8b).
 */
public final class SurvivalResultService {

    /** How long a finished result waits for the API match id before it is dropped. */
    public static final long MATCH_ID_WAIT_MILLIS = 60_000L;
    /** How many finished matches the status keeps. */
    static final int RECENT_RESULTS = 8;

    /** How the service reaches players (Bukkit in production, fakes in tests). */
    public interface Players {
        String nameOf(UUID playerId);

        void tell(UUID playerId, Component message);

        void broadcast(Component message);

        static Players bukkit() {
            return new Players() {
                @Override
                public String nameOf(UUID playerId) {
                    Player p = Bukkit.getPlayer(playerId);
                    return p == null ? playerId.toString().substring(0, 8) : p.getName();
                }

                @Override
                public void tell(UUID playerId, Component message) {
                    Player p = Bukkit.getPlayer(playerId);
                    if (p != null) p.sendMessage(message);
                }

                @Override
                public void broadcast(Component message) {
                    Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(message));
                }
            };
        }
    }

    /** One finished match's result state, for {@code /knk siege survival status} (§11). */
    public record Status(String lobbyKey, OptionalLong matchId, SurvivalResultPoster.State state, String code, String detail,
                         OffsetDateTime at) {
        public String line() {
            return "Result " + lobbyKey + " match " + (matchId.isPresent() ? matchId.getAsLong() : "?") + ": " + state.name().toLowerCase()
                + (code == null ? "" : " (" + code + ")") + (detail == null || detail.isBlank() ? "" : " - " + detail) + " at " + at;
        }
    }

    private final class Tracking implements MatchHook {
        final SiegeLobbyRuntime lobby;
        final SurvivalMatch match;
        final SurvivalCheckpoints checkpoints;
        final Function<UUID, OptionalInt> kitOf;
        final List<Leaver> leavers = new ArrayList<>();
        boolean markerWritten;
        boolean presented;

        Tracking(SiegeLobbyRuntime lobby, SurvivalMatch match, SurvivalCheckpoints checkpoints, Function<UUID, OptionalInt> kitOf) {
            this.lobby = lobby;
            this.match = match;
            this.checkpoints = checkpoints;
            this.kitOf = kitOf;
        }

        @Override
        public void roundStarted(int round, Optional<String> specialProfileId) {
            refreshMarker(this);
        }

        @Override
        public void roundCompleted(int round) {
            refreshMarker(this);
        }
    }

    private record PendingResult(Tracking tracking, SurvivalResultInput input, long sinceMillis) { }

    private final SurvivalResultServices services;
    private final Players players;
    private final Clock clock;
    private final Logger logger;
    private final Map<Integer, Tracking> trackings = new LinkedHashMap<>();
    private final List<PendingResult> awaitingMatchId = new ArrayList<>();
    private final Deque<Status> recent = new ArrayDeque<>();

    public SurvivalResultService(SurvivalResultServices services, Players players, Clock clock, Logger logger) {
        this.services = services;
        this.players = players == null ? Players.bukkit() : players;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.logger = logger;
    }

    public SurvivalResultServices services() {
        return services;
    }

    // ---- running matches ----

    /**
     * A survival match started: installs the checkpoint hook and the marker bookkeeping.
     *
     * @param kitOf         the kit a player plays (S7b picker), empty outside kits mode
     * @param pendingRollOf the player's pending Mystery Box roll id (S7b), for the checkpoint
     */
    public SurvivalCheckpoints matchStarted(SiegeLobbyRuntime lobby, SurvivalMatch match, Function<UUID, OptionalInt> kitOf,
                                            Function<UUID, Optional<String>> pendingRollOf) {
        SurvivalCheckpoints checkpoints = new SurvivalCheckpoints(match, services.api(), lobby::apiMatchId, lobby::userIdOf, kitOf,
            pendingRollOf, services.checkpointIntervalMillis(), services.mainThread(), logger);
        Tracking tracking = new Tracking(lobby, match, checkpoints, kitOf);
        trackings.put(lobby.id(), tracking);
        match.addHook(checkpoints);
        match.addHook(tracking);
        return checkpoints;
    }

    /** The Siege observer's leave (the roster still holds the member): remembered for the result (S62), economy forfeits. */
    public void memberLeft(SiegeLobbyRuntime lobby, UUID playerId, Integer userId, boolean duringMatch) {
        Tracking tracking = trackings.get(lobby.id());
        if (tracking == null || tracking.presented) return;
        if (duringMatch && userId != null && tracking.match.siege().roster().contains(playerId)) {
            int deaths = tracking.match.siege().roster().member(playerId).map(MemberView::deaths).orElse(0);
            tracking.leavers.add(new Leaver(playerId, players.nameOf(playerId), userId, OffsetDateTime.now(clock), deaths));
        }
        tracking.match.onMemberLeft(playerId, false); // the runtime evaluates the end on the next tick
        refreshMarker(tracking);
    }

    /** The Siege match of the lobby is gone (ended or released): the tracking stops; a pending post goes on. */
    public void matchEnded(int lobbyId) {
        trackings.remove(lobbyId);
    }

    /** Every runtime tick: markers for matches whose id just became known, results waiting for their id. */
    public void tick(long nowMillis) {
        for (Tracking tracking : new ArrayList<>(trackings.values())) {
            if (!tracking.markerWritten && tracking.lobby.apiMatchId().isPresent()) refreshMarker(tracking);
        }
        if (awaitingMatchId.isEmpty()) return;
        for (PendingResult pending : new ArrayList<>(awaitingMatchId)) {
            OptionalLong id = pending.tracking().lobby.apiMatchId();
            if (id.isPresent()) {
                awaitingMatchId.remove(pending);
                post(pending.tracking(), id.getAsLong(), pending.input());
            } else if (nowMillis - pending.sinceMillis() >= MATCH_ID_WAIT_MILLIS) {
                awaitingMatchId.remove(pending);
                log(Level.SEVERE, "Lobby " + pending.tracking().lobby.key() + ": the API match id never became known; the survival result ("
                    + pending.input().reason() + ", waves " + pending.input().waves() + ") is dropped - nothing to settle against");
                remember(new Status(pending.tracking().lobby.key(), OptionalLong.empty(), SurvivalResultPoster.State.FAILED, "NoMatchId",
                    "dropped after " + (MATCH_ID_WAIT_MILLIS / 1000) + " s", OffsetDateTime.now(clock)));
            }
        }
    }

    // ---- the end ----

    /**
     * The Siege match ended (any reason): builds the result, writes it to the recovery store, shows the survival result
     * instead of the PvP one and posts it. Returns true so {@code SiegeService} skips its PvP announcement and lines.
     */
    public boolean present(SiegeLobbyRuntime lobby, SurvivalMatch match) {
        Tracking tracking = trackings.get(lobby.id());
        if (tracking == null) {
            tracking = new Tracking(lobby, match, null, id -> OptionalInt.empty());
        }
        if (tracking.presented) return true;
        tracking.presented = true;
        SurvivalEndReason reason = match.endReason().orElse(SurvivalEndReason.TECHNICAL_LIMIT);
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<String> notes = new ArrayList<>();
        SurvivalResultInput input;
        try {
            if (tracking.checkpoints != null) tracking.checkpoints.requestFinal();
            input = SurvivalResults.collect(match, reason, tracking.leavers, lobby::userIdOf, tracking.kitOf, players::nameOf, now, notes);
        } catch (RuntimeException e) {
            log(Level.SEVERE, "Lobby " + lobby.key() + ": the survival result could not be collected", e);
            return true;
        }
        for (String note : notes) log(Level.WARNING, "Lobby " + lobby.key() + ": " + note);
        show(input);
        OptionalLong matchId = lobby.apiMatchId();
        if (matchId.isPresent()) {
            post(tracking, matchId.getAsLong(), input);
        } else {
            log(Level.WARNING, "Lobby " + lobby.key() + ": the API match id is not known yet; the result waits up to "
                + (MATCH_ID_WAIT_MILLIS / 1000) + " s for createMatch");
            awaitingMatchId.add(new PendingResult(tracking, input, nowMillis()));
        }
        return true;
    }

    private void show(SurvivalResultInput input) {
        try {
            players.broadcast(SurvivalResultScreen.announcement(input));
            for (PlayerResult p : input.players()) {
                if (!p.presentAtEnd()) continue;
                for (Component line : SurvivalResultScreen.memberLines(input, p)) players.tell(p.playerId(), line);
            }
        } catch (RuntimeException e) {
            log(Level.WARNING, "Showing the survival result failed", e);
        }
    }

    private void post(Tracking tracking, long matchId, SurvivalResultInput input) {
        MatchResultDto body = SurvivalResultBuilder.build(matchId, input);
        services.store().save(matchId, SurvivalRecoveryStore.State.RESULT, body);
        String context = "Lobby " + tracking.lobby.key();
        log(Level.INFO, context + ": posting the survival result of match " + matchId + " (" + body.endReason() + ", waves "
            + body.reachedWave() + "/" + body.completedWave() + ", " + body.players().size() + " players, settles "
            + body.settlement().settlesRewards() + ": " + body.settlement().totalCoins() + " coins / " + body.settlement().totalXp() + " xp)");
        remember(new Status(tracking.lobby.key(), OptionalLong.of(matchId), SurvivalResultPoster.State.PENDING, null, body.endReason(), OffsetDateTime.now(clock)));
        services.poster().post(matchId, body, context).thenAccept(outcome -> services.mainThread().execute(() -> {
            finish(tracking.lobby.key(), matchId, outcome);
            for (PlayerResult p : input.players()) {
                if (p.presentAtEnd()) players.tell(p.playerId(), SurvivalResultScreen.followUp(input, p, outcome));
            }
        }));
    }

    private void finish(String lobbyKey, long matchId, Outcome outcome) {
        boolean keepFile = outcome.state() == SurvivalResultPoster.State.FAILED && SurvivalResultPoster.CODE_TRANSPORT.equals(outcome.code());
        if (!keepFile) services.store().delete(matchId);
        String detail = outcome.posted() ? SurvivalResultPoster.describeSettlement(outcome.stored()).replaceFirst("^; ", "")
            + (outcome.fromStore() ? " (stored row)" : "") : outcome.message();
        recent.removeIf(s -> s.matchId().isPresent() && s.matchId().getAsLong() == matchId);
        remember(new Status(lobbyKey, OptionalLong.of(matchId), outcome.state(), outcome.code(), detail, OffsetDateTime.now(clock)));
    }

    // ---- recovery marker and startup replay (S61) ----

    /** Writes the provisional restart result while the match runs (the match id must be known). */
    void refreshMarker(Tracking tracking) {
        OptionalLong id = tracking.lobby.apiMatchId();
        if (id.isEmpty() || tracking.presented) return;
        try {
            SurvivalResultInput provisional = SurvivalResults.collect(tracking.match, SurvivalEndReason.SERVER_RESTART, tracking.leavers,
                tracking.lobby::userIdOf, tracking.kitOf, players::nameOf, OffsetDateTime.now(clock), null);
            tracking.markerWritten = services.store().save(id.getAsLong(), SurvivalRecoveryStore.State.OPEN,
                SurvivalResultBuilder.build(id.getAsLong(), provisional));
        } catch (RuntimeException e) {
            log(Level.WARNING, "Lobby " + tracking.lobby.key() + ": the recovery marker could not be written", e);
        }
    }

    /**
     * Startup: every stored entry is posted again - a pending result as it is (idempotent), an open marker as the
     * {@code ServerRestart} result with {@code settlesRewards=false} (the API answers {@code ResultAlreadyStored} when
     * the Siege abort already wrote its row). Files stay only for transport failures.
     */
    public int recoverOnStartup() {
        List<SurvivalRecoveryStore.Entry> entries = services.store().list();
        for (SurvivalRecoveryStore.Entry entry : entries) {
            MatchResultDto body = entry.result();
            if (body == null) {
                services.store().delete(entry.matchId());
                continue;
            }
            if (entry.state() == SurvivalRecoveryStore.State.OPEN) body = asServerRestart(body, OffsetDateTime.now(clock));
            String context = "Recovery of match " + entry.matchId();
            log(Level.WARNING, context + ": " + (entry.state() == SurvivalRecoveryStore.State.OPEN
                ? "the last run left this survival match open; posting a ServerRestart result (no settlement)"
                : "the last run did not get the result (" + body.endReason() + ") to the API; posting it again"));
            final MatchResultDto toPost = body;
            remember(new Status("recovery", OptionalLong.of(entry.matchId()), SurvivalResultPoster.State.PENDING, null, toPost.endReason(), OffsetDateTime.now(clock)));
            services.poster().post(entry.matchId(), toPost, context).thenAccept(outcome ->
                services.mainThread().execute(() -> finish("recovery", entry.matchId(), outcome)));
        }
        return entries.size();
    }

    /** The open marker as the restart result: {@code ServerRestart}, ended now, nothing settled (S61, S62). */
    static MatchResultDto asServerRestart(MatchResultDto open, OffsetDateTime endedAt) {
        List<PlayerResultDto> players = new ArrayList<>();
        if (open.players() != null) {
            for (PlayerResultDto p : open.players()) {
                players.add(new PlayerResultDto(p.userId(), p.presentAtEnd(), p.leftAt(), p.kitId(), p.kills(), p.bonusKills(), p.deaths(),
                    p.repairWork(), p.matchCurrencyEarned(), p.matchCurrencySpent(), p.finalMatchBalance(), p.coinsAccrued(), p.xpAccrued(),
                    0, 0, null, null, null));
            }
        }
        return new MatchResultDto(open.siegeMatchId(), open.siegeLobbyId(), open.siegeScenarioId(), open.difficultyProfileId(),
            open.configurationGeneratedAt(), open.startedAt(), endedAt, SurvivalResultRules.endReasonCode(SurvivalEndReason.SERVER_RESTART),
            open.reachedWave(), open.completedWave(), open.earnedKills(), open.systemKills(), players,
            new SettlementDto(false, 0, 0, null, null, null, null), open.equipmentMode(), null, null, null);
    }

    // ---- status ----

    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        for (Status s : recent) lines.add(s.line());
        for (PendingResult p : awaitingMatchId) {
            lines.add("Result " + p.tracking().lobby.key() + ": waiting for the API match id (" + p.input().reason() + ")");
        }
        return lines;
    }

    /** The most recent status of a match, when the service still remembers it. */
    public Optional<Status> statusOf(long matchId) {
        return recent.stream().filter(s -> s.matchId().isPresent() && s.matchId().getAsLong() == matchId).findFirst();
    }

    private void remember(Status status) {
        recent.addFirst(status);
        while (recent.size() > RECENT_RESULTS) recent.removeLast();
    }

    private long nowMillis() {
        return clock.millis();
    }

    private void log(Level level, String message) {
        if (logger != null) logger.log(level, "[Survival] " + message);
    }

    private void log(Level level, String message, Throwable error) {
        if (logger != null) logger.log(level, "[Survival] " + message, error);
    }
}
