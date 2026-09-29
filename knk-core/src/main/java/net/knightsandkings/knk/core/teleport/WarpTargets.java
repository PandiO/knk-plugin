package net.knightsandkings.knk.core.teleport;

import java.util.List;

import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;
import net.knightsandkings.knk.core.util.NamedTargets;

/**
 * Name lookup for {@code /warp <name>} (docs/specs/teleport/DESIGN.md §3.2): case-insensitive, over
 * the player's destination list. A name several places share (a Town and a District both called
 * "Market") is ambiguous and the player picks with the {@code type:name} form ({@code town:Market}).
 * Pure, so it is unit-tested without a server.
 *
 * <p>Since road navigation plan §2 R21 the lookup itself is {@link NamedTargets}, bound here to
 * {@link KnkTeleportDestination#name()} / {@link KnkTeleportDestination#domainType()}; this class
 * keeps the static API {@code WarpCommand} uses.
 */
public final class WarpTargets {

    private static final NamedTargets<KnkTeleportDestination> TARGETS =
        new NamedTargets<>(KnkTeleportDestination::name, KnkTeleportDestination::domainType);

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
        NamedTargets.Match<KnkTeleportDestination> match = TARGETS.resolve(destinations, input);
        if (match.found()) {
            return new Match(match.target(), List.of());
        }
        return match.choices().isEmpty() ? Match.NONE : new Match(null, match.choices());
    }

    /**
     * Places whose name starts with {@code input} (ignoring case and spacing), for "did you mean" when
     * {@link #resolve} finds nothing. Never picks one itself: a warp can cost gems.
     */
    public static List<KnkTeleportDestination> suggestions(List<KnkTeleportDestination> destinations, String input) {
        return TARGETS.suggestions(destinations, input);
    }

    /** Tab completions for a single word - same as {@link #complete(List, List)} with just {@code prefix}. */
    public static List<String> complete(List<KnkTeleportDestination> destinations, String prefix) {
        return TARGETS.complete(destinations, prefix);
    }

    /**
     * Tab completions for the last of {@code words} (the command's arguments so far). A place's name
     * - or its {@code type:name} form when several places share the name - is offered one word at a
     * time, since the client completes one argument: after "Residential" the next word "District" is
     * offered. Matching ignores case.
     */
    public static List<String> complete(List<KnkTeleportDestination> destinations, List<String> words) {
        return TARGETS.complete(destinations, words);
    }

    /** Trimmed, with runs of spaces as one: "Residential  District " matches "Residential District". */
    static String normalize(String text) {
        return NamedTargets.normalize(text);
    }
}
