package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.production.shop.ShopItemRef;
import net.knightsandkings.knk.core.production.shop.ShopOffer;
import net.knightsandkings.knk.core.production.shop.ShopPurchaseRequest;
import net.knightsandkings.knk.core.production.shop.ShopPurchaseResult;
import net.knightsandkings.knk.core.production.shop.ShopQuote;
import net.knightsandkings.knk.core.production.shop.ShopRefusal;
import net.knightsandkings.knk.core.production.shop.ShopSellLine;
import net.knightsandkings.knk.core.production.shop.ShopSellQuote;
import net.knightsandkings.knk.core.production.shop.ShopSellRequest;
import net.knightsandkings.knk.core.production.shop.ShopSellResult;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/** PandiO/knk-workspace#687: the shop routes of knk-web-api P10 (#248; API contract shops § 4). */
class ShopsApiImplTest {

    private static final String OFFER = """
            {"shopId":5,"shopName":"Bakery","kind":"Processing","level":2,"shopTypeName":"Bakery","townId":3,
             "townName":"Aldor","hideSoldOut":false,"buysFromPlayers":true,"items":[
              {"catalogueEntryId":12,"itemBlueprintId":11,"itemBlueprintName":"Bread","itemInstanceId":null,"unitPrice":4,
               "priceSource":"Static","stock":null,"soldOut":false,"pinned":true,"sortOrder":0,"enchantments":null},
              {"catalogueEntryId":null,"itemBlueprintId":20,"itemBlueprintName":"Sword","itemInstanceId":900,"unitPrice":150,
               "priceSource":"Formula","stock":1,"soldOut":false,"pinned":false,"sortOrder":2147483647,
               "enchantments":["Sharpness 2"]},
              {"catalogueEntryId":13,"itemBlueprintId":14,"itemBlueprintName":"Cake","unitPrice":null,"priceSource":"Formula"}]}
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
    private final ShopsApiImpl api = new ShopsApiImpl("http://api.test/api", client, mapper,
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
    void offer_readsTheShopAndLeavesOutItemsWithoutAPrice() {
        reply(200, OFFER);

        ShopOffer offer = api.offer(5).join();

        assertEquals("GET", seen.get(0).method());
        assertEquals("http://api.test/api/Shops/5/offer", seen.get(0).url().toString());
        assertEquals("secret", seen.get(0).header("X-API-Key"));
        assertEquals("Bakery", offer.shopName());
        assertEquals("Processing", offer.kind());
        assertEquals(2, offer.level());
        assertEquals("Aldor", offer.townName());
        assertTrue(offer.buysFromPlayers());
        assertEquals(2, offer.items().size());
        assertEquals(12, offer.items().get(0).catalogueEntryId());
        assertNull(offer.items().get(0).stock());
        assertTrue(offer.items().get(0).pinned());
        assertEquals(900L, offer.items().get(1).itemInstanceId());
        assertEquals(List.of("Sharpness 2"), offer.items().get(1).enchantments());
        assertEquals("i900", offer.items().get(1).ref().toString());
    }

    @Test
    void quote_sendsThePlayerTheItemAndTheAmountOnly() throws Exception {
        reply(200, """
                {"shopId":5,"catalogueEntryId":12,"itemBlueprintId":11,"itemBlueprintName":"Bread","amount":8,"unitPrice":4,
                 "totalPrice":32,"priceSource":"Static","stock":null,"refusal":null,"refusalMessage":null}
                """);

        ShopQuote quote = api.quote(5, ShopPurchaseRequest.quote(3, ShopItemRef.parse("e12"), 8)).join();

        assertEquals("http://api.test/api/Shops/5/quote", seen.get(0).url().toString());
        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals(3, body.get("userId").asInt());
        assertEquals(12, body.get("catalogueEntryId").asInt());
        assertEquals(8, body.get("amount").asLong());
        assertFalse(body.has("itemBlueprintId"));
        assertFalse(body.has("expectedUnitPrice"));
        assertFalse(body.has("idempotencyKey"));
        assertFalse(body.get("confirmed").asBoolean());
        assertTrue(quote.buyable());
        assertEquals(32, quote.totalPrice());
    }

    @Test
    void quote_carriesTheRefusal() {
        reply(200, """
                {"shopId":5,"itemBlueprintId":11,"amount":64,"unitPrice":4,"totalPrice":256,"stock":10,
                 "refusal":"NotEnoughStock","refusalMessage":"Only 10 left"}
                """);

        ShopQuote quote = api.quote(5, ShopPurchaseRequest.quote(3, ShopItemRef.parse("b11"), 64)).join();

        assertFalse(quote.buyable());
        assertEquals("NotEnoughStock", quote.refusal());
    }

    @Test
    void purchase_sendsThePriceSeenTheConfirmationTheTargetAndTheKey() throws Exception {
        reply(200, """
                {"shopId":5,"userId":3,"itemBlueprintId":11,"amount":8,"unitPrice":4,"totalPrice":32,"deliverTo":"Inbox",
                 "itemDeliveryId":77,"townId":3,"coinsAfter":968,"replayed":false}
                """);

        ShopPurchaseResult result = api.purchase(5, ShopPurchaseRequest.purchase(3, ShopItemRef.parse("e12"), 8, 4,
                ShopPurchaseRequest.DeliverTo.INBOX, "buy-s5-u3-abc")).join();

        assertEquals("http://api.test/api/Shops/5/purchase", seen.get(0).url().toString());
        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals(4, body.get("expectedUnitPrice").asLong());
        assertTrue(body.get("confirmed").asBoolean());
        assertEquals("Inbox", body.get("deliverTo").asText());
        assertEquals("buy-s5-u3-abc", body.get("idempotencyKey").asText());
        assertEquals(968L, result.coinsAfter());
        assertEquals(77L, result.itemDeliveryId());
    }

    @Test
    void purchase_refusalIsA409WithItsCode() {
        reply(409, "{\"code\":\"PriceChanged\",\"error\":\"The price is now 5\"}");

        CompletionException error = assertThrows(CompletionException.class, () -> api.purchase(5,
                ShopPurchaseRequest.purchase(3, ShopItemRef.parse("e12"), 1, 4, ShopPurchaseRequest.DeliverTo.INVENTORY,
                        "k")).join());

        ApiException api = (ApiException) error.getCause();
        assertEquals(409, api.getStatusCode());
        assertEquals(ShopRefusal.PRICE_CHANGED, ShopRefusal.fromBody(api.getResponseBody()));
    }

    @Test
    void sellQuote_sendsBlueprintAndUniqueLines() throws Exception {
        reply(200, """
                {"shopId":5,"lines":[
                  {"lineNo":0,"itemBlueprintId":11,"itemBlueprintName":"Bread","amount":20,"unitPrice":2,"linePrice":40},
                  {"lineNo":1,"itemBlueprintId":20,"itemInstanceId":900,"amount":1,"unitPrice":0,"linePrice":0,
                   "refusal":"CategoryNotAccepted","refusalMessage":"Not bought here"}],
                 "total":40,"accepted":false,"townCanPay":true}
                """);

        ShopSellQuote quote = api.sellQuote(5, ShopSellRequest.quote(3,
                List.of(ShopSellLine.blueprint(11, 20), ShopSellLine.instance(900)))).join();

        assertEquals("http://api.test/api/Shops/5/sell-quote", seen.get(0).url().toString());
        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals(11, body.get("lines").get(0).get("itemBlueprintId").asInt());
        assertEquals(20, body.get("lines").get(0).get("amount").asLong());
        assertFalse(body.get("lines").get(0).has("itemInstanceId"));
        assertEquals(900, body.get("lines").get(1).get("itemInstanceId").asLong());
        assertFalse(body.get("lines").get(1).has("itemBlueprintId"));
        assertFalse(body.has("expectedTotal"));
        assertFalse(quote.accepted());
        assertEquals(40, quote.total());
        assertTrue(quote.line(0).accepted());
        assertEquals("CategoryNotAccepted", quote.line(1).refusal());
    }

    @Test
    void sell_sendsTheTotalSeenAndTheKey() throws Exception {
        reply(200, """
                {"shopId":5,"userId":3,"total":40,"lines":[{"lineNo":0,"itemBlueprintId":11,"amount":20,"unitPrice":2,
                 "linePrice":40}],"stockTransactionIds":[1],"currencyTransactionIds":["x"],"coinsAfter":1040,"replayed":true}
                """);

        ShopSellResult result = api.sell(5, ShopSellRequest.sell(3, List.of(ShopSellLine.blueprint(11, 20)), 40,
                "sell-s5-u3-abc")).join();

        assertEquals("http://api.test/api/Shops/5/sell", seen.get(0).url().toString());
        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals(40, body.get("expectedTotal").asLong());
        assertTrue(body.get("confirmed").asBoolean());
        assertEquals("sell-s5-u3-abc", body.get("idempotencyKey").asText());
        assertEquals(40, result.total());
        assertEquals(1040L, result.coinsAfter());
        assertTrue(result.replayed());
    }
}
