package net.knightsandkings.knk.core.domain.arenas;

/** A kit duelists may use in an arena's kit modes (DESIGN.md §8.4), in the arena's order. */
public record KnkArenaKit(int kitId, String kitName, int sequenceNumber) {
}
