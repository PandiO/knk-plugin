package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.roads.build.PassabilityRules;
import net.knightsandkings.knk.core.roads.build.SurfaceGrid;
import net.knightsandkings.knk.core.util.BlockKey;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.PriorityQueue;

import static net.knightsandkings.knk.core.roads.walk.WalkGrid.DX;
import static net.knightsandkings.knk.core.roads.walk.WalkGrid.DZ;
import static net.knightsandkings.knk.core.roads.walk.WalkGrid.NO_LINK;

/**
 * Bounded A* over walk cells (KNG-51 {@code LAST_MILE_PATHFINDING.md} §4, §5) — the
 * {@link WalkPathfinder} navigation uses. Pure and thread-safe: the search holds no state, every
 * {@link #find} allocates its own maps; it reads only the request's captured {@link WalkTerrain}.
 *
 * <p><b>Cells.</b> A <em>standing</em> cell is a {@link WalkGrid} cell over any walk floor
 * ({@link PassabilityRules#isWalkFloor}: never a fence, wall, pane, iron bars, iron door or a door
 * block itself; never a climbable), with the profile's headroom, where door and climbable blocks count
 * as passable. A standing cell whose feet block is water is a <em>wading</em> cell (cost ×
 * {@code waterFactor}); one with water above the feet would be swimming and is not walkable (§11-3).
 * A <em>ladder</em> cell is a climbable block with a passable block above it; its key is the feet
 * block, so it never collides with a standing cell's floor key.
 *
 * <p><b>Links</b> (§4): the {@link WalkGrid} links (level, ±1 with the jump/stair rule, diagonals
 * with the corner rule) — a step up onto a full block costs {@code +jumpCost}; a diagonal must have
 * an accessible, door-free flanking cell on its corner path and neither end may be a door cell (doors
 * are passed straight on); <b>drops</b> of 2..{@code maxDrop} blocks, orthogonal and directed, when
 * the landing column is clear from the mover's head height down, cost {@code k + dropPenalty × k};
 * <b>ladders</b>: standing ↔ an orthogonally adjacent ladder cell at feet level or one below
 * (getting on from the top) and the ladder cell at the feet of a standing cell's own column; ladder ↔
 * ladder one block up/down ({@code climbCost}); ladder → standing cells as the reverse of those.
 *
 * <p><b>Cost</b> of entering a cell = move cost (× water factor) + door cost (door cells; blocked if
 * the profile opens no doors) + wall cost (a standing cell with a wall among its 8 neighbours, so
 * paths round corners a block wide where there is room) + the {@link CellAccess} verdict
 * ({@code +∞} = never entered). The start cell is exempt from access (the mover is already there).
 *
 * <p><b>Search.</b> Start = the nearest cell within {@code startSnap} of the feet; a target with no
 * cell within {@code goalSnap} → NO_PATH. Heuristic = {@code max(horizontal distance, |Δfloor y|)} to
 * the target — admissible and consistent with the costs above (every move costs at least that metric)
 * apart from the goal predicate's own slack. Ties on {@code f} prefer the larger {@code g}, then
 * insertion order (deterministic, as {@code AStarRouter}). Expansions over {@code maxExpansions}, or a
 * search that only failed because paths hit the length cap → FALLBACK; a reachable area exhausted
 * without arriving → NO_PATH. Either may carry a {@link WalkResult#partialPath() partial path} (§11-5,
 * revised 2026-10-07): to the expanded cell closest to the target (3D, ties to the cheaper), when it
 * is at least {@link #MIN_PARTIAL_GAIN} blocks closer than the start cell.
 */
public final class WalkSearch implements WalkPathfinder {

    /** A partial path must end at least this many blocks closer to the target than the start cell. */
    public static final double MIN_PARTIAL_GAIN = 2.0;

    private static final double SQRT2 = Math.sqrt(2.0);
    private static final double EPS = 1e-9;

    @Override
    public WalkResult find(WalkRequest request) {
        return new Run(request).run();
    }

