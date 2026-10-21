package net.knightsandkings.knk.core.duel;

/**
 * What {@link DuelStateMachine} asks the Paper runtime to do (plan Phase 4 {@code DuelService}), in
 * list order, on the main thread — e.g. with an exhaustive {@code switch (effect) { case
 * DuelEffect.EnterBattleground e -> ... }}. Effects carry facts; messages and API calls are the runtime's.
 */
public sealed interface DuelEffect {

    /** The match changed phase (refresh boss bar, sidebar, menus). */
    record PhaseChanged(DuelPhase from, DuelPhase to) implements DuelEffect {}

    /** Lock-in succeeded: start both {@code ARENA} lobby teleports together (D6). */
    record StartLobbyTeleports() implements DuelEffect {}

    /**
     * Both duelists arrived in the lobby: take the restore snapshot, announce the match (§9.1). Warmup
     * lasts {@code warmupSeconds}, then the match waits for a battleground.
     */
    record WarmupStarted(int warmupSeconds) implements DuelEffect {}

    /**
     * Kit modes, on arrival: grant {@code kitId} through {@code KitGrantPlacer}, tagged as a loan (§8.4).
     *
     * @param placeholder {@code KIT_CHOICE}: the arena's first eligible kit until the player picks (O3)
     */
    record GrantKit(DuelSide side, int kitId, boolean placeholder) implements DuelEffect {}

    /** {@code KIT_CHOICE} in warmup: remove the loaned kit {@code fromKitId}, grant {@code toKitId}. */
    record SwapKit(DuelSide side, int fromKitId, int toKitId) implements DuelEffect {}

    /** Warmup is over: put the match in the arena's queue for a free battleground ({@link ArenaQueue}). */
    record AwaitBattleground() implements DuelEffect {}

    /** Kit modes, at the countdown: the kit choice is final (the deadline; a duelist who never picked keeps the default). */
    record KitsLocked(int challengerKitId, int opponentKitId) implements DuelEffect {}

    /**
     * Battleground allocated: grant the entry exemption, {@code ARENA_SYSTEM} teleport both to their
     * spawns, freeze them for {@code countdownSeconds} (§8.2, §8.3). A failed teleport →
     * {@link DuelStateMachine#battlegroundTeleportFailed()}.
     */
    record EnterBattleground(int battlegroundId, int countdownSeconds) implements DuelEffect {}

    /** "FIGHT!": unfreeze, damage unlocked. {@code timeLimitSeconds} 0 = no limit (D11). */
    record FightStarted(int timeLimitSeconds) implements DuelEffect {}

    /** {@code /duel draw} by {@code side}; the other side can agree until {@code expiresAtTick}. */
    record DrawOffered(DuelSide side, long expiresAtTick) implements DuelEffect {}

    /**
     * The one result (end-once). Runtime: settle a decisive result or refund every other one through
     * the API (§7.4, §7.5), and move the escrow ({@link DuelEscrowPlan}).
     *
     * @param phaseAtEnd the phase the match was in when it ended
     */
    record MatchEnded(DuelResult result, DuelPhase phaseAtEnd) implements DuelEffect {}

    /**
     * The match is closed: revoke the battleground exemption, restore gear and the snapshot (§8.4), and
     * send both duelists to the arena exit when {@code fromBattleground}. Comes after the winner's linger
     * for a completed match, at once for a cancel or abort.
     */
    record Release(boolean fromBattleground) implements DuelEffect {}
}
