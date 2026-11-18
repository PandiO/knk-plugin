package net.knightsandkings.knk.core.navigation.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import net.knightsandkings.knk.core.landmarks.LandmarkExtent;
import net.knightsandkings.knk.core.navigation.destinations.Candidate;
import net.knightsandkings.knk.core.navigation.destinations.NavigationContext;
import net.knightsandkings.knk.core.navigation.destinations.Provider;
import net.knightsandkings.knk.core.navigation.model.Landmark;
import net.knightsandkings.knk.core.navigation.model.NavigationModel;

/**
 * The landmarks of the navigation model as a World-scope provider of the destination engine (destination engine
 * DESIGN § 3.2, E16; off-road DESIGN § 6, O27; PandiO/knk-workspace#708). Registered by the plugin only with
 * {@code navigation.landmarks} on; never in the old catalogue, so a landmark is reached through the engine only.
 *
 * <p>Each landmark with {@code NavigationEnabled} gives one candidate: key {@code landmark:<id>} (the id, stable across
 * renames, so a running session survives a rename and ends when the landmark is deleted or disabled), label the name,
 * type word {@code landmark}, id for {@code landmark:#<id>}, detail {@code in <domain>} when linked, source
 * {@value #ID}. The place follows the extent: a Region extent is the region ({@link Candidate.Place.Region}, arrival is
 * WorldGuard containment), a Radius extent the pin with that arrival radius, none the pin within
 * {@code arrive-distance}. Landmarks of every world are offered; the engine lists another world's apart (E11), and the
 * exclusive scopes above World hide them (E2). Answers from the model only (§ 7.1); the list is built once per model.
 */
public final class LandmarkProvider implements Provider {

    public static final String ID = "landmarks";
    public static final String TYPE_WORD = "landmark";
    public static final String KEY_PREFIX = "landmark:";
    /** After the domains, beside the Locations (the catalogue's order). */
    static final int PRIORITY = 1;

    private final Supplier<NavigationModel> model;
    private NavigationModel builtFor;
    private List<Candidate> built = List.of();

    /** @param model the current navigation model; null when there is none yet */
    public LandmarkProvider(Supplier<NavigationModel> model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public synchronized List<Candidate> candidates(NavigationContext ctx) {
        NavigationModel current = model.get();
        if (current == null) {
            return List.of();
        }
        if (current != builtFor) {
            built = List.copyOf(fromModel(current));
            builtFor = current;
        }
        return built;
    }

    /** The candidates of every enabled landmark of {@code model}. */
    public static List<Candidate> fromModel(NavigationModel model) {
        List<Candidate> out = new ArrayList<>();
        for (Landmark landmark : model.landmarks()) {
            Candidate candidate = candidate(landmark);
            if (candidate != null) {
                out.add(candidate);
            }
        }
        return out;
    }

    /** One landmark's candidate; null when it is not offered (disabled, or no name). */
    public static Candidate candidate(Landmark landmark) {
        if (!landmark.navigationEnabled() || landmark.name().isBlank()) {
            return null;
        }
        String detail = landmark.domainName() == null || landmark.domainName().isBlank()
            ? null : "in " + landmark.domainName();
        return new Candidate(key(landmark.id()), landmark.name(), TYPE_WORD, List.of(), landmark.id(), detail, PRIORITY, ID,
            place(landmark));
    }

    /** The place of {@code landmark}'s extent. */
    public static Candidate.Place place(Landmark landmark) {
        return switch (LandmarkExtent.of(landmark)) {
            case LandmarkExtent.Region region -> new Candidate.Place.Region(landmark.world(), region.regionId());
            case LandmarkExtent.Cylinder cylinder ->
                new Candidate.Place.Point(landmark.world(), landmark.x(), landmark.y(), landmark.z(), cylinder.radius());
            case LandmarkExtent.Point point -> new Candidate.Place.Point(landmark.world(), landmark.x(), landmark.y(), landmark.z());
        };
    }

    public static String key(int landmarkId) {
        return KEY_PREFIX + landmarkId;
    }
}
