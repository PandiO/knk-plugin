package net.knightsandkings.knk.paper.worlds;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bukkit.World;
import org.bukkit.event.world.WorldUnloadEvent;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.worlds.WorldReadiness;
import net.knightsandkings.knk.core.worlds.WorldSpec;

/** KNG-114: a configured world is never unloaded, and a missing required world keeps players (not operators) out. */
class KnkWorldsListenerTest {

    private final World hub = mock(World.class);
    private final World arena = mock(World.class);

    private static WorldReadiness readiness(WorldReadiness.State hubState) {
        return new WorldReadiness(List.of(new WorldReadiness.Status(
                new WorldSpec("hub", WorldSpec.Environment.NORMAL, WorldSpec.Generator.VOID, true, true), hubState, null)));
    }

    @Test
    void anUnloadOfAConfiguredWorldIsCancelled() {
        when(hub.getName()).thenReturn("hub");
        when(arena.getName()).thenReturn("arena");
        KnkWorldsListener listener = new KnkWorldsListener(() -> readiness(WorldReadiness.State.CREATED), uuid -> false);

        WorldUnloadEvent hubUnload = new WorldUnloadEvent(hub);
        WorldUnloadEvent arenaUnload = new WorldUnloadEvent(arena);
        listener.onWorldUnload(hubUnload);
        listener.onWorldUnload(arenaUnload);

        assertTrue(hubUnload.isCancelled());
        assertFalse(arenaUnload.isCancelled(), "a world KnK does not load is not its business");
    }

    @Test
    void onlyOperatorsMayJoinWhileARequiredWorldIsMissing() {
        WorldReadiness missing = readiness(WorldReadiness.State.MISSING);

        String refusal = KnkWorldsListener.refusal(missing, false);
        assertTrue(refusal != null && refusal.contains("world hub is not available"), refusal);
        assertNull(KnkWorldsListener.refusal(missing, true));
        assertNull(KnkWorldsListener.refusal(readiness(WorldReadiness.State.LOADED), false));
        assertNull(KnkWorldsListener.refusal(WorldReadiness.none(), false));
    }
}
