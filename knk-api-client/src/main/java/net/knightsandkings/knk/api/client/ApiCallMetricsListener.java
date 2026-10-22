package net.knightsandkings.knk.api.client;

import java.io.IOException;

import net.knightsandkings.knk.core.metrics.PluginMetrics;
import net.knightsandkings.knk.core.telemetry.ApiRouteTemplates;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Response;

/**
 * Times every REST call the plugin makes to knk-web-api for {@code knk_api_call_duration_seconds} (KNG-232),
 * from {@code callStart} to {@code callEnd}, so the response body is included. Installed on the OkHttp client, so
 * it also covers the API classes that call the client directly instead of through {@code BaseApiImpl}. The
 * route is a template ({@link ApiRouteTemplates}: ids, codes and names replaced), never the raw URL.
 * One instance per call (OkHttp's {@link EventListener.Factory}); nothing happens while metrics are off.
 */
final class ApiCallMetricsListener extends EventListener {

    static final EventListener.Factory FACTORY = call -> new ApiCallMetricsListener();

    private long startNanos;
    private int status;

    @Override
    public void callStart(Call call) {
        startNanos = System.nanoTime();
    }

    @Override
    public void responseHeadersEnd(Call call, Response response) {
        status = response.code();
    }

    @Override
    public void callEnd(Call call) {
        record(call, status);
    }

    @Override
    public void callFailed(Call call, IOException ioe) {
        record(call, 0);
    }

    private void record(Call call, int outcomeStatus) {
        if (!PluginMetrics.API_CALLS.isEnabled() || startNanos == 0) {
            return;
        }
        PluginMetrics.apiCall(call.request().method(), ApiRouteTemplates.template(call.request().url().toString()),
            outcomeStatus, System.nanoTime() - startNanos);
    }
}
