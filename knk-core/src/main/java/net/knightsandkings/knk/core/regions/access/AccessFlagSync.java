package net.knightsandkings.knk.core.regions.access;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.knightsandkings.knk.core.domain.domains.DomainAccessRule;
import net.knightsandkings.knk.core.regions.access.RegionAccessRules.AccessState;

/**
 * Writes every domain's AllowEntry/AllowExit (and name, for messages) onto its WorldGuard region
 * (KNG-56). WorldGuard saves the flags, so the game server enforces the last synced rules at
 * startup, at join and while the API is down.
 *
 * <p>Given the complete rule list from the API, a region whose flags differ is corrected, and a
 * region that still carries access flags but no longer belongs to any domain (deleted domain,
 * renamed region) has them cleared - a stale "deny" would otherwise lock players out for good.
 * Idempotent: a second run with the same rules changes nothing and does not save.
 */
public final class AccessFlagSync {

    /** The three values the sync owns on a region. */
    public record AccessFlags(AccessState entry, AccessState exit, String name) {
        public static final AccessFlags NONE = new AccessFlags(AccessState.UNSET, AccessState.UNSET, null);
    }

    /** Where the flags live (WorldGuard in production). Region ids match case-insensitively, like WorldGuard. */
    public interface Store {
        /** The region's current access flags, or empty when no loaded world has the region. */
        Optional<AccessFlags> read(String regionId);

        void write(String regionId, AccessFlags flags);

        /** Ids of every region that currently carries any of the access flags. */
        Collection<String> regionsWithAccessFlags();

        void persist();
    }

    /** What one run did. */
    public record Report(int rules, int updated, int cleared, int unchanged, List<String> missingRegions,
                         List<String> failures, boolean persistFailed) {
        public String summary() {
            return rules + " domain rule(s): " + updated + " region(s) updated, " + cleared + " cleared, "
                + unchanged + " unchanged, " + missingRegions.size() + " region(s) not found"
                + (failures.isEmpty() ? "" : ", " + failures.size() + " failed")
                + (persistFailed ? ", SAVE FAILED" : "");
        }
    }

    public Report run(Collection<DomainAccessRule> rules, Store store) {
        Objects.requireNonNull(rules, "rules");
        int updated = 0;
        int cleared = 0;
        int unchanged = 0;
        List<String> missing = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Set<String> owned = new HashSet<>();

        for (DomainAccessRule rule : rules) {
            String regionId = rule.wgRegionId();
            if (regionId == null || regionId.isBlank()) {
                continue;
            }
            owned.add(key(regionId));
            AccessFlags desired = new AccessFlags(
                RegionAccessRules.fromAllowed(rule.allowEntry()),
                RegionAccessRules.fromAllowed(rule.allowExit()),
                rule.name());
            try {
                Optional<AccessFlags> current = store.read(regionId);
                if (current.isEmpty()) {
                    missing.add(regionId);
                } else if (current.get().equals(desired)) {
                    unchanged++;
                } else {
                    store.write(regionId, desired);
                    updated++;
                }
            } catch (RuntimeException e) {
                failures.add(regionId + ": " + e.getMessage());
            }
        }

        for (String regionId : List.copyOf(store.regionsWithAccessFlags())) {
            if (owned.contains(key(regionId))) {
                continue;
            }
            try {
                store.write(regionId, AccessFlags.NONE);
                cleared++;
            } catch (RuntimeException e) {
                failures.add(regionId + ": " + e.getMessage());
            }
        }

        boolean persistFailed = false;
        if (updated + cleared > 0) {
            try {
                store.persist();
            } catch (RuntimeException e) {
                persistFailed = true;
                failures.add("save: " + e.getMessage());
            }
        }
        return new Report(rules.size(), updated, cleared, unchanged, List.copyOf(missing), List.copyOf(failures), persistFailed);
    }

    private static String key(String regionId) {
        return regionId.toLowerCase(Locale.ROOT);
    }
}
