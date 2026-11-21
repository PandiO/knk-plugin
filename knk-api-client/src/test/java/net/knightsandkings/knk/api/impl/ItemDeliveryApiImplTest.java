package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.ApiKeyAuthProvider;
import net.knightsandkings.knk.api.dto.PlayerNotificationDto;
import net.knightsandkings.knk.api.mapper.UsersMapper;
import net.knightsandkings.knk.core.domain.users.PlayerNotification;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.production.inbox.ClaimResult;
import net.knightsandkings.knk.core.production.inbox.DeliveryRequest;
import net.knightsandkings.knk.core.production.inbox.DeliveryResult;
import net.knightsandkings.knk.core.production.inbox.InboxNotice;
import net.knightsandkings.knk.core.production.inbox.InboxView;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/** PandiO/knk-workspace#684: the delivery and inbox routes of knk-web-api P7 (#60, contract comment on #684). */
class ItemDeliveryApiImplTest {

    private static final String VIEW = """
            {"userId":3,"purpose":"Inbox","storageId":40,"usedUnits":120.5,"capUnits":2304,"fillPercent":5,
             "warningPercent":null,"premiumTierGroupId":null,"isLocked":false,"entries":[
              {"itemBlueprintId":11,"itemBlueprintName":"Bread","itemInstanceId":null,"customDisplayName":null,"amount":64,
               "nextExpiresAt":"2026-12-21T10:00:00","nextExpiringAmount":64},
              {"itemBlueprintId":12,"itemBlueprintName":"Sword","itemInstanceId":900,"customDisplayName":"Excalibur","amount":1,
               "nextExpiresAt":"2026-12-22T10:00:00Z","nextExpiringAmount":1},
              {"itemBlueprintId":13,"itemBlueprintName":"Gone","amount":0}]}
            """;

    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final List<Integer> statuses = new ArrayList<>();
    private final List<String> replies = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        seen.add(chain.request());
        if (chain.request().body() != null) {
            Buffer buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            bodies.add(buffer.readUtf8());
        }
        int i = Math.min(seen.size() - 1, statuses.size() - 1);
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(statuses.get(i)).message("x")
                .body(ResponseBody.create(replies.get(i), MediaType.get("application/json"))).build();
    }).build();
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final ItemDeliveryApiImpl api = new ItemDeliveryApiImpl("http://api.test/api", client, mapper,
            new ApiKeyAuthProvider("secret"), executor, false);

    private void reply(int status, String body) {
        statuses.add(status);
        replies.add(body);
    }

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void deliver_postsTheContractBody_andReadsTheResult() throws Exception {
        reply(200, """
                {"id":55,"status":"PartlyLost","inboxedUnits":10,"lostUnits":2,"capUnits":2304,
                 "expiresAt":"2026-12-21T10:00:00Z","replayed":true,"lines":[]}
                """);
        DeliveryResult result = api.deliver(new DeliveryRequest(3, DeliveryRequest.Reason.KIT, "Kit", "Starter", null,
                "kit-claim-12", List.of(DeliveryRequest.Line.of(11, 12), DeliveryRequest.Line.unique(900)))).join();

        assertEquals("POST", seen.get(0).method());
        assertEquals("http://api.test/api/ItemDeliveries", seen.get(0).url().toString());
        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals(3, body.get("userId").asInt());
        assertEquals("Kit", body.get("reason").asText());
        assertEquals("kit-claim-12", body.get("idempotencyKey").asText());
        assertEquals(11, body.get("lines").get(0).get("itemBlueprintId").asInt());
        assertEquals(12, body.get("lines").get(0).get("amount").asLong());
        assertFalse(body.get("lines").get(0).has("itemInstanceId"));
        assertEquals(900, body.get("lines").get(1).get("itemInstanceId").asLong());
        assertFalse(body.get("lines").get(1).has("itemBlueprintId"));
        assertFalse(body.has("note"), "nulls are left out");

        assertEquals(55, result.deliveryId());
        assertEquals("PartlyLost", result.status());
        assertEquals(10, result.inboxedUnits());
        assertEquals(2, result.lostUnits());
        assertEquals(Instant.parse("2026-12-21T10:00:00Z"), result.expiresAt());
        assertTrue(result.replayed());
    }

    @Test
    void deliver_409ProductionDisabled_carriesTheStatusAndBody() {
        reply(409, "{\"code\":\"ProductionDisabled\",\"error\":\"off\"}");
        CompletionException ex = assertThrows(CompletionException.class, () -> api.deliver(new DeliveryRequest(3,
                DeliveryRequest.Reason.LOOTBOX, null, null, null, "k", List.of(DeliveryRequest.Line.of(1, 1)))).join());
        ApiException api = (ApiException) ex.getCause();
        assertEquals(409, api.getStatusCode());
        assertTrue(api.getResponseBody().contains("ProductionDisabled"));
    }

    @Test
    void inbox_readsTheView_datesWithAndWithoutZone_dropsEmptyEntries() {
        reply(200, VIEW);
        InboxView view = api.inbox(3).join();
        assertEquals("GET", seen.get(0).method());
        assertEquals("http://api.test/api/Inbox/3", seen.get(0).url().toString());
        assertEquals(40, view.storageId());
        assertEquals(121, view.usedUnits());
        assertEquals(2304, view.capUnits());
        assertNull(view.warningPercent());
        assertEquals(2, view.entries().size());
        assertEquals(Instant.parse("2026-12-21T10:00:00Z"), view.entries().get(0).nextExpiresAt());
        assertEquals(Instant.parse("2026-12-22T10:00:00Z"), view.entries().get(1).nextExpiresAt());
        assertTrue(view.entries().get(1).unique());
        assertEquals("Excalibur", view.entries().get(1).displayName());
    }

    @Test
    void claim_actsForThePlayer_andReadsAcceptedReasonsAndView() throws Exception {
        reply(200, """
                {"storageId":40,"result":{"status":"PartiallyApplied","replayed":false,"accepted":20,
                  "lines":[{"lineNo":1,"requested":30,"accepted":20,"rejected":10,"rejectReason":"NotEnoughStock"}]},
                 "view":""" + VIEW + "}");
        ClaimResult result = api.claim(3, "inbox-claim-u3-abc", List.of(DeliveryRequest.Line.of(11, 30))).join();
        assertEquals("http://api.test/api/Inbox/3/claim", seen.get(0).url().toString());
        assertEquals("3", seen.get(0).header("X-Acting-User-Id"));
        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals("inbox-claim-u3-abc", body.get("idempotencyKey").asText());
        assertEquals(30, body.get("lines").get(0).get("amount").asLong());
        assertEquals(20, result.accepted());
        assertEquals(List.of("NotEnoughStock"), result.reasons());
        assertEquals("PartiallyApplied", result.status());
        assertEquals(2, result.view().entries().size());
    }

    @Test
    void notification_inboxPayload_isMapped() throws Exception {
        PlayerNotificationDto dto = mapper.readValue("""
                {"id":1,"userId":3,"uuid":null,"username":"Ann","type":"InboxUpdated",
                 "inbox":{"event":"Delivered","deliveryId":55,"reason":"Kit","inboxedUnits":10,"lostUnits":2,
                          "warningPercent":95,"fillPercent":96,"capUnits":2304,"expiringUnits":0,"expiresAt":null}}
                """, PlayerNotificationDto.class);
        PlayerNotification notification = UsersMapper.mapPlayerNotification(dto);
        assertEquals(PlayerNotification.TYPE_INBOX_UPDATED, notification.type());
        InboxNotice notice = notification.inbox();
        assertTrue(notice.delivered());
        assertEquals(55L, notice.deliveryId());
        assertEquals(2, notice.lostUnits());
        assertEquals(95, notice.warningPercent());
        assertNull(UsersMapper.mapPlayerNotification(mapper.readValue(
                "{\"id\":2,\"userId\":3,\"username\":\"Ann\",\"type\":\"RankChanged\"}", PlayerNotificationDto.class)).inbox());
    }

    @Test
    void instant_parsesBothForms_andNullForJunk() {
        assertEquals(Instant.parse("2026-12-21T10:00:00Z"), ItemDeliveryApiImpl.instant("2026-12-21T10:00:00"));
        assertEquals(Instant.parse("2026-12-21T09:00:00Z"), ItemDeliveryApiImpl.instant("2026-12-21T10:00:00+01:00"));
        assertNull(ItemDeliveryApiImpl.instant("soon"));
        assertNull(ItemDeliveryApiImpl.instant(null));
    }
}
