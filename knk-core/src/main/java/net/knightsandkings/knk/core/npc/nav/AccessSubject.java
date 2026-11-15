package net.knightsandkings.knk.core.npc.nav;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Who moves, as far as K&K's access rules care (implementation plan Phase 2; BEHAVIOR_MODEL NAV-09): the road
 * policy, the walk access and the gate rule ask a subject instead of a player, so an NPC never borrows a player's
 * rights. A player is adapted by {@code paper.navigation.PlayerAccessSubject}; until NPCs have their own permission
 * identity (Phase 5) an NPC uses a configured {@link NpcAccessProfile} through {@link NpcAccessSubject}. Read on the
 * main thread.
 */
public interface AccessSubject {

    /** What a subject's own lists say about a region, before the domain's entry and exit rules are asked. */
    enum RegionOverride {
        /** The domain's rules decide. */
        RULES,
        /** The subject may enter and leave whatever the domain says. */
        ALLOW,
        /** The subject may not enter, whatever the domain says. */
        DENY
    }

    /** For logs and messages. */
    String name();

    /** The player behind the subject; empty for an NPC. */
    Optional<UUID> playerId();

    /** Ignores domain entry and exit rules ({@code knk.region.bypass} for players). */
    boolean bypassesDomainRules();

    /** Passes any closed gate door by using it (players: {@code knk.gate.admin}). */
    boolean gateAdmin();

    /** May use a closed gate door that allows pass-through (players: {@code knk.gate.passthrough.use}). */
    boolean mayUseGates();

    /** May open the hand-openable door block at this position. */
    boolean mayOpenDoor(String world, int x, int y, int z);

    /** The world the subject is in, or null when unknown. */
    String world();

    /** The WorldGuard regions the subject stands in now (exit rules; a region it is in is not "entered"). */
    Set<String> currentRegions();

    /** The subject's own verdict on a region; {@link RegionOverride#RULES} unless it has lists of its own. */
    default RegionOverride regionOverride(String regionId) {
        return RegionOverride.RULES;
    }
}
