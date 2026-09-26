package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.AdjustBalancesDto;
import net.knightsandkings.knk.api.dto.BalanceAdjustmentResultDto;
import net.knightsandkings.knk.api.dto.ActiveModeUpdateDto;
import net.knightsandkings.knk.api.dto.FreezePlayerDto;
import net.knightsandkings.knk.api.dto.GatePassThroughMethodUpdateDto;
import net.knightsandkings.knk.api.dto.PresenceUpdateDto;
import net.knightsandkings.knk.api.dto.SalaryPayoutResultDto;
import net.knightsandkings.knk.api.dto.TeleportAuditDto;
import net.knightsandkings.knk.api.dto.UpsertGroupMembershipDto;
import net.knightsandkings.knk.api.dto.UpsertPermissionGrantByNodeDto;
import net.knightsandkings.knk.api.dto.UserCreateDto;
import net.knightsandkings.knk.api.dto.UserDto;
import net.knightsandkings.knk.api.mapper.UsersMapper;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.BalanceAdjustmentResult;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.SalaryPayoutResult;
import net.knightsandkings.knk.core.domain.users.UserDetail;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.core.teleport.TeleportAudit;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * Command-side implementation for user write operations.
 * NOTE: Enable only when migration mode allows writes.
 */
public class UsersCommandApiImpl extends BaseApiImpl implements UsersCommandApi {
    private static final Logger LOGGER = Logger.getLogger(UsersCommandApiImpl.class.getName());

    // Swagger shows /api/Users; baseUrl is expected to already include /api
    private static final String USERS_ENDPOINT = "/Users";

    /** InventoryMenu content port CP7: header naming the in-game staff member a request acts for. */
    public static final String ACTING_USER_HEADER = "X-Acting-User-Id";

    /** Null for the plain instance; set on instances made by {@link #withActor(int)}. */
    private final Integer actingUserId;

    public UsersCommandApiImpl(
        String baseUrl,
        OkHttpClient httpClient,
        ObjectMapper objectMapper,
        AuthProvider authProvider,
        ExecutorService executor,
        boolean debugLogging
    ) {
        this(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging, null);
    }

    private UsersCommandApiImpl(
        String baseUrl,
        OkHttpClient httpClient,
        ObjectMapper objectMapper,
        AuthProvider authProvider,
        ExecutorService executor,
        boolean debugLogging,
        Integer actingUserId
    ) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.actingUserId = actingUserId;
    }

    @Override
    public UsersCommandApi withActor(int actorUserId) {
        return new UsersCommandApiImpl(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging, actorUserId);
    }

    @Override
    protected Request.Builder newRequest(String url) {
        Request.Builder builder = super.newRequest(url);
        if (actingUserId != null) {
            builder.header(ACTING_USER_HEADER, String.valueOf(actingUserId));
        }
        return builder;
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
    public CompletableFuture<Void> setPresenceById(int id, boolean isOnline) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + USERS_ENDPOINT + "/" + id + "/presence";
            try {
                String bodyJson = objectMapper.writeValueAsString(new PresenceUpdateDto(isOnline));
                putJson(url, bodyJson);
                return null;
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to set presence", e);
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
    public CompletableFuture<BalanceAdjustmentResult> adjustBalancesById(int id, int coinsDelta, int gemsDelta, int experienceDelta, String reason, boolean notifyPlayer) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + USERS_ENDPOINT + "/" + id + "/balances";
            try {
                String bodyJson = objectMapper.writeValueAsString(new AdjustBalancesDto(coinsDelta, gemsDelta, experienceDelta, reason, notifyPlayer));
                String responseJson = putJson(url, bodyJson);
                BalanceAdjustmentResultDto dto = objectMapper.readValue(responseJson, BalanceAdjustmentResultDto.class);
                return UsersMapper.mapBalanceAdjustmentResult(dto);
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to adjust balances", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> addGroupMembership(int userId, int groupId, OffsetDateTime expiresAt) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/UserPermissionGroups";
            try {
                String bodyJson = objectMapper.writeValueAsString(new UpsertGroupMembershipDto(userId, groupId, expiresAt));
                putJson(url, bodyJson);
                return null;
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to add group membership", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> removeGroupMembership(int userId, int groupId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/UserPermissionGroups/" + userId + "/" + groupId;
            try {
                delete(url);
                return null;
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to remove group membership", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> grantPermission(int userId, String node, OffsetDateTime expiresAt) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/PermissionGrants/by-node";
            try {
                String bodyJson = objectMapper.writeValueAsString(new UpsertPermissionGrantByNodeDto(userId, node, true, expiresAt));
                putJson(url, bodyJson);
                return null;
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to grant permission", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> revokePermission(int userId, String node) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/PermissionGrants/by-node?holderId=" + userId + "&node=" + node;
            try {
                delete(url);
                return null;
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to revoke permission", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> freezeById(int userId, String reason) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + USERS_ENDPOINT + "/" + userId + "/freeze";
            try {
                String bodyJson = objectMapper.writeValueAsString(new FreezePlayerDto(reason));
                putJson(url, bodyJson);
                return null;
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to freeze player", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> unfreezeById(int userId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + USERS_ENDPOINT + "/" + userId + "/unfreeze";
            try {
                putJson(url, "{}");
                return null;
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to unfreeze player", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> recordTeleportAudit(TeleportAudit audit) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + USERS_ENDPOINT + "/" + audit.targetUserId() + "/teleport-audit";
            try {
                String bodyJson = objectMapper.writeValueAsString(new TeleportAuditDto(
                    audit.kind().name(),
                    audit.subjectUserId(),
                    audit.visitedUserId(),
                    toPoint(audit.from()),
                    toPoint(audit.to()),
                    audit.silent(),
                    audit.reason(),
                    audit.console() ? "console" : "command"
                ));
                postJson(url, bodyJson);
                return null;
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to record teleport audit", e);
            }
        }, executor);
    }

    private static TeleportAuditDto.Point toPoint(TeleportAudit.Point point) {
        return new TeleportAuditDto.Point(point.world(), point.x(), point.y(), point.z());
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
