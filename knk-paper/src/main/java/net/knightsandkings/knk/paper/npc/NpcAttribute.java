package net.knightsandkings.knk.paper.npc;

import org.bukkit.attribute.Attribute;

/**
 * The attributes the NPC adapters touch. {@code org.bukkit.attribute.Attribute}'s constants need a running server's
 * registry, so the adapters and their tests name attributes through this enum and only the live
 * {@link MobAttributes#bukkit()} implementation resolves them.
 */
public enum NpcAttribute {
    MAX_HEALTH,
    MOVEMENT_SPEED,
    ATTACK_DAMAGE,
    FOLLOW_RANGE;

    /** The Bukkit attribute (server only). */
    public Attribute bukkit() {
        return switch (this) {
            case MAX_HEALTH -> Attribute.MAX_HEALTH;
            case MOVEMENT_SPEED -> Attribute.MOVEMENT_SPEED;
            case ATTACK_DAMAGE -> Attribute.ATTACK_DAMAGE;
            case FOLLOW_RANGE -> Attribute.FOLLOW_RANGE;
        };
    }
}
