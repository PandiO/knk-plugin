package net.knightsandkings.knk.paper.duel.ui;

import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.domain.item.KnkKit;
import net.knightsandkings.knk.core.domain.item.KnkKitClaimResult;
import net.knightsandkings.knk.core.ports.api.KitsQueryApi;
import net.knightsandkings.knk.paper.kit.KitGrantPlacer;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The item lines of a kit for the kit picker's preview (DESIGN §6.4 "with item previews"): resolved once per kit
 * through the same path as the loaned kit ({@code KitGrantPlacer.resolveAsync}), without claiming it. Empty until
 * the first load finished; the picker shows the lines the next time it opens.
 */
public final class DuelKitPreviews implements IntFunction<List<String>> {

    private static final int MAX_LINES = 12;

    private final KitsQueryApi kits;
    private final ItemBlueprintsDataAccess blueprints;
    private final MinecraftMaterialRefsDataAccess materials;
    private final DuelGui gui;
    private final Logger logger;
    private final Map<Integer, List<String>> lines = new ConcurrentHashMap<>();
    private final Set<Integer> loading = ConcurrentHashMap.newKeySet();

    public DuelKitPreviews(KitsQueryApi kits, ItemBlueprintsDataAccess blueprints, MinecraftMaterialRefsDataAccess materials,
                           DuelGui gui, Logger logger) {
        this.kits = Objects.requireNonNull(kits, "kits");
        this.blueprints = Objects.requireNonNull(blueprints, "blueprints");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.gui = Objects.requireNonNull(gui, "gui");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public List<String> apply(int kitId) {
        List<String> known = lines.get(kitId);
        if (known != null) return known;
        if (loading.add(kitId)) load(kitId);
        return List.of();
    }

    /** Start loading the arena's kits before anyone opens the picker. */
    public void warm(List<Integer> kitIds) {
        for (int id : kitIds) apply(id);
    }

    private void load(int kitId) {
        kits.getById(kitId)
                .thenCompose(kit -> KitGrantPlacer.resolveAsync(claimOf(kit), blueprints, materials))
                .whenComplete((resolved, error) -> {
                    loading.remove(kitId);
                    if (error != null || resolved == null) {
                        logger.log(Level.FINE, "[Duel] No preview for kit " + kitId, error);
                        return;
                    }
                    List<String> out = resolved.stream().limit(MAX_LINES).map(item -> gui.describe(item.itemStack())).toList();
                    lines.put(kitId, resolved.size() > MAX_LINES ? append(out, "…and " + (resolved.size() - MAX_LINES) + " more") : out);
                });
    }

    private static List<String> append(List<String> list, String line) {
        return java.util.stream.Stream.concat(list.stream(), java.util.stream.Stream.of(line)).toList();
    }

    private static KnkKitClaimResult claimOf(KnkKit kit) {
        return new KnkKitClaimResult(kit.id(), kit.helmetId(), kit.chestplateId(), kit.leggingsId(), kit.bootsId(),
                kit.shieldId(), kit.handId(), kit.contents() == null ? List.of() : kit.contents());
    }
}
