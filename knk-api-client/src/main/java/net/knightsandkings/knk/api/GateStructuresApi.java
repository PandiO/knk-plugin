package net.knightsandkings.knk.api;

import net.knightsandkings.knk.api.dto.GateStructureDto;
import net.knightsandkings.knk.api.dto.GateStructureOverridesUpdateDto;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * API interface for gate structure operations.
 * Provides methods to fetch gate structures (and their embedded doors) from the Web API.
 *
 * This interface is in knk-api-client (not knk-core) because it returns DTOs.
 * Framework adapters (in knk-paper) implement this and use it to load gates into knk-core.
 *
 * Item 5 (docs/features/gate-structure-animation/GATESTRUCTURE_QOL_IMPLEMENTATION_PLAN.md) moved
 * per-door operations (state/health/operational-settings/snapshots) to {@link GateDoorsApi} -
 * this interface now covers only structure-level reads and the cascading override endpoint.
 */
public interface GateStructuresApi {

    /**
     * Fetch all active gate structures (with their doors' geometry, but not block snapshots)
     * from the Web API. Calls GET /api/GateStructures.
     *
     * @return CompletableFuture with list of all gate structures
     */
    CompletableFuture<List<GateStructureDto>> getAll();

    /**
     * Get a single gate structure by ID, without its doors' block snapshots.
     * Calls GET /api/GateStructures/{id}
     *
     * @param id Gate structure ID
     * @return CompletableFuture with gate structure details
     */
    CompletableFuture<GateStructureDto> getById(int id);

    /**
     * Get a single gate structure by ID, including every door's block snapshots and
     * opened-block snapshots. Calls GET /api/GateStructures/{id}?includeSnapshots=true
     *
     * @param id Gate structure ID
     * @return CompletableFuture with gate structure details, doors, and their snapshots
     */
    CompletableFuture<GateStructureDto> getByIdWithSnapshots(int id);

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
     * Set/clear the structure-level cascading overrides (decision 5.0-B) - every overridable
     * door field that's non-null here wins over each child GateDoor's own value at read time,
     * with no per-door write needed. Calls PATCH /api/GateStructures/{id}/overrides
     *
     * @param id Gate structure ID
     * @param request Override fields to set/clear
     * @return CompletableFuture that completes when the update is done
     */
    CompletableFuture<Void> updateOverrides(int id, GateStructureOverridesUpdateDto request);
}
