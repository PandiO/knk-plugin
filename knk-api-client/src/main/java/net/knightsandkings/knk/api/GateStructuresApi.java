package net.knightsandkings.knk.api;

import net.knightsandkings.knk.api.dto.GateBlockSnapshotDto;
import net.knightsandkings.knk.api.dto.GateStructureDto;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * API interface for gate structure operations.
 * Provides methods to fetch gate structures and their block snapshots from the Web API.
 * 
 * This interface is in knk-api-client (not knk-core) because it returns DTOs.
 * Framework adapters (in knk-paper) implement this and use it to load gates into knk-core.
 */
public interface GateStructuresApi {

    /**
     * Fetch all active gate structures from the Web API.
     * Calls GET /api/GateStructures or similar endpoint.
     *
     * @return CompletableFuture with list of all gate structures
     */
    CompletableFuture<List<GateStructureDto>> getAll();

    /**
     * Get a single gate structure by ID.
     * Calls GET /api/GateStructures/{id}
     *
     * @param id Gate structure ID
     * @return CompletableFuture with gate structure details
     */
    CompletableFuture<GateStructureDto> getById(int id);

    /**
     * Fetch every gate structure belonging to a District, for incremental (on-demand) loading
     * as a player enters that district rather than loading the entire world's gates at startup.
     * Calls GET /api/GateStructures?districtId={id}&amp;pageSize=500 (a real, indexed filter on
     * Structure.DistrictId) with a large page size so a district with more than the endpoint's
     * default page size (10) isn't silently truncated.
     *
     * @param districtId District ID
     * @return CompletableFuture with the gate structures in that district
     */
    CompletableFuture<List<GateStructureDto>> getByDistrict(int districtId);

    /**
     * Update the state (IsOpened, IsDestroyed, IsJammed) of a gate structure.
     * Calls PUT /api/GateStructures/{id}/state
     *
     * @param id Gate structure ID
     * @param isOpened New opened state
     * @param isDestroyed New destroyed state
     * @param isJammed New jammed state
     * @return CompletableFuture that completes when update is done
     */
    CompletableFuture<Void> updateGateState(int id, boolean isOpened, boolean isDestroyed, boolean isJammed);

    CompletableFuture<Void> updateOperationalSettings(int id, boolean isActive, boolean isInvincible);

    /**
     * Update the current health of a gate structure.
     * Calls PUT /api/GateStructures/{id}/health
     *
     * @param id Gate structure ID
     * @param healthCurrent New current health value
     * @return CompletableFuture that completes when update is done
     */
    CompletableFuture<Void> updateGateHealth(int id, double healthCurrent);

    /**
     * Get all block snapshots for a specific gate.
     * Calls GET /api/GateStructures/{id}/snapshots
     *
     * @param gateId Gate structure ID
     * @return CompletableFuture with list of block snapshots
     */
    CompletableFuture<List<GateBlockSnapshotDto>> getGateSnapshots(int gateId);

    /**
     * Get the separately-scanned, fully-open shape for a specific gate, if one exists - see
     * docs/features/gate-structure-animation/ROTATION_GAP_FILL_DESIGN.md. Empty (not null)
     * when the gate has no such scan; its mere presence/absence is what selects Mechanism 2.
     * Calls GET /api/GateStructures/{id}/openedSnapshots
     *
     * @param gateId Gate structure ID
     * @return CompletableFuture with the list of opened-block snapshots (empty if none)
     */
    CompletableFuture<List<GateBlockSnapshotDto>> getGateOpenedSnapshots(int gateId);
}
