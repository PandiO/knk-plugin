package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.ShopDtos;
import net.knightsandkings.knk.api.mapper.ShopMapper;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.ShopsApi;
import net.knightsandkings.knk.core.production.shop.ShopOffer;
import net.knightsandkings.knk.core.production.shop.ShopPurchaseRequest;
import net.knightsandkings.knk.core.production.shop.ShopPurchaseResult;
import net.knightsandkings.knk.core.production.shop.ShopQuote;
import net.knightsandkings.knk.core.production.shop.ShopSellQuote;
import net.knightsandkings.knk.core.production.shop.ShopSellRequest;
import net.knightsandkings.knk.core.production.shop.ShopSellResult;
import okhttp3.OkHttpClient;

/**
 * Buying from and selling to shops (PandiO/knk-workspace#687): {@code api/Shops/{id}/offer}, {@code quote},
 * {@code purchase}, {@code sell-quote} and {@code sell} of knk-web-api P10 (#248), with the plugin key.
 */
public class ShopsApiImpl extends BaseApiImpl implements ShopsApi {

    static final String SHOPS = "/Shops";

    public ShopsApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper, AuthProvider authProvider,
                        ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<ShopOffer> offer(int shopId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = url(shopId, "offer");
            try {
                return ShopMapper.toOffer(parse(get(url), ShopDtos.OfferDto.class, url), shopId);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read the offer of shop " + shopId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<ShopQuote> quote(int shopId, ShopPurchaseRequest request) {
        return post(shopId, "quote", ShopMapper.toDto(request), ShopDtos.QuoteDto.class)
                .thenApply(dto -> ShopMapper.toQuote(dto, shopId));
    }

    @Override
    public CompletableFuture<ShopPurchaseResult> purchase(int shopId, ShopPurchaseRequest request) {
        return post(shopId, "purchase", ShopMapper.toDto(request), ShopDtos.PurchaseResultDto.class)
                .thenApply(dto -> ShopMapper.toPurchase(dto, shopId));
    }

    @Override
    public CompletableFuture<ShopSellQuote> sellQuote(int shopId, ShopSellRequest request) {
        return post(shopId, "sell-quote", ShopMapper.toDto(request), ShopDtos.SellQuoteDto.class)
                .thenApply(dto -> ShopMapper.toSellQuote(dto, shopId));
    }

    @Override
    public CompletableFuture<ShopSellResult> sell(int shopId, ShopSellRequest request) {
        return post(shopId, "sell", ShopMapper.toDto(request), ShopDtos.SellResultDto.class)
                .thenApply(dto -> ShopMapper.toSellResult(dto, shopId));
    }

    private <T> CompletableFuture<T> post(int shopId, String route, Object body, Class<T> type) {
        return CompletableFuture.supplyAsync(() -> {
            String url = url(shopId, route);
            try {
                return parse(postJson(url, objectMapper.writeValueAsString(body)), type, url);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to write the shop request", e);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to call " + route + " of shop " + shopId, e);
            }
        }, executor);
    }

    private String url(int shopId, String route) {
        return baseUrl + SHOPS + "/" + shopId + "/" + route;
    }
}
