package net.knightsandkings.knk.core.teleport;

/**
 * What started a teleport (docs/specs/teleport/DESIGN.md §3.4). Staff teleports are instant and
 * skip warmup, cooldown, combat tag and the safe-location check (DESIGN §4 D8); every other kind
 * is a player-initiated teleport that goes through all of them.
 */
public enum TeleportKind {
    /** {@code /tp}, {@code /tphere}, {@code /knk tp}, and the staff "send a player" forms. */
    STAFF,
    /** {@code /tpa} / {@code /tpahere} (Phase 3). */
    REQUEST,
    /** {@code /spawn} (Phase 4). */
    SPAWN,
    /** {@code /hub}: to the hub spawn set on the Game Settings page (KNG-188). Priced and cooled down by the groups' spawn settings. */
    HUB,
    /** {@code /warp} and the teleport menu (Phases 5-6). */
    WARP,
    /** {@code /back} (Phase 7). */
    BACK,
    /**
     * An arena duel's lobby teleport (arena duels DESIGN D6): a player teleport with the normal warmup and the
     * combat-tag guard, but no cooldown and no charge. Only the duel runtime starts these.
     */
    ARENA,
    /**
     * The duel runtime's own moves to the battleground spawns and the arena exit (arena duels DESIGN D7): instant,
     * no cooldown, combat tag or charge, safe-spot checked, not audited as a staff teleport.
     */
    ARENA_SYSTEM;

    public boolean isStaff() {
        return this == STAFF;
    }

    /** A teleport the plugin makes on its own schedule (no warmup, combat tag or cooldown, but a safe spot). */
    public boolean isSystem() {
        return this == ARENA_SYSTEM;
    }

    /** Whether this kind starts (and is refused by) a teleport cooldown: player teleports except the arena's. */
    public boolean usesCooldown() {
        return !isStaff() && this != ARENA && this != ARENA_SYSTEM;
    }
}
