package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.api.dto.GateBlockSnapshotDto;
import net.knightsandkings.knk.api.dto.GateDoorDto;
import net.knightsandkings.knk.api.dto.GateStructureDto;
import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.gates.CachedGateStructure;
import net.knightsandkings.knk.core.gates.GateBlockPairing;
import net.knightsandkings.knk.core.gates.GateFrameCalculator;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.util.CoordinateParser;
import net.knightsandkings.knk.core.util.VectorMath;
import net.knightsandkings.knk.paper.tasks.GateRegionDataFormat;
import org.bukkit.util.Vector;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Adapter for loading gate structures (and their doors) from API DTOs into the GateManager cache.
 * This class handles the conversion from API DTOs to domain objects.
 *
 * Exists in knk-paper (not knk-core) to avoid circular dependency between
 * knk-core and knk-api-client. This follows hexagonal architecture:
 * - Core business logic lives in knk-core (GateManager state machine, etc.)
 * - Framework adapters (DTO conversions) live in knk-paper
 *
 * Item 5 (docs/features/gate-structure-animation/GATESTRUCTURE_QOL_IMPLEMENTATION_PLAN.md) moved
 * per-door data (geometry, animation, health, block snapshots) onto GateDoorDto/CachedGateDoor -
 * this adapter now builds one CachedGateStructure plus one CachedGateDoor per embedded door.
 */
public class GateLoaderAdapter {
    private static final Logger LOGGER = Logger.getLogger(GateLoaderAdapter.class.getName());

    private final GateManager gateManager;

    public GateLoaderAdapter(GateManager gateManager) {
        this.gateManager = gateManager;
    }

    /**
     * Load every gate structure and its doors' block snapshots into the runtime cache.
     *
     * @param gateStructuresApi API client used to retrieve gate data
     * @return future completed after every gate structure has been cached
     */
    public CompletableFuture<Void> loadAll(net.knightsandkings.knk.api.GateStructuresApi gateStructuresApi) {
        if (gateStructuresApi == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("GateStructuresApi is not configured"));
        }

