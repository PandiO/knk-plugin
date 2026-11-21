package net.knightsandkings.knk.core.production.shop.display;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The registered display spots as the API last listed them (P12, PandiO/knk-workspace#250): by block, by shop and by
 * chunk. Replaced as a whole on every read, and changed in place after an admin command. Main thread only.
 */
public final class DisplaySpotIndex {

    /** Fill order: sort order, then id. */
    public static final Comparator<DisplaySpot> ORDER = Comparator.comparingInt(DisplaySpot::sortOrder)
            .thenComparingInt(DisplaySpot::id);

    private final Map<Integer, DisplaySpot> byId = new HashMap<>();
    private final Map<String, DisplaySpot> byBlock = new HashMap<>();

    public void replace(Collection<DisplaySpot> spots) {
        byId.clear();
        byBlock.clear();
        if (spots != null) {
            spots.forEach(this::put);
        }
    }

    public void put(DisplaySpot spot) {
        if (spot == null) {
            return;
        }
        remove(spot.id());
        byId.put(spot.id(), spot);
        byBlock.put(spot.blockKey(), spot);
    }

    public Optional<DisplaySpot> remove(int id) {
        DisplaySpot gone = byId.remove(id);
        if (gone != null) {
            byBlock.remove(gone.blockKey(), gone);
        }
        return Optional.ofNullable(gone);
    }

    public Optional<DisplaySpot> byId(int id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<DisplaySpot> at(String world, int x, int y, int z) {
        return Optional.ofNullable(byBlock.get(DisplaySpot.blockKey(world, x, y, z)));
    }

    /** A shop's spots in fill order (disabled ones included). */
    public List<DisplaySpot> ofShop(int shopId) {
        List<DisplaySpot> spots = new ArrayList<>();
        for (DisplaySpot spot : byId.values()) {
            if (spot.shopId() == shopId) {
                spots.add(spot);
            }
        }
        spots.sort(ORDER);
        return spots;
    }

    /** The shops with at least one spot in this chunk. */
    public List<Integer> shopsInChunk(String world, int chunkX, int chunkZ) {
        TreeSet<Integer> shops = new TreeSet<>();
        for (DisplaySpot spot : byId.values()) {
            if (spot.world().equals(world) && spot.chunkX() == chunkX && spot.chunkZ() == chunkZ) {
                shops.add(spot.shopId());
            }
        }
        return new ArrayList<>(shops);
    }

    /** Every shop with spots, lowest id first. */
    public List<Integer> shops() {
        TreeSet<Integer> shops = new TreeSet<>();
        byId.values().forEach(spot -> shops.add(spot.shopId()));
        return new ArrayList<>(shops);
    }

    public List<DisplaySpot> all() {
        List<DisplaySpot> spots = new ArrayList<>(byId.values());
        spots.sort(Comparator.comparingInt(DisplaySpot::shopId).thenComparing(ORDER));
        return spots;
    }

    public int size() {
        return byId.size();
    }
}
