package net.knightsandkings.knk.paper.tasks;

import net.knightsandkings.knk.core.exception.ApiException;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PandiO/knk-workspace#180: reads the API's answer out of a failed world-task call (the exception arrives wrapped in
 * CompletionException/RuntimeException). 409 = the task moved on (claimed by someone else, cancelled, released,
 * completed); its body names the current status.
 */
public final class WorldTaskApiErrors {
    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"([A-Za-z]+)\"");
    private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private WorldTaskApiErrors() {
    }

    public static Optional<ApiException> apiException(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof ApiException api) {
                return Optional.of(api);
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    /** The HTTP status, or -1 when the call didn't get an answer (API down, timeout). */
    public static int status(Throwable error) {
        return apiException(error).map(ApiException::getStatusCode).orElse(-1);
    }

    /** The task is no longer this server's to work on: 409 (moved on) or 404 (deleted). */
    public static boolean taskMovedOn(Throwable error) {
        int status = status(error);
        return status == 409 || status == 404;
    }

    /** The task's current status from a 409 body ({@code "status":"Cancelled"}), if the API sent one. */
    public static Optional<String> currentStatus(Throwable error) {
        return apiException(error).map(ApiException::getResponseBody).flatMap(body -> find(STATUS, body));
    }

    /** The API's message from a 4xx body, for the player. */
    public static Optional<String> message(Throwable error) {
        return apiException(error).map(ApiException::getResponseBody).flatMap(body -> find(MESSAGE, body))
            .map(text -> text.replace("\\\"", "\""));
    }

    private static Optional<String> find(Pattern pattern, String body) {
        if (body == null) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(body);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
