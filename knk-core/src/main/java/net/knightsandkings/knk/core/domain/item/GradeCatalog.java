package net.knightsandkings.knk.core.domain.item;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The grade table, readable synchronously from event handlers (Linear KNG-6,
 * docs/specs/items/GRADE_DROPCHANCE.md §4). The plugin refreshes it from the API in the background
 * ({@link #replace}); until the first load succeeds, or for a star count the API doesn't have, lookups by
 * stars fall back to {@link #DEFAULTS} - the seeded values - so the enchant-book cap never waits on the
 * network and never silently turns off.
 * <p>
 * Items carry their grade as stars (a PDC tag), and the divisor is looked up here at click time, so retuning
 * a grade in the web app applies to items that already exist.
 */
public final class GradeCatalog {

    private static final GradeCatalog INSTANCE = new GradeCatalog();

    /** What knk-web-api seeds (KitSeed / ItemBlueprintV1Seed). Names of 6-10 are placeholders. */
    public static final List<KnkGrade> DEFAULTS = List.of(
            new KnkGrade(null, "Common", 1, 70.0, 5),
            new KnkGrade(null, "Uncommon", 2, 60.0, 4),
            new KnkGrade(null, "Rare", 3, 40.0, 3),
            new KnkGrade(null, "Epic", 4, 25.0, 2),
            new KnkGrade(null, "Legendary", 5, 15.0, 1),
            new KnkGrade(null, "Mythic", 6, 8.0, null),
            new KnkGrade(null, "Ascended", 7, 5.0, null),
            new KnkGrade(null, "Relic", 8, 1.0, null),
            new KnkGrade(null, "Exalted", 9, 0.5, null),
            new KnkGrade(null, "Divine", 10, 0.05, null)
    );

    private static final Map<Integer, KnkGrade> DEFAULTS_BY_STARS = indexByStars(DEFAULTS);

    private record Snapshot(Map<Integer, KnkGrade> byStars, Map<Integer, KnkGrade> byId) {
    }

    private volatile Snapshot live;

    public GradeCatalog() {
    }

    public static GradeCatalog getInstance() {
        return INSTANCE;
    }

    /** Swaps in the grades loaded from the API. Grades without stars can't be looked up by stars and are skipped there. */
    public void replace(Collection<KnkGrade> grades) {
        if (grades == null) {
            return;
        }
        Map<Integer, KnkGrade> byId = grades.stream()
                .filter(g -> g != null && g.id() != null)
                .collect(Collectors.toUnmodifiableMap(KnkGrade::id, Function.identity(), (a, b) -> a));
        live = new Snapshot(indexByStars(grades), byId);
    }

    /** True once {@link #replace} has run at least once. */
    public boolean isLoaded() {
        return live != null;
    }

    /** The live grade with these stars, else the seeded default, else empty. */
    public Optional<KnkGrade> byStars(int stars) {
        Snapshot snapshot = live;
        if (snapshot != null && snapshot.byStars().containsKey(stars)) {
            return Optional.of(snapshot.byStars().get(stars));
        }
        return Optional.ofNullable(DEFAULTS_BY_STARS.get(stars));
    }

    /** The live grade with this id; empty before the first load (defaults have no ids). */
    public Optional<KnkGrade> byId(int id) {
        Snapshot snapshot = live;
        return snapshot == null ? Optional.empty() : Optional.ofNullable(snapshot.byId().get(id));
    }

    /**
     * The stars of {@code grade}: its own when set, otherwise looked up by id. Blueprints loaded through the
     * list endpoint carry only the grade's id and name.
     */
    public Optional<Integer> starsOf(KnkGrade grade) {
        if (grade == null) {
            return Optional.empty();
        }
        if (grade.stars() != null) {
            return grade.stars() > 0 ? Optional.of(grade.stars()) : Optional.empty();
        }
        return grade.id() == null ? Optional.empty()
                : byId(grade.id()).map(KnkGrade::stars).filter(s -> s != null && s > 0);
    }

    /** Two grades with the same stars: the lowest id wins (as knk-web-api's ItemBlueprintV1Seed picks). */
    private static Map<Integer, KnkGrade> indexByStars(Collection<KnkGrade> grades) {
        return grades.stream()
                .filter(g -> g != null && g.stars() != null)
                .collect(Collectors.toUnmodifiableMap(KnkGrade::stars, Function.identity(), GradeCatalog::lowerId));
    }

    private static KnkGrade lowerId(KnkGrade a, KnkGrade b) {
        if (a.id() == null) return b.id() == null ? a : b;
        if (b.id() == null) return a;
        return a.id() <= b.id() ? a : b;
    }
}
