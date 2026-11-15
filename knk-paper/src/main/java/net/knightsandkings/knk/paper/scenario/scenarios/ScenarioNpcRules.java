package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.gates.target.GateBox;
import net.knightsandkings.knk.core.npc.nav.AccessRules;
import net.knightsandkings.knk.core.npc.nav.AccessSubject;
import net.knightsandkings.knk.paper.gates.GateDoorBounds;

/**
 * Access rules for the NPC scenarios (implementation plan 4b.3 item 14): fake access data for regions (a scenario
 * denies and allows entry to its own WorldGuard region) and a scenario gate's real state and box from the plugin's
 * gate cache ({@link GateDoorBounds}, as {@code NavigationAccess} answers on a server). A door that is not open
 * (closed, closing, opening) blocks NPCs, as a door without NPC gate rights does.
 */
public final class ScenarioNpcRules implements AccessRules {

    private final GateManager gates;
    private final Set<String> denied = ConcurrentHashMap.newKeySet();

    /** @param gates the plugin's gate cache, or null for a scenario without gates */
    public ScenarioNpcRules(GateManager gates) {
        this.gates = gates;
    }

    /** NPCs may no longer enter the region (unless they stand in it). */
    public void deny(String regionId) {
        denied.add(regionId);
    }

    public void allow(String regionId) {
        denied.remove(regionId);
    }

    @Override
    public Predicate<String> mayEnter(AccessSubject subject) {
        Set<String> current = Set.copyOf(subject.currentRegions());
        return region -> current.contains(region) || !denied.contains(region);
    }

    @Override
    public boolean gateBlocks(AccessSubject subject, int doorId) {
        CachedGateDoor door = gates == null ? null : gates.getGate(doorId);
        return door != null && door.getCurrentState() != AnimationState.OPEN;
    }

    @Override
    public Optional<GateBox> doorBox(int doorId) {
        CachedGateDoor door = gates == null ? null : gates.getGate(doorId);
        return door == null ? Optional.empty() : Optional.ofNullable(GateDoorBounds.of(door, gates));
    }
}
