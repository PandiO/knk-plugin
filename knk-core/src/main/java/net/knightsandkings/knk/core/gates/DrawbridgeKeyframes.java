package net.knightsandkings.knk.core.gates;

import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.util.VectorMath;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * KNG-193: the keyframed plank of a drawbridge ({@code ROTATION} door with a horizontal hinge),
 * precomputed once at load (knk-workspace
 * {@code docs/specs/gate-structure-animation/DRAWBRIDGE_KEYFRAME_ANIMATION.md} §5, decisions
 * D1-D8). A Java port of {@code scripts/gate-animation/drawbridge_keyframes_sim.py}; the numbers
 * must match it, so rounding is half-up ({@link Math#round}) like the script.
 *
 * <p>Every world cell of the swing is written as {@code anchor + i·uStep + k·e + y·(0,1,0)}
 * (§5.2): {@code uStep} is the hinge step, {@code e} a unit world axis so that ({@code uStep},
 * {@code e}) is an exact basis of the horizontal grid, pointing to the side the door opens
 * toward, and one step of {@code k} moves a strip one block face outward. The door's shape is the
 * set of closed-scan blocks as (a = along-hinge index, row) (§5.11). Each keyframe draws the side
 * profile as a naive 8-connected digital line from the hinge to the rounded tip
 * {@code (K sin θ, (H-1) cos θ)} (D3), extrudes it along the hinge, and samples the shape mask
 * with the nearest-row rule (D7). There is one keyframe per change of the rounded tip cell,
 * timed linearly in angle (D4).
 *
 * <p>Pure functions over {@link Vector}s - no Bukkit server access - so the keyframes are
 * unit-testable in knk-core. Blockdata orientation is supplied by the caller
 * ({@link Orienter}, knk-paper's {@code GateBlockOrientation}).
 */
public final class DrawbridgeKeyframes {

    /** The tip path is sampled this finely over the swing angle, like the reference script. */
    static final int TIP_SAMPLES = 9000;
    private static final double EPSILON = 1e-9;

    private DrawbridgeKeyframes() {
    }

    /** Rotates a block's own facing/axis by the door's swing angle; identity when unknown. */
    @FunctionalInterface
    public interface Orienter {
        Orienter IDENTITY = (blockData, angleDegrees) -> blockData;

        String orient(String blockData, double angleDegrees);
    }

    /** One world cell of a keyframe. */
    public record Cell(Vector position, String blockData) {
        public long packed() {
            return GateSpatialIndex.packCell(position);
        }
    }

    /**
     * One whole-door shape. {@code startAngleDegrees} is the swing angle at which the plank's tip
     * enters this keyframe's tip cell {@code (tipK, tipY)} (profile coordinates: strips out from
     * the hinge, rows up).
     */
    public record Keyframe(int index, double startAngleDegrees, int tipK, int tipY, List<Cell> cells) {
        public Set<Long> packedPositions() {
            Set<Long> packed = new HashSet<>(cells.size() * 2);
            for (Cell cell : cells) {
                packed.add(cell.packed());
            }
            return packed;
        }

        public List<Vector> positions() {
            List<Vector> positions = new ArrayList<>(cells.size());
            for (Cell cell : cells) {
                positions.add(cell.position());
            }
            return positions;
        }
    }

    /**
     * The keyframes of one door, closed first. {@code reach} is K: the number of strips the open
     * deck reaches past the hinge; {@code height} is H, the mask's row count.
     */
    public record Plan(Vector anchor, Vector uStep, Vector e, double f, long sigma, int height, int reach,
                       List<Keyframe> keyframes, List<String> warnings) {

        public int size() {
            return keyframes.size();
        }

        public Keyframe keyframe(int index) {
            return keyframes.get(Math.max(0, Math.min(keyframes.size() - 1, index)));
        }

        public Keyframe first() {
            return keyframes.get(0);
        }

        public Keyframe last() {
            return keyframes.get(keyframes.size() - 1);
        }

        /** The last keyframe whose start angle is at most {@code angleDegrees}. */
        public int indexAt(double angleDegrees) {
            int index = 0;
            for (int n = 1; n < keyframes.size(); n++) {
                if (keyframes.get(n).startAngleDegrees() <= angleDegrees + EPSILON) {
                    index = n;
                } else {
                    break;
                }
            }
            return index;
        }

        /** The keyframe shown at an animation frame: the door's swing angle at that frame, mapped as {@link #indexAt}. */
        public int indexAtFrame(CachedGateDoor gate, int frame) {
            return indexAt(GateFrameCalculator.calculateRotationAngle(gate, frame));
        }

        public Keyframe keyframeAtFrame(CachedGateDoor gate, int frame) {
            return keyframe(indexAtFrame(gate, frame));
        }

        /**
         * A world cell's strip coordinates {@code {i, k, y}} in this door's basis
         * ({@code anchor + i·uStep + k·e + y·up}); its along-hinge position is {@code i + f·k}.
         */
        public int[] stripIndices(Vector position) {
            Vector local = position.clone().subtract(anchor);
            int a = (int) Math.round(uStep.getX());
            int b = (int) Math.round(uStep.getZ());
            int ex = (int) Math.round(e.getX());
            int ez = (int) Math.round(e.getZ());
            int det = a * ez - b * ex;
            int dx = (int) Math.round(local.getX());
            int dz = (int) Math.round(local.getZ());
            return new int[]{(dx * ez - dz * ex) / det, (a * dz - b * dx) / det, (int) Math.round(local.getY())};
        }

        /** Every cell of the keyframes from one index to another, both included, in either order. */
        public Set<Long> cellsBetween(int fromIndex, int toIndex) {
            Set<Long> cells = new HashSet<>();
            int lo = Math.max(0, Math.min(fromIndex, toIndex));
            int hi = Math.min(keyframes.size() - 1, Math.max(fromIndex, toIndex));
            for (int n = lo; n <= hi; n++) {
                cells.addAll(keyframes.get(n).packedPositions());
            }
            return cells;
        }
    }

    /**
     * Either a {@link Plan} or the reason this door keeps today's per-block path
     * ({@code fallbackReason}, one warning at load). {@code warnings} are non-fatal notes
     * (e.g. a deck length that disagrees with the open scan, D2).
     */
    public record Result(Plan plan, String fallbackReason, List<String> warnings) {
        public boolean hasPlan() {
            return plan != null;
        }

        static Result fallback(String reason) {
            return new Result(null, reason, List.of());
        }
    }

    /** The door's deck length in blocks (D1/D2): {@code MotionDistanceBlocks}, or GeometryHeight (then the mask height) for 0. */
    public static int deckLengthBlocks(CachedGateDoor door, int motionDistanceBlocks, int maskHeight) {
        if (motionDistanceBlocks > 0) {
            return motionDistanceBlocks;
        }
        return door.getGeometryHeight() > 0 ? door.getGeometryHeight() : maskHeight;
    }

    /**
     * Builds the keyframes of a door, or says why it keeps the old path.
     *
     * @param door                 the loaded door: anchor, uStep/vStep, angle, closed blocks and the optional open scan
     * @param motionDistanceBlocks {@code MotionDistanceBlocks} (0 or negative = GeometryHeight), used only without an open scan
     * @param orienter             rotates a block's facing/axis by the keyframe angle
     */
    public static Result build(CachedGateDoor door, int motionDistanceBlocks, Orienter orienter) {
        if (door == null) {
            return Result.fallback("no door");
        }
        if (!"ROTATION".equals(door.getMotionType())) {
            return Result.fallback("not a ROTATION door");
        }
        if ("DOUBLE_DOORS".equals(door.getGateType())) {
            return Result.fallback("DOUBLE_DOORS (vertical hinge) is out of scope for the keyframed plank");
        }
        double maxAngle = door.getRotationMaxAngleDegrees();
        if (!(maxAngle > 0) || maxAngle > 90) {
            return Result.fallback("RotationMaxAngleDegrees " + door.getRotationMaxAngleDegrees() + " is outside (0, 90]");
        }
        Vector anchor = door.getAnchorPoint();
        Vector uStep = door.getUStep();
        Vector vStep = door.getVStep();
        if (anchor == null || uStep == null || vStep == null) {
            return Result.fallback("missing basis (anchor, uStep or vStep)");
        }
        if (!isIntegral(uStep) || !isIntegral(vStep) || !isIntegral(anchor)) {
            return Result.fallback("basis is not on the block grid: uStep=" + uStep + ", vStep=" + vStep);
        }
        int a = (int) Math.round(uStep.getX());
        int b = (int) Math.round(uStep.getZ());
        if (Math.round(uStep.getY()) != 0 || (a == 0 && b == 0)) {
            return Result.fallback("hinge step " + uStep + " is not horizontal");
        }
        if (Math.round(vStep.getX()) != 0 || Math.round(vStep.getZ()) != 0 || Math.round(vStep.getY()) != 1) {
            return Result.fallback("height step " + vStep + " is not straight up (KNG-132)");
        }
        if (door.getBlocks().isEmpty()) {
            return Result.fallback("no closed scan");
        }

        Vector e = chooseE(uStep, a, b, maxAngle);
        if (e == null) {
            return Result.fallback("hinge slope " + a + ":" + b + " has no unit-axis strip direction (needs |a| = 1 or |b| = 1)");
        }
        int ex = (int) Math.round(e.getX());
        int ez = (int) Math.round(e.getZ());
        int det = a * ez - b * ex;
        double f = (ex * a + ez * b) / (double) (a * a + b * b);
        long sigma = (long) a * a + (long) b * b;
        Orienter orient = orienter != null ? orienter : Orienter.IDENTITY;

        // Shape mask (§5.11): the closed blocks as (a, row); all must sit on the hinge plane (k = 0).
        Map<Long, BlockSnapshot> mask = new LinkedHashMap<>();
        int height = 0;
        int cmin = Integer.MAX_VALUE;
        int cmax = Integer.MIN_VALUE;
        for (BlockSnapshot block : door.getBlocks()) {
            if (block == null) {
                continue;
            }
            Vector rel = block.getRelativePosition();
            if (rel == null || !isIntegral(rel)) {
                return Result.fallback("closed block " + block.getId() + " is not on the block grid: " + rel);
            }
            int dx = (int) Math.round(rel.getX());
            int dy = (int) Math.round(rel.getY());
            int dz = (int) Math.round(rel.getZ());
            int i = (dx * ez - dz * ex) / det;
            int k = (a * dz - b * dx) / det;
            if (k != 0) {
                return Result.fallback("closed block " + block.getId() + " at " + rel + " is off the hinge plane (k=" + k
                    + "; GeometryDepth > 1 or a sheared scan)");
            }
            if (dy < 0) {
                return Result.fallback("closed block " + block.getId() + " at " + rel + " is below the hinge row");
            }
            mask.putIfAbsent(packIndex(i, dy), block);
            height = Math.max(height, dy + 1);
            cmin = Math.min(cmin, i);
            cmax = Math.max(cmax, i);
        }
        if (mask.isEmpty()) {
            return Result.fallback("no closed scan");
        }

        // Reach K (§5.3): from the open scan when there is one, else from the deck length (D1/D2).
        List<String> warnings = new ArrayList<>();
        boolean hasOpenScan = !door.getOpenBlocks().isEmpty() && door.getOpenAnchorPoint() != null;
        int reach;
        if (hasOpenScan) {
            Vector openAnchor = door.getOpenAnchorPoint();
            reach = Integer.MIN_VALUE;
            for (BlockSnapshot openBlock : door.getOpenBlocks()) {
                if (openBlock == null || openBlock.getRelativePosition() == null) {
                    continue;
                }
                Vector local = openAnchor.clone().add(openBlock.getRelativePosition()).subtract(anchor);
                if (!isIntegral(local)) {
                    return Result.fallback("open block " + openBlock.getId() + " is not on the block grid: " + local);
                }
                int dx = (int) Math.round(local.getX());
                int dz = (int) Math.round(local.getZ());
                reach = Math.max(reach, (a * dz - b * dx) / det);
            }
            if (reach < 1) {
                return Result.fallback("the open scan does not reach past the hinge toward " + e + " (K=" + reach + ")");
            }
            if (motionDistanceBlocks > 0 && sigma * (motionDistanceBlocks - 1) != reach) {
                warnings.add("MotionDistanceBlocks=" + motionDistanceBlocks + " disagrees with the open scan, which reaches "
                    + reach + " strip(s) (" + (reach / (double) sigma + 1) + " blocks); the open scan wins (D2)");
            }
        } else {
            int length = deckLengthBlocks(door, motionDistanceBlocks, height);
            if (length < 2) {
                return Result.fallback("deck length " + length + " block(s) is too short for a swing");
            }
            reach = (int) (sigma * (length - 1));
        }

        // Tip path and keyframes (§5.3-5.5).
        List<Keyframe> keyframes = new ArrayList<>();
        List<double[]> tipPath = tipPath(reach, height - 1, maxAngle);
        Vector up = new Vector(0, 1, 0);
        for (int n = 0; n < tipPath.size(); n++) {
            int tipK = (int) tipPath.get(n)[0];
            int tipY = (int) tipPath.get(n)[1];
            double startAngle = tipPath.get(n)[2];
            Map<Long, Cell> cells = new LinkedHashMap<>();
            List<int[]> profile = profileLine(tipK, tipY);
            int p = profile.size();
            for (int step = 0; step < p; step++) {
                int k = profile.get(step)[0];
                int y = profile.get(step)[1];
                int row = p > 1 ? roundHalfUp(step * (height - 1) / (double) (p - 1)) : 0;
                for (int i = cmin - Math.abs(k) - 2; i < cmax + Math.abs(k) + 2; i++) {
                    double along = i + f * k;
                    if (along < cmin - EPSILON || along > cmax + EPSILON) {
                        continue;
                    }
                    int column;
                    boolean present;
                    if (Math.abs(along - Math.rint(along)) < EPSILON) {
                        column = (int) Math.rint(along);
                        present = mask.containsKey(packIndex(column, row));
                    } else {
                        column = (int) Math.floor(along);
                        present = mask.containsKey(packIndex(column, row))
                            && mask.containsKey(packIndex((int) Math.ceil(along), row));
                    }
                    if (!present) {
                        continue;
                    }
                    BlockSnapshot source = mask.get(packIndex(column, row));
                    Vector position = anchor.clone()
                        .add(uStep.clone().multiply(i))
                        .add(e.clone().multiply(k))
                        .add(up.clone().multiply(y));
                    // The hinge row (k = 0, y = 0) never moves and keeps its scanned blockdata (§5.5).
                    String blockData = (k == 0 && y == 0)
                        ? source.getBlockData()
                        : orient.orient(source.getBlockData(), startAngle);
                    cells.putIfAbsent(GateSpatialIndex.packCell(position), new Cell(position, blockData));
                }
            }
            keyframes.add(new Keyframe(n, startAngle, tipK, tipY, List.copyOf(cells.values())));
        }

        // Keyframe 0 is the closed scan verbatim; the last keyframe is the open scan verbatim when there is one (§5.5).
        keyframes.set(0, new Keyframe(0, keyframes.get(0).startAngleDegrees(), keyframes.get(0).tipK(),
            keyframes.get(0).tipY(), verbatim(anchor, door.getBlocks())));
        if (hasOpenScan) {
            int lastIndex = keyframes.size() - 1;
            Keyframe computed = keyframes.get(lastIndex);
            keyframes.set(lastIndex, new Keyframe(lastIndex, computed.startAngleDegrees(), computed.tipK(), computed.tipY(),
                verbatim(door.getOpenAnchorPoint(), door.getOpenBlocks())));
        }

        return new Result(new Plan(anchor.clone(), uStep.clone(), e, f, sigma, height, reach,
            Collections.unmodifiableList(keyframes), List.copyOf(warnings)), null, List.copyOf(warnings));
    }

    /**
     * The unit world axis {@code e} with {@code det(uStep, e) = ±1} (so every horizontal grid cell is
     * {@code i·uStep + k·e} for integers i, k) that points toward the side the door opens to: the
     * largest positive dot with the height step rotated about the hinge by the full swing. Null when
     * no unit axis is unimodular with the hinge step (a slope like 3:2).
     */
    static Vector chooseE(Vector uStep, int a, int b, double maxAngleDegrees) {
        Vector opensToward = VectorMath.rotateAroundAxis(new Vector(0, 1, 0), uStep, maxAngleDegrees);
        Vector[] candidates = {new Vector(1, 0, 0), new Vector(-1, 0, 0), new Vector(0, 0, 1), new Vector(0, 0, -1)};
        Vector best = null;
        double bestDot = EPSILON;
        for (Vector candidate : candidates) {
            int det = a * (int) candidate.getZ() - b * (int) candidate.getX();
            if (det != 1 && det != -1) {
                continue;
            }
            double dot = candidate.dot(opensToward);
            if (dot > bestDot) {
                bestDot = dot;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * {@code {tipK, tipY, startAngle}} per keyframe: the rounded tip cell {@code (K sin θ, (H-1) cos θ)}
     * sampled finely over {@code [0, maxAngle]}, one entry each time it changes.
     */
    static List<double[]> tipPath(int reach, int heightMinusOne, double maxAngleDegrees) {
        List<double[]> path = new ArrayList<>();
        int lastK = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        for (int s = 0; s <= TIP_SAMPLES; s++) {
            double angle = maxAngleDegrees * s / TIP_SAMPLES;
            double t = Math.toRadians(angle);
            int k = roundHalfUp(reach * Math.sin(t));
            int y = roundHalfUp(heightMinusOne * Math.cos(t));
            if (k != lastK || y != lastY) {
                path.add(new double[]{k, y, angle});
                lastK = k;
                lastY = y;
            }
        }
        return path;
    }

    /** The naive digital segment (0,0) -> (tipK, tipY), ordered from the hinge: one cell per major-axis step. */
    static List<int[]> profileLine(int tipK, int tipY) {
        int n = Math.max(Math.abs(tipK), Math.abs(tipY));
        List<int[]> line = new ArrayList<>(n + 1);
        if (n == 0) {
            line.add(new int[]{0, 0});
            return line;
        }
        for (int s = 0; s <= n; s++) {
            line.add(new int[]{roundHalfUp(tipK * s / (double) n), roundHalfUp(tipY * s / (double) n)});
        }
        return line;
    }

    private static List<Cell> verbatim(Vector origin, List<BlockSnapshot> blocks) {
        Map<Long, Cell> cells = new LinkedHashMap<>();
        for (BlockSnapshot block : blocks) {
            if (block == null || block.getRelativePosition() == null) {
                continue;
            }
            Vector position = origin.clone().add(block.getRelativePosition());
            cells.putIfAbsent(GateSpatialIndex.packCell(position), new Cell(position, block.getBlockData()));
        }
        return List.copyOf(cells.values());
    }

    /** Half-up rounding, like the reference script's {@code floor(x + 0.5)} and {@link Math#round}. */
    static int roundHalfUp(double value) {
        return (int) Math.floor(value + 0.5);
    }

    private static boolean isIntegral(Vector v) {
        return Math.abs(v.getX() - Math.rint(v.getX())) < 1e-6
            && Math.abs(v.getY() - Math.rint(v.getY())) < 1e-6
            && Math.abs(v.getZ() - Math.rint(v.getZ())) < 1e-6;
    }

    private static long packIndex(int i, int row) {
        return ((long) i << 32) ^ (row & 0xFFFFFFFFL);
    }
}
