package net.knightsandkings.knk.api;

import net.knightsandkings.knk.api.dto.GateDoorDto;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * API interface for gate door operations - introduced by item 5's multi-door support (see
 * docs/features/gate-structure-animation/GATESTRUCTURE_QOL_IMPLEMENTATION_PLAN.md). A GateDoor
 * is one independently-animating door belonging to a GateStructure; this interface covers the
 * per-door reads and state/health/operational-settings writes that used to live on
 * {@link GateStructuresApi} before a structure could hold more than one door.
 */
public interface GateDoorsApi {

    /**
     * Fetch every door belonging to a GateStructure. Calls GET /api/GateStructures/{id}/doors
     *
     * @param gateStructureId Parent gate structure ID
     * @return CompletableFuture with the structure's doors
     */
    CompletableFuture<List<GateDoorDto>> getByStructureId(int gateStructureId);

    /**
     * Get a single gate door by ID. Calls GET /api/GateDoors/{id}
     *
     * @param id Gate door ID
     * @return CompletableFuture with gate door details
     */
    CompletableFuture<GateDoorDto> getById(int id);

    /**
     * Update a door's opened/destroyed state.
     * Calls PUT /api/GateDoors/{id}/state
     *
     * @param id Gate door ID
     * @param openedState New state - one of CLOSED, OPENING, OPEN, CLOSING, JAMMED
     * @param isDestroyed New destroyed state
     * @return CompletableFuture that completes when the update is done
     */
    CompletableFuture<Void> updateState(int id, String openedState, boolean isDestroyed);

    /**
     * Update a door's active/invincible operational settings.
     * Calls PUT /api/GateDoors/{id}/operational-settings
     *
     * @param id Gate door ID
     * @param isActive New active state
     * @param isInvincible New invincible state
     * @return CompletableFuture that completes when the update is done
     */
    CompletableFuture<Void> updateOperationalSettings(int id, boolean isActive, boolean isInvincible);

    /**
     * Update the current health of a gate door.
     * Calls PUT /api/GateDoors/{id}/health
     *
     * @param id Gate door ID
     * @param healthCurrent New current health value
     * @return CompletableFuture that completes when update is done
     */
    CompletableFuture<Void> updateHealth(int id, double healthCurrent);
}
