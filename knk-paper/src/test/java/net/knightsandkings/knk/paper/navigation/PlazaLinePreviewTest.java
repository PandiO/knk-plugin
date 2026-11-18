package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.ArgumentCaptor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.navigation.shape.NavWorld;
import net.knightsandkings.knk.core.navigation.shape.PlazaCurve;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.paper.roads.RoadNetworkCache;

/**
 * {@code /knk road plazaline} (off-road navigation phase 7 step 1, PandiO/knk-workspace#712): the lines of a named
 * plaza or of the player's route are written to chat and drawn for ten seconds, and nothing else is touched. On the
 * NPC scene's plaza (15 × 15 inside walls at 7 and 23, node at block 15 64 15, roads west, north and east).
 */
class PlazaLinePreviewTest {

    private static final String W = "world";

    private ServerMock server;
    private Plugin plugin;
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final RoadNetworkCache cache = mock(RoadNetworkCache.class);
    private final NavigationService navigation = mock(NavigationService.class);
    private PlazaLinePreview preview;

    /** The walled plaza with its road openings and roads, on an open floor at 64. */
    private static final class PlazaWorld implements NavWorld {
        final Set<Long> walls = new HashSet<>();

        PlazaWorld() {
            walls(7, 7, 23, 7);
            walls(7, 23, 23, 23);
            walls(7, 7, 7, 23);
            walls(23, 7, 23, 23);
            for (int a = 14; a <= 16; a++) {
                walls.remove(key(a, 7));
                walls.remove(key(7, a));
                walls.remove(key(23, a));
            }
            walls(13, -26, 13, 6);
            walls(17, -26, 17, 6);
            walls(-26, 13, 6, 13);
            walls(-26, 17, 6, 17);
            walls(24, 13, 56, 13);
            walls(24, 17, 56, 17);
        }

