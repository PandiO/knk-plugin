package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.ClaimTaskDto;
import net.knightsandkings.knk.api.dto.CompleteTaskDto;
import net.knightsandkings.knk.api.dto.FailTaskDto;
import net.knightsandkings.knk.api.dto.WorldTaskDto;
import net.knightsandkings.knk.api.dto.WorldTaskLeaseDto;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

/**
 * Implementation of WorldTasksApi for communicating with the Web API.
 */
public class WorldTasksApiImpl extends BaseApiImpl implements WorldTasksApi {
    private static final Logger LOGGER = Logger.getLogger(WorldTasksApiImpl.class.getName());
    private static final String WORLD_TASKS_ENDPOINT = "/WorldTasks";

    public WorldTasksApiImpl(
            String baseUrl,
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            AuthProvider authProvider,
            ExecutorService executor,
            boolean debugLogging
    ) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<List<WorldTaskDto>> listByStatus(String status) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + WORLD_TASKS_ENDPOINT + "/status/" + status;
            try {
                LOGGER.fine("Fetching tasks with status: " + status);
                String responseJson = get(url);
                return objectMapper.readValue(responseJson, new TypeReference<List<WorldTaskDto>>() {});
            } catch (ApiException | IOException e) {
                LOGGER.severe("Failed to list tasks by status: " + e.getMessage());
                throw new RuntimeException("Failed to list tasks by status: " + status, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<WorldTaskDto> getByLinkCode(String linkCode) {
        return CompletableFuture.supplyAsync(() -> {
            // #180: task codes stay out of the log (BaseApiImpl.loggableUrl redacts the path segment too).
            String url = baseUrl + WORLD_TASKS_ENDPOINT + "/by-link-code/"
                + URLEncoder.encode(linkCode == null ? "" : linkCode.trim(), StandardCharsets.UTF_8);
            try {
                LOGGER.fine("Fetching task by task code");
                String responseJson = get(url);
                return objectMapper.readValue(responseJson, WorldTaskDto.class);
            } catch (ApiException e) {
                if (e.getStatusCode() == 404) {
                    return null;
                }
                LOGGER.severe("Failed to get task by task code: " + e.getMessage());
                throw new RuntimeException("Failed to get task by task code", e);
            } catch (IOException e) {
                LOGGER.severe("Failed to parse task response: " + e.getMessage());
                throw new RuntimeException("Failed to get task by task code", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<WorldTaskDto> getById(int id) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + WORLD_TASKS_ENDPOINT + "/" + id;
            try {
                LOGGER.fine("Fetching task by ID: " + id);
                String responseJson = get(url);
                return objectMapper.readValue(responseJson, WorldTaskDto.class);
            } catch (ApiException e) {
                if (e.getStatusCode() == 404) {
                    return null;
                }
                LOGGER.severe("Failed to get task by ID: " + e.getMessage());
                throw new RuntimeException("Failed to get task by ID: " + id, e);
            } catch (IOException e) {
                LOGGER.severe("Failed to parse task response: " + e.getMessage());
                throw new RuntimeException("Failed to get task by ID: " + id, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<WorldTaskDto> claim(int id, String linkCode, String serverId, String minecraftUsername) {
        return claim(id, new ClaimTaskDto(linkCode, serverId, minecraftUsername));
    }

    @Override
    public CompletableFuture<WorldTaskDto> claim(int id, ClaimTaskDto claimDto) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + WORLD_TASKS_ENDPOINT + "/" + id + "/claim";
            try {
                String bodyJson = objectMapper.writeValueAsString(claimDto);
                LOGGER.fine("Claiming task " + id + " (" + claimDto.claimMode() + ") for " + claimDto.minecraftUsername()
                    + " on server " + claimDto.serverId());
                // The body carries the task code: never logged.
                String responseJson = postJson(url, bodyJson, false);
                return objectMapper.readValue(responseJson, WorldTaskDto.class);
            } catch (ApiException e) {
                LOGGER.warning("Failed to claim task " + id + ": " + e.getMessage());
                throw e;
            } catch (IOException e) {
                LOGGER.severe("Failed to claim task: " + e.getMessage());
                throw new RuntimeException("Failed to claim task: " + id, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<WorldTaskDto> complete(int id, String outputJson) {
        return complete(id, outputJson, null);
    }

    @Override
    public CompletableFuture<WorldTaskDto> complete(int id, String outputJson, String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + WORLD_TASKS_ENDPOINT + "/" + id + "/complete";
            try {
                CompleteTaskDto completeDto = new CompleteTaskDto(outputJson, serverId);
                String bodyJson = objectMapper.writeValueAsString(completeDto);
                LOGGER.fine("Completing task " + id + " with output: " + snippet(outputJson));
                String responseJson = postJson(url, bodyJson);
                return objectMapper.readValue(responseJson, WorldTaskDto.class);
            } catch (ApiException e) {
                LOGGER.severe("Failed to complete task " + id + ": " + e.getMessage());
                throw e;
            } catch (IOException e) {
                LOGGER.severe("Failed to complete task: " + e.getMessage());
                throw new RuntimeException("Failed to complete task: " + id, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<WorldTaskDto> fail(int id, String errorMessage) {
        return fail(id, errorMessage, null);
    }

    @Override
    public CompletableFuture<WorldTaskDto> fail(int id, String errorMessage, String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + WORLD_TASKS_ENDPOINT + "/" + id + "/fail";
            try {
                FailTaskDto failDto = new FailTaskDto(errorMessage, serverId);
                String bodyJson = objectMapper.writeValueAsString(failDto);
                LOGGER.fine("Failing task " + id + " with error: " + errorMessage);
                String responseJson = postJson(url, bodyJson);
                return objectMapper.readValue(responseJson, WorldTaskDto.class);
            } catch (ApiException e) {
                LOGGER.severe("Failed to fail task " + id + ": " + e.getMessage());
                throw e;
            } catch (IOException e) {
                LOGGER.severe("Failed to fail task: " + e.getMessage());
                throw new RuntimeException("Failed to fail task: " + id, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<WorldTaskDto> release(int id, String serverId, String reason) {
        return postLease(id, "release", new WorldTaskLeaseDto(serverId, reason));
    }

    @Override
    public CompletableFuture<WorldTaskDto> cancel(int id, String reason) {
        return postLease(id, "cancel", new WorldTaskLeaseDto(null, reason));
    }

    @Override
    public CompletableFuture<WorldTaskDto> heartbeat(int id, String serverId) {
        return postLease(id, "heartbeat", new WorldTaskLeaseDto(serverId, null));
    }

    @Override
    public CompletableFuture<List<WorldTaskDto>> listOpenForServer(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + WORLD_TASKS_ENDPOINT + "/open?serverId="
                + URLEncoder.encode(serverId == null ? "" : serverId, StandardCharsets.UTF_8);
            try {
                String responseJson = get(url);
                return objectMapper.readValue(responseJson, new TypeReference<List<WorldTaskDto>>() {});
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                throw new RuntimeException("Failed to list the tasks this server holds", e);
            }
        }, executor);
    }

    /** #180: release / cancel / heartbeat. ApiException (with its status, 409 when the task moved on) is passed through. */
    private CompletableFuture<WorldTaskDto> postLease(int id, String action, WorldTaskLeaseDto body) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + WORLD_TASKS_ENDPOINT + "/" + id + "/" + action;
            try {
                String responseJson = postJson(url, objectMapper.writeValueAsString(body));
                return objectMapper.readValue(responseJson, WorldTaskDto.class);
            } catch (ApiException e) {
                LOGGER.fine("World task " + id + " " + action + " refused: " + e.getMessage());
                throw e;
            } catch (IOException e) {
                throw new RuntimeException("Failed to " + action + " task " + id, e);
            }
        }, executor);
    }
}
