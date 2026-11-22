package net.knightsandkings.knk.core.landmarks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.knightsandkings.knk.core.domain.landmarks.LandmarkAreaKind;

/**
 * The argument rules of {@code /knk landmark} (off-road phase 5.1, PandiO/knk-workspace#708, DESIGN § 4.5; without the
 * radius form since PandiO/knk-workspace#985, landmark-domain LD08), Bukkit-free. Landmark names may have spaces
 * ("Throne room"), so a name is the words up to the keyword {@code region} or, where another argument follows, the longest run of words that names an existing landmark. A
 * landmark can always be named by its id, {@code #12}.
 */
public final class LandmarkCommandArgs {

    /** As the API allows (Landmark.NameMaxLength). */
    public static final int MAX_NAME_LENGTH = 100;
    /** LD08 (PandiO/knk-workspace#985): the answer to {@code create <name> radius <n>}. */
    public static final String RADIUS_GONE =
        "The radius area is gone: draw a round region with //sel cyl, then use region <id>.";

    private LandmarkCommandArgs() {
    }

    /**
     * What {@code create <name> [region <id>]} asked for: a point ({@link LandmarkAreaKind#NONE}) or its own region
     * ({@link LandmarkAreaKind#OWN_REGION} with {@code regionId}).
     */
    public record Create(String name, LandmarkAreaKind areaKind, String regionId) {
    }

    /** Thrown for a {@code radius} argument (LD08); the command shows the usage line with it. */
    public static final class RadiusGone extends IllegalArgumentException {
        RadiusGone() {
            super(RADIUS_GONE);
        }
    }

    /**
     * Parses the words after {@code create}. Throws {@link IllegalArgumentException} with the message to show
     * ({@link RadiusGone} for the removed {@code radius} form).
     */
    public static Create parseCreate(List<String> words) {
        List<String> tokens = clean(words);
        LandmarkAreaKind areaKind = LandmarkAreaKind.NONE;
        String regionId = null;
        int nameEnd = tokens.size();
        // the keyword places of the old form: "<name> radius <n>" or a trailing "radius" ("Radius tower" is a name)
        if ((tokens.size() >= 3 && tokens.get(tokens.size() - 2).equalsIgnoreCase("radius"))
            || (tokens.size() >= 2 && tokens.get(tokens.size() - 1).equalsIgnoreCase("radius"))) {
            throw new RadiusGone();
        }
        if (tokens.size() >= 2) {
            String keyword = tokens.get(tokens.size() - 2).toLowerCase(Locale.ROOT);
            String value = tokens.get(tokens.size() - 1);
            if (keyword.equals("region")) {
                areaKind = LandmarkAreaKind.OWN_REGION;
                regionId = value;
                nameEnd = tokens.size() - 2;
            }
        }
        if (tokens.size() >= 1 && nameEnd == tokens.size()
            && tokens.get(tokens.size() - 1).equalsIgnoreCase("region")) {
            throw new IllegalArgumentException("Give a value after region.");
        }
        String name = String.join(" ", tokens.subList(0, nameEnd));
        return new Create(validName(name), areaKind, regionId);
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
