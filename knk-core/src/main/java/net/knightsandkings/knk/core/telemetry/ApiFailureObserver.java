package net.knightsandkings.knk.core.telemetry;

/**
 * Told about every failed plugin → API call (KNG-34 link 6, {@code api.call_failed}): an HTTP error
 * status or an I/O failure. Called on the API client's worker thread; must not block or throw.
 */
@FunctionalInterface
public interface ApiFailureObserver {

    ApiFailureObserver NONE = (method, routeTemplate, status, exceptionType, correlationId) -> { };

    /**
     * @param routeTemplate the path with values replaced ({@link ApiRouteTemplates#template})
     * @param status        the HTTP status, or 0 when no answer arrived
     * @param exceptionType the exception's simple class name for I/O failures, else null
     * @param correlationId the action's correlation id, or null
     */
    void callFailed(String method, String routeTemplate, int status, String exceptionType, String correlationId);
}
