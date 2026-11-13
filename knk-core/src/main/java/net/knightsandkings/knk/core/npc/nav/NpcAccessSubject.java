package net.knightsandkings.knk.core.npc.nav;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * An NPC as an {@link AccessSubject}: its {@link NpcAccessProfile} and where it stands now. Immutable; built per
 * plan from the NPC's position, so the exit rules are judged from where the NPC is, not where a player is.
 */
public final class NpcAccessSubject implements AccessSubject {

    private final String name;
    private final NpcAccessProfile profile;
    private final String world;
    private final Set<String> currentRegions;

    public NpcAccessSubject(String name, NpcAccessProfile profile, String world, Set<String> currentRegions) {
        this.name = Objects.requireNonNull(name, "name");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.world = world;
        this.currentRegions = currentRegions == null ? Set.of() : Set.copyOf(currentRegions);
    }

    public NpcAccessProfile profile() {
        return profile;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Optional<UUID> playerId() {
        return Optional.empty();
    }

    @Override
    public boolean bypassesDomainRules() {
        return profile.bypassDomainRules();
    }

    /** Never: an NPC cannot use a gate door (decision Q5). */
    @Override
    public boolean gateAdmin() {
        return false;
    }

    /** Never: an NPC cannot use a gate door (decision Q5). */
    @Override
    public boolean mayUseGates() {
        return false;
    }

    @Override
    public boolean mayOpenDoor(String world, int x, int y, int z) {
        return profile.openDoors();
    }

    @Override
    public String world() {
        return world;
    }

    @Override
    public Set<String> currentRegions() {
        return currentRegions;
    }

    @Override
    public RegionOverride regionOverride(String regionId) {
        return profile.override(regionId);
    }
}
