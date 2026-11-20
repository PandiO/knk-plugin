package net.knightsandkings.knk.api.impl;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Shared bits of the P6 terminal and menu clients (PandiO/knk-workspace#74): a request that acts for a player
 * ({@code X-Acting-User-Id}) and a quiet call whose expected statuses (404 for an unbound block) are not logged as API
 * errors, because every chest a player opens asks.
 */
final class ProductionHttp {

    static final MediaType JSON = MediaType.get("application/json");

    /** The status and body of a call; the body is empty for 204. */
    record Reply(int code, String body) {
        boolean ok() {
            return code >= 200 && code < 300;
        }
    }

    private ProductionHttp() {
    }

    static Request.Builder acting(Request.Builder builder, Integer actingUserId) {
        if (actingUserId != null) {
            builder.header(UsersCommandApiImpl.ACTING_USER_HEADER, String.valueOf(actingUserId));
        }
        return builder;
    }

    static Request.Builder jsonPost(Request.Builder builder, String json) {
        return builder.addHeader("Content-Type", "application/json").addHeader("Accept", "application/json")
                .post(RequestBody.create(json, JSON));
    }

    /** Runs the call without the error logging of {@code BaseApiImpl.execute}; the caller decides what a status means. */
    static Reply quiet(OkHttpClient client, Request request) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            return new Reply(response.code(), response.body() != null ? response.body().string() : "");
        }
    }
}
