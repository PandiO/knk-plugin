package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static net.knightsandkings.knk.core.roads.route.NetworkFixture.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessPolicyTest {

    private final RoadNetworkSnapshot town = NetworkFixture.town();
    private final RoadEdge gateEdge = town.requireEdge(E_BE);
    private final RoadEdge plainEdge = town.requireEdge(E_AB);
    private final RoadEdge castleEdge = town.requireEdge(E_C_CASTLE);

    // ---- helpers -------------------------------------------------------------------------------

    static GateAvailability.GateView gate(AnimationState state, boolean jammed, boolean destroyed,
                                          boolean passThrough, boolean siegeLocked, boolean siegeCarries) {
        return new GateAvailability.GateView(GATE_DOOR, "West Gate", state, jammed, destroyed, passThrough,
            siegeLocked, siegeCarries);
    }

    static GateAvailability gates(GateAvailability.GateView view, boolean canPass) {
        return new GateAvailability(id -> id == GATE_DOOR ? Optional.of(view) : Optional.empty(), id -> canPass);
    }

    static DomainSnapshot domain(int id, String name, String region, Boolean allowEntry, Boolean allowExit) {
        return new DomainSnapshot(id, name, null, region, allowEntry, allowExit, "Town", Set.of(), Set.of(), Set.of(),
            Set.of());
    }

    static DomainAvailability.DomainLookup lookup(DomainSnapshot... domains) {
        Map<String, DomainSnapshot> m = new HashMap<>();
        for (DomainSnapshot d : domains) {
            m.put(d.wgRegionId(), d);
        }
        return region -> Optional.ofNullable(m.get(region));
    }

    // ---- static flags --------------------------------------------------------------------------

    @Test
    void staticFlagsBlockClosedAndNoGpsButNotOneway() {
        StaticFlagsAvailability flags = new StaticFlagsAvailability();
        assertTrue(flags.check(plainEdge).isOpen());
        assertTrue(flags.check(town.requireEdge(E_CASTLE20)).isOpen(), "oneway is the router's");
        RoadEdge closed = edge(90, A, B, line(0, 64, 0, 10, 64, 0)).flags(RoadEdgeFlag.CLOSED).build();
        EdgeVerdict v = flags.check(closed);
        assertTrue(v.isBlocked());
        assertEquals(EdgeVerdict.CauseType.FLAG, v.cause().type());
        assertEquals("Closed", v.cause().id());
        RoadEdge hidden = edge(91, A, B, line(0, 64, 0, 10, 64, 0)).flags(RoadEdgeFlag.NO_GPS).build();
        assertTrue(flags.check(hidden).isBlocked());
        assertEquals("NoGps", flags.check(hidden).cause().id());
    }

    // ---- gates ---------------------------------------------------------------------------------

    @Test
    void openOrDestroyedGateIsOpen() {
        assertTrue(gates(gate(AnimationState.OPEN, false, false, false, false, false), false).check(gateEdge).isOpen());
        assertTrue(gates(gate(AnimationState.CLOSED, true, true, false, false, false), false).check(gateEdge).isOpen(),
            "destroyed beats everything");
        assertTrue(gates(gate(AnimationState.CLOSED, false, false, false, false, false), false).check(plainEdge)
            .isOpen(), "an edge without doors");
    }

    @Test
    void closedGateIsBlockedWithTheGateNamed() {
        EdgeVerdict v = gates(gate(AnimationState.CLOSED, false, false, false, false, false), false).check(gateEdge);
        assertTrue(v.isBlocked());
        assertEquals("the West Gate is closed", v.message());
        assertEquals(EdgeVerdict.CauseType.GATE, v.cause().type());
        assertEquals(Integer.toString(GATE_DOOR), v.cause().id());
        assertEquals("the West Gate", v.cause().name());
    }

    @Test
    void closedPassThroughGateIsPassThroughOnlyForPlayersTheRuleAllows() {
        EdgeVerdict allowed = gates(gate(AnimationState.CLOSED, false, false, true, false, false), true)
            .check(gateEdge);
        assertTrue(allowed.isPassThrough());
        assertTrue(allowed.isUsable());
        assertEquals("right-click the West Gate to pass", allowed.message());
        EdgeVerdict denied = gates(gate(AnimationState.CLOSED, false, false, true, false, false), false)
            .check(gateEdge);
        assertTrue(denied.isBlocked());
    }

    @Test
    void aGateAdminPassesADoorWithoutPassThrough() {
        // live test 2026-10-08 (N11): knk.gate.admin bypasses AllowPassThrough at the door, so the route does too
        EdgeVerdict admin = gates(gate(AnimationState.CLOSED, false, false, false, false, false), true).check(gateEdge);
        assertTrue(admin.isPassThrough(), admin.toString());
        assertEquals("right-click the West Gate to pass", admin.message());
    }

    @Test
    void animatingOrJammedGateIsBlocked() {
        assertEquals("the West Gate is opening",
            gates(gate(AnimationState.OPENING, false, false, true, false, false), true).check(gateEdge).message());
        assertEquals("the West Gate is closing",
            gates(gate(AnimationState.CLOSING, false, false, true, false, false), true).check(gateEdge).message());
        assertEquals("the West Gate is jammed",
            gates(gate(AnimationState.CLOSED, true, false, true, false, false), true).check(gateEdge).message());
    }

    @Test
    void siegeLockedGateFollowsTheSiegeCarryRule() {
        EdgeVerdict carried = gates(gate(AnimationState.CLOSED, false, false, false, true, true), false)
            .check(gateEdge);
        assertTrue(carried.isPassThrough(), "R39/D2: the siege carries non-members through");
        EdgeVerdict stuck = gates(gate(AnimationState.CLOSED, false, false, true, true, false), true)
            .check(gateEdge);
        assertTrue(stuck.isBlocked(), "siege lock beats the ordinary pass-through rule");
        assertEquals("the West Gate is locked for a siege", stuck.message());
    }

    @Test
    void unknownGateIsOpenAndDoorsAreDecidedOncePerRequest() {
        AtomicInteger lookups = new AtomicInteger();
        GateAvailability policy = new GateAvailability(id -> {
            lookups.incrementAndGet();
            return Optional.empty();
        }, id -> false);
        assertTrue(policy.check(gateEdge).isOpen());
        policy.check(gateEdge);
        policy.check(gateEdge);
        assertEquals(1, lookups.get(), "cached per door id");
        assertEquals("the gate", new GateAvailability.GateView(1, null, AnimationState.CLOSED, false, false, false,
            false, false).displayName());
    }

    @Test
    void edgeWithSeveralDoorsGetsTheStrictestVerdict() {
        RoadEdge twoDoors = edge(92, A, B, line(0, 64, 0, 10, 64, 0)).gates(1, 2).build();
        Map<Integer, GateAvailability.GateView> views = Map.of(
            1, new GateAvailability.GateView(1, "North", AnimationState.CLOSED, false, false, true, false, false),
            2, new GateAvailability.GateView(2, "South", AnimationState.CLOSED, false, false, false, false, false));
        // the pass rule as on the server for a non-admin with the use node: only door 1 allows pass-through
        GateAvailability policy = new GateAvailability(id -> Optional.ofNullable(views.get(id)), id -> id == 1);
        EdgeVerdict v = policy.check(twoDoors);
        assertTrue(v.isBlocked());
        assertEquals("the South is closed", v.message());
        Map<Integer, GateAvailability.GateView> both = Map.of(
            1, views.get(1),
            2, new GateAvailability.GateView(2, "South", AnimationState.OPEN, false, false, false, false, false));
        assertTrue(new GateAvailability(id -> Optional.ofNullable(both.get(id)), id -> id == 1).check(twoDoors)
            .isPassThrough());
    }

    // ---- domains -------------------------------------------------------------------------------

    @Test
    void entryDeniedDomainBlocksEdgesEnteringItsRegion() {
        DomainAvailability policy = new DomainAvailability(new DomainAccessEvaluator(),
            lookup(domain(CASTLE_DOMAIN, "Kardenna Castle", CASTLE_REGION, false, null)), Set.of(), false);
        EdgeVerdict v = policy.check(castleEdge);
        assertTrue(v.isBlocked());
        assertEquals("you may not enter Kardenna Castle", v.message());
        assertEquals(EdgeVerdict.CauseType.DOMAIN, v.cause().type());
        assertEquals("42", v.cause().id());
        assertTrue(policy.check(plainEdge).isOpen());
        assertTrue(policy.check(town.requireEdge(E_CASTLE20)).isBlocked(), "the other castle edge too");
    }

    @Test
    void alreadyInsideTheDeniedDomainIsNotAnEntry() {
        DomainAvailability policy = new DomainAvailability(new DomainAccessEvaluator(),
            lookup(domain(CASTLE_DOMAIN, "Kardenna Castle", CASTLE_REGION, false, null)), Set.of(CASTLE_REGION), false);
        assertTrue(policy.check(castleEdge).isOpen());
        assertTrue(policy.check(plainEdge).isOpen());
    }

    @Test
    void exitDeniedDomainBlocksEdgesThatLeaveIt() {
        DomainAvailability policy = new DomainAvailability(new DomainAccessEvaluator(),
            lookup(domain(CASTLE_DOMAIN, "Kardenna Castle", CASTLE_REGION, null, false)), Set.of(CASTLE_REGION), false);
        assertEquals(List.of(CASTLE_REGION), policy.exitDeniedRegions());
        assertTrue(policy.check(castleEdge).isOpen(), "stays inside the castle");
        EdgeVerdict v = policy.check(plainEdge);
        assertTrue(v.isBlocked());
        assertEquals("you may not leave Kardenna Castle", v.message());
        // not inside → nothing to leave
        DomainAvailability outside = new DomainAvailability(new DomainAccessEvaluator(),
            lookup(domain(CASTLE_DOMAIN, "Kardenna Castle", CASTLE_REGION, null, false)), Set.of(), false);
        assertTrue(outside.check(plainEdge).isOpen());
        assertTrue(outside.check(castleEdge).isOpen(), "entering an exit-only domain is allowed");
    }

    @Test
    void bypassOpensEverythingAndUnknownRegionsAreOpen() {
        DomainAvailability bypass = new DomainAvailability(new DomainAccessEvaluator(),
            lookup(domain(CASTLE_DOMAIN, "Kardenna Castle", CASTLE_REGION, false, false)), Set.of(CASTLE_REGION), true);
        assertTrue(bypass.check(castleEdge).isOpen());
        assertTrue(bypass.check(plainEdge).isOpen());
        assertTrue(bypass.exitDeniedRegions().isEmpty());
        DomainAvailability unknown = new DomainAvailability(new DomainAccessEvaluator(), lookup(), Set.of("elsewhere"),
            false);
        assertTrue(unknown.check(castleEdge).isOpen());
    }

    @Test
    void domainLookupsAreCachedPerRegion() {
        AtomicInteger lookups = new AtomicInteger();
        DomainAvailability policy = new DomainAvailability(new DomainAccessEvaluator(), region -> {
            lookups.incrementAndGet();
            return Optional.of(domain(CASTLE_DOMAIN, "Kardenna Castle", region, false, null));
        }, Set.of(), false);
        policy.check(castleEdge);
        policy.check(town.requireEdge(E_CASTLE20));
        assertEquals(1, lookups.get());
    }

    // ---- composite -----------------------------------------------------------------------------

    @Test
    void compositeTakesFirstBlockedThenFirstPassThroughAndCachesPerEdge() {
        AtomicInteger calls = new AtomicInteger();
        AccessPolicy counting = e -> {
            calls.incrementAndGet();
            return EdgeVerdict.open();
        };
        AccessPolicy hint = e -> EdgeVerdict.passThrough("hint", EdgeVerdict.Cause.gate(1, "g"));
        AccessPolicy block = e -> EdgeVerdict.blocked("no", EdgeVerdict.Cause.flag("Closed", "closed"));
        CompositeAccessPolicy c = CompositeAccessPolicy.of(counting, hint, block, counting);
        EdgeVerdict v = c.check(plainEdge);
        assertTrue(v.isBlocked());
        assertEquals("no", v.message());
        assertEquals(1, calls.get(), "stops at the first block");
        c.check(plainEdge);
        assertEquals(1, calls.get(), "cached per edge");
        assertTrue(CompositeAccessPolicy.of(counting, hint, counting).check(gateEdge).isPassThrough());
        assertTrue(CompositeAccessPolicy.of().check(gateEdge).isOpen());
        assertTrue(AccessPolicy.ALL_OPEN.check(castleEdge).isOpen());
    }

    @Test
    void verdictInvariants() {
        assertThrows(IllegalArgumentException.class, () -> EdgeVerdict.blocked("x", null));
        assertEquals("", EdgeVerdict.open().message());
        EdgeVerdict a = EdgeVerdict.passThrough("a", EdgeVerdict.Cause.gate(1, "a"));
        EdgeVerdict b = EdgeVerdict.passThrough("b", EdgeVerdict.Cause.gate(2, "b"));
        assertEquals(a, EdgeVerdict.stricter(a, b), "ties keep the first");
        assertEquals(b, EdgeVerdict.stricter(EdgeVerdict.open(), b));
        assertFalse(a.isBlocked());
    }
}