        private void walls(int x1, int z1, int x2, int z2) {
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    walls.add(key(x, z));
                }
            }
        }

        private static long key(int x, int z) {
            return ((long) x << 32) | (z & 0xffffffffL);
        }

        @Override
        public boolean isLoaded(String w, int x, int z) {
            return true;
        }

        @Override
        public boolean isStandableFloor(String w, int x, int floorY, int z) {
            return floorY == 64 && !walls.contains(key(x, z));
        }

        @Override
        public Set<String> regionsAt(String w, int x, int feetY, int z) {
            return Set.of();
        }
    }

    private static RoadNetworkSnapshot snapshot() {
        return RoadNetworkSnapshot.builder(W)
            .addNode(new RoadNode(1, 15, 64, 15, RoadNodeKind.JUNCTION, "Brink", 0))
            .addNode(new RoadNode(2, -25, 64, 15, RoadNodeKind.ENDPOINT, null, 0))
            .addNode(new RoadNode(3, 15, 64, -25, RoadNodeKind.ENDPOINT, null, 0))
            .addNode(new RoadNode(4, 55, 64, 15, RoadNodeKind.ENDPOINT, null, 0))
            .addEdge(edge(10, 2, 1, straight(-25, 15, 15, 15)))
            .addEdge(edge(11, 1, 3, straight(15, 15, 15, -25)))
            .addEdge(edge(12, 4, 1, straight(55, 15, 15, 15)))
            .build();
    }

    private static List<int[]> straight(int x1, int z1, int x2, int z2) {
        List<int[]> out = new ArrayList<>();
        int n = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        for (int i = 0; i <= n; i++) {
            out.add(new int[] {x1 + Integer.signum(x2 - x1) * i, 64, z1 + Integer.signum(z2 - z1) * i});
        }
        return out;
    }

    private static RoadEdge edge(int id, int from, int to, List<int[]> geometry) {
        return new RoadEdge(id, from, to, geometry, geometry.size() - 1, 3.0, OptionalInt.empty(), OptionalInt.empty(),
            1.0, Set.of(), List.of(), List.of(), List.of(), RoadEdgeSource.DETECTED, false);
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("KnightsAndKings");
        when(world.getName()).thenReturn(W);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 15.5, 65, 20.5));
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(cache.snapshot(W)).thenReturn(snapshot());
        PlazaWorld plaza = new PlazaWorld();
        preview = new PlazaLinePreview(plugin, () -> cache, () -> navigation, () -> plaza,
            w -> PlazaCurve.Options.defaults(4, 0.8));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private List<String> messages() {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(player, atLeast(1)).sendMessage(captor.capture());
        return captor.getAllValues().stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c)).toList();
    }

    private void verifyParticles() {
        verify(player, atLeast(20)).spawnParticle(eq(Particle.DUST), anyDouble(), anyDouble(), anyDouble(), anyInt(),
            anyDouble(), anyDouble(), anyDouble(), anyDouble(), any());
    }

    private void verifyNoParticles() {
        verify(player, never()).spawnParticle(eq(Particle.DUST), anyDouble(), anyDouble(), anyDouble(), anyInt(),
            anyDouble(), anyDouble(), anyDouble(), anyDouble(), any());
    }

    @Test
    void aNamedPlazaShowsEveryPairOfItsRoadsInChatAndAsParticlesForTenSeconds() {
        preview.run(player, new String[] {"brink"});

        List<String> said = messages();
        assertEquals(4, said.size(), "a header and one line per pair of roads: " + said);
        assertTrue(said.get(0).contains("plaza-pull 0.35, curve clearance 0.65, A* clearance 0.80"),
            said.get(0));
        assertTrue(said.get(1).contains("Brink (#1): edge #10 → edge #11: curve "), said.get(1));
        assertTrue(said.get(1).contains("right angle"), said.get(1));
        assertTrue(said.get(2).contains("edge #10 → edge #12: curve"), said.get(2));
        assertTrue(said.get(3).contains("edge #11 → edge #12: curve"), said.get(3));
        assertEquals(1, preview.showing());

        server.getScheduler().performTicks(1);
        verifyParticles();

        server.getScheduler().performTicks(PlazaLinePreview.SHOW_SECONDS * 20L + 20);
        assertEquals(0, preview.showing(), "gone after ten seconds");
        clearInvocations(player);
        server.getScheduler().performTicks(40);
        verifyNoParticles();
        verifyNoMoreInteractions(navigation);
    }

    @Test
    void aSecondPreviewReplacesTheFirst() {
        preview.run(player, new String[] {"Brink"});
        preview.run(player, new String[] {"1"});
        assertEquals(1, preview.showing());
    }

    @Test
    void theRoutesPlazasArePreviewedWithoutTouchingTheSession() {
        List<double[]> polyline = new ArrayList<>();
        straight(-25, 15, 15, 15).forEach(p -> polyline.add(new double[] {p[0], p[1], p[2]}));
        straight(15, 14, 15, -25).forEach(p -> polyline.add(new double[] {p[0], p[1], p[2]}));
        RoadNode brink = snapshot().requireNode(1);
        when(navigation.routePlazas(player)).thenReturn(Optional.of(
            new NavigationService.RoutePlazas(W, polyline, List.of(brink))));

        preview.run(player, new String[0]);

        List<String> said = messages();
        assertEquals(2, said.size(), said.toString());
        assertTrue(said.get(1).contains("Brink (#1) on your route: curve"), said.get(1));
        verify(navigation).routePlazas(player);
        verifyNoMoreInteractions(navigation);
        server.getScheduler().performTicks(1);
        verifyParticles();
    }

    @Test
    void withoutARouteItSaysWhatToDo() {
        when(navigation.routePlazas(player)).thenReturn(Optional.empty());

        preview.run(player, new String[0]);

        assertTrue(messages().get(0).contains("You have no route"), messages().toString());
        assertEquals(0, preview.showing());
    }

    @Test
    void anUnknownNodeOrADeadEndIsSaid() {
        preview.run(player, new String[] {"\"Cinix", "Square\""});
        assertTrue(messages().get(0).contains("No node with the id or name \"Cinix Square\""), messages().toString());

        clearInvocations(player);
        preview.run(player, new String[] {"2"});
        assertTrue(messages().get(0).contains("fewer than two roads"), messages().toString());
        assertEquals(0, preview.showing());
    }
}
