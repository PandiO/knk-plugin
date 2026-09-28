package net.knightsandkings.knk.core.regions;

import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SimpleRegionTransitionService.previewAccess - the teleport engine's up-front AllowEntry/AllowExit
 * check (docs/specs/teleport/DESIGN.md §3.4 step 1): same verdict as handleRegionTransition, no side
 * effects, unknown regions allowed.
 */
class RegionAccessPreviewTest {

    private static DomainSnapshot town(int id, String name, boolean allowEntry, boolean allowExit) {
        return new DomainSnapshot(id, name, null, "town_" + id, allowEntry, allowExit, "Town",
            Set.of(), Set.of(), Set.of(), Set.of());
    }

    private static SimpleRegionTransitionService serviceWith(DomainSnapshot... domains) {
        RegionDomainResolver resolver = new RegionDomainResolver();
        for (DomainSnapshot domain : domains) {
            resolver.registerDomain(domain);
        }
        return new SimpleRegionTransitionService(resolver, null,
            entered -> { throw new AssertionError("preview must not fire the entered-domains callback"); });
    }

    @Test
    void enteringAClosedDomainIsDenied() {
        SimpleRegionTransitionService service = serviceWith(town(1, "Kardenna", false, true));

        RegionTransitionDecision decision = service.previewAccess(Set.of(), Set.of("town_1"));

        assertFalse(decision.isMovementAllowed());
        assertEquals("You are not allowed to enter Kardenna.", decision.getMessage().orElseThrow());
    }

    @Test
    void leavingADomainWithExitClosedIsDenied() {
        SimpleRegionTransitionService service = serviceWith(town(2, "Jail", true, false));

        RegionTransitionDecision decision = service.previewAccess(Set.of("town_2"), Set.of());

        assertFalse(decision.isMovementAllowed());
        assertEquals("You are not allowed to leave Jail.", decision.getMessage().orElseThrow());
    }

    @Test
    void openDomainsAndUnknownRegionsAreAllowed() {
        SimpleRegionTransitionService service = serviceWith(town(3, "Open", true, true));

        assertNull(service.previewAccess(Set.of(), Set.of("town_3")));
        assertNull(service.previewAccess(Set.of(), Set.of("not_cached_region")));
    }

    @Test
    void stayingInsideAClosedDomainIsAllowed() {
        SimpleRegionTransitionService service = serviceWith(town(4, "Closed", false, false));

        assertNull(service.previewAccess(Set.of("town_4"), Set.of("town_4")));
    }
}
