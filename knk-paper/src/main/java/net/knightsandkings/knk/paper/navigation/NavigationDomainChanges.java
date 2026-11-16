package net.knightsandkings.knk.paper.navigation;

import java.util.List;
import java.util.Objects;

import net.knightsandkings.knk.core.realtime.Coalescer;
import net.knightsandkings.knk.core.realtime.EntityChangeRouter;

/**
 * PandiO/knk-workspace#533 and #593: a domain rule changed in the web app with realtime on reaches the NPC checks.
 * <p>
 * A change to a Town, District, Structure or DomainSubRegion arrives as {@code EntityChanged} (the region → domain map
 * is cleared by its own handler) and, when it touches AllowEntry or AllowExit, also as {@code DomainAccessChanged}
 * (the WorldGuard flag sync). Neither told NPC navigation, and the NPC checks answer from that map, so a group walked
 * into a district whose entry had just been removed. Both now ask, coalesced into one run, for the {@code rewarm}:
 * the roads' regions are asked about again in place and then road navigation's availability event is sent (paper: a
 * {@link DomainCacheRewarm} over {@code RegionDomainResolver.refreshRegions}, active only while NPC navigation runs).
 */
public final class NavigationDomainChanges {

    /** The coalescer's key: changes within its delay share one re-warm. */
    public static final String KEY = "navigation region domains";
    /** The entity types whose change can change a region's entry or exit rule. */
    public static final List<String> DOMAIN_TYPES = List.of("Town", "District", "Structure", "DomainSubRegion");

    private final Coalescer coalescer;
    private final Runnable rewarm;

    /**
     * @param coalescer the realtime coalescer (runs on the main thread after its delay)
     * @param rewarm    asks about the roads' regions again, then sends the availability event
     */
    public NavigationDomainChanges(Coalescer coalescer, Runnable rewarm) {
        this.coalescer = Objects.requireNonNull(coalescer, "coalescer");
        this.rewarm = Objects.requireNonNull(rewarm, "rewarm");
    }

    /** Every {@link #DOMAIN_TYPES} change asks for the re-warm (after the handlers registered before it). */
    public NavigationDomainChanges register(EntityChangeRouter router) {
        for (String type : DOMAIN_TYPES) {
            router.on(type, (id, key, op) -> changed());
        }
        return this;
    }

    /** A domain's rule may have changed: the re-warm runs once after the coalescer's delay. */
    public void changed() {
        coalescer.request(KEY, rewarm);
    }

    /** {@code DomainAccessChanged}: {@code then} (the flag sync, may be null) and the re-warm. */
    public Runnable withDomainAccess(Runnable then) {
        return () -> {
            try {
                if (then != null) {
                    then.run();
                }
            } finally {
                changed();
            }
        };
    }
}
