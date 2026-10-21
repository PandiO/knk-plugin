package net.knightsandkings.knk.core.regions.access;

import net.knightsandkings.knk.core.regions.RegionTransitionType;

import java.util.Collection;
import java.util.Optional;
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
    }

    /** Why a move is refused, with the player-facing message. */
    public record Refusal(RegionTransitionType type, String regionId, String message) {
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
}
