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
        String wanted = input.trim();
        String type = null;
        int colon = wanted.indexOf(':');
        if (colon > 0 && colon < wanted.length() - 1) {
            type = wanted.substring(0, colon);
            wanted = wanted.substring(colon + 1);
        }
        List<KnkTeleportDestination> matches = new ArrayList<>();
        for (KnkTeleportDestination destination : destinations) {
            if (destination.name().equalsIgnoreCase(wanted)
                    && (type == null || destination.domainType().equalsIgnoreCase(type))) {
                matches.add(destination);
            }
        }
        if (matches.isEmpty() && type != null) {
            // "Spawn:Hill" could be a place literally named that.
            String literal = input.trim();
            for (KnkTeleportDestination destination : destinations) {
                if (destination.name().equalsIgnoreCase(literal)) {
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
     * Tab completions for {@code prefix}: each name, or its {@code type:name} form when the name is
     * shared, starting with {@code prefix} (ignoring case). Names with spaces are left out - a
     * command argument can't hold them.
     */
    public static List<String> complete(List<KnkTeleportDestination> destinations, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        Set<String> seen = new LinkedHashSet<>();
        Set<String> shared = new LinkedHashSet<>();
        for (KnkTeleportDestination destination : destinations) {
            if (!seen.add(destination.name().toLowerCase(Locale.ROOT))) {
                shared.add(destination.name().toLowerCase(Locale.ROOT));
            }
        }
        List<String> out = new ArrayList<>();
        for (KnkTeleportDestination destination : destinations) {
            String candidate = shared.contains(destination.name().toLowerCase(Locale.ROOT))
                ? destination.qualifiedName() : destination.name();
            if (!candidate.contains(" ") && candidate.toLowerCase(Locale.ROOT).startsWith(lower) && !out.contains(candidate)) {
                out.add(candidate);
            }
        }
        return out;
    }
}
