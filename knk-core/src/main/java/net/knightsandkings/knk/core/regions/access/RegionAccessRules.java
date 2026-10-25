package net.knightsandkings.knk.core.regions.access;

import net.knightsandkings.knk.core.regions.RegionTransitionType;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The domain AllowEntry/AllowExit rule, on WorldGuard regions (KNG-56).
 *
 * <p>A move is refused when any region it enters forbids entry, or any region it leaves forbids
 * leaving - every region counts, not only the highest-priority one (that is where WorldGuard's own
 * {@code entry}/{@code exit} flags differ). A player who is a member or owner of the region (the
 * domain's owner or residents) passes that region. Bypass ({@code knk.region.bypass}) is the
 * caller's concern. Entry is checked before exit, as the border check always did.
 *
 * <p>A domain can have several regions (its main region and sub-regions, KNG-255): moving between two regions of the
 * same domain is neither leaving nor entering it. Regions are matched to a domain by {@link RegionView#domainId()}
 * (the {@code knk-domain-id} flag); a region without one counts as its own domain, as before.
 *
 * <p>Pure and Bukkit/WorldGuard-free; the regions come in as {@link RegionView}s.
 */
public final class RegionAccessRules {

    /** The access state a region carries for entry or exit. */
    public enum AccessState { ALLOW, DENY, UNSET }

    /** What the rules need to know about one region, for one player. */
    public interface RegionView {
        String regionId();

        /** The domain's name for messages, or null to fall back to the region id. */
        String displayName();

        AccessState entry();

        AccessState exit();

        /** Is the player an owner or member (resident) of this region? */
        boolean isMember();

        /** KNG-255: the id of the domain the region belongs to, or null when unknown (synced before KNG-255). */
        default String domainId() {
            return null;
        }
    }

    /** Why a move is refused, with the player-facing message. */
    public record Refusal(RegionTransitionType type, String regionId, String message) {
    }

    /**
     * As {@link #evaluate(Collection, Collection)}, without the crossings between regions of one domain: a region entered
     * while still in another region of its domain doesn't count, nor one left for another region of its domain.
     *
     * @param destination every region at the position the move goes to (WorldGuard's "to" set); the origin is derived
     *                    from it ({@code destination - entered + exited}). Null: no domain grouping.
     */
    public Optional<Refusal> evaluate(Collection<? extends RegionView> entered, Collection<? extends RegionView> exited,
                                      Collection<? extends RegionView> destination) {
        return evaluate(null, entered, exited, destination, DomainAccessExemptions.NONE);
    }

    /**
     * As {@link #evaluate(Collection, Collection, Collection)} (KNG-255 domain grouping) with the player's
     * {@link DomainAccessExemptions} (arena duels P0) applied to the crossings that remain.
     */
    public Optional<Refusal> evaluate(UUID playerId, Collection<? extends RegionView> entered,
                                      Collection<? extends RegionView> exited, Collection<? extends RegionView> destination,
                                      DomainAccessExemptions exemptions) {
        if (destination == null) {
            return evaluate(playerId, entered, exited, exemptions);
        }
        Set<String> enteredIds = ids(entered);
        Set<String> destinationDomains = new HashSet<>();
        Set<String> originDomains = new HashSet<>();
        for (RegionView region : destination) {
            String domain = domainKey(region);
            if (domain != null) {
                destinationDomains.add(domain);
                if (!enteredIds.contains(regionKey(region))) {
                    originDomains.add(domain);
                }
            }
        }
        for (RegionView region : nullSafe(exited)) {
            String domain = domainKey(region);
            if (domain != null) {
                originDomains.add(domain);
            }
        }
        List<? extends RegionView> reallyEntered = nullSafe(entered).stream()
            .filter(region -> domainKey(region) == null || !originDomains.contains(domainKey(region))).toList();
        List<? extends RegionView> reallyExited = nullSafe(exited).stream()
            .filter(region -> domainKey(region) == null || !destinationDomains.contains(domainKey(region))).toList();
        return evaluate(playerId, reallyEntered, reallyExited, exemptions);
    }

    public Optional<Refusal> evaluate(Collection<? extends RegionView> entered, Collection<? extends RegionView> exited) {
        return evaluate(null, entered, exited, DomainAccessExemptions.NONE);
    }

    /**
     * The rule with the player's {@link DomainAccessExemptions} (arena duels DESIGN §8.2): an entry exemption passes
     * that region's entry deny; a confinement refuses leaving that region, whatever its exit state.
     *
     * @param playerId the moving player; null asks no exemptions
     */
    public Optional<Refusal> evaluate(UUID playerId, Collection<? extends RegionView> entered,
                                      Collection<? extends RegionView> exited, DomainAccessExemptions exemptions) {
        DomainAccessExemptions asked = playerId == null || exemptions == null ? DomainAccessExemptions.NONE : exemptions;
        if (entered != null) {
            for (RegionView region : entered) {
                if (region.entry() == AccessState.DENY && !region.isMember()
                        && !asked.exemptFromEntry(playerId, region.regionId())) {
                    return Optional.of(new Refusal(RegionTransitionType.ENTER, region.regionId(),
                        "You are not allowed to enter " + name(region) + "."));
                }
            }
        }
        if (exited != null) {
            for (RegionView region : exited) {
                Optional<String> confined = asked.confinement(playerId, region.regionId());
                if (confined.isPresent()) {
                    return Optional.of(new Refusal(RegionTransitionType.EXIT, region.regionId(), confined.get()));
                }
                if (region.exit() == AccessState.DENY && !region.isMember()) {
                    return Optional.of(new Refusal(RegionTransitionType.EXIT, region.regionId(),
                        "You are not allowed to leave " + name(region) + "."));
                }
            }
        }
        return Optional.empty();
    }

    /** The access state for a domain's AllowEntry/AllowExit value ({@code null} = not restricted). */
    public static AccessState fromAllowed(Boolean allowed) {
        if (allowed == null) {
            return AccessState.UNSET;
        }
        return allowed ? AccessState.ALLOW : AccessState.DENY;
    }

    private static String name(RegionView region) {
        String name = region.displayName();
        return name != null && !name.isBlank() ? name : region.regionId();
    }

    private static <T> Collection<T> nullSafe(Collection<T> regions) {
        return regions != null ? regions : List.of();
    }

    private static Set<String> ids(Collection<? extends RegionView> regions) {
        Set<String> ids = new HashSet<>();
        for (RegionView region : nullSafe(regions)) {
            ids.add(regionKey(region));
        }
        return ids;
    }

    private static String regionKey(RegionView region) {
        return region.regionId() == null ? "" : region.regionId().toLowerCase(Locale.ROOT);
    }

    private static String domainKey(RegionView region) {
        String id = region.domainId();
        return id == null || id.isBlank() ? null : id.trim();
    }
}
