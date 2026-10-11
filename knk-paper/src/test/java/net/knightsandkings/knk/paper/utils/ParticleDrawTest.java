package net.knightsandkings.knk.paper.utils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * R9: per-viewer drawing with the 64-block range and same-world filters SiegeWorldPresenter.ring had.
 * Written for the developer's local build (paper-api is not resolvable in the cloud).
 */
class ParticleDrawTest {
    private World world;
    private World otherWorld;
    private Player near;
    private Player far;
    private Player elsewhere;

    @BeforeEach
    void setUp() {
        world = mock(World.class);
        otherWorld = mock(World.class);
        near = viewer(world, 10, 64, 10);
        far = viewer(world, 200, 64, 10);
        elsewhere = viewer(otherWorld, 10, 64, 10);
    }

    private static Player viewer(World world, double x, double y, double z) {
        Player player = mock(Player.class);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, x, y, z));
        return player;
    }

    @Test
    void ringReachesOnlyViewersInRangeAndWorld() {
        ParticleDraw.ring(List.of(near, far, elsewhere), new Location(world, 12, 64, 12), 3, Particle.FLAME);

        verify(near, times(24)).spawnParticle(eq(Particle.FLAME), anyDouble(), anyDouble(), anyDouble(), eq(1), eq(0d), eq(0d), eq(0d), eq(0d));
        verify(far, never()).spawnParticle(any(Particle.class), anyDouble(), anyDouble(), anyDouble(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
        verify(elsewhere, never()).spawnParticle(any(Particle.class), anyDouble(), anyDouble(), anyDouble(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void aZeroRadiusRingDrawsNothing() {
        ParticleDraw.ring(List.of(near), new Location(world, 12, 64, 12), 0, Particle.FLAME);
        verify(near, never()).spawnParticle(any(Particle.class), anyDouble(), anyDouble(), anyDouble(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void polylineSpacesParticlesAlongTheSegmentsForOneViewer() {
        List<Vector> points = List.of(new Vector(10, 65, 10), new Vector(20, 65, 10), new Vector(20, 65, 20));
        ParticleDraw.polyline(near, points, 2.0, Particle.DUST, null);

        // 20 blocks of line at 2-block spacing: the start, 10 in-between points and the end ≈ 11-12 spawns
        verify(near, atLeast(10)).spawnParticle(eq(Particle.DUST), anyDouble(), anyDouble(), anyDouble(), eq(1), eq(0d), eq(0d), eq(0d), eq(0d));
        ParticleDraw.polyline(far, points, 2.0, Particle.DUST, null);
        verify(far, never()).spawnParticle(any(Particle.class), anyDouble(), anyDouble(), anyDouble(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void pillarStacksParticlesUpwards() {
        ParticleDraw.pillar(near, new Vector(10, 64, 10), 2.0, 0.5, Particle.FLAME, null);
        verify(near, times(5)).spawnParticle(eq(Particle.FLAME), eq(10d), anyDouble(), eq(10d), eq(1), eq(0d), eq(0d), eq(0d), eq(0d));
    }
}
