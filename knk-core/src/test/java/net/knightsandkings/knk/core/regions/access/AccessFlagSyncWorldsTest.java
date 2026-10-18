package net.knightsandkings.knk.core.regions.access;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.domains.DomainAccessRule;
import net.knightsandkings.knk.core.regions.access.AccessFlagSync.AccessFlags;
import net.knightsandkings.knk.core.regions.access.AccessFlagSync.RegionRef;
import net.knightsandkings.knk.core.regions.access.RegionAccessRules.AccessState;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * KNG-112: the hub and the gameplay world each have a region {@code town_1} for their own town. Each rule lands on its
 * own world's region only; a rule without a world (a domain the API has no world for yet) still covers every world.
 */
class AccessFlagSyncWorldsTest {

    /** In-memory WorldGuard with worlds: "world|region" (lower-case) -> flags. */
    private static final class TwoWorlds implements AccessFlagSync.Store {
        final Map<String, AccessFlags> regions = new LinkedHashMap<>();
        final List<String> writes = new ArrayList<>();

        TwoWorlds region(String world, String id, AccessFlags flags) {
            regions.put(key(world, id), flags);
            return this;
        }

        AccessFlags flags(String world, String id) {
            return regions.get(key(world, id));
        }

        private static String key(String world, String id) {
            return world.toLowerCase(Locale.ROOT) + "|" + id.toLowerCase(Locale.ROOT);
        }

        @Override
        public Optional<AccessFlags> read(String regionId) {
            return regions.entrySet().stream().filter(e -> e.getKey().endsWith("|" + regionId.toLowerCase(Locale.ROOT)))
                .map(Map.Entry::getValue).findFirst();
        }

        @Override
        public void write(String regionId, AccessFlags flags) {
            for (String key : List.copyOf(regions.keySet())) {
                if (key.endsWith("|" + regionId.toLowerCase(Locale.ROOT))) {
                    regions.put(key, flags);
                    writes.add(key);
                }
            }
        }

        @Override
        public Optional<AccessFlags> read(String world, String regionId) {
            return world == null ? read(regionId) : Optional.ofNullable(regions.get(key(world, regionId)));
        }

        @Override
        public void write(String world, String regionId, AccessFlags flags) {
            if (world == null) {
                write(regionId, flags);
            } else if (regions.containsKey(key(world, regionId))) {
                regions.put(key(world, regionId), flags);
                writes.add(key(world, regionId));
            }
        }

        @Override
        public Collection<String> regionsWithAccessFlags() {
            throw new AssertionError("the world-aware listing is used");
        }

        @Override
        public Collection<RegionRef> regionsWithAccessFlagsByWorld() {
            return regions.entrySet().stream().filter(e -> !e.getValue().equals(AccessFlags.NONE))
                .map(e -> new RegionRef(e.getKey().substring(0, e.getKey().indexOf('|')),
                    e.getKey().substring(e.getKey().indexOf('|') + 1)))
                .toList();
        }

        @Override
        public void persist() {
        }
    }

    private static final AccessFlags OPEN = new AccessFlags(AccessState.ALLOW, AccessState.ALLOW, "Hubtown");

    @Test
    void eachWorldsRuleLandsOnItsOwnRegionOnly() {
        TwoWorlds store = new TwoWorlds()
            .region("hub", "town_1", AccessFlags.NONE)
            .region("gameplay", "town_1", AccessFlags.NONE);

        new AccessFlagSync().run(List.of(
            new DomainAccessRule(1, "Hubtown", "town_1", true, true, "Town", "hub"),
            new DomainAccessRule(2, "Oakhaven", "town_1", false, true, "Town", "gameplay")), store);

        assertEquals(OPEN, store.flags("hub", "town_1"));
        assertEquals(new AccessFlags(AccessState.DENY, AccessState.ALLOW, "Oakhaven"), store.flags("gameplay", "town_1"));
    }

    @Test
    void anOrphanedRegionInOneWorldIsClearedWhileTheOtherWorldsKeepsItsRule() {
        TwoWorlds store = new TwoWorlds()
            .region("hub", "town_1", OPEN)
            .region("gameplay", "town_1", new AccessFlags(AccessState.DENY, AccessState.ALLOW, "Gone"));

        AccessFlagSync.Report report = new AccessFlagSync().run(List.of(
            new DomainAccessRule(1, "Hubtown", "town_1", true, true, "Town", "hub")), store);

        assertEquals(1, report.cleared());
        assertEquals(OPEN, store.flags("hub", "town_1"));
        assertEquals(AccessFlags.NONE, store.flags("gameplay", "town_1"));
    }

    @Test
    void aRuleWithoutAWorldStillCoversEveryWorld() {
        TwoWorlds store = new TwoWorlds()
            .region("hub", "town_1", AccessFlags.NONE)
            .region("gameplay", "town_1", OPEN);

        AccessFlagSync.Report report = new AccessFlagSync().run(List.of(
            new DomainAccessRule(1, "Hubtown", "town_1", true, true, "Town")), store);

        assertEquals(0, report.cleared());
        assertEquals(OPEN, store.flags("hub", "town_1"));
    }
}
