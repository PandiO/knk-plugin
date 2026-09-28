package net.knightsandkings.knk.core.teleport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;

/**
 * Name lookup for {@code /warp <name>} (docs/specs/teleport/DESIGN.md §3.2): case-insensitive, over
 * the player's destination list. A name several places share (a Town and a District both called
 * "Market") is ambiguous and the player picks with the {@code type:name} form ({@code town:Market}).
 * Pure, so it is unit-tested without a server.
 */
public final class WarpTargets {

    private WarpTargets() {
    }

    /** What {@link #resolve} found. */
    public record Match(KnkTeleportDestination destination, List<KnkTeleportDestination> choices) {
        public static final Match NONE = new Match(null, List.of());

        public boolean found() {
            return destination != null;
        }

        public boolean ambiguous() {
            return destination == null && choices.size() > 1;
        }
    }

    /**
     * Find {@code input} ("Kardenna", "town:Kardenna") in {@code destinations}: exactly one match →
     * found; several → ambiguous with the choices; none → {@link Match#NONE}.
     */
    public static Match resolve(List<KnkTeleportDestination> destinations, String input) {
        if (input == null || input.isBlank() || destinations == null || destinations.isEmpty()) {
            return Match.NONE;
        }
        String wanted = normalize(input);
        String type = null;
        int colon = wanted.indexOf(':');
        if (colon > 0 && colon < wanted.length() - 1) {
            type = wanted.substring(0, colon);
            wanted = wanted.substring(colon + 1);
        }
        List<KnkTeleportDestination> matches = new ArrayList<>();
        for (KnkTeleportDestination destination : destinations) {
            if (normalize(destination.name()).equalsIgnoreCase(wanted)
                    && (type == null || destination.domainType().equalsIgnoreCase(type))) {
                matches.add(destination);
            }
        }
        if (matches.isEmpty() && type != null) {
            // "Spawn:Hill" could be a place literally named that.
            String literal = normalize(input);
            for (KnkTeleportDestination destination : destinations) {
                if (normalize(destination.name()).equalsIgnoreCase(literal)) {
                    matches.add(destination);
                }
            }
        }
        if (matches.size() == 1) {
            return new Match(matches.get(0), List.of());
        }
        return matches.isEmpty() ? Match.NONE : new Match(null, List.copyOf(matches));
    }

    /**
     * Places whose name starts with {@code input} (ignoring case and spacing), for "did you mean" when
     * {@link #resolve} finds nothing. Never picks one itself: a warp can cost gems.
     */
    public static List<KnkTeleportDestination> suggestions(List<KnkTeleportDestination> destinations, String input) {
        String wanted = normalize(input).toLowerCase(Locale.ROOT);
        if (wanted.isEmpty() || destinations == null) {
            return List.of();
        }
        int colon = wanted.indexOf(':');
        String type = colon > 0 ? wanted.substring(0, colon) : null;
        String bare = colon > 0 ? wanted.substring(colon + 1) : wanted;
        List<KnkTeleportDestination> out = new ArrayList<>();
        for (KnkTeleportDestination destination : destinations) {
            String name = normalize(destination.name()).toLowerCase(Locale.ROOT);
            boolean typeFits = type == null || destination.domainType().equalsIgnoreCase(type);
            if ((typeFits && name.startsWith(bare)) || name.startsWith(wanted)) {
                out.add(destination);
            }
        }
        return out;
    }

    /** Tab completions for a single word - same as {@link #complete(List, List)} with just {@code prefix}. */
    public static List<String> complete(List<KnkTeleportDestination> destinations, String prefix) {
        return complete(destinations, List.of(prefix == null ? "" : prefix));
    }

    /**
     * Tab completions for the last of {@code words} (the command's arguments so far). A place's name
     * - or its {@code type:name} form when several places share the name - is offered one word at a
     * time, since the client completes one argument: after "Residential" the next word "District" is
     * offered. Matching ignores case.
     */
    public static List<String> complete(List<KnkTeleportDestination> destinations, List<String> words) {
        if (destinations == null || words == null || words.isEmpty()) {
            return List.of();
        }
        int index = words.size() - 1;
        String current = words.get(index).toLowerCase(Locale.ROOT);
        Set<String> seen = new LinkedHashSet<>();
        Set<String> shared = new LinkedHashSet<>();
        for (KnkTeleportDestination destination : destinations) {
            String key = normalize(destination.name()).toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                shared.add(key);
            }
        }
        List<String> out = new ArrayList<>();
        for (KnkTeleportDestination destination : destinations) {
            String name = normalize(destination.name());
            boolean isShared = shared.contains(name.toLowerCase(Locale.ROOT));
            String candidate = isShared ? destination.domainType().toLowerCase(Locale.ROOT) + ":" + name : name;
            String[] parts = candidate.split(" ");
            if (parts.length <= index || !samePrefix(parts, words, index)) {
                continue;
            }
            String part = parts[index];
            boolean matches = part.toLowerCase(Locale.ROOT).startsWith(current)
                // "mar" also offers "town:Market" on the first word.
                || (index == 0 && isShared && name.toLowerCase(Locale.ROOT).startsWith(current));
            if (matches && !out.contains(part)) {
                out.add(part);
            }
        }
        return out;
    }

    /** Whether the first {@code count} words of the name are the words already typed. */
    private static boolean samePrefix(String[] parts, List<String> words, int count) {
        for (int i = 0; i < count; i++) {
            if (!parts[i].equalsIgnoreCase(words.get(i).trim())) {
                return false;
            }
        }
        return true;
    }

    /** Trimmed, with runs of spaces as one: "Residential  District " matches "Residential District". */
    static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }
}
