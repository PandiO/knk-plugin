package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.api.dto.GateBlockSnapshotDto;
import net.knightsandkings.knk.api.dto.GateStructureDto;
import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGate;
import net.knightsandkings.knk.core.gates.GateBlockPairing;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.util.CoordinateParser;
import net.knightsandkings.knk.core.util.VectorMath;
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
 * Adapter for loading gates from API DTOs into the GateManager cache.
 * This class handles the conversion from API DTOs to domain objects.
 * 
 * Exists in knk-paper (not knk-core) to avoid circular dependency between
 * knk-core and knk-api-client. This follows hexagonal architecture:
 * - Core business logic lives in knk-core (GateManager state machine, etc.)
 * - Framework adapters (DTO conversions) live in knk-paper
 */
public class GateLoaderAdapter {
    private static final Logger LOGGER = Logger.getLogger(GateLoaderAdapter.class.getName());

    private final GateManager gateManager;

    public GateLoaderAdapter(GateManager gateManager) {
        this.gateManager = gateManager;
    }

    /**
     * Load every gate structure and its block snapshots into the runtime cache.
     *
     * @param gateStructuresApi API client used to retrieve gate data
     * @return future completed after every gate has been cached
     */
    public CompletableFuture<Void> loadAll(net.knightsandkings.knk.api.GateStructuresApi gateStructuresApi) {
        if (gateStructuresApi == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("GateStructuresApi is not configured"));
        }

