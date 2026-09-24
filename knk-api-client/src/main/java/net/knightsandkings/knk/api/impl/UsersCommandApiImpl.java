package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.CoinsUpdateDto;
import net.knightsandkings.knk.api.dto.ActiveModeUpdateDto;
import net.knightsandkings.knk.api.dto.GatePassThroughMethodUpdateDto;
import net.knightsandkings.knk.api.dto.SalaryPayoutResultDto;
import net.knightsandkings.knk.api.dto.UserCreateDto;
import net.knightsandkings.knk.api.dto.UserDto;
import net.knightsandkings.knk.api.mapper.UsersMapper;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.SalaryPayoutResult;
import net.knightsandkings.knk.core.domain.users.UserDetail;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import okhttp3.OkHttpClient;

/**
 * Command-side implementation for user write operations.
 * NOTE: Enable only when migration mode allows writes.
 */
public class UsersCommandApiImpl extends BaseApiImpl implements UsersCommandApi {
    private static final Logger LOGGER = Logger.getLogger(UsersCommandApiImpl.class.getName());

    // Swagger shows /api/Users; baseUrl is expected to already include /api
    private static final String USERS_ENDPOINT = "/Users";

    public UsersCommandApiImpl(
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
    public CompletableFuture<Void> setCoinsById(int id, int coins) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + USERS_ENDPOINT + "/" + id + "/coins";
            try {
                String bodyJson = objectMapper.writeValueAsString(new CoinsUpdateDto(coins));
                putJson(url, bodyJson);
                return null;
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to set coins by ID", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> setCoinsByUuid(UUID uuid, int coins) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + USERS_ENDPOINT + "/" + uuid + "/coins";
            try {
                String bodyJson = objectMapper.writeValueAsString(new CoinsUpdateDto(coins));
                putJson(url, bodyJson);
                return null;
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to set coins by UUID", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> setGatePassThroughMethodById(int id, GatePassThroughMethod method) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + USERS_ENDPOINT + "/" + id + "/gate-passthrough-method";
            try {
                String bodyJson = objectMapper.writeValueAsString(
                    new GatePassThroughMethodUpdateDto(method.toWireValue()));
                putJson(url, bodyJson);
                return null;
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to set gate pass-through method", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> setActiveModeById(int id, ActiveMode mode) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + USERS_ENDPOINT + "/" + id + "/active-mode";
            try {
                String bodyJson = objectMapper.writeValueAsString(new ActiveModeUpdateDto(mode.toWireValue()));
                putJson(url, bodyJson);
                return null;
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to set active mode", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<SalaryPayoutResult> payOutSalaryById(int id) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + USERS_ENDPOINT + "/" + id + "/salary/payout";
            try {
                String responseJson = postJson(url, "{}");
                SalaryPayoutResultDto dto = objectMapper.readValue(responseJson, SalaryPayoutResultDto.class);
                return UsersMapper.mapSalaryPayoutResult(dto);
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to pay out salary", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<UserDetail> create(UserDetail user) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + USERS_ENDPOINT;
            try {
                UserCreateDto createDto = new UserCreateDto(
                    user.username(),
                    user.uuid(),
                    user.email(),
                    user.createdAt()
                );
                String bodyJson = objectMapper.writeValueAsString(createDto);
                LOGGER.fine("Creating user with payload: " + snippet(bodyJson));
                String responseJson = postJson(url, bodyJson);
                UserDto dto = objectMapper.readValue(responseJson, UserDto.class);
                return UsersMapper.mapUserDetail(dto);
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to create user", e);
            }
        }, executor);
    }
}
