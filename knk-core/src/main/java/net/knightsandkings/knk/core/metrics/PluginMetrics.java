package net.knightsandkings.knk.core.metrics;

/**
 * The plugin's own instruments (KNG-232), shared by the API client and the Paper module. They record only
 * after {@link #setEnabled(boolean) enabling}: with {@code metrics.enabled: false} every call returns at once.
 *
 * <ul>
 *   <li>{@link #API_CALLS}: duration of each REST call to knk-web-api by method, route template (ids
 *       replaced, see {@code ApiRouteTemplates}) and outcome class.</li>
 *   <li>{@link #TASKS}: duration of the plugin's own recurring tasks by name, via {@link #timed}. A
 *       synchronous task's seconds-per-second is its share of the main thread.</li>
 * </ul>
 */
public final class PluginMetrics {

    public static final LabeledHistogram API_CALLS = new LabeledHistogram("knk_api_call_duration_seconds",
        "Duration of REST calls from the plugin to knk-web-api.", "method", "route", "outcome");

    public static final LabeledHistogram TASKS = new LabeledHistogram("knk_task_duration_seconds",
        "Duration of the plugin's recurring tasks (synchronous ones run on the main thread).", "task");

    static {
        API_CALLS.setEnabled(false);
        TASKS.setEnabled(false);
    }

    private PluginMetrics() {
    }

    public static void setEnabled(boolean enabled) {
        API_CALLS.setEnabled(enabled);
        TASKS.setEnabled(enabled);
    }

    /** Wraps {@code task} so each run is recorded under {@code name}. Safe to use while metrics are off. */
    public static Runnable timed(String name, Runnable task) {
        return () -> TASKS.time(task, name);
    }

    /** {@code 2xx}, {@code 3xx}, {@code 4xx}, {@code 5xx}, or {@code error} when no response arrived (status 0). */
    public static String outcome(int status) {
        if (status < 100 || status > 599) {
            return "error";
        }
        return (status / 100) + "xx";
    }

    /** Records one REST call. */
    public static void apiCall(String method, String route, int status, long nanos) {
        API_CALLS.observe(nanos / 1e9, method, route, outcome(status));
    }
}
