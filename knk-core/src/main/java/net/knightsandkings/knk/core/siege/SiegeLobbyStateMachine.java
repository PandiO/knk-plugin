package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeLobby;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeRotationEntry;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;
import net.knightsandkings.knk.core.siege.SiegeEffect.AnnounceEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.Announcement;
import net.knightsandkings.knk.core.siege.SiegeEffect.Audience;
import net.knightsandkings.knk.core.siege.SiegeEffect.CancelMatchmakingEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.CancelReason;
import net.knightsandkings.knk.core.siege.SiegeEffect.DisableReason;
import net.knightsandkings.knk.core.siege.SiegeEffect.DrawScenarioEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.EndMatchEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.LobbyDisabledEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.MatchmakingStartedEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.PhaseChangedEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.RefreshConfigEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.SendToHubEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.SplitTeamsEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.StartMatchEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.StartMessageEffect;
import net.knightsandkings.knk.core.siege.VoteTally.VoteChoice;
import net.knightsandkings.knk.core.siege.VoteTally.VoteResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * One lobby's match loop (DESIGN §5.4, §6.1–6.5), as a pure state machine: each call returns the
 * {@link SiegeEffect}s the Paper runtime must apply, in order. Nothing here touches Bukkit, blocks,
 * or reads a clock.
 * <p>
 * <b>Time</b> is the {@link #tick(int)} call: one call = one second (the Phase 5 1 s sync ticker).
 * Tests drive it tick by tick.
 * <p>
 * <b>Timeline</b> (seconds before the start, from {@code SiegeConfiguration}; defaults 30/25/15/10):
 * each configured announcement mark → online announcement; {@code voteClose} → voting closes (members
 * told the hub is {@code voteClose − hub} s away); {@code draw} → scenario drawn by {@link VoteTally}
 * and <b>locked</b> ({@link SiegeRuntimeLocks}), or the round is cancelled when the drawn scenario
 * needs more members than there are (→ COOLDOWN, nothing recorded); {@code hub} → HUB, members sent
 * to the hub; {@code teamSplit} → teams split; 0 → match starts (cancelled instead if members fell
 * below PlayersMin in the hub) and lasts {@link MatchDurationCalculator} seconds.
 * <p>
 * <b>Configuration</b> is frozen while a match is prepared or played:
 * {@link #offerConfiguration} applies at once only between matches (DISABLED/COOLDOWN), otherwise
 * it waits for the next cooldown. Entering cooldown asks for a refresh ({@link RefreshConfigEffect}).
 * Not thread-safe: main thread only.
 */
public final class SiegeLobbyStateMachine {

    /** Seconds after the start at which the teams' start messages show (legacy: progressExpire − 2). */
    public static final int START_MESSAGE_DELAY_SECONDS = 2;

    /**
     * A player's {@code /siege skip} during matchmaking leaves this many seconds (never less than
     * {@code voteClose + 1}, so voting still gets its last second). Developer request 2026-09-26.
     */
    public static final int PLAYER_SKIP_MATCHMAKING_SECONDS = 60;

    public enum SkipRequester {
        /** {@code /siege skip} ({@code knk.siege.skip}): cooldown → matchmaking; matchmaking → 1 minute left. */
        PLAYER,
        /** {@code /siege admin skip}: cooldown → matchmaking; matchmaking → T-(voteClose+1). */
        ADMIN
    }

    public enum SkipResult {
        COOLDOWN_SKIPPED,
        /**
         * Matchmaking was shortened: an admin to one second before voting closes (legacy T-31), a player
         * to {@link #PLAYER_SKIP_MATCHMAKING_SECONDS}; {@link #secondsRemaining()} tells how long is left.
         */
        MATCHMAKING_SHORTENED,
        /** Matchmaking is already at or past that point; nothing changed. */
        TOO_LATE,
        /** Nothing to skip in this phase for this requester (e.g. a match in progress); nothing changed. */
        NOT_SKIPPABLE
    }

    public record SkipOutcome(SkipResult result, List<SiegeEffect> effects) {
        public boolean skipped() {
            return result == SkipResult.COOLDOWN_SKIPPED || result == SkipResult.MATCHMAKING_SHORTENED;
        }
    }

    private final SiegeRuntimeLocks locks;
    private final RandomGenerator random;

    private KnkSiegeLobby lobby;
    private KnkSiegeConfiguration configuration;
    private SiegeTimeline timeline;
    private KnkSiegeLobby pendingLobby;
    private KnkSiegeConfiguration pendingConfiguration;

    private SiegePhase phase = SiegePhase.DISABLED;
    private int secondsRemaining;
    private int secondsElapsed;
    private List<KnkSiegeScenario> candidates = List.of();
    private VoteTally tally;
    private KnkSiegeScenario drawnScenario;
    private int matchDurationSeconds;

    /**
     * Starts DISABLED; call {@link #start()} to begin matchmaking (bootstrap, DESIGN §6.1).
     *
     * @throws IllegalArgumentException when the configuration's timeline is invalid for the lobby
     */
    public SiegeLobbyStateMachine(
            KnkSiegeLobby lobby,
            KnkSiegeConfiguration configuration,
            SiegeRuntimeLocks locks,
            RandomGenerator random
    ) {
        this.locks = Objects.requireNonNull(locks, "locks");
        this.random = Objects.requireNonNull(random, "random");
        apply(Objects.requireNonNull(lobby, "lobby"), Objects.requireNonNull(configuration, "configuration"),
                validTimeline(lobby, configuration));
    }

    // ==================== Commands ====================

    /** DISABLED or COOLDOWN → MATCHMAKING (bootstrap, {@code /siege admin start}). No-op otherwise. */
    public List<SiegeEffect> start() {
        if (!phase.isBetweenMatches()) return List.of();
        List<SiegeEffect> effects = new ArrayList<>();
        beginMatchmaking(effects);
        return List.copyOf(effects);
    }

    /**
     * One second passes.
     *
     * @param memberCount the lobby's current member count (checked against PlayersMin at the draw
     *                    and at the start, and used for the match length)
     */
    public List<SiegeEffect> tick(int memberCount) {
        List<SiegeEffect> effects = new ArrayList<>();
        switch (phase) {
            case DISABLED -> { }
            case MATCHMAKING, HUB -> {
                secondsRemaining--;
                matchmakingStep(memberCount, effects);
            }
            case IN_PROGRESS -> {
                secondsRemaining--;
                secondsElapsed++;
                if (secondsRemaining <= 0) {
                    endMatch(SiegeEndReason.TIME_EXPIRED, effects);
                } else if (secondsElapsed == START_MESSAGE_DELAY_SECONDS) {
                    effects.add(new StartMessageEffect(drawnScenario));
                }
            }
            case ENDING -> enterCooldown(effects);
            case COOLDOWN -> {
                secondsRemaining--;
                if (secondsRemaining <= 0) beginMatchmaking(effects);
            }
        }
        return List.copyOf(effects);
    }

    /** A member's vote (DESIGN §6.3); {@link VoteResult#VOTING_CLOSED} outside the voting window. */
    public VoteResult vote(UUID member, VoteChoice choice) {
        if (!isVotingOpen()) return VoteResult.VOTING_CLOSED;
        return tally.vote(member, choice);
    }

    /** A member left: drop their vote (DESIGN §6.2 "no side effects beyond dropping votes"). */
    public void removeMember(UUID member) {
        if (tally != null) tally.removeVoter(member);
    }

    /**
     * The runtime ended the running match: an instant-victory capture, elimination or not enough
     * players (DESIGN §6.8, §7.5). IN_PROGRESS → ENDING; the next tick enters cooldown.
     * No-op outside IN_PROGRESS.
     */
    public List<SiegeEffect> endMatch(SiegeEndReason reason) {
        if (phase != SiegePhase.IN_PROGRESS) return List.of();
        List<SiegeEffect> effects = new ArrayList<>();
        endMatch(Objects.requireNonNull(reason, "reason"), effects);
        return List.copyOf(effects);
    }

    /**
     * Admin stop or server shutdown: aborts whatever is running and disables the lobby until
     * {@link #start()}. A running match ends with this reason (no winner, no rewards).
     *
     * @param reason {@link SiegeEndReason#ADMIN_STOPPED} or {@link SiegeEndReason#SERVER_RESTART}
     */
    public List<SiegeEffect> stop(SiegeEndReason reason) {
        if (!reason.isAbort()) throw new IllegalArgumentException("stop() takes an abort reason, got " + reason);
        if (phase == SiegePhase.DISABLED) return List.of();

        List<SiegeEffect> effects = new ArrayList<>();
        switch (phase) {
            case MATCHMAKING, HUB -> effects.add(new CancelMatchmakingEffect(
                    reason == SiegeEndReason.SERVER_RESTART ? CancelReason.SERVER_RESTART : CancelReason.ADMIN_STOPPED,
                    phase == SiegePhase.HUB));
            case IN_PROGRESS -> effects.add(new EndMatchEffect(drawnScenario, reason));
            default -> { }
        }
        locks.release(lobby.id());
        clearRound();
        applyPending();
        secondsRemaining = 0;
        moveTo(SiegePhase.DISABLED, effects);
        effects.add(new LobbyDisabledEffect(DisableReason.STOPPED));
        return List.copyOf(effects);
    }

    /**
     * {@code /siege skip} and {@code /siege admin skip} (DESIGN §11.1, fixes N9): never throws, and
     * says why nothing happened instead of v2's "already in matchmaking or progress" for both cases.
     */
    public SkipOutcome skip(SkipRequester requester) {
        switch (phase) {
            case COOLDOWN -> {
                List<SiegeEffect> effects = new ArrayList<>();
                beginMatchmaking(effects);
                return new SkipOutcome(SkipResult.COOLDOWN_SKIPPED, List.copyOf(effects));
            }
            case MATCHMAKING -> {
                int target = requester == SkipRequester.ADMIN
                        ? timeline.voteClose() + 1
                        : Math.max(PLAYER_SKIP_MATCHMAKING_SECONDS, timeline.voteClose() + 1);
                if (secondsRemaining <= target) return new SkipOutcome(SkipResult.TOO_LATE, List.of());
                secondsRemaining = target;
                return new SkipOutcome(SkipResult.MATCHMAKING_SHORTENED, List.of());
            }
            case HUB -> {
                return new SkipOutcome(requester == SkipRequester.ADMIN ? SkipResult.TOO_LATE : SkipResult.NOT_SKIPPABLE, List.of());
            }
            default -> {
                return new SkipOutcome(SkipResult.NOT_SKIPPABLE, List.of());
            }
        }
    }

    /**
     * New lobby/global configuration from a runtime-config refresh. Applied immediately between
     * matches; otherwise kept until the lobby enters cooldown (never mid-match).
     *
     * @return true when applied immediately
     * @throws IllegalArgumentException for another lobby, or a timeline that doesn't fit
     */
    public boolean offerConfiguration(KnkSiegeLobby newLobby, KnkSiegeConfiguration newConfiguration) {
        if (newLobby.id() != lobby.id()) {
            throw new IllegalArgumentException("Configuration for lobby " + newLobby.id() + " offered to lobby " + lobby.id());
        }
        SiegeTimeline newTimeline = validTimeline(newLobby, newConfiguration);
        if (phase.isBetweenMatches()) {
            apply(newLobby, newConfiguration, newTimeline);
            pendingLobby = null;
            pendingConfiguration = null;
            return true;
        }
        pendingLobby = newLobby;
        pendingConfiguration = newConfiguration;
        return false;
    }

    // ==================== Queries ====================

    public SiegePhase phase() {
        return phase;
    }

    /** Seconds until the phase's next boundary: match start, match end, or end of cooldown. */
    public int secondsRemaining() {
        return Math.max(0, secondsRemaining);
    }

    /** Seconds since the match started (IN_PROGRESS/ENDING). */
    public int secondsElapsed() {
        return secondsElapsed;
    }

    public KnkSiegeLobby lobby() {
        return lobby;
    }

    /** The global configuration this lobby is running with (frozen during a match). */
    public KnkSiegeConfiguration configuration() {
        return configuration;
    }

    public SiegeTimeline timeline() {
        return timeline;
    }

    public boolean hasPendingConfiguration() {
        return pendingLobby != null;
    }

    public List<KnkSiegeScenario> candidates() {
        return candidates;
    }

    public Optional<VoteTally> voteTally() {
        return Optional.ofNullable(tally);
    }

    /** The drawn (and locked) scenario, from the draw until cooldown. */
    public Optional<KnkSiegeScenario> drawnScenario() {
        return Optional.ofNullable(drawnScenario);
    }

    public int matchDurationSeconds() {
        return matchDurationSeconds;
    }

    public boolean isJoinable() {
        return phase.isJoinable();
    }

    public boolean isVotingOpen() {
        return phase == SiegePhase.MATCHMAKING && tally != null && secondsRemaining > timeline.voteClose();
    }

    /**
     * Join capacity (DESIGN §6.2): before the draw the largest PlayersMax among the candidates,
     * after it the drawn scenario's.
     */
    public int joinCapacity() {
        if (drawnScenario != null) return drawnScenario.playersMax();
        return candidates.stream().mapToInt(KnkSiegeScenario::playersMax).max().orElse(0);
    }

    /**
     * Minimum title experience to join (DESIGN §6.2): before the draw the lowest requirement among
     * the candidates, after it the drawn scenario's (0 = none).
     */
    public int joinMinTitleExperience() {
        if (drawnScenario != null) return orZero(drawnScenario.minTitleExperience());
        return candidates.stream().mapToInt(s -> orZero(s.minTitleExperience())).min().orElse(0);
    }

    // ==================== Transitions ====================

    private void matchmakingStep(int memberCount, List<SiegeEffect> effects) {
        int r = secondsRemaining;
        if (r > 0 && r < lobby.matchmakingSeconds() && timeline.announcementMarks().contains(r)) {
            effects.add(new AnnounceEffect(Audience.ONLINE, Announcement.MATCHMAKING_COUNTDOWN, r, r > timeline.hub()));
        }
        if (r == timeline.voteClose()) {
            effects.add(new AnnounceEffect(Audience.MEMBERS, Announcement.VOTING_CLOSED, r - timeline.hub(), r > timeline.hub()));
        }
        if (r == timeline.draw() && !drawScenario(memberCount, effects)) return;
        if (r == timeline.hub()) {
            if (drawnScenario == null) {
                cancel(CancelReason.NO_SCENARIO_AVAILABLE, effects);
                return;
            }
            moveTo(SiegePhase.HUB, effects);
            effects.add(new SendToHubEffect(drawnScenario));
        }
        if (r == timeline.teamSplit() && drawnScenario != null) {
            effects.add(new SplitTeamsEffect(drawnScenario));
        }
        if (r <= 0) startMatch(memberCount, effects);
    }

    private boolean drawScenario(int memberCount, List<SiegeEffect> effects) {
        Optional<VoteTally.Draw> draw = tally == null ? Optional.empty() : tally.draw(
                random,
                id -> lobby.scenario(id).map(s -> locks.isScenarioAvailable(lobby.id(), s)).orElse(false),
                lobby.rotation());
        if (draw.isEmpty()) {
            cancel(CancelReason.NO_SCENARIO_AVAILABLE, effects);
            return false;
        }
        KnkSiegeScenario scenario = lobby.scenario(draw.get().scenarioId()).orElseThrow();
        if (memberCount < scenario.playersMin()) {
            cancel(CancelReason.NOT_ENOUGH_PLAYERS, effects);
            return false;
        }
        if (!locks.tryLock(lobby.id(), scenario)) {
            cancel(CancelReason.NO_SCENARIO_AVAILABLE, effects);
            return false;
        }
        drawnScenario = scenario;
        effects.add(new DrawScenarioEffect(scenario, draw.get()));
        return true;
    }

    private void startMatch(int memberCount, List<SiegeEffect> effects) {
        if (drawnScenario == null) {
            cancel(CancelReason.NO_SCENARIO_AVAILABLE, effects);
            return;
        }
        if (memberCount < drawnScenario.playersMin()) {
            cancel(CancelReason.NOT_ENOUGH_PLAYERS, effects);
            return;
        }
        matchDurationSeconds = MatchDurationCalculator.seconds(drawnScenario.matchLength(), memberCount);
        secondsRemaining = matchDurationSeconds;
        secondsElapsed = 0;
        moveTo(SiegePhase.IN_PROGRESS, effects);
        effects.add(new StartMatchEffect(drawnScenario, matchDurationSeconds, memberCount));
    }

    private void endMatch(SiegeEndReason reason, List<SiegeEffect> effects) {
        secondsRemaining = 0;
        moveTo(SiegePhase.ENDING, effects);
        effects.add(new EndMatchEffect(drawnScenario, reason));
    }

    private void beginMatchmaking(List<SiegeEffect> effects) {
        applyPending();
        if (!lobby.hasReadyScenario()) {
            locks.release(lobby.id());
            clearRound();
            secondsRemaining = 0;
            moveTo(SiegePhase.DISABLED, effects);
            effects.add(new LobbyDisabledEffect(DisableReason.NO_READY_SCENARIO));
            return;
        }
        List<KnkSiegeRotationEntry> available = lobby.rotation().stream()
                .filter(e -> locks.isScenarioAvailable(lobby.id(), e.scenario()))
                .toList();
        if (available.isEmpty()) {
            cancel(CancelReason.NO_SCENARIO_AVAILABLE, effects);
            return;
        }

        clearRound();
        candidates = WeightedPicker.pickDistinct(available, Math.max(1, lobby.voteCandidateCount()),
                KnkSiegeRotationEntry::weight, random).stream().map(KnkSiegeRotationEntry::scenario).toList();
        tally = new VoteTally(candidates.stream().map(KnkSiegeScenario::id).toList(), lobby.allowRandomVote());
        secondsRemaining = lobby.matchmakingSeconds();
        moveTo(SiegePhase.MATCHMAKING, effects);
        effects.add(new MatchmakingStartedEffect(candidates));
        effects.add(new AnnounceEffect(Audience.ONLINE, Announcement.MATCHMAKING_OPENED, secondsRemaining, true));
    }

    private void cancel(CancelReason reason, List<SiegeEffect> effects) {
        effects.add(new CancelMatchmakingEffect(reason, phase == SiegePhase.HUB));
        enterCooldown(effects);
    }

    private void enterCooldown(List<SiegeEffect> effects) {
        locks.release(lobby.id());
        clearRound();
        applyPending();
        secondsRemaining = lobby.cooldownSeconds();
        moveTo(SiegePhase.COOLDOWN, effects);
        effects.add(new RefreshConfigEffect(lobby.id()));
    }

    private void moveTo(SiegePhase next, List<SiegeEffect> effects) {
        if (phase == next) return;
        effects.add(new PhaseChangedEffect(phase, next));
        phase = next;
    }

    private void clearRound() {
        candidates = List.of();
        tally = null;
        drawnScenario = null;
        matchDurationSeconds = 0;
        secondsElapsed = 0;
    }

    private void applyPending() {
        if (pendingLobby == null) return;
        apply(pendingLobby, pendingConfiguration, validTimeline(pendingLobby, pendingConfiguration));
        pendingLobby = null;
        pendingConfiguration = null;
    }

    private void apply(KnkSiegeLobby newLobby, KnkSiegeConfiguration newConfiguration, SiegeTimeline newTimeline) {
        this.lobby = newLobby;
        this.configuration = newConfiguration;
        this.timeline = newTimeline;
    }

    private static SiegeTimeline validTimeline(KnkSiegeLobby lobby, KnkSiegeConfiguration configuration) {
        SiegeTimeline timeline = SiegeTimeline.from(configuration);
        timeline.requireFits(lobby.matchmakingSeconds());
        return timeline;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
