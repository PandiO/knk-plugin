package net.knightsandkings.knk.core.survival.spawn;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which zones future spawns may use (DESIGN §6, S22, S63). Without recapture, ownership only moves to the NPC
 * team, so an activated zone stays active (latched, defensively). With recapture enabled, prerequisites are
 * re-evaluated against the current ownership on every {@link #update}: zones that become ineligible stop new
 * spawns and the frontier moves outward. Eligibility changes affect future spawn selections only; enemies already
 * spawned are not this class's business and are never moved or erased for it.
 */
public final class SpawnFrontier {
    private final List<SpawnZone> zones;
    private final boolean recaptureEnabled;
    private final Set<String> active = new LinkedHashSet<>();

    /** Zones newly activated and deactivated by an update. */
    public record Change(Set<String> activated, Set<String> deactivated) {
        public Change {
            activated = Set.copyOf(activated);
            deactivated = Set.copyOf(deactivated);
        }

        public boolean isEmpty() {
            return activated.isEmpty() && deactivated.isEmpty();
        }
    }

    public SpawnFrontier(List<SpawnZone> zones, boolean recaptureEnabled) {
        this.zones = List.copyOf(zones);
        this.recaptureEnabled = recaptureEnabled;
        for (SpawnZone zone : this.zones) {
            if (zone.isInitial()) active.add(zone.id());
        }
    }

    public boolean recaptureEnabled() {
        return recaptureEnabled;
    }

    /** Ids of the zones future spawns may use. */
    public Set<String> activeZoneIds() {
        return Set.copyOf(active);
    }

    public List<SpawnZone> activeZones() {
        return zones.stream().filter(z -> active.contains(z.id())).toList();
    }

    public boolean isActive(String zoneId) {
        return active.contains(zoneId);
    }

    /** Re-evaluates the zones after an ownership change. */
    public Change update(Set<String> npcHeldObjectives) {
        Set<String> activated = new LinkedHashSet<>();
        Set<String> deactivated = new LinkedHashSet<>();
        for (SpawnZone zone : zones) {
            boolean eligible = zone.isEligible(npcHeldObjectives);
            if (eligible) {
                if (active.add(zone.id())) activated.add(zone.id());
            } else if (recaptureEnabled && !zone.isInitial()) {
                if (active.remove(zone.id())) deactivated.add(zone.id());
            }
        }
        return new Change(activated, deactivated);
    }
}
