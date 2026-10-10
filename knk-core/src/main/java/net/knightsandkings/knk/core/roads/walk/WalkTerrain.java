package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.build.SurfaceGrid;

import java.util.Objects;

/**
 * The captured world a walk search reads (KNG-51 {@code LAST_MILE_PATHFINDING.md} §3, §8): the
 * builder's {@link SurfaceGrid} port (passable/solid/hazard, floor material, stair/slab), the gate
 * footprints ({@link GateCells}, counted as passable headroom like the builder does) and the
 * walk-only flags ({@link WalkCells}). knk-paper backs all three with captured chunks (Phase B);
 * tests use ASCII fixtures. Everything must be safe to read off the main thread.
 */
public record WalkTerrain(SurfaceGrid surface, GateCells gates, WalkCells cells) {

    public WalkTerrain {
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(gates, "gates");
        Objects.requireNonNull(cells, "cells");
    }
}
