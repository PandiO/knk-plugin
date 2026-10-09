package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.roads.build.TileProposal.Item;
import net.knightsandkings.knk.core.roads.build.TileProposal.Kind;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Parses the selection of {@code /knk road proposal accept|reject …} (plan §5.7, decision D3): {@code all},
 * item numbers and ranges ({@code 3}, {@code 1 4 7-9}, {@code 1,4}), or a kind ({@code added}, {@code removed},
 * {@code changed}, {@code moved}). Words and numbers can be mixed. Pure.
 */
public final class ProposalSelection {

    /** The parsed selection, or why it could not be parsed. */
    public record Result(Set<Integer> numbers, String error) {
        public Result {
            numbers = numbers == null ? Set.of() : Set.copyOf(numbers);
        }

        public boolean ok() {
            return error == null;
        }
    }

    /** The selector words, for tab completion. */
    public static final List<String> WORDS = List.of("all", "added", "removed", "changed", "moved");

    private ProposalSelection() {
    }

    public static Result parse(List<String> tokens, List<Item> items) {
        if (tokens.isEmpty()) {
            return new Result(null, "Say which items: all, a kind (added, removed, changed, moved) or numbers like 1 4 7-9.");
        }
        Set<Integer> known = new TreeSet<>();
        items.forEach(i -> known.add(i.n()));
        Set<Integer> out = new TreeSet<>();
        for (String raw : tokens) {
            for (String token : raw.split(",")) {
                String t = token.trim().toLowerCase(Locale.ROOT);
                if (t.isEmpty()) {
                    continue;
                }
                if (t.equals("all")) {
                    out.addAll(known);
                    continue;
                }
                List<Kind> kinds = kindsOf(t);
                if (!kinds.isEmpty()) {
                    items.stream().filter(i -> kinds.contains(i.kind())).forEach(i -> out.add(i.n()));
                    continue;
                }
                int dash = t.indexOf('-', 1);
                try {
                    int from = Integer.parseInt(dash < 0 ? t : t.substring(0, dash));
                    int to = dash < 0 ? from : Integer.parseInt(t.substring(dash + 1));
                    if (to < from) {
                        return new Result(null, "Range " + token.trim() + " runs backwards.");
                    }
                    for (int n = from; n <= to; n++) {
                        if (!known.contains(n)) {
                            return new Result(null, "There is no item " + n + " (items: " + describe(known) + ").");
                        }
                        out.add(n);
                    }
                } catch (NumberFormatException e) {
                    return new Result(null, "'" + token.trim() + "' is not an item number, a range or one of " + WORDS + ".");
                }
            }
        }
        if (out.isEmpty()) {
            return new Result(null, "Nothing matches that selection.");
        }
        return new Result(out, null);
    }

    private static List<Kind> kindsOf(String word) {
        return switch (word) {
            case "added", "add" -> List.of(Kind.EDGE_ADDED);
            case "removed", "remove" -> List.of(Kind.EDGE_REMOVED, Kind.NODE_REMOVED);
            case "changed", "change" -> List.of(Kind.EDGE_CHANGED);
            case "moved", "move" -> List.of(Kind.NODE_MOVED);
            default -> List.of();
        };
    }

    private static String describe(Set<Integer> known) {
        if (known.isEmpty()) {
            return "none";
        }
        int first = ((TreeSet<Integer>) known).first();
        int last = ((TreeSet<Integer>) known).last();
        return known.size() == last - first + 1 ? first + "-" + last : known.toString();
    }
}