    /**
     * The {@link WalkGrid} the search uses for a terrain and profile: walk floors only (no fences,
     * walls, panes, doors or climbables), door and climbable blocks passable.
     */
    public static WalkGrid gridFor(WalkTerrain terrain, MovementProfile profile) {
        return gridFor(terrain.surface(), terrain.cells(), terrain, profile);
    }

    private static WalkGrid gridFor(SurfaceGrid surface, WalkCells cells, WalkTerrain terrain, MovementProfile profile) {
        return new WalkGrid(surface, terrain.gates(), cells, profile.headroom(),
            (grid, x, y, z) -> !cells.isClimbable(x, y, z) && !cells.isDoor(x, y, z)
                && PassabilityRules.isWalkFloor(grid.floorMaterial(x, y, z)));
    }

    private static final class Node {
        final long key;
        final boolean ladder;
        double g;
        double len;
        Node parent;
        boolean closed;

        Node(long key, boolean ladder) {
            this.key = key;
            this.ladder = ladder;
        }
    }

    private record Entry(double f, double g, long seq, Node node) {
    }

    private static final class Run {
        private final WalkRequest request;
        private final SurfaceGrid surface;
        private final WalkCells cells;
        private final MovementProfile profile;
        private final CellAccess access;
        private final WalkGrid grid;
        private final LongMap<Node> nodes = new LongMap<>(1024);
        private final PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> {
            int c = Double.compare(a.f, b.f);
            if (c != 0) {
                return c;
            }
            c = Double.compare(b.g, a.g);
            return c != 0 ? c : Long.compare(a.seq, b.seq);
        });
        private long seq;
        private int expansions;
        private boolean lengthCapped;
        private double cap;
        private Node closest;
        private double closestDistance = Double.POSITIVE_INFINITY;
        private double startDistance;

        Run(WalkRequest request) {
            this.request = request;
            MemoSurface memo = new MemoSurface(request.terrain().surface(), request.terrain().cells());
            this.surface = memo;
            this.cells = memo.cells();
            this.profile = request.profile();
            this.access = request.access();
            this.grid = gridFor(surface, cells, request.terrain(), profile);
        }

        WalkResult run() {
            WalkBudget budget = request.budget();
            Node start = nearest(request.startX(), request.startY() - 1, request.startZ(), budget.startSnap());
            if (start == null) {
                return WalkResult.noPath("no walkable cell near the start", 0);
            }
            if (nearest(request.targetX(), request.targetFloorY(), request.targetZ(), budget.goalSnap()) == null) {
                return WalkResult.noPath("no walkable cell near the target", 0);
            }
            cap = budget.lengthCap(request.straightDistance());
            startDistance = targetDistance(start);
            nodes.put(start.key, start);
            push(start);

            while (!open.isEmpty()) {
                Entry entry = open.poll();
                Node u = entry.node;
                if (u.closed || entry.g > u.g) {
                    continue;
                }
                u.closed = true;
                if (request.goal().reached(BlockKey.x(u.key) + 0.5, floorY(u), BlockKey.z(u.key) + 0.5)) {
                    return WalkResult.found(path(u), expansions);
                }
                noteClosest(u);
                if (expansions >= budget.maxExpansions()) {
                    return WalkResult.fallback("expansion budget", expansions, partial());
                }
                expansions++;
                if (u.ladder) {
                    expandLadder(u);
                } else {
                    expandStanding(u);
                }
            }
            return lengthCapped
                ? WalkResult.fallback("length cap", expansions, partial())
                : WalkResult.noPath("target unreachable", expansions, partial());
        }

        // ===== partial path (§11-5) =====

