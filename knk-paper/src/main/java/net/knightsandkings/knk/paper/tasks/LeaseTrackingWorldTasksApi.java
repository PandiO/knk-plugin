package net.knightsandkings.knk.paper.tasks;

import net.knightsandkings.knk.api.dto.ClaimTaskDto;
import net.knightsandkings.knk.api.dto.WorldTaskDto;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * PandiO/knk-workspace#180: the {@link WorldTasksApi} the handlers, commands and the headless poller use. It sends this
 * server's id with every claim, completion and failure, and keeps {@link WorldTaskLeases} in step: a claim is held (and
 * heartbeated) until it completes, fails, or the API says it moved on.
 */
public class LeaseTrackingWorldTasksApi implements WorldTasksApi {
    private final WorldTasksApi delegate;
    private final WorldTaskLeases leases;

    public LeaseTrackingWorldTasksApi(WorldTasksApi delegate, WorldTaskLeases leases) {
        this.delegate = delegate;
        this.leases = leases;
    }

    @Override
    public CompletableFuture<List<WorldTaskDto>> listByStatus(String status) {
        return delegate.listByStatus(status);
    }

    @Override
    public CompletableFuture<WorldTaskDto> getByLinkCode(String linkCode) {
        return delegate.getByLinkCode(linkCode);
    }

    @Override
    public CompletableFuture<WorldTaskDto> getById(int id) {
        return delegate.getById(id);
    }

    @Override
    public CompletableFuture<WorldTaskDto> claim(int id, String linkCode, String serverId, String minecraftUsername) {
        return claim(id, new ClaimTaskDto(linkCode, serverId, minecraftUsername));
    }

    @Override
    public CompletableFuture<WorldTaskDto> claim(int id, ClaimTaskDto claim) {
        ClaimTaskDto withServer = new ClaimTaskDto(claim.taskCode(),
            claim.serverId() != null ? claim.serverId() : leases.serverId(),
            claim.minecraftUsername(), claim.uuid(), claim.claimMode());
        return delegate.claim(id, withServer).thenApply(task -> {
            if (task != null && task.id() != null) {
                leases.hold(task.id(), parseUuid(withServer.uuid()), task.taskType());
            }
            return task;
        });
    }

    @Override
    public CompletableFuture<WorldTaskDto> complete(int id, String outputJson) {
        return complete(id, outputJson, leases.serverId());
    }

    @Override
    public CompletableFuture<WorldTaskDto> complete(int id, String outputJson, String serverId) {
        return delegate.complete(id, outputJson, serverId).whenComplete((task, error) -> settled(id, error));
    }

    @Override
    public CompletableFuture<WorldTaskDto> fail(int id, String errorMessage) {
        return fail(id, errorMessage, leases.serverId());
    }

    @Override
    public CompletableFuture<WorldTaskDto> fail(int id, String errorMessage, String serverId) {
        return delegate.fail(id, errorMessage, serverId).whenComplete((task, error) -> settled(id, error));
    }

    @Override
    public CompletableFuture<WorldTaskDto> release(int id, String serverId, String reason) {
        leases.forget(id);
        return delegate.release(id, serverId != null ? serverId : leases.serverId(), reason);
    }

    @Override
    public CompletableFuture<WorldTaskDto> release(int id, String serverId, String reason, boolean handBack) {
        leases.forget(id);
        return delegate.release(id, serverId != null ? serverId : leases.serverId(), reason, handBack);
    }

    @Override
    public CompletableFuture<WorldTaskDto> cancel(int id, String reason) {
        leases.forget(id);
        return delegate.cancel(id, reason);
    }

    @Override
    public CompletableFuture<WorldTaskDto> heartbeat(int id, String serverId) {
        return delegate.heartbeat(id, serverId != null ? serverId : leases.serverId());
    }

    @Override
    public CompletableFuture<List<WorldTaskDto>> listOpenForServer(String serverId) {
        return delegate.listOpenForServer(serverId != null ? serverId : leases.serverId());
    }

    /** Done (or refused for good: 409 moved on, 422 result refused and the task failed): nothing left to renew. */
    private void settled(int id, Throwable error) {
        if (error == null) {
            leases.forget(id);
            return;
        }
        int status = WorldTaskApiErrors.status(error);
        if (status == 409 || status == 404 || status == 422) {
            leases.forget(id);
            return;
        }
        // The result didn't reach the API (down, timeout). A player handler keeps its session so the player can save
        // again; a headless scan has nobody to retry it, so it goes back to Pending and the poller runs it again.
        leases.held().stream()
            .filter(task -> task.taskId() == id && task.playerId() == null)
            .findFirst()
            .ifPresent(task -> leases.release(id, "The game server could not report the scan result"));
    }

    private static UUID parseUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(uuid.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
