package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.api.dto.ClaimTaskDto;
import net.knightsandkings.knk.api.dto.WorldTaskDto;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * API for managing WorldTasks - tasks that require in-game world data.
 */
public interface WorldTasksApi {
    /**
     * List tasks by status.
     * @param status Task status ("Pending", "InProgress", "Completed", "Failed", "Cancelled", "Expired")
     * @return List of tasks with the given status
     */
    CompletableFuture<List<WorldTaskDto>> listByStatus(String status);

    /**
     * Get an open task by its task code (the API column is still called LinkCode).
     * @param linkCode The 6-character task code from the web app
     * @return The task, or null if no open task has this code (#180: codes of closed tasks are dead)
     */
    CompletableFuture<WorldTaskDto> getByLinkCode(String linkCode);

    /**
     * Get a task by its ID.
     * @param id The task ID
     * @return The task, or null if not found
     */
    CompletableFuture<WorldTaskDto> getById(int id);

    /**
     * Claim a task for processing.
     * @param id Task ID
     * @param linkCode The link code (for verification)
     * @param serverId Server ID claiming the task
     * @param minecraftUsername Username claiming the task
     * @return The updated task
     */
    CompletableFuture<WorldTaskDto> claim(int id, String linkCode, String serverId, String minecraftUsername);

    /**
     * PandiO/knk-workspace#180: claim with the player's uuid and a claim mode (code, id, headless, offer). A task that is
     * no longer Pending, or a claim that loses the race, fails with an {@code ApiException} with status 409.
     */
    default CompletableFuture<WorldTaskDto> claim(int id, ClaimTaskDto claim) {
        return claim(id, claim.taskCode(), claim.serverId(), claim.minecraftUsername());
    }

    /**
     * Complete a task with output data.
     * @param id Task ID
     * @param outputJson JSON output data collected in-game
     * @return The completed task
     */
    CompletableFuture<WorldTaskDto> complete(int id, String outputJson);

    /** #180: completes on behalf of {@code serverId} (the API refuses it when another server holds the claim). */
    default CompletableFuture<WorldTaskDto> complete(int id, String outputJson, String serverId) {
        return complete(id, outputJson);
    }

    /**
     * Mark a task as failed.
     * @param id Task ID
     * @param errorMessage Error description
     * @return The failed task
     */
    CompletableFuture<WorldTaskDto> fail(int id, String errorMessage);

    /** #180: fails on behalf of {@code serverId}. */
    default CompletableFuture<WorldTaskDto> fail(int id, String errorMessage, String serverId) {
        return fail(id, errorMessage);
    }

    /**
     * #180: hands a claimed task back (InProgress → Pending; it keeps its task code). In-game {@code cancel}, quit,
     * restart, plugin disable.
     */
    default CompletableFuture<WorldTaskDto> release(int id, String serverId, String reason) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("release"));
    }

    /** #180: ends the task for good (Pending/InProgress → Cancelled); the player discards it in game. */
    default CompletableFuture<WorldTaskDto> cancel(int id, String reason) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("cancel"));
    }

    /**
     * #180: renews the claim's lease (the API releases a claim without a heartbeat for 3 minutes). 409 when the task is
     * no longer this server's: cancelled in the web app, released, completed.
     */
    default CompletableFuture<WorldTaskDto> heartbeat(int id, String serverId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("heartbeat"));
    }

    /** #180: the tasks this server holds according to the API (resync after a restart). */
    default CompletableFuture<List<WorldTaskDto>> listOpenForServer(String serverId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("listOpenForServer"));
    }
}
