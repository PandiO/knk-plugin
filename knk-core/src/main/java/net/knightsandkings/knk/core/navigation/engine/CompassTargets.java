package net.knightsandkings.knk.core.navigation.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.knightsandkings.knk.core.navigation.destinations.Candidate;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine;
import net.knightsandkings.knk.core.navigation.destinations.DestinationList;
import net.knightsandkings.knk.core.util.NamedTargets;

/**
 * The compass's configured general targets (DESIGN § 4.1 notes, § 5; the design's {@code CompassTargetsProvider},
 * phase 3, PandiO/knk-workspace#69): outside a siege the compass cycles the lines of the web app's compass settings,
 * in that order, each resolved like {@code /navigate <line>} - as a <b>filter and ordering of the World list</b>, not
 * a second catalogue. A line that names nothing in the list (unknown, ambiguous, another world) is left out, as today.
 * A candidate named by two lines is offered once, at its first line. Pure.
 */
public final class CompassTargets {

    /**
     * One line of the compass cycle.
     *
     * @param line      the configured text ({@code "Kardenna spawn"})
     * @param candidate the listed candidate it names
     * @param mode      the trailing {@code spawn} / {@code region} of the line, else {@code DEFAULT}
     */
    public record Pick(String line, Candidate candidate, Candidate.Mode mode) {
        public Pick {
            Objects.requireNonNull(line, "line");
            Objects.requireNonNull(candidate, "candidate");
            mode = mode == null ? Candidate.Mode.DEFAULT : mode;
        }
    }

    private CompassTargets() {
    }

    /** The configured lines that name a candidate of {@code list}, in the configured order. */
    public static List<Pick> of(DestinationList list, List<String> lines) {
        if (list == null || list.refused() || lines == null) {
            return List.of();
        }
        List<Pick> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            Parsed parsed = parse(line);
            NamedTargets.Match<Candidate> match = DestinationEngine.NAMES.resolve(list.candidates(), parsed.name());
            if (match.found() && seen.add(match.target().key())) {
                out.add(new Pick(line, match.target(), parsed.mode()));
            }
        }
        return out;
    }

    /** The name words and the trailing mode word of a line, as {@code /navigate} parses its arguments. */
    public record Parsed(String name, Candidate.Mode mode) {
    }

    public static Parsed parse(String line) {
        List<String> words = new ArrayList<>(Arrays.asList(line.trim().split("\\s+")));
        Candidate.Mode mode = Candidate.Mode.DEFAULT;
        if (words.size() >= 2) {
            Candidate.Mode trailing = Candidate.Mode.parse(words.get(words.size() - 1)).orElse(null);
            if (trailing != null) {
                mode = trailing;
                words.remove(words.size() - 1);
            }
        }
        return new Parsed(String.join(" ", words).trim(), mode);
    }
}
