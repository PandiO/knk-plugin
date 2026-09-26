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
    /** {@code /warp} and the teleport menu (Phases 5-6). */
    WARP,
    /** {@code /back} (Phase 7). */
    BACK;

    public boolean isStaff() {
        return this == STAFF;
    }
}
