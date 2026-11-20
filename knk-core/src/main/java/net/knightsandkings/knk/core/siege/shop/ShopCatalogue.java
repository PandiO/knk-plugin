package net.knightsandkings.knk.core.siege.shop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The effective shop catalogue of one match, frozen at its start (siege shop DESIGN § 5.3, P25;
 * PandiO/knk-workspace#868): the API's {@code GET api/SiegeShop/catalogue} answer for the mode and scenario, read once.
 * Admin edits made during the match apply from the next one (A23). Immutable.
 * <p>
 * Categories without visible entries are left out (§ 5.3.2), and so are weapon entries while
 * {@code siege.weapons.enabled} is off (§ 5.3.4, {@link #withoutWeapons()}).
 */
public final class ShopCatalogue {

    private static final ShopCatalogue EMPTY = new ShopCatalogue(null, null, List.of());

    private final String gameMode;
    private final Integer scenarioId;
    private final List<ShopCategory> categories;
    private final Map<Integer, ShopEntry> entries;

    /**
     * @param gameMode   {@code Siege} or {@code SiegeSurvival}
     * @param categories as the API listed them; sorted here by (sort order, id), entries likewise, empty ones dropped
     */
    public ShopCatalogue(String gameMode, Integer scenarioId, List<ShopCategory> categories) {
        this.gameMode = gameMode;
        this.scenarioId = scenarioId;
        List<ShopCategory> sorted = new ArrayList<>();
        Map<Integer, ShopEntry> byId = new LinkedHashMap<>();
        if (categories != null) {
            categories.stream()
                    .filter(c -> c != null && !c.entries().isEmpty())
                    .sorted(Comparator.comparingInt(ShopCategory::sortOrder).thenComparingInt(ShopCategory::id))
                    .forEach(c -> {
                        List<ShopEntry> list = c.entries().stream()
                                .filter(e -> e != null && !byId.containsKey(e.id()))
                                .sorted(Comparator.comparingInt(ShopEntry::sortOrder).thenComparingInt(ShopEntry::id))
                                .toList();
                        list.forEach(e -> byId.put(e.id(), e));
                        if (!list.isEmpty()) sorted.add(c.withEntries(list));
                    });
        }
        this.categories = List.copyOf(sorted);
        this.entries = Map.copyOf(byId);
    }

    public static ShopCatalogue empty() {
        return EMPTY;
    }

    public String gameMode() {
        return gameMode;
    }

    public Integer scenarioId() {
        return scenarioId;
    }

    /** The visible categories in shop order. */
    public List<ShopCategory> categories() {
        return categories;
    }

    public Optional<ShopCategory> category(int id) {
        return categories.stream().filter(c -> c.id() == id).findFirst();
    }

    public Optional<ShopEntry> entry(int id) {
        return Optional.ofNullable(entries.get(id));
    }

    public List<ShopEntry> entries() {
        return categories.stream().flatMap(c -> c.entries().stream()).toList();
    }

    public boolean isEmpty() {
        return categories.isEmpty();
    }

    /** The same catalogue without siege weapon entries (§ 5.3.4: {@code siege.weapons.enabled} off). */
    public ShopCatalogue withoutWeapons() {
        return new ShopCatalogue(gameMode, scenarioId, categories.stream()
                .map(c -> c.withEntries(c.entries().stream().filter(e -> e.kind() != ShopEntryKind.SIEGE_WEAPON).toList()))
                .toList());
    }
}
