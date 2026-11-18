package net.knightsandkings.knk.core.landmarks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.knightsandkings.knk.core.navigation.model.Landmark;

/**
 * The argument rules of {@code /knk landmark} (off-road phase 5.1, PandiO/knk-workspace#708, DESIGN § 4.5), Bukkit-free.
 * Landmark names may have spaces ("Throne room"), so a name is the words up to a keyword ({@code region},
 * {@code radius}) or, where another argument follows, the longest run of words that names an existing landmark. A
 * landmark can always be named by its id, {@code #12}.
 */
public final class LandmarkCommandArgs {

    /** The largest radius, as the API allows (knk-web-api LandmarkService.MaxRadius). */
    public static final double MAX_RADIUS = 512;
    /** As the API allows (Landmark.NameMaxLength). */
    public static final int MAX_NAME_LENGTH = 100;

    private LandmarkCommandArgs() {
    }

    /** What {@code create <name> [region <id> | radius <n>]} asked for. */
    public record Create(String name, Landmark.Extent extent, String regionId, Double radius) {
    }

    /**
     * Parses the words after {@code create}. Throws {@link IllegalArgumentException} with the message to show.
     */
    public static Create parseCreate(List<String> words) {
        List<String> tokens = clean(words);
        Landmark.Extent extent = Landmark.Extent.NONE;
        String regionId = null;
        Double radius = null;
        int nameEnd = tokens.size();
        if (tokens.size() >= 2) {
            String keyword = tokens.get(tokens.size() - 2).toLowerCase(Locale.ROOT);
            String value = tokens.get(tokens.size() - 1);
            if (keyword.equals("region")) {
                extent = Landmark.Extent.REGION;
                regionId = value;
                nameEnd = tokens.size() - 2;
            } else if (keyword.equals("radius")) {
                extent = Landmark.Extent.RADIUS;
                radius = parseRadius(value);
                nameEnd = tokens.size() - 2;
            }
        }
        if (tokens.size() >= 1 && nameEnd == tokens.size()) {
            String last = tokens.get(tokens.size() - 1).toLowerCase(Locale.ROOT);
            if (last.equals("region") || last.equals("radius")) {
                throw new IllegalArgumentException("Give a value after " + last + ".");
            }
        }
        String name = String.join(" ", tokens.subList(0, nameEnd));
        return new Create(validName(name), extent, regionId, radius);
    }

    /** A radius above 0 and at most {@link #MAX_RADIUS}. */
    public static double parseRadius(String text) {
        double radius;
        try {
            radius = Double.parseDouble(text.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The radius must be a number of blocks: " + text);
        }
        if (!(radius > 0) || radius > MAX_RADIUS || !Double.isFinite(radius)) {
            throw new IllegalArgumentException("The radius must be above 0 and at most " + (int) MAX_RADIUS + " blocks.");
        }
        return radius;
    }

    /** A trimmed name of 1 to {@link #MAX_NAME_LENGTH} characters, not starting with {@code #} (that is an id). */
    public static String validName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Give the landmark a name.");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("A landmark name can be at most " + MAX_NAME_LENGTH + " characters.");
        }
        if (trimmed.startsWith("#")) {
            throw new IllegalArgumentException("A landmark name can't start with #: that names an id.");
        }
        return trimmed;
    }

    /** {@code #12} as 12; empty for anything else. */
    public static Optional<Integer> idRef(String word) {
        if (word == null || word.length() < 2 || word.charAt(0) != '#') {
            return Optional.empty();
        }
        try {
            int id = Integer.parseInt(word.substring(1));
            return id > 0 ? Optional.of(id) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** A landmark reference and what follows it. */
    public record Split(String reference, List<String> rest) {
        public Split {
            rest = List.copyOf(rest);
        }
    }

    /**
     * Splits {@code words} into a landmark reference and the words after it: {@code #id} is one word; otherwise the
     * longest run of leading words that equals one of {@code names} (ignoring case), leaving at least
     * {@code minRest} words. Empty when nothing matches.
     */
    public static Optional<Split> split(List<String> words, Collection<String> names, int minRest) {
        List<String> tokens = clean(words);
        if (tokens.isEmpty()) {
            return Optional.empty();
        }
        if (idRef(tokens.get(0)).isPresent()) {
            return tokens.size() - 1 >= minRest ? Optional.of(new Split(tokens.get(0), tokens.subList(1, tokens.size())))
                : Optional.empty();
        }
        for (int end = tokens.size() - minRest; end >= 1; end--) {
            String candidate = String.join(" ", tokens.subList(0, end));
            for (String name : names) {
                if (name != null && name.trim().equalsIgnoreCase(candidate)) {
                    return Optional.of(new Split(name.trim(), tokens.subList(end, tokens.size())));
                }
            }
        }
        return Optional.empty();
    }

    private static List<String> clean(List<String> words) {
        List<String> out = new ArrayList<>();
        if (words != null) {
            for (String word : words) {
                if (word != null && !word.isBlank()) {
                    out.add(word.trim());
                }
            }
        }
        return out;
    }
}
