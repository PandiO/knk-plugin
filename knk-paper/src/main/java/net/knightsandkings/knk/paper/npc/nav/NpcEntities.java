package net.knightsandkings.knk.paper.npc.nav;

import org.bukkit.entity.Entity;

/**
 * The one check every player-system guard uses (NAV-08): is this entity an NPC rather than a real player? Citizens
 * (and most NPC plugins) mark their entities with the {@code NPC} metadata key; player-shaped NPCs are {@code Player}
 * instances and would otherwise reach player listeners (region tracking, statistics, telemetry, combat tags).
 */
public final class NpcEntities {

    public static final String METADATA_KEY = "NPC";

    private NpcEntities() {
    }

    public static boolean isNpc(Entity entity) {
        return entity != null && entity.hasMetadata(METADATA_KEY);
    }
}
