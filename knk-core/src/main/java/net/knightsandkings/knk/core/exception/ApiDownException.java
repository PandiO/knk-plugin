package net.knightsandkings.knk.core.exception;

/**
 * A read was not sent to knk-web-api because the connectivity state is DOWN (KNG-171). It takes
 * the place of the connection error the call would have ended with, so callers handle it the same
 * way (cache fallback, offline store, "service unavailable"). No stack trace: it is expected and
 * can occur on every cache miss during an outage.
 */
public class ApiDownException extends ApiException {

    public ApiDownException(String what) {
        super("knk-web-api is DOWN; " + what + " not requested");
    }

    /** True when {@code error} or one of its causes is an {@link ApiDownException}. */
    public static boolean isApiDown(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ApiDownException) {
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