        return gateStructuresApi.getAll().thenCompose(gates -> loadAndCacheAll(gateStructuresApi, gates));
    }

    /**
     * Load and cache just the gate structures belonging to a single District - used to load a
     * district's gates on demand as a player enters it, rather than requiring every gate in the
     * world to already be loaded (and an admin to run /knk gate admin reload for anything added
     * since server start). See DistrictGateLoader, which guards against re-fetching a district
     * that's already loaded.
     *
     * getByDistrict hits GET /api/GateStructures?districtId={id} (a query-filtered search), which
     * the backend serves from a lightweight list/search DTO with no geometry fields (anchorPoint,
     * referencePoint1/2, geometryWidth/Height/Depth, etc.) - unlike the unfiltered GetAllAsync()
     * that loadAll() uses, or GetById(). Caching those DTOs directly (as loadAll does with its
     * already-full ones) silently produced gates with anchor (0,0,0) and default axes - which,
     * for a gate already correctly loaded at startup, meant the first time a player entered its
     * district mid-session, this OVERWROTE the good in-memory CachedGate with a broken one (all
     * animated block positions would then compute relative to world (0,0,0) instead of the
     * gate's real location). So each id is re-fetched individually via getById (the same full
     * DTO getAll()/loadAll() already relies on) before building/caching it.
     *
     * @param gateStructuresApi API client used to retrieve gate data
     * @param districtId District ID whose gates should be loaded
     * @return future completed with the ids of every gate in that district successfully cached -
     *         used by callers (see DistrictGateLoader) to run a world/DB sync check-and-fix pass
     *         (Mechanism B, docs/features/gate-structure-animation/GATE_WORLD_SYNC_DESIGN.md)
     *         against exactly the gates that were just (re)loaded, nothing more.
     */
    public CompletableFuture<List<Integer>> loadForDistrict(net.knightsandkings.knk.api.GateStructuresApi gateStructuresApi, int districtId) {
        if (gateStructuresApi == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("GateStructuresApi is not configured"));
        }

        return gateStructuresApi.getByDistrict(districtId).thenCompose(summaries -> {
            int count = summaries == null ? 0 : summaries.size();
            LOGGER.info("District " + districtId + ": API returned " + count + " gate(s)");
            if (summaries == null || summaries.isEmpty()) {
                return CompletableFuture.completedFuture(List.<Integer>of());
            }

            List<Integer> loadedGateIds = Collections.synchronizedList(new ArrayList<>());
            List<CompletableFuture<Void>> loads = new ArrayList<>();
            for (GateStructureDto summary : summaries) {
                if (summary == null || summary.getId() == null) {
                    continue;
                }
                int gateId = summary.getId();

                loads.add(gateStructuresApi.getById(gateId).thenCompose(fullDto -> {
                    if (fullDto == null || fullDto.getId() == null) {
                        LOGGER.warning("District " + districtId + ": full lookup for gate " + gateId + " returned nothing; skipping");
                        return CompletableFuture.completedFuture(null);
                    }
                    CompletableFuture<List<GateBlockSnapshotDto>> snapshotsFuture = gateStructuresApi.getGateSnapshots(gateId);
                    CompletableFuture<List<GateBlockSnapshotDto>> openedSnapshotsFuture = gateStructuresApi.getGateOpenedSnapshots(gateId);
                    return snapshotsFuture.thenAcceptBoth(openedSnapshotsFuture, (snapshots, openedSnapshots) -> {
                        loadAndCacheGate(fullDto, snapshots == null ? List.of() : snapshots,
                            openedSnapshots == null ? List.of() : openedSnapshots);
                        loadedGateIds.add(gateId);
                    });
                }));
            }

            return CompletableFuture.allOf(loads.toArray(new CompletableFuture[0]))
                .thenApply(unused -> new ArrayList<>(loadedGateIds));
        });
    }

    /**
     * Shared per-gate loading loop: fetch each gate's block snapshots and cache it, used by both
     * loadAll (every gate) and loadForDistrict (one district's gates).
     */
    private CompletableFuture<Void> loadAndCacheAll(net.knightsandkings.knk.api.GateStructuresApi gateStructuresApi, List<GateStructureDto> gates) {
        List<CompletableFuture<Void>> loads = new ArrayList<>();
        for (GateStructureDto gate : gates == null ? List.<GateStructureDto>of() : gates) {
            if (gate == null || gate.getId() == null) {
                continue;
            }

            int gateId = gate.getId();
            CompletableFuture<List<GateBlockSnapshotDto>> snapshotsFuture = gateStructuresApi.getGateSnapshots(gateId);
            CompletableFuture<List<GateBlockSnapshotDto>> openedSnapshotsFuture = gateStructuresApi.getGateOpenedSnapshots(gateId);
            loads.add(snapshotsFuture.thenAcceptBoth(openedSnapshotsFuture, (snapshots, openedSnapshots) ->
                loadAndCacheGate(gate, snapshots == null ? List.of() : snapshots,
                    openedSnapshots == null ? List.of() : openedSnapshots)));
        }

        return CompletableFuture.allOf(loads.toArray(new CompletableFuture[0]));
    }

    /**
     * Load and cache a single gate from a DTO.
     * This method handles all DTO-to-domain conversion.
     *
     * @param dto Gate structure DTO from API
     * @param snapshotDtos List of block snapshot DTOs
     */
    public void loadAndCacheGate(GateStructureDto dto, List<GateBlockSnapshotDto> snapshotDtos) {
        loadAndCacheGate(dto, snapshotDtos, List.of());
    }

    /**
     * Load and cache a single gate from a DTO, including its optional Mechanism 2 open-state
     * scan (see docs/features/gate-structure-animation/ROTATION_GAP_FILL_DESIGN.md). This method
     * handles all DTO-to-domain conversion.
     *
     * @param dto Gate structure DTO from API
     * @param snapshotDtos List of block snapshot DTOs
     * @param openedSnapshotDtos List of opened-block snapshot DTOs - empty (not null) when the
     *                           gate has no manually-scanned open state
     */
    public void loadAndCacheGate(GateStructureDto dto, List<GateBlockSnapshotDto> snapshotDtos,
                                  List<GateBlockSnapshotDto> openedSnapshotDtos) {
        if (dto == null || dto.getId() == null) {
            LOGGER.warning("Cannot load gate: DTO or ID is null");
            return;
        }

        CachedGate cachedGate = buildCachedGate(dto, snapshotDtos, openedSnapshotDtos);
        gateManager.cacheGate(cachedGate);

        LOGGER.info("Cached gate: " + cachedGate.getName() + " (ID: " + cachedGate.getId() +
                   ") with " + cachedGate.getBlocks().size() + " blocks"
                   + (cachedGate.getOpenBlocks().isEmpty() ? "" : " and " + cachedGate.getOpenBlocks().size() + " open-scan blocks"));
    }

    /**
     * Build a CachedGate from DTO data.
     * Precomputes local basis vectors and motion vectors.
     *
     * @param dto Gate structure DTO
     * @param snapshotDtos List of block snapshot DTOs
     * @param openedSnapshotDtos List of opened-block snapshot DTOs (empty when none)
     * @return CachedGate instance
     */
    private CachedGate buildCachedGate(GateStructureDto dto, List<GateBlockSnapshotDto> snapshotDtos,
                                        List<GateBlockSnapshotDto> openedSnapshotDtos) {
        // Parse anchor point
        Vector anchorPoint = CoordinateParser.parseCoordinate(dto.getAnchorPoint());
        if (anchorPoint == null) {
            LOGGER.warning("Gate " + dto.getName() + " has invalid anchor point, using (0,0,0)");
            anchorPoint = new Vector(0, 0, 0);
        }

        // Create CachedGate
        CachedGate gate = new CachedGate(
            dto.getId(),
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
            dto.getFaceDirection() != null ? dto.getFaceDirection() : "north"
        );

        gate.setRegionClosedId(dto.getRegionClosedId());
        gate.setRegionOpenedId(dto.getRegionOpenedId());
        gate.setWorldName(CoordinateParser.parseWorldName(dto.getAnchorPoint()));
        gate.setCanRespawn(dto.getCanRespawn() != null ? dto.getCanRespawn() : true);
        gate.setRespawnRateSeconds(dto.getRespawnRateSeconds() != null ? dto.getRespawnRateSeconds() : 300);
        gate.setClipToGeometryBounds(Boolean.TRUE.equals(dto.getClipToGeometryBounds()));

        gate.setShowHealthDisplay(dto.getShowHealthDisplay() == null || dto.getShowHealthDisplay());
        gate.setHealthDisplayMode(dto.getHealthDisplayMode());
        gate.setHealthDisplayYOffset(dto.getHealthDisplayYOffset() != null ? dto.getHealthDisplayYOffset() : 2);
        gate.setInfoDisplayLocation(CoordinateParser.parseCoordinate(dto.getInfoDisplayLocation()));
        gate.setGateNameDisplayMode(dto.getGateNameDisplayMode());
        gate.setStatusDisplayMode(dto.getStatusDisplayMode());
        gate.setCurrentSiegeId(dto.getCurrentSiegeId());
        gate.setAllowPassThrough(Boolean.TRUE.equals(dto.getAllowPassThrough()));
        gate.setPassThroughDurationSeconds(
            dto.getPassThroughDurationSeconds() != null ? dto.getPassThroughDurationSeconds() : 2);
        gate.setOpenAnchorPoint(CoordinateParser.parseCoordinate(dto.getOpenAnchorPoint()));

        // Precompute local basis vectors
        precomputeBasisVectors(gate, dto);

        // Precompute motion vector
        precomputeMotionVector(gate, dto);

        // Load block snapshots
        loadBlockSnapshots(gate, snapshotDtos);

        // Mechanism 2 (ROTATION_GAP_FILL_DESIGN.md): load the optional manually-scanned open
        // state and pair it against the closed blocks just loaded above. A gate with none of
        // these ends up with an empty openBlocks/openBlockPairing, which is itself the trigger
        // GateFrameCalculator uses to fall through to today's exact procedural behavior.
        loadOpenBlockSnapshots(gate, openedSnapshotDtos);

        // Set initial state based on IsOpened
        if (dto.getIsOpened() != null && dto.getIsOpened()) {
            gate.setCurrentState(AnimationState.OPEN);
            gate.setCurrentFrame(gate.getAnimationDurationTicks());
        } else {
            gate.setCurrentState(AnimationState.CLOSED);
            gate.setCurrentFrame(0);
        }

        return gate;
    }

    /**
     * Precompute local basis vectors (u, v, n) from reference points.
     * For PLANE_GRID geometry mode.
     */
    private void precomputeBasisVectors(CachedGate gate, GateStructureDto dto) {
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
     * Precompute motion vector based on motion type and geometry.
     */
    private void precomputeMotionVector(CachedGate gate, GateStructureDto dto) {
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
    private Vector resolveHingeAxis(CachedGate gate) {
        if ("DOUBLE_DOORS".equals(gate.getGateType())) {
            return gate.getVAxis();
        }
        return gate.getUAxis();
    }

    /**
     * MotionDistanceBlocks wins; legacy gates fall back to the geometry axis matching the motion type.
     */
    private int resolveMotionDistance(CachedGate gate, GateStructureDto dto, String motionType) {
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
     * Load block snapshots into the gate.
     * Sorts by SortOrder to ensure stable block placement order.
     */
    private void loadBlockSnapshots(CachedGate gate, List<GateBlockSnapshotDto> snapshotDtos) {
        if (snapshotDtos == null || snapshotDtos.isEmpty()) {
            LOGGER.warning("Gate " + gate.getName() + " (ID: " + gate.getId() + ") has no block snapshots; it cannot be animated.");
            return;
        }

        for (GateBlockSnapshotDto dto : sortedBySortOrder(snapshotDtos)) {
            BlockSnapshot snapshot = toBlockSnapshot(dto);
            if (snapshot != null) {
                gate.addBlock(snapshot);
            }
        }

        LOGGER.fine("Loaded " + gate.getBlocks().size() + " blocks for gate " + gate.getName());
    }

    /**
     * Mechanism 2 (ROTATION_GAP_FILL_DESIGN.md): load the gate's optional manually-scanned open
     * state (empty/no-op when it has none) and pair each closed block to its open-state
     * counterpart - by SortOrder position for VERTICAL/LATERAL (both scans walk the same
     * deterministic loop from their own anchor), or by nearest 3D world-space distance for
     * ROTATION (Decision 2: greedy, ship v1; see GateBlockPairing).
     */
    private void loadOpenBlockSnapshots(CachedGate gate, List<GateBlockSnapshotDto> openedSnapshotDtos) {
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
