package net.knightsandkings.knk.core.telemetry;

import java.util.Locale;
import java.util.Set;

/**
 * Turns a called API URL into a route template for {@code api.call_failed} (KNG-34 link 6): the
 * path only (no host, query or fragment), with every value-like segment replaced by {@code {id}} so
 * user ids, UUIDs, codes and looked-up usernames never reach a diagnostic event. A segment is kept
 * only when it is a word ({@code [A-Za-z][A-Za-z-]*}, e.g. {@code Users}, {@code by-name}) that doesn't
 * follow a name/uuid/code marker segment.
 */
public final class ApiRouteTemplates {

    /** Segments after which the next one is a player name or UUID even if it looks like a word. */
    private static final Set<String> VALUE_MARKERS = Set.of("username", "by-name", "by-username", "uuid", "by-uuid", "name",
        "by-link-code", "by-key", "link-code", "code", "token");

    private ApiRouteTemplates() {
    }

    public static String template(String url) {
        if (url == null || url.isBlank()) {
            return "unknown";
        }
        String path = url;
        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            int slash = path.indexOf('/', scheme + 3);
            path = slash < 0 ? "/" : path.substring(slash);
        }
        int cut = indexOfAny(path, '?', '#');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        StringBuilder out = new StringBuilder();
        boolean valueNext = false;
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            boolean word = segment.matches("[A-Za-z][A-Za-z-]*");
            String lower = segment.toLowerCase(Locale.ROOT);
            out.append('/').append(word && !valueNext ? segment : "{id}");
            valueNext = word && VALUE_MARKERS.contains(lower);
        }
        String template = out.length() == 0 ? "/" : out.toString();
        return template.length() <= 128 ? template : template.substring(0, 128);
    }

    private static int indexOfAny(String s, char a, char b) {
        int ia = s.indexOf(a);
        int ib = s.indexOf(b);
        if (ia < 0) {
            return ib;
        }
        return ib < 0 ? ia : Math.min(ia, ib);
    }
}
