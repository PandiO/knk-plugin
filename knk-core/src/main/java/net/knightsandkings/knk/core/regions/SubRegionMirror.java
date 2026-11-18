package net.knightsandkings.knk.core.regions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import net.knightsandkings.knk.core.domain.domains.DomainAccessRule;

/**
 * Keeps a domain's sub-regions copies of its main region (KNG-255, knk-workspace
 * {@code docs/specs/domain-subregions/DESIGN.md} §5). The main region is the source of truth: each sub-region gets its
 * priority, all its flags (category flags, an admin's {@code pvp allow}, a greeting, the KnK access flags), its owners
 * and its members, and the main region as WorldGuard parent - a safety net, so a flag set on the main region between
 * two runs already reaches the sub-region through inheritance where the sub-region carries no copy.
 *
 * <p>One-way: the sub-region's flag set <em>becomes</em> the main region's, so a flag set by hand on a sub-region is
 * replaced on the next run. Only the region's own bookkeeping flags ({@link #OWN_FLAGS}) are kept. Idempotent: a second
 * run changes nothing and doesn't save. A domain whose main region is missing is left alone (never synced from
 * nothing); a missing sub-region is reported, never created.
 *
 * <p>Pure and WorldGuard-free; the regions come in through a {@link Store}.
 */
public final class SubRegionMirror {

    /** Flags that belong to the region itself and are never copied or removed. */
    public static final Set<String> OWN_FLAGS = Set.of("knk-creation-timestamp", "knk-orphaned-since");

    private static final Pattern SUB_REGION_NAME = Pattern.compile("domain_(\\d+)_sub_(\\d+)");

    /**
     * What is mirrored of a region. Owners and members are opaque strings the store understands (players by UUID or
     * name, groups); flags by WorldGuard flag name with the store's own value objects (compared with {@code equals}).
     */
    public record RegionState(int priority, String parentId, Map<String, Object> flags, Set<String> owners,
                              Set<String> members) {
        public RegionState {
            flags = flags == null ? Map.of() : Map.copyOf(flags);
            owners = owners == null ? Set.of() : Set.copyOf(owners);
            members = members == null ? Set.of() : Set.copyOf(members);
        }
    }

    /** A domain's regions: the main region and its sub-regions, all in {@code world} (null: whichever world has them). */
    public record DomainRegions(String label, String world, String mainRegionId, List<String> subRegionIds) {
        public DomainRegions {
            subRegionIds = subRegionIds == null ? List.of() : List.copyOf(subRegionIds);
        }

        /** The domains of the access rules that have sub-regions. */
        public static List<DomainRegions> of(Collection<DomainAccessRule> rules) {
            List<DomainRegions> domains = new ArrayList<>();
            for (DomainAccessRule rule : rules) {
                if (rule.wgRegionId() != null && !rule.wgRegionId().isBlank() && !rule.subRegionIds().isEmpty()) {
                    domains.add(new DomainRegions(rule.domainType() + "#" + rule.id() + " '" + rule.name() + "'",
                        rule.worldName(), rule.wgRegionId(), rule.subRegionIds()));
                }
            }
            return domains;
        }
    }

    /** Where the regions live (WorldGuard in production). Region ids match case-insensitively, like WorldGuard. */
    public interface Store {
        /** The region's state, or empty when it doesn't exist (in {@code world}; null world: the first that has it). */
        Optional<RegionState> read(String world, String regionId);

        /**
         * Gives the region {@code desired}: priority, parent, owners, members, and exactly {@code desired.flags()} besides
         * its {@link #OWN_FLAGS}, which stay as they are. Throws when the region or the parent can't be found.
         */
        void write(String world, String regionId, RegionState desired);

        void persist();
    }

    /** What one run did. */
    public record Report(int domains, int updated, int unchanged, List<String> missingMainRegions,
                         List<String> missingSubRegions, List<String> failures, boolean persistFailed) {
        public String summary() {
            return domains + " domain(s) with sub-regions: " + updated + " sub-region(s) updated, " + unchanged
                + " unchanged, " + missingSubRegions.size() + " not found"
                + (missingMainRegions.isEmpty() ? "" : ", " + missingMainRegions.size() + " main region(s) not found")
                + (failures.isEmpty() ? "" : ", " + failures.size() + " failed")
                + (persistFailed ? ", SAVE FAILED" : "");
        }
    }

    /** Whether {@code regionId} is a sub-region's final name ({@code domain_<domainId>_sub_<id>}). */
    public static boolean isSubRegionName(String regionId) {
        return regionId != null && SUB_REGION_NAME.matcher(regionId.trim().toLowerCase(Locale.ROOT)).matches();
    }

    public Report run(Collection<DomainRegions> domains, Store store) {
        Objects.requireNonNull(domains, "domains");
        int updated = 0;
        int unchanged = 0;
        List<String> missingMain = new ArrayList<>();
        List<String> missingSub = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (DomainRegions domain : domains) {
            Optional<RegionState> main;
            try {
                main = store.read(domain.world(), domain.mainRegionId());
            } catch (RuntimeException e) {
                failures.add(domain.mainRegionId() + ": " + e.getMessage());
                continue;
            }
            if (main.isEmpty()) {
                missingMain.add(label(domain.world(), domain.mainRegionId()));
                continue;
            }
            RegionState desired = desiredFor(domain.mainRegionId(), main.get());
            for (String subRegionId : domain.subRegionIds()) {
                if (subRegionId.equalsIgnoreCase(domain.mainRegionId())) {
                    continue;
                }
                try {
                    Optional<RegionState> current = store.read(domain.world(), subRegionId);
                    if (current.isEmpty()) {
                        missingSub.add(label(domain.world(), subRegionId));
                    } else if (matches(current.get(), desired)) {
                        unchanged++;
                    } else {
                        store.write(domain.world(), subRegionId, desired);
                        updated++;
                    }
                } catch (RuntimeException e) {
                    failures.add(subRegionId + ": " + e.getMessage());
                }
            }
        }

        boolean persistFailed = false;
        if (updated > 0) {
            try {
                store.persist();
            } catch (RuntimeException e) {
                persistFailed = true;
                failures.add("save: " + e.getMessage());
            }
        }
        return new Report(domains.size(), updated, unchanged, List.copyOf(missingMain), List.copyOf(missingSub),
            List.copyOf(failures), persistFailed);
    }

    /** What every sub-region of the domain whose main region is {@code mainRegionId} must look like. */
    public static RegionState desiredFor(String mainRegionId, RegionState main) {
        return new RegionState(main.priority(), mainRegionId, withoutOwnFlags(main.flags()), main.owners(), main.members());
    }

    static boolean matches(RegionState current, RegionState desired) {
        return current.priority() == desired.priority()
            && current.parentId() != null && current.parentId().equalsIgnoreCase(desired.parentId())
            && withoutOwnFlags(current.flags()).equals(desired.flags())
            && current.owners().equals(desired.owners())
            && current.members().equals(desired.members());
    }

    static Map<String, Object> withoutOwnFlags(Map<String, Object> flags) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, Object> flag : flags.entrySet()) {
            if (!OWN_FLAGS.contains(flag.getKey().toLowerCase(Locale.ROOT)) && flag.getValue() != null) {
                copy.put(flag.getKey().toLowerCase(Locale.ROOT), flag.getValue());
            }
        }
        return copy;
    }

    private static String label(String world, String regionId) {
        return world == null ? regionId : regionId + " (" + world + ")";
    }
}
