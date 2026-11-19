package net.knightsandkings.knk.paper.items.identity;

import net.knightsandkings.knk.core.items.identity.ItemConversionLog;
import net.knightsandkings.knk.core.items.identity.MaterialBlueprintMap;
import net.knightsandkings.knk.core.ports.api.ItemIdentityApi;
import net.knightsandkings.knk.paper.mapper.BlueprintTag;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Shared fakes for the item identity MockBukkit tests (#141). */
final class ItemIdentityTestSupport {

    static final MaterialBlueprintMap MAP = MaterialBlueprintMap.of(List.of(
            new MaterialBlueprintMap.Entry("minecraft:oak_log", 10, true),
            new MaterialBlueprintMap.Entry("minecraft:bread", 20, false),
            new MaterialBlueprintMap.Entry("minecraft:cod", 30, true),
            new MaterialBlueprintMap.Entry("minecraft:emerald", 40, true)));

    private ItemIdentityTestSupport() {
    }

    /** The API without a server: a fixed map (or a failure) and the reported counts. */
    static final class FakeApi implements ItemIdentityApi {
        MaterialBlueprintMap map = MAP;
        boolean fail;
        final List<List<ItemConversionLog.Count>> reports = new ArrayList<>();

        @Override
        public CompletableFuture<MaterialBlueprintMap> fetchMaterialMap() {
            return fail ? CompletableFuture.failedFuture(new IllegalStateException("API down")) : CompletableFuture.completedFuture(map);
        }

        @Override
        public CompletableFuture<Void> reportConversionCounts(List<ItemConversionLog.Count> counts) {
            if (fail) {
                return CompletableFuture.failedFuture(new IllegalStateException("API down"));
            }
            reports.add(counts);
            return CompletableFuture.completedFuture(null);
        }
    }

    /** A plain item carrying a (stale) blueprint tag. */
    static ItemStack taggedPlain(Material material, int amount, int blueprintId) {
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(BlueprintTag.BLUEPRINT_KEY, PersistentDataType.INTEGER, blueprintId);
        item.setItemMeta(meta);
        return item;
    }

    @SuppressWarnings("deprecation")
    static ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    static ItemIdentityService service(FakeApi api, boolean loaded) {
        ItemIdentityService service = new ItemIdentityService(null, api, new ItemIdentitySettings(true, true, 10, 10), null);
        if (loaded) {
            service.replaceMap(api.map);
        }
        return service;
    }
}