        return gateStructuresApi.getAll().thenCompose(structures -> loadAndCacheAll(gateStructuresApi, structures));
    }

    /**
     * Load and cache just the gate structures belonging to a single District - used to load a
     * district's gates on demand as a player enters it, rather than requiring every gate in the
     * world to already be loaded (and an admin to run /knk gate admin reload for anything added
     * since server start). See DistrictGateLoader, which guards against re-fetching a district
     * that's already loaded.
     *
     * getByDistrict hits GET /api/GateStructures?districtId={id} (a query-filtered search), which
     * the backend serves from a lightweight list/search DTO with no door geometry - unlike the
     * unfiltered getAll()/getByIdWithSnapshots() this method falls back to per id. So each id is
     * re-fetched individually via getByIdWithSnapshots before building/caching it, the same full
     * fetch loadAll()/loadAndCacheAll() use.
     *
     * @param gateStructuresApi API client used to retrieve gate data
     * @param districtId District ID whose gates should be loaded
     * @return future completed with the ids of every gate door in that district successfully
     *         cached - used by callers (see DistrictGateLoader) to run a world/DB sync
     *         check-and-fix pass (Mechanism B,
     *         docs/features/gate-structure-animation/GATE_WORLD_SYNC_DESIGN.md) against exactly
     *         the gates that were just (re)loaded, nothing more.
     */
    public CompletableFuture<List<Integer>> loadForDistrict(net.knightsandkings.knk.api.GateStructuresApi gateStructuresApi, int districtId) {
        if (gateStructuresApi == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("GateStructuresApi is not configured"));
        }

        return gateStructuresApi.getByDistrict(districtId).thenCompose(summaries -> {
            int count = summaries == null ? 0 : summaries.size();
            LOGGER.info("District " + districtId + ": API returned " + count + " gate structure(s)");
            if (summaries == null || summaries.isEmpty()) {
                return CompletableFuture.completedFuture(List.<Integer>of());
            }

            List<Integer> loadedDoorIds = Collections.synchronizedList(new ArrayList<>());
            List<CompletableFuture<Void>> loads = new ArrayList<>();
            for (GateStructureDto summary : summaries) {
                if (summary == null || summary.getId() == null) {
                    continue;
                }
                int structureId = summary.getId();

                loads.add(gateStructuresApi.getByIdWithSnapshots(structureId).thenAccept(fullDto -> {
                    if (fullDto == null || fullDto.getId() == null) {
                        LOGGER.warning("District " + districtId + ": full lookup for gate structure " + structureId + " returned nothing; skipping");
                        return;
                    }
                    loadedDoorIds.addAll(loadAndCacheStructure(fullDto));
                }));
            }

            return CompletableFuture.allOf(loads.toArray(new CompletableFuture[0]))
                .thenApply(unused -> new ArrayList<>(loadedDoorIds));
        });
    }

    /**
     * Shared per-structure loading loop: re-fetch each structure with its doors' snapshots
     * included and cache it, used by loadAll (every gate structure).
     */
    private CompletableFuture<Void> loadAndCacheAll(net.knightsandkings.knk.api.GateStructuresApi gateStructuresApi, List<GateStructureDto> structures) {
        List<CompletableFuture<Void>> loads = new ArrayList<>();
        for (GateStructureDto structure : structures == null ? List.<GateStructureDto>of() : structures) {
            if (structure == null || structure.getId() == null) {
                continue;
            }

            int structureId = structure.getId();
            loads.add(gateStructuresApi.getByIdWithSnapshots(structureId).thenAccept(this::loadAndCacheStructure));
        }

        return CompletableFuture.allOf(loads.toArray(new CompletableFuture[0]));
    }

    /**
     * Build and cache one CachedGateStructure plus one CachedGateDoor per embedded door from a
     * full (snapshots-included) GateStructureDto.
     *
     * @param dto Gate structure DTO from API, with GateDoors (each including BlockSnapshots/
     *            OpenedBlockSnapshots) populated
     * @return the ids of every door successfully cached
     */
    public List<Integer> loadAndCacheStructure(GateStructureDto dto) {
        if (dto == null || dto.getId() == null) {
            LOGGER.warning("Cannot load gate structure: DTO or ID is null");
            return List.of();
        }

        CachedGateStructure structure = buildCachedGateStructure(dto);
        gateManager.cacheStructure(structure);

        List<Integer> doorIds = new ArrayList<>();
        List<GateDoorDto> doorDtos = dto.getGateDoors();
        if (doorDtos == null || doorDtos.isEmpty()) {
            LOGGER.warning("Gate structure '" + dto.getName() + "' (ID: " + dto.getId() + ") has no doors.");
            return doorIds;
        }

        for (GateDoorDto doorDto : doorDtos) {
            if (doorDto == null || doorDto.getId() == null) {
                continue;
            }
            CachedGateDoor door = buildCachedGateDoor(doorDto, structure);
            gateManager.cacheGate(door);
            doorIds.add(door.getId());

            LOGGER.info("Cached gate door: " + door.getName() + " (ID: " + door.getId() + ", structure: "
                + structure.getName() + ") with " + door.getBlocks().size() + " blocks"
                + (door.getOpenBlocks().isEmpty() ? "" : " and " + door.getOpenBlocks().size() + " open-scan blocks"));
        }

        return doorIds;
    }

    /** Structure-level fields only (decision 5.0-A "stays on GateStructure" list). */
    private CachedGateStructure buildCachedGateStructure(GateStructureDto dto) {
        CachedGateStructure structure = new CachedGateStructure(dto.getId(), dto.getName());

        structure.setCurrentSiegeId(dto.getCurrentSiegeId());
        structure.setOverridable(dto.getIsOverridable() == null || dto.getIsOverridable());
        structure.setAnimateDuringSiege(dto.getAnimateDuringSiege() == null || dto.getAnimateDuringSiege());
        structure.setSiegeObjective(Boolean.TRUE.equals(dto.getIsSiegeObjective()));

        structure.setIsActiveOverride(dto.getIsActiveOverride());
        structure.setCanRespawnOverride(dto.getCanRespawnOverride());
        structure.setIsDestroyedOverride(dto.getIsDestroyedOverride());
        structure.setIsInvincibleOverride(dto.getIsInvincibleOverride());
        structure.setOpenedStateOverride(dto.getOpenedStateOverride());
        structure.setAllowPassThroughOverride(dto.getAllowPassThroughOverride());
        structure.setPassThroughDurationSecondsOverride(dto.getPassThroughDurationSecondsOverride());
        structure.setShowHealthDisplayOverride(dto.getShowHealthDisplayOverride());
        structure.setHealthDisplayModeOverride(dto.getHealthDisplayModeOverride());
        structure.setHealthDisplayYOffsetOverride(dto.getHealthDisplayYOffsetOverride());
        structure.setGateNameDisplayModeOverride(dto.getGateNameDisplayModeOverride());
        structure.setStatusDisplayModeOverride(dto.getStatusDisplayModeOverride());
        structure.setAllowContinuousDamageOverride(dto.getAllowContinuousDamageOverride());
        structure.setContinuousDamageMultiplierOverride(dto.getContinuousDamageMultiplierOverride());

        return structure;
    }

    /**
     * Build a CachedGateDoor from a door DTO. Precomputes local basis vectors and motion vectors,
     * mirroring the pre-item-5 buildCachedGate logic exactly, just reading from GateDoorDto.
     */
    private CachedGateDoor buildCachedGateDoor(GateDoorDto dto, CachedGateStructure structure) {
        // Parse anchor point
        Vector anchorPoint = CoordinateParser.parseCoordinate(dto.getAnchorPoint());
        if (anchorPoint == null) {
            LOGGER.warning("Gate door " + dto.getName() + " has invalid anchor point, using (0,0,0)");
            anchorPoint = new Vector(0, 0, 0);
        }

        CachedGateDoor door = new CachedGateDoor(
            dto.getId(),
            structure.getId(),
            dto.getName(),
            dto.getGateType() != null ? dto.getGateType() : "SLIDING",
            dto.getMotionType() != null ? dto.getMotionType() : "VERTICAL",
            dto.getGeometryDefinitionMode() != null ? dto.getGeometryDefinitionMode() : "PLANE_GRID",
            dto.getAnimationDurationTicks() != null ? dto.getAnimationDurationTicks() : 60,
            dto.getAnimationTickRate() != null ? dto.getAnimationTickRate() : 1,
            anchorPoint,
            dto.getGeometryWidth() != null ? dto.getGeometryWidth() : 0,
            dto.getGeometryHeight() != null ? dto.getGeometryHeight() : 0,
            dto.getGeometryDepth() != null ? dto.getGeometryDepth() : 0,
            dto.getHealthCurrent() != null ? dto.getHealthCurrent() : 500.0,
            dto.getHealthMax() != null ? dto.getHealthMax() : 500.0,
            dto.getIsActive() != null ? dto.getIsActive() : false,
            dto.getIsDestroyed() != null ? dto.getIsDestroyed() : false,
            dto.getIsInvincible() != null ? dto.getIsInvincible() : true,
            dto.getRotationMaxAngleDegrees() != null ? dto.getRotationMaxAngleDegrees() : 90,
            dto.getFaceDirection() != null ? dto.getFaceDirection() : "NORTH"
        );

        door.setStructure(structure);

        door.setClosedRegionData(dto.getClosedRegionData());
        door.setOpenedRegionData(dto.getOpenedRegionData());
        door.setWorldName(CoordinateParser.parseWorldName(dto.getAnchorPoint()));
        door.setCanRespawn(dto.getCanRespawn() != null ? dto.getCanRespawn() : true);
        door.setRespawnRateSeconds(dto.getRespawnRateSeconds() != null ? dto.getRespawnRateSeconds() : 300);
        door.setClipToGeometryBounds(Boolean.TRUE.equals(dto.getClipToGeometryBounds()));

        door.setShowHealthDisplay(dto.getShowHealthDisplay() == null || dto.getShowHealthDisplay());
        door.setHealthDisplayMode(dto.getHealthDisplayMode());
        door.setHealthDisplayYOffset(dto.getHealthDisplayYOffset() != null ? dto.getHealthDisplayYOffset() : 2);
        door.setInfoDisplayLocation(CoordinateParser.parseCoordinate(dto.getInfoDisplayLocation()));
        door.setGateNameDisplayMode(dto.getGateNameDisplayMode());
        door.setStatusDisplayMode(dto.getStatusDisplayMode());
        door.setDoorNameDisplayMode(dto.getDoorNameDisplayMode());
        door.setAllowPassThrough(Boolean.TRUE.equals(dto.getAllowPassThrough()));
        door.setPassThroughDurationSeconds(
            dto.getPassThroughDurationSeconds() != null ? dto.getPassThroughDurationSeconds() : 2);
        door.setAllowContinuousDamage(dto.getAllowContinuousDamage() == null || dto.getAllowContinuousDamage());
        door.setContinuousDamageMultiplier(
            dto.getContinuousDamageMultiplier() != null ? dto.getContinuousDamageMultiplier() : 1.0);
        door.setOpenAnchorPoint(CoordinateParser.parseCoordinate(dto.getOpenAnchorPoint()));

        // Precompute local basis vectors
        precomputeBasisVectors(door, dto);

        // REGION mode (item 6.6): project the captured world-space footprint(s) into the u/v
        // index space precomputeBasisVectors just established. Must run after it - the basis
        // (anchor/uStep/vStep/nStep) is exactly what the projection needs.
        precomputeFootprintPolygons(door, dto);

        // Precompute motion vector
        precomputeMotionVector(door, dto);

        // Load block snapshots
        loadBlockSnapshots(door, dto.getBlockSnapshots());

        // Mechanism 2 (ROTATION_GAP_FILL_DESIGN.md): load the optional manually-scanned open
        // state and pair it against the closed blocks just loaded above. A door with none of
        // these ends up with an empty openBlocks/openBlockPairing, which is itself the trigger
        // GateFrameCalculator uses to fall through to today's exact procedural behavior.
        loadOpenBlockSnapshots(door, dto.getOpenedBlockSnapshots());

        // Set initial state from OpenedState (CLOSED/OPENING/OPEN/CLOSING/JAMMED - decision 5.0-C).
        String openedState = dto.getOpenedState();
        door.setIsJammed(GateDoorOpenStateMapper.isJammedFromWireValue(openedState));
        if (GateDoorOpenStateMapper.animationStateFromWireValue(openedState)
            == net.knightsandkings.knk.core.domain.gates.AnimationState.OPEN) {
            door.setCurrentState(net.knightsandkings.knk.core.domain.gates.AnimationState.OPEN);
            door.setCurrentFrame(door.getAnimationDurationTicks());
        } else {
            door.setCurrentState(net.knightsandkings.knk.core.domain.gates.AnimationState.CLOSED);
            door.setCurrentFrame(0);
        }

        return door;
    }

    /**
     * Precompute local basis vectors (u, v, n) from reference points.
     * For PLANE_GRID geometry mode.
     */
    private void precomputeBasisVectors(CachedGateDoor gate, GateDoorDto dto) {
        Vector ref1 = CoordinateParser.parseCoordinate(dto.getReferencePoint1());
        Vector ref2 = CoordinateParser.parseCoordinate(dto.getReferencePoint2());
        Vector anchor = gate.getAnchorPoint();

        if (ref1 != null && ref2 != null && anchor != null) {
            Vector uDelta = ref1.clone().subtract(anchor);
            Vector vDelta = ref2.clone().subtract(anchor);

            // u-axis: direction from anchor to ref1 (width direction)
            Vector u = uDelta.clone().normalize();
            gate.setUAxis(u);

            // v-axis: direction from anchor to ref2 (height direction)
            Vector v = vDelta.clone().normalize();
            gate.setVAxis(v);

            // n-axis: cross product (normal direction, motion axis)
            Vector n = u.clone().crossProduct(v).normalize();
            gate.setNAxis(n);

            // Lattice step vectors: shortest integer step along the same directions as u/v/n.
            // Identical to u/v/n for cardinal gates, but correctly reaches the adjacent block
            // for diagonal gates instead of under-shooting by stepping a unit vector.
            Vector uStep = VectorMath.primitiveLatticeStep(uDelta);
            Vector vStep = VectorMath.primitiveLatticeStep(vDelta);
            Vector nStep = VectorMath.primitiveLatticeStep(uStep.clone().crossProduct(vStep));
            gate.setUStep(uStep);
            gate.setVStep(vStep);
            gate.setNStep(nStep);
            // Mechanism 1 (ROTATION_GAP_FILL_DESIGN.md): >1 for a diagonal-hinge ROTATION gate,
            // whose open-state footprint would otherwise checkerboard. Computed for every gate
            // (not just ROTATION ones) since it's a cheap, purely geometric property of uStep;
            // GateAnimationTask is what actually gates its use on MotionType/GeometryDefinitionMode.
            gate.setSublatticeIndex(VectorMath.sublatticeIndex(uStep));

            LOGGER.fine("Gate " + gate.getName() + " basis vectors: u=" + u + ", v=" + v + ", n=" + n
                + "; steps: uStep=" + uStep + ", vStep=" + vStep + ", nStep=" + nStep);
        } else {
            // Fallback to standard axes
            gate.setUAxis(new Vector(1, 0, 0));
            gate.setVAxis(new Vector(0, 1, 0));
            gate.setNAxis(new Vector(0, 0, 1));
            gate.setUStep(new Vector(1, 0, 0));
            gate.setVStep(new Vector(0, 1, 0));
            gate.setNStep(new Vector(0, 0, 1));
            gate.setSublatticeIndex(1);
            LOGGER.warning("Gate " + gate.getName() + " missing reference points, using default axes");
        }
    }

    /**
     * REGION mode (item 6.6): projects the door's captured world-space footprint(s) into u/v
     * index space, once at load time rather than per-frame. Must run after {@link
     * #precomputeBasisVectors} - it needs the anchor/uStep/vStep/nStep that method just set.
     * No-op for PLANE_GRID/FLOOD_FILL gates, and leaves the corresponding footprint null (not an
     * empty list) when a REGION door's region hasn't been captured yet or fails to parse -
     * {@code GateFrameCalculator.isWithinGeometryBounds}/{@code rasterizeRotationFrame} both
     * treat a null/empty footprint as "fail open", matching how those same methods already treat
     * a missing PLANE_GRID basis.
     *
     * <p><strong>Orientation limitation, found during 6.6, resolved via {@code CONVEX_POLYHEDRON}
     * support</strong>: a {@code POLYGON2D} capture ({@link GateRegionDataFormat}'s §9.1 shape) is
     * fundamentally a WorldEdit X/Z-plane outline extruded through a Y range - it cannot precisely
     * represent a shape whose real variation is in Y (e.g., a vertically-standing closed door,
     * where the door's own v-axis is world-vertical); every captured vertex collapses to the
     * region's own {@code minY}. {@code CUBOID} capture has an analogous gap: its extracted
     * "bottom face" is always world-axis-aligned, so it can't precisely bound a diagonally-oriented
     * rectangular door (e.g., entity 14's diagonal hinge) in a plane other than horizontal. Both
     * remain limited to a genuinely horizontal-ish/axis-aligned footprint - that's an inherent
     * property of those two WorldEdit selection types themselves (the same limitation {@code
     * //sel poly}/{@code //sel cuboid} have), not something fixable here. For anything else
     * (vertical, diagonal, or both), capture with {@code //sel convex} instead: {@code
     * CONVEX_POLYHEDRON} vertices each carry a real, independent {@code (x, y, z)} - nothing
     * collapses - so once projected into u/v space they correctly span whatever orientation the
     * door actually has. The one extra step that type needs: WorldEdit exposes its vertices as an
     * unordered {@code Set}, so after projection they're re-ordered via {@link
     * GateFrameCalculator#convexHull2D} (safe specifically because a convex 3D shape's projection
     * onto any plane is itself convex) before being handed to {@code pointInPolygon}.
     */
    private void precomputeFootprintPolygons(CachedGateDoor gate, GateDoorDto dto) {
        if (!"REGION".equals(dto.getGeometryDefinitionMode())) {
            return;
        }

        gate.setClosedFootprintUV(projectFootprintToUV(gate, dto.getClosedRegionData()));
        gate.setOpenFootprintUV(projectFootprintToUV(gate, dto.getOpenedRegionData()));
    }

    private List<double[]> projectFootprintToUV(CachedGateDoor gate, String regionDataJson) {
        Vector anchor = gate.getAnchorPoint();
        Vector uStep = gate.getUStep();
        Vector vStep = gate.getVStep();
        Vector nStep = gate.getNStep();

        if (regionDataJson == null || regionDataJson.isBlank() || anchor == null
            || uStep == null || vStep == null || nStep == null) {
            return null;
        }

        try {
            List<double[]> worldVertices = GateRegionDataFormat.extractFootprintVerticesXYZ(regionDataJson);
            List<double[]> footprintUV = new ArrayList<>(worldVertices.size());
            for (double[] vertex : worldVertices) {
                Vector local = new Vector(vertex[0], vertex[1], vertex[2]).subtract(anchor);
                double[] indices = GateFrameCalculator.projectOntoBasis(local, uStep, vStep, nStep);
                footprintUV.add(new double[]{indices[0], indices[1]});
            }

            // CONVEX_POLYHEDRON's vertices come from WorldEdit as an unordered Set - re-derive a
            // valid boundary trace via 2D convex hull now that they're projected. Never applied to
            // POLYGON2D/CUBOID, whose vertices are already in a valid (possibly concave, for
            // POLYGON2D) order - hulling those would silently "fill in" a real concave notch.
            if (GateRegionDataFormat.isConvexPolyhedron(regionDataJson)) {
                footprintUV = GateFrameCalculator.convexHull2D(footprintUV);
            }

            return footprintUV;
        } catch (Exception e) {
            LOGGER.warning("Gate " + gate.getName() + " has invalid stored region data, footprint clipping disabled: " + e.getMessage());
            return null;
        }
    }

    /**
     * Precompute motion vector based on motion type and geometry.
     */
    private void precomputeMotionVector(CachedGateDoor gate, GateDoorDto dto) {
        String motionType = gate.getMotionType();
        Vector nAxis = gate.getNAxis();

        if (motionType == null || nAxis == null) {
            gate.setMotionVector(new Vector(0, 0, 0));
            return;
        }

        int distance = resolveMotionDistance(gate, dto, motionType);

        switch (motionType) {
            case "VERTICAL":
                gate.setMotionVector(new Vector(0, distance, 0));
                break;
            case "LATERAL":
                // Slides sideways along the door plane, not through it. Uses the lattice step
                // (not the unit uAxis) so "N blocks" of travel moves N real blocks even when
                // the gate is diagonally oriented.
                Vector uStep = gate.getUStep();
                Vector lateralStep = uStep != null && uStep.lengthSquared() > 0 ? uStep.clone() : new Vector(1, 0, 0);
                gate.setMotionVector(lateralStep.multiply(distance));
                break;
            case "ROTATION":
                // No linear motion vector, rotation handled separately
                gate.setMotionVector(new Vector(0, 0, 0));
                gate.setHingeAxis(resolveHingeAxis(gate));
                break;
            default:
                gate.setMotionVector(new Vector(0, 0, 0));
        }

        LOGGER.fine("Gate " + gate.getName() + " motion vector: " + gate.getMotionVector());
    }

    /**
     * The line a rotating gate swings around. Rodrigues' rotation formula leaves the
     * rotation axis's own component of a vector unchanged and mixes the other two - so the
     * hinge must be the axis whose offset should NOT change as the gate opens:
     * - DRAWBRIDGE hinges along its bottom edge (uAxis, the width direction) and sweeps
     *   height-offset blocks (vAxis) out into depth (nAxis) to lie flat as a bridge.
     * - DOUBLE_DOORS hinge along a vertical edge (vAxis, the height direction) and sweep
     *   width-offset blocks (uAxis) out into depth (nAxis).
     * nAxis itself is never a valid hinge for either: rotating around it just mixes uAxis
     * and vAxis into each other, spinning the door flat within its own plane instead of
     * swinging it open.
     */
    private Vector resolveHingeAxis(CachedGateDoor gate) {
        if ("DOUBLE_DOORS".equals(gate.getGateType())) {
            return gate.getVAxis();
        }
        return gate.getUAxis();
    }

    /**
     * MotionDistanceBlocks wins; legacy gates fall back to the geometry axis matching the motion type.
     */
    private int resolveMotionDistance(CachedGateDoor gate, GateDoorDto dto, String motionType) {
        Integer configured = dto.getMotionDistanceBlocks();
        if (configured != null && configured != 0) {
            return configured;
        }

        return switch (motionType) {
            case "VERTICAL" -> gate.getGeometryHeight();
            case "LATERAL" -> gate.getGeometryWidth();
            default -> 0;
        };
    }

    /**
     * Load block snapshots into the gate door.
     * Sorts by SortOrder to ensure stable block placement order.
     */
    private void loadBlockSnapshots(CachedGateDoor gate, List<GateBlockSnapshotDto> snapshotDtos) {
        if (snapshotDtos == null || snapshotDtos.isEmpty()) {
            LOGGER.warning("Gate door " + gate.getName() + " (ID: " + gate.getId() + ") has no block snapshots; it cannot be animated.");
            return;
        }

        for (GateBlockSnapshotDto dto : sortedBySortOrder(snapshotDtos)) {
            BlockSnapshot snapshot = toBlockSnapshot(dto);
            if (snapshot != null) {
                gate.addBlock(snapshot);
            }
        }

        LOGGER.fine("Loaded " + gate.getBlocks().size() + " blocks for gate door " + gate.getName());
    }

    /**
     * Mechanism 2 (ROTATION_GAP_FILL_DESIGN.md): load the door's optional manually-scanned open
     * state (empty/no-op when it has none) and pair each closed block to its open-state
     * counterpart - by SortOrder position for VERTICAL/LATERAL (both scans walk the same
     * deterministic loop from their own anchor), or by nearest 3D world-space distance for
     * ROTATION (Decision 2: greedy, ship v1; see GateBlockPairing).
     */
    private void loadOpenBlockSnapshots(CachedGateDoor gate, List<GateBlockSnapshotDto> openedSnapshotDtos) {
        if (openedSnapshotDtos == null || openedSnapshotDtos.isEmpty()) {
            return;
        }

        for (GateBlockSnapshotDto dto : sortedBySortOrder(openedSnapshotDtos)) {
            BlockSnapshot snapshot = toBlockSnapshot(dto);
            if (snapshot != null) {
                gate.addOpenBlock(snapshot);
            }
        }

        if (gate.getOpenBlocks().isEmpty() || gate.getOpenAnchorPoint() == null) {
            return;
        }

        List<BlockSnapshot> closedBlocks = gate.getBlocks();
        List<BlockSnapshot> openBlocks = gate.getOpenBlocks();

        Map<Integer, BlockSnapshot> pairing = new HashMap<>();
        if ("ROTATION".equals(gate.getMotionType())) {
            List<Vector> closedWorldPositions = new ArrayList<>();
            for (BlockSnapshot block : closedBlocks) {
                closedWorldPositions.add(gate.getAnchorPoint().clone().add(block.getRelativePosition()));
            }
            List<Vector> openWorldPositions = new ArrayList<>();
            for (BlockSnapshot block : openBlocks) {
                openWorldPositions.add(gate.getOpenAnchorPoint().clone().add(block.getRelativePosition()));
            }

            Map<Integer, Integer> indexPairing = GateBlockPairing.pairNearestNeighbor(closedWorldPositions, openWorldPositions);
            for (Map.Entry<Integer, Integer> entry : indexPairing.entrySet()) {
                pairing.put(closedBlocks.get(entry.getKey()).getId(), openBlocks.get(entry.getValue()));
            }
        } else {
            // VERTICAL/LATERAL: both scans walk the same deterministic (i,j,k) loop from their
            // respective anchors, so index position IS the correspondence (DUAL_SCAN_ANIMATION_
            // DESIGN.md's original design) - a block with no counterpart at the same index (the
            // open scan has fewer/more blocks) is simply left unpaired.
            int pairCount = Math.min(closedBlocks.size(), openBlocks.size());
            for (int i = 0; i < pairCount; i++) {
                pairing.put(closedBlocks.get(i).getId(), openBlocks.get(i));
            }
        }

        gate.setOpenBlockPairing(pairing);
        LOGGER.fine("Gate " + gate.getName() + " paired " + pairing.size() + "/" + closedBlocks.size()
            + " closed block(s) to their scanned open-state counterpart");
    }

    private List<GateBlockSnapshotDto> sortedBySortOrder(List<GateBlockSnapshotDto> dtos) {
        List<GateBlockSnapshotDto> sorted = new ArrayList<>(dtos);
        sorted.sort(Comparator.comparingInt(GateBlockSnapshotDto::sortOrder));
        return sorted;
    }

    /**
     * Converts one scanned block DTO to a domain BlockSnapshot, or null for an air block (see
     * {@link #isAirBlock}). Shared by the closed (BlockSnapshots) and Mechanism 2 open
     * (OpenedBlockSnapshots) loading paths - identical conversion either way.
     */
    private BlockSnapshot toBlockSnapshot(GateBlockSnapshotDto dto) {
        String blockData = resolveBlockData(dto);
        if (isAirBlock(blockData)) {
            return null;
        }

        Vector relativePos = new Vector(
            dto.relativeX() != null ? dto.relativeX() : 0,
            dto.relativeY() != null ? dto.relativeY() : 0,
            dto.relativeZ() != null ? dto.relativeZ() : 0
        );

        return new BlockSnapshot(
            dto.id(),
            relativePos,
            0, // no minecraftBlockRefId in the API contract; block identity travels via blockData/materialName
            blockData,
            dto.sortOrder() != null ? dto.sortOrder() : 0
        );
    }

    /**
     * Prefers the full Bukkit block-data string; falls back to the bare material name
     * (GateBlockPlacer can still resolve a plain material via Material.matchMaterial).
     */
    private String resolveBlockData(GateBlockSnapshotDto dto) {
        if (dto.blockDataJson() != null && !dto.blockDataJson().isBlank()) {
            return dto.blockDataJson();
        }
        return dto.materialName() != null ? dto.materialName() : "";
    }

    private boolean isAirBlock(String blockData) {
        if (blockData == null || blockData.isBlank()) {
            return true;
        }

        String normalized = blockData.trim().toLowerCase();
        int stateStart = normalized.indexOf('[');
        if (stateStart >= 0) {
            normalized = normalized.substring(0, stateStart);
        }

        return "air".equals(normalized)
            || "minecraft:air".equals(normalized)
            || "cave_air".equals(normalized)
            || "minecraft:cave_air".equals(normalized)
            || "void_air".equals(normalized)
            || "minecraft:void_air".equals(normalized);
    }
}
