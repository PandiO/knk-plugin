package net.knightsandkings.knk.paper.npc.citizens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.NavPoint;

/** PandiO/knk-workspace#361: the rest of the engine's path, for the recheck of a local leg. */
class CitizensNpcMoverPathTest {

    @Test
    void thePathStartsAtThePointNearestTheNpc() {
        List<Vector> path = List.of(new Vector(0, 64, 0), new Vector(1, 64, 0), new Vector(2, 65, 0), new Vector(3, 65, 0));

        List<NavPoint> ahead = CitizensNpcMover.aheadOf("world", new Vector(2.2, 65, 0.4), path);

        assertEquals(List.of(new NavPoint("world", 2.5, 65, 0.5), new NavPoint("world", 3.5, 65, 0.5)), ahead,
            "what lies behind the NPC is left out; feet positions at block centres");
    }

    @Test
    void anEmptyPathHasNothingAhead() {
        assertTrue(CitizensNpcMover.aheadOf("world", new Vector(0, 64, 0), List.of()).isEmpty());
    }

    /** PandiO/knk-workspace#377: a group's follower catching up walks faster through Citizens' speed modifier. */
    @Test
    void theSpeedFactorGoesToTheNextOrdersAndTheRunningOne() {
        net.citizensnpcs.api.npc.NPC npc = org.mockito.Mockito.mock(net.citizensnpcs.api.npc.NPC.class);
        net.citizensnpcs.api.ai.Navigator navigator = org.mockito.Mockito.mock(net.citizensnpcs.api.ai.Navigator.class);
        net.citizensnpcs.api.ai.NavigatorParameters defaults =
            org.mockito.Mockito.mock(net.citizensnpcs.api.ai.NavigatorParameters.class);
        net.citizensnpcs.api.ai.NavigatorParameters local =
            org.mockito.Mockito.mock(net.citizensnpcs.api.ai.NavigatorParameters.class);
        org.mockito.Mockito.when(npc.getNavigator()).thenReturn(navigator);
        org.mockito.Mockito.when(navigator.getDefaultParameters()).thenReturn(defaults);
        org.mockito.Mockito.when(navigator.getLocalParameters()).thenReturn(local);
        org.mockito.Mockito.when(npc.isSpawned()).thenReturn(true);
        CitizensNpcMover mover = new CitizensNpcMover(npc, removed -> { });

        mover.setSpeedFactor(1.3); // standing still: only the orders to come
        org.mockito.Mockito.verify(defaults).speedModifier(1.3f);
        org.mockito.Mockito.verifyNoInteractions(local);

        org.mockito.Mockito.when(navigator.isNavigating()).thenReturn(true);
        mover.setSpeedFactor(1);
        org.mockito.Mockito.verify(defaults).speedModifier(1f);
        org.mockito.Mockito.verify(local).speedModifier(1f);

        mover.setSpeedFactor(50); // never absurd
        org.mockito.Mockito.verify(defaults).speedModifier(3f);
    }
}
