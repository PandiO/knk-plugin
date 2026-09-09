package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.api.dto.GateBlockSnapshotDto;
import net.knightsandkings.knk.api.dto.GateStructureDto;
import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGate;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.util.CoordinateParser;
import net.knightsandkings.knk.core.util.VectorMath;
import org.bukkit.util.Vector;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
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
     * @return future completed after every gate in that district has been cached
     */
    public CompletableFuture<Void> loadForDistrict(net.knightsandkings.knk.api.GateStructuresApi gateStructuresApi, int districtId) {
        if (gateStructuresApi == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("GateStructuresApi is not configured"));
        }

        return gateStructuresApi.getByDistrict(districtId).thenCompose(summaries -> {
            int count = summaries == null ? 0 : summaries.size();
            LOGGER.info("District " + districtId + ": API returned " + count + " gate(s)");
            if (summaries == null || summaries.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }

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
                    return gateStructuresApi.getGateSnapshots(gateId)
                        .thenAccept(snapshots -> loadAndCacheGate(fullDto, snapshots == null ? List.of() : snapshots));
                }));
            }

            return CompletableFuture.allOf(loads.toArray(new CompletableFuture[0]));
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

            loads.add(gateStructuresApi.getGateSnapshots(gate.getId())
                .thenAccept(snapshots -> loadAndCacheGate(gate, snapshots == null ? List.of() : snapshots)));
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
        if (dto == null || dto.getId() == null) {
            LOGGER.warning("Cannot load gate: DTO or ID is null");
            return;
        }

        CachedGate cachedGate = buildCachedGate(dto, snapshotDtos);
        gateManager.cacheGate(cachedGate);
        
        LOGGER.info("Cached gate: " + cachedGate.getName() + " (ID: " + cachedGate.getId() + 
                   ") with " + cachedGate.getBlocks().size() + " blocks");
    }

    /**
     * Build a CachedGate from DTO data.
     * Precomputes local basis vectors and motion vectors.
     *
     * @param dto Gate structure DTO
     * @param snapshotDtos List of block snapshot DTOs
     * @return CachedGate instance
     */
    private CachedGate buildCachedGate(GateStructureDto dto, List<GateBlockSnapshotDto> snapshotDtos) {
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

        // Precompute local basis vectors
        precomputeBasisVectors(gate, dto);

        // Precompute motion vector
        precomputeMotionVector(gate, dto);

        // Load block snapshots
        loadBlockSnapshots(gate, snapshotDtos);

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

        List<GateBlockSnapshotDto> sortedSnapshots = new ArrayList<>(snapshotDtos);
        sortedSnapshots.sort(Comparator.comparingInt(GateBlockSnapshotDto::sortOrder));

        for (GateBlockSnapshotDto dto : sortedSnapshots) {
            String blockData = resolveBlockData(dto);
            if (isAirBlock(blockData)) {
                continue;
            }

            Vector relativePos = new Vector(
                dto.relativeX() != null ? dto.relativeX() : 0,
                dto.relativeY() != null ? dto.relativeY() : 0,
                dto.relativeZ() != null ? dto.relativeZ() : 0
            );

            BlockSnapshot snapshot = new BlockSnapshot(
                dto.id(),
                relativePos,
                0, // no minecraftBlockRefId in the API contract; block identity travels via blockData/materialName
                blockData,
                dto.sortOrder() != null ? dto.sortOrder() : 0
            );

            gate.addBlock(snapshot);
        }

        LOGGER.fine("Loaded " + gate.getBlocks().size() + " blocks for gate " + gate.getName());
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
