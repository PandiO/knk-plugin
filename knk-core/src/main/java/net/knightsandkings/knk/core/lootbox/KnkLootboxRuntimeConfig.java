package net.knightsandkings.knk.core.lootbox;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code GET api/LootboxSpawns/runtime-config} (docs/specs/lootboxes/DESIGN.md §3.3): the global settings, the
 * enabled types, every grade and every area. The plugin re-reads it every {@code runtime-refresh-seconds}.
 *
 * @param maxClaimsPerPlayerPerDay per player per UTC day, all types together; null = no cap
 */
public record KnkLootboxRuntimeConfig(
        boolean enabled,
        int globalMaxActive,
        Integer maxClaimsPerPlayerPerDay,
        int announceMinItemStars,
        int announceSpawnMinBoxStars,
        String dropAnnouncementTemplate,
        String spawnAnnouncementTemplate,
        Instant serverTimeUtc,
        List<KnkLootboxType> types,
        List<KnkLootboxGrade> grades,
        List<KnkLootboxArea> areas
) {
    public KnkLootboxRuntimeConfig {
        types = types == null ? List.of() : List.copyOf(types);
        grades = grades == null ? List.of() : List.copyOf(grades);
        areas = areas == null ? List.of() : List.copyOf(areas);
    }

    /** Before the first successful read: nothing spawns, every lookup is empty. */
    public static KnkLootboxRuntimeConfig empty() {
        return new KnkLootboxRuntimeConfig(false, 0, null, 5, 6, null, null, null, List.of(), List.of(), List.of());
    }

    public Optional<KnkLootboxType> typeById(int id) {
        return types.stream().filter(t -> t.id() == id).findFirst();
    }

    /**
     * The enabled type for a category, matched case-insensitively on the category name or the type name
     * ({@code weapons}, {@code Weapons Lootbox}); spaces may be typed as underscores.
     */
    public Optional<KnkLootboxType> typeByCategory(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = normalize(name);
        return types.stream()
                .filter(t -> wanted.equals(normalize(t.categoryName())) || wanted.equals(normalize(t.name())))
                .findFirst();
    }

    public Optional<KnkLootboxArea> areaById(int id) {
        return areas.stream().filter(a -> a.id() == id).findFirst();
    }

    /** Area names are unique; matched case-insensitively. */
    public Optional<KnkLootboxArea> areaByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return areas.stream().filter(a -> a.name() != null && a.name().equalsIgnoreCase(name)).findFirst();
    }

    /** The grade name for a star count ("Legendary"), or empty. */
    public Optional<String> gradeName(int stars) {
        return grades.stream().filter(g -> g.stars() == stars).map(KnkLootboxGrade::name).findFirst();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replace('_', ' ').toLowerCase(Locale.ROOT);
    }
}
