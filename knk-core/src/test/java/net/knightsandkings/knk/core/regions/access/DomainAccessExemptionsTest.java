package net.knightsandkings.knk.core.regions.access;

import net.knightsandkings.knk.core.regions.RegionTransitionType;
import net.knightsandkings.knk.core.regions.access.RegionAccessRules.AccessState;
import net.knightsandkings.knk.core.regions.access.RegionAccessRules.Refusal;
import net.knightsandkings.knk.core.regions.access.RegionAccessRules.RegionView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Arena duels Phase 0 (KNG-209): entry exemptions and confinement on top of the AllowEntry/AllowExit rule. */
class DomainAccessExemptionsTest {

    private static final UUID DUELIST = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private final RegionAccessRules rules = new RegionAccessRules();

    private record Region(String regionId, String displayName, AccessState entry, AccessState exit, boolean isMember)
        implements RegionView {
    }

    private static Region region(String id, AccessState entry, AccessState exit) {
        return new Region(id, "Name of " + id, entry, exit, false);
    }

    /** The duelist may enter battleground_1 and is kept in it. */
    private static DomainAccessExemptions duel() {
        return new DomainAccessExemptions() {
            @Override
            public boolean exemptFromEntry(UUID playerId, String regionId) {
                return DUELIST.equals(playerId) && "battleground_1".equals(regionId);
            }

            @Override
            public Optional<String> confinement(UUID playerId, String regionId) {
                return DUELIST.equals(playerId) && "battleground_1".equals(regionId)
                    ? Optional.of("You can't leave the battleground during the duel.") : Optional.empty();
            }
        };
    }

    private final Region battleground = region("battleground_1", AccessState.DENY, AccessState.UNSET);

    @Test
    void withoutExemptionsTheRuleIsUnchanged() {
        Optional<Refusal> plain = rules.evaluate(List.of(battleground), List.of());
        assertEquals(plain, rules.evaluate(DUELIST, List.of(battleground), List.of(), DomainAccessExemptions.NONE));
        assertEquals(plain, rules.evaluate(DUELIST, List.of(battleground), List.of(), new DomainAccessExemptions.Registry()));
        assertEquals(plain, rules.evaluate(DUELIST, List.of(battleground), List.of(), null));
        assertTrue(rules.evaluate(DUELIST, List.of(), List.of(battleground), new DomainAccessExemptions.Registry()).isEmpty());
    }

    @Test
    void anEntryExemptionPassesThatRegionsEntryDenyForThatPlayerOnly() {
        assertTrue(rules.evaluate(DUELIST, List.of(battleground), List.of(), duel()).isEmpty());
        assertEquals(RegionTransitionType.ENTER,
            rules.evaluate(OTHER, List.of(battleground), List.of(), duel()).orElseThrow().type());
        // Another closed region entered in the same move still refuses.
        Region closed = region("battleground_2", AccessState.DENY, AccessState.UNSET);
        assertEquals("battleground_2",
            rules.evaluate(DUELIST, List.of(battleground, closed), List.of(), duel()).orElseThrow().regionId());
    }

    @Test
    void aConfinementRefusesLeavingAnOpenRegion() {
        Optional<Refusal> refusal = rules.evaluate(DUELIST, List.of(), List.of(battleground), duel());

        assertEquals(RegionTransitionType.EXIT, refusal.orElseThrow().type());
        assertEquals("battleground_1", refusal.get().regionId());
        assertEquals("You can't leave the battleground during the duel.", refusal.get().message());
        assertTrue(rules.evaluate(OTHER, List.of(), List.of(battleground), duel()).isEmpty());
    }

    @Test
    void noPlayerAsksNoExemptions() {
        assertFalse(rules.evaluate(null, List.of(battleground), List.of(), duel()).isEmpty());
        assertTrue(rules.evaluate(null, List.of(), List.of(battleground), duel()).isEmpty());
    }

    @Test
    void theRegistryCombinesItsProviders() {
        DomainAccessExemptions.Registry registry = new DomainAccessExemptions.Registry();
        assertTrue(registry.isEmpty());
        assertFalse(registry.exemptFromEntry(DUELIST, "battleground_1"));
        assertTrue(registry.confinement(DUELIST, "battleground_1").isEmpty());

        DomainAccessExemptions provider = duel();
        registry.register(provider);
        registry.register(DomainAccessExemptions.NONE);
        assertTrue(registry.exemptFromEntry(DUELIST, "battleground_1"));
        assertFalse(registry.exemptFromEntry(DUELIST, null));
        assertEquals(Optional.of("You can't leave the battleground during the duel."), registry.confinement(DUELIST, "battleground_1"));

        registry.unregister(provider);
        assertFalse(registry.exemptFromEntry(DUELIST, "battleground_1"));
        assertTrue(registry.confinement(DUELIST, "battleground_1").isEmpty());
    }
}