        private double targetDistance(Node n) {
            double dx = BlockKey.x(n.key) + 0.5 - request.targetX();
            double dy = floorY(n) - request.targetFloorY();
            double dz = BlockKey.z(n.key) + 0.5 - request.targetZ();
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        private void noteClosest(Node u) {
            double d = targetDistance(u);
            if (d < closestDistance - EPS || Math.abs(d - closestDistance) <= EPS && closest != null && u.g < closest.g) {
                closest = u;
                closestDistance = d;
            }
        }

        /** The way to the closest expanded cell, or null when it is not clearly closer than the start. */
        private WalkPath partial() {
            if (closest == null || closest.parent == null || closestDistance > startDistance - MIN_PARTIAL_GAIN) {
                return null;
            }
            return path(closest);
        }

        // ===== cells =====

        private boolean isStanding(int x, int y, int z) {
            return grid.isCell(x, y, z) && wadeOk(x, y, z);
        }

        private boolean wadeOk(int x, int y, int z) {
            if (cells.isWater(x, y + 1, z) && !profile.wades()) {
                return false;
            }
            for (int h = 2; h <= profile.headroom(); h++) {
                if (cells.isWater(x, y + h, z)) {
                    return false; // head under water: swimming, out of scope (§11-3)
                }
            }
            return true;
        }

        private boolean isLadder(int x, int y, int z) {
            return y >= surface.minY() && y + 1 < surface.maxY()
                && cells.isClimbable(x, y, z) && !surface.isHazard(x, y, z)
                && grid.isPassable(x, y + 1, z) && !surface.isHazard(x, y + 1, z);
        }

        private boolean isDoorCell(int x, int y, int z) {
            for (int h = 1; h <= profile.headroom(); h++) {
                if (cells.isDoor(x, y + h, z)) {
                    return true;
                }
            }
            return false;
        }

        /** Room to fall or reach through: passable and not a hazard. */
        private boolean clear(int x, int y, int z) {
            return y >= surface.minY() && (y >= surface.maxY() || grid.isPassable(x, y, z) && !surface.isHazard(x, y, z));
        }

        private int floorY(Node n) {
            int y = BlockKey.y(n.key);
            return n.ladder ? y - 1 : y;
        }

        /**
         * The cell nearest to a floor position within {@code radius} (distance from the point to the
         * cell's block column square, combined with the floor-y difference); null when there is none.
         */
        private Node nearest(double px, double pFloorY, double pz, int radius) {
            int bx = (int) Math.floor(px);
            int by = (int) Math.floor(pFloorY);
            int bz = (int) Math.floor(pz);
            Node best = null;
            double bestDist = Double.POSITIVE_INFINITY;
            for (int dy = -radius; dy <= radius + 1; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int x = bx + dx;
                        int y = by + dy;
                        int z = bz + dz;
                        double hx = Math.max(0, Math.max(x - px, px - (x + 1)));
                        double hz = Math.max(0, Math.max(z - pz, pz - (z + 1)));
                        if (isStanding(x, y, z)) {
                            double d = Math.sqrt(hx * hx + hz * hz + (y - pFloorY) * (y - pFloorY));
                            if (d <= radius + EPS && d < bestDist) {
                                bestDist = d;
                                best = new Node(BlockKey.pack(x, y, z), false);
                            }
                        }
                        if (isLadder(x, y, z)) {
                            double fy = y - 1;
                            double d = Math.sqrt(hx * hx + hz * hz + (fy - pFloorY) * (fy - pFloorY));
                            if (d <= radius + EPS && d < bestDist) {
                                bestDist = d;
                                best = new Node(BlockKey.pack(x, y, z), true);
                            }
                        }
                    }
                }
            }
            return best;
        }

        // ===== expansion =====

        private void expandStanding(Node u) {
            int x = BlockKey.x(u.key);
            int y = BlockKey.y(u.key);
            int z = BlockKey.z(u.key);
            for (int dir = 0; dir < WalkGrid.DIRECTIONS; dir++) {
                boolean diagonal = WalkGrid.isDiagonal(dir);
                int nx = x + DX[dir];
                int nz = z + DZ[dir];
                int dy = grid.neighbourDy(u.key, dir);
                if (dy != NO_LINK) {
                    int ny = y + dy;
                    if (!wadeOk(nx, ny, nz)) {
                        continue;
                    }
                    if (diagonal && !diagonalOk(u.key, dir, x, y, z, BlockKey.pack(nx, ny, nz))) {
                        continue;
                    }
                    double horizontal = diagonal ? SQRT2 : 1.0;
                    double move = horizontal + (dy == 1 && !surface.isStairOrSlab(nx, ny, nz) ? profile.jumpCost() : 0.0);
                    relaxStanding(u, nx, ny, nz, move, horizontal + Math.abs(dy));
                } else if (!diagonal) {
                    tryDrop(u, x, y, z, nx, nz);
                }
                if (!diagonal) {
                    // onto a ladder beside: feet level (floor-eq y) or from the top (feet one lower)
                    if (isLadder(nx, y + 1, nz)) {
                        relaxLadder(u, nx, y + 1, nz, 1.0, 1.0);
                    }
                    if (isLadder(nx, y, nz)) {
                        relaxLadder(u, nx, y, nz, 1.0, 2.0);
                    }
                }
            }
            if (isLadder(x, y + 1, z)) {
                relaxLadder(u, x, y + 1, z, 1.0, 0.0); // the ladder at the feet of this column
            }
        }

        private void expandLadder(Node u) {
            int x = BlockKey.x(u.key);
            int y = BlockKey.y(u.key);
            int z = BlockKey.z(u.key);
            if (isLadder(x, y + 1, z)) {
                relaxLadder(u, x, y + 1, z, profile.climbCost(), 1.0);
            }
            if (isLadder(x, y - 1, z)) {
                relaxLadder(u, x, y - 1, z, profile.climbCost(), 1.0);
            }
            if (isStanding(x, y - 1, z)) {
                relaxStanding(u, x, y - 1, z, 1.0, 0.0); // the floor at the ladder's foot
            }
            for (int dir = 0; dir < WalkGrid.DIRECTIONS; dir += 2) {
                int nx = x + DX[dir];
                int nz = z + DZ[dir];
                if (isStanding(nx, y - 1, nz)) {
                    relaxStanding(u, nx, y - 1, nz, 1.0, 1.0); // a ledge beside, feet level
                }
                if (isStanding(nx, y, nz)) {
                    relaxStanding(u, nx, y, nz, 1.0, 2.0);     // stepping off the top
                }
            }
        }

        /**
         * A diagonal needs an L through a flanking cell that is walkable, accessible and not a door
         * cell (the grid's corner rule is geometric only), and neither end may be a door cell.
         */
        private boolean diagonalOk(long from, int diagonal, int x, int y, int z, long target) {
            if (isDoorCell(x, y, z)
                || isDoorCell(BlockKey.x(target), BlockKey.y(target), BlockKey.z(target))) {
                return false;
            }
            int first = (diagonal + 7) & 7;
            int second = (diagonal + 1) & 7;
            return viaFlank(from, first, second, target) || viaFlank(from, second, first, target);
        }

        private boolean viaFlank(long from, int firstDir, int secondDir, long target) {
            int dy1 = grid.neighbourDy(from, firstDir);
            if (dy1 == NO_LINK) {
                return false;
            }
            long corner = BlockKey.neighbour(from, DX[firstDir], dy1, DZ[firstDir]);
            int cx = BlockKey.x(corner);
            int cy = BlockKey.y(corner);
            int cz = BlockKey.z(corner);
            if (!wadeOk(cx, cy, cz) || isDoorCell(cx, cy, cz) || !access.allows(cx, cy + 1, cz)) {
                return false;
            }
            int dy2 = grid.neighbourDy(corner, secondDir);
            return dy2 != NO_LINK && BlockKey.neighbour(corner, DX[secondDir], dy2, DZ[secondDir]) == target;
        }

        /** A directed drop of 2..maxDrop blocks into the orthogonal neighbour column. */
        private void tryDrop(Node u, int x, int y, int z, int nx, int nz) {
            if (profile.maxDrop() < 2) {
                return;
            }
            for (int by = y + profile.headroom(); by >= y - 1; by--) {
                if (!clear(nx, by, nz)) {
                    return;
                }
            }
            for (int k = 2; k <= profile.maxDrop(); k++) {
                int ly = y - k;
                if (ly < surface.minY()) {
                    return;
                }
                if (isStanding(nx, ly, nz)) {
                    relaxStanding(u, nx, ly, nz, k + profile.dropPenalty() * k, 1.0 + k);
                    return;
                }
                if (!clear(nx, ly, nz)) {
                    return;
                }
            }
        }

        // ===== relaxation =====

        private void relaxStanding(Node u, int x, int y, int z, double move, double stepLength) {
            double accessCost = accessCost(x, y + 1, z);
            if (accessCost == CellAccess.BLOCKED) {
                return;
            }
            double cost = move;
            if (cells.isWater(x, y + 1, z)) {
                cost *= profile.waterFactor();
            }
            if (isDoorCell(x, y, z)) {
                if (!profile.opensDoors()) {
                    return;
                }
                cost += profile.doorCost();
            }
            if (profile.wallCost() > 0 && besideWall(x, y, z)) {
                cost += profile.wallCost();
            }
            relax(u, BlockKey.pack(x, y, z), false, cost + accessCost, stepLength);
        }

        /**
         * A wall - a solid block the mover cannot pass, at feet or head height - in one of the 8 columns
         * around the cell. Blocks outside the capture are neither solid nor passable: no wall.
         */
        private boolean besideWall(int x, int y, int z) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    for (int h = 1; h <= profile.headroom(); h++) {
                        if (surface.isSolid(x + dx, y + h, z + dz) && !grid.isPassable(x + dx, y + h, z + dz)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private void relaxLadder(Node u, int x, int y, int z, double move, double stepLength) {
            double accessCost = accessCost(x, y, z);
            if (accessCost == CellAccess.BLOCKED) {
                return;
            }
            relax(u, BlockKey.pack(x, y, z), true, move + accessCost, stepLength);
        }

        /** The access verdict; NaN or negative answers count as blocked (they would break the search). */
        private double accessCost(int x, int feetY, int z) {
            double cost = access.extraCost(x, feetY, z);
            return cost >= 0.0 ? cost : CellAccess.BLOCKED;
        }

        private void relax(Node u, long key, boolean ladder, double cost, double stepLength) {
            Node v = nodes.get(key);
            if (v != null && v.closed) {
                return;
            }
            double g = u.g + cost;
            double len = u.len + stepLength;
            if (len > cap + EPS) {
                lengthCapped = true;
                return;
            }
            if (v == null) {
                v = new Node(key, ladder);
                nodes.put(key, v);
            } else if (g >= v.g - EPS) {
                return;
            }
            v.g = g;
            v.len = len;
            v.parent = u;
            push(v);
        }

        private void push(Node n) {
            open.add(new Entry(n.g + heuristic(n), n.g, seq++, n));
        }

        private double heuristic(Node n) {
            double dx = BlockKey.x(n.key) + 0.5 - request.targetX();
            double dz = BlockKey.z(n.key) + 0.5 - request.targetZ();
            double dy = Math.abs(floorY(n) - request.targetFloorY());
            return Math.max(Math.sqrt(dx * dx + dz * dz), dy);
        }

        private WalkPath path(Node end) {
            Deque<Node> chain = new ArrayDeque<>();
            for (Node n = end; n != null; n = n.parent) {
                chain.addFirst(n);
            }
            long[] keys = new long[chain.size()];
            boolean[] ladders = new boolean[chain.size()];
            int i = 0;
            for (Node n : chain) {
                keys[i] = n.key;
                ladders[i] = n.ladder;
                i++;
            }
            return new WalkPath(keys, ladders, end.len, end.g);
        }
    }
}
