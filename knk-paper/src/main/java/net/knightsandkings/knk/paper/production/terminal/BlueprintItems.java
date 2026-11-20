package net.knightsandkings.knk.paper.production.terminal;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.api.impl.enchantment.LocalEnchantmentRepositoryImpl;
import net.knightsandkings.knk.paper.item.BlueprintItemAssembler;
import net.knightsandkings.knk.paper.kit.KitGrantPlacer;

/**
 * One item per blueprint (PandiO/knk-workspace#74), built the way kits build them ({@link BlueprintItemAssembler}, the
 * blueprint's default enchantments): what a terminal shows and hands out and what a display holds. Cached per blueprint;
 * a blueprint that can't be built is a {@code PAPER} named after it.
 */
public class BlueprintItems {

    private static final Logger LOGGER = Logger.getLogger(BlueprintItems.class.getName());
    private static final BlueprintItemAssembler ASSEMBLER = new BlueprintItemAssembler(new LocalEnchantmentRepositoryImpl());

    /** One item and its stack size. */
    public record Template(ItemStack item, int maxStack, boolean built) {
        public ItemStack copy(int amount) {
            ItemStack copy = item.clone();
            copy.setAmount(Math.max(1, Math.min(amount, Math.max(1, maxStack))));
            return copy;
        }
    }

    private final ItemBlueprintsDataAccess blueprints;
    private final MinecraftMaterialRefsDataAccess materials;
    private final Map<Integer, Template> cache = new ConcurrentHashMap<>();

    public BlueprintItems(ItemBlueprintsDataAccess blueprints, MinecraftMaterialRefsDataAccess materials) {
        this.blueprints = blueprints;
        this.materials = materials;
    }

    /** The cached item, or null when it was never built. */
    public Template cached(int blueprintId) {
        return cache.get(blueprintId);
    }

    public CompletableFuture<Template> template(int blueprintId, String fallbackName) {
        Template cached = cache.get(blueprintId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return blueprints.getByIdAsync(blueprintId)
                .thenCompose(result -> {
                    KnkItemBlueprint blueprint = result != null ? result.value().orElse(null) : null;
                    if (blueprint == null) {
                        return CompletableFuture.completedFuture(fallback(fallbackName));
                    }
                    return KitGrantPlacer.resolveMaterialNamespaceKey(blueprint, materials)
                            .thenApply(key -> build(blueprint, key, fallbackName));
                })
                .exceptionally(ex -> {
                    LOGGER.log(Level.FINE, "terminal: blueprint " + blueprintId + " could not be built", ex);
                    return fallback(fallbackName);
                })
                .thenApply(template -> {
                    if (template.built()) {
                        cache.put(blueprintId, template);
                    }
                    return template;
                });
    }

    private static Template build(KnkItemBlueprint blueprint, String materialKey, String fallbackName) {
        if (materialKey == null || materialKey.isBlank()) {
            return fallback(fallbackName);
        }
        try {
            BlueprintItemAssembler.Result result = ASSEMBLER.assembleDefaults(blueprint, materialKey, Map.of(), 1);
            ItemStack item = result.itemStack();
            item.setAmount(1);
            return new Template(item, BlueprintItemAssembler.maxStackSize(blueprint, item), true);
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "terminal: blueprint " + blueprint.id() + " has no usable material " + materialKey, e);
            return fallback(fallbackName);
        }
    }

    static Template fallback(String name) {
        ItemStack paper = new ItemStack(Material.PAPER);
        var meta = paper.getItemMeta();
        if (meta != null && name != null) {
            meta.setDisplayName(name);
            paper.setItemMeta(meta);
        }
        return new Template(paper, 64, false);
    }
}
