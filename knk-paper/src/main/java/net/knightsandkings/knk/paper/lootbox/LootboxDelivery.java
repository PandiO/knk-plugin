package net.knightsandkings.knk.paper.lootbox;

import net.knightsandkings.knk.core.dataaccess.EnchantmentDefinitionsDataAccess;
import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.domain.enchantments.KnkEnchantmentDefinition;
import net.knightsandkings.knk.core.domain.item.KnkGrade;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprintDefaultEnchantment;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimEnchantment;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.LootboxDeliveryMethod;
import net.knightsandkings.knk.core.lootbox.LootboxRejectedException;
import net.knightsandkings.knk.core.ports.api.LootboxesCommandApi;
import net.knightsandkings.knk.paper.item.BlueprintItemAssembler;
import net.knightsandkings.knk.paper.kit.KitGrantPlacer;
import net.knightsandkings.knk.paper.mapper.ItemInstanceTag;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hands a claimed item to the player (docs/specs/lootboxes/DESIGN.md §3.4) and confirms it to the API.
 * <ol>
 *   <li>Resolve (async): the blueprint, its material and any enchantment definition the claim can't describe itself.</li>
 *   <li>Build (main thread) with {@link BlueprintItemAssembler}: the blueprint with the claim's item grade (star line
 *       and {@code knk_grade} tag), then the claim's enchantments in two passes. The blueprint's own defaults go on as
 *       authored ({@code /knk itemblueprints give} rules: v1's one-offs keep Sharpness on pickaxes); the rolled ones
 *       with the vanilla rules on as a safety net behind the API's applicability check. Anything the item still
 *       couldn't take is logged as a warning and written to the claim's delivery note, never dropped silently. The
 *       last step stamps {@code knk_item_instance} when the claim has an instance (stackable items get no tag, so they
 *       stack with ordinary ones).</li>
 *   <li>Place: into the inventory; whatever doesn't fit drops at the player's feet, owner-locked and not for mobs.</li>
 *   <li>Confirm {@code delivered} (retried a few times). A claim left unconfirmed is redelivered on the next join,
 *       where an instanced item already in the inventory or ender chest is confirmed instead of given twice.</li>
 * </ol>
 * A claim this server already handed over is never given again while it runs, whatever the API says about it: a
 * replay or a {@code pending} read can race a confirmation still on its way, and a failed confirmation followed by a
 * relog would otherwise give a second copy (of a stackable item always; of an instanced one once it was moved out of
 * the inventory and ender chest). Such a delivery is only confirmed again.
 */
public final class LootboxDelivery {

    private static final Logger LOGGER = Logger.getLogger(LootboxDelivery.class.getName());
    private static final int ACK_ATTEMPTS = 3;
    // Claims handed over since the server started (claim id -> how), main thread only. Bounded: the oldest are long
    // confirmed by the time 10,000 newer ones exist.
    private static final int HANDED_OVER_LIMIT = 10_000;

    /**
     * What happened. {@code given} = an item went to the player now; {@code alreadyHeld} = the claim was handed over
     * before (a redelivery found the instance in their inventory or ender chest, or this server gave it already), so
     * nothing was given now. {@code item} is a copy of what was given (for messages).
     */
    public record Outcome(boolean given, boolean alreadyHeld, ItemStack item, LootboxDeliveryMethod method, List<String> skipped) {
        static Outcome failed() {
            return new Outcome(false, false, null, null, List.of());
        }
    }

    /** The claim's enchantments split into the blueprint's own (applied as authored) and rolled ones (vanilla rules). */
    record Requests(List<BlueprintItemAssembler.EnchantmentRequest> defaults, List<BlueprintItemAssembler.EnchantmentRequest> rolled) {
    }

    private record Resolved(KnkItemBlueprint blueprint, String materialKey, Map<Integer, KnkEnchantmentDefinition> definitions) {
    }

    private final Executor mainThread;
    private final ItemBlueprintsDataAccess blueprints;
    private final MinecraftMaterialRefsDataAccess materials;
    private final EnchantmentDefinitionsDataAccess definitions;
    private final LootboxesCommandApi commandApi;
    private final BlueprintItemAssembler assembler;
    private final Map<Integer, LootboxDeliveryMethod> handedOver = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, LootboxDeliveryMethod> eldest) {
            return size() > HANDED_OVER_LIMIT;
        }
    };

    public LootboxDelivery(
            Executor mainThread,
            ItemBlueprintsDataAccess blueprints,
            MinecraftMaterialRefsDataAccess materials,
            EnchantmentDefinitionsDataAccess definitions,
            LootboxesCommandApi commandApi,
            BlueprintItemAssembler assembler
    ) {
        this.mainThread = mainThread;
        this.blueprints = blueprints;
        this.materials = materials;
        this.definitions = definitions;
        this.commandApi = commandApi;
        this.assembler = assembler;
    }

    /** Whether the player has a free slot: the pre-check before any claim with {@code full-inventory: refuse}. */
    public static boolean hasRoom(Player player) {
        PlayerInventory inventory = player.getInventory();
        return inventory != null && inventory.firstEmpty() >= 0;
    }

    /**
     * The claim's item, built and not handed over yet (the opening reel shows it first, DESIGN.md §3.9).
     * {@code skipped} lists enchantments the item couldn't take (logged and sent as the delivery note).
     */
    public record Prepared(KnkLootboxClaimResult claim, ItemStack item, List<String> skipped) {
        public Prepared {
            skipped = skipped == null ? List.of() : List.copyOf(skipped);
        }
    }

    /**
     * Builds, gives and confirms {@code claim} for {@code player}. The future completes on the main thread; with a
     * failed outcome (blueprint gone, player left) the claim stays unconfirmed and comes back through {@code pending}.
     */
    public CompletableFuture<Outcome> deliver(Player player, KnkLootboxClaimResult claim, boolean redelivery) {
        return prepare(claim).thenApply(prepared -> {
            if (prepared == null) {
                return Outcome.failed();
            }
            try {
                return handOver(player, prepared, redelivery);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Lootbox claim " + claim.claimId() + ": delivery failed; left for redelivery", e);
                return Outcome.failed();
            }
        });
    }

    /**
     * Resolves and builds the claim's item without giving it. Completes on the main thread; null when the blueprint
     * can't be resolved (the claim stays unconfirmed and is redelivered on the next join).
     */
    public CompletableFuture<Prepared> prepare(KnkLootboxClaimResult claim) {
        CompletableFuture<Prepared> done = new CompletableFuture<>();
        resolve(claim).whenComplete((resolved, ex) -> mainThread.execute(() -> {
            try {
                if (ex != null || resolved == null) {
                    LOGGER.log(Level.SEVERE, "Lootbox claim " + claim.claimId() + ": could not resolve blueprint "
                            + claim.itemBlueprintId() + "; left for redelivery", ex);
                    done.complete(null);
                    return;
                }
                List<String> skipped = new ArrayList<>();
                ItemStack item = build(resolved.blueprint(), resolved.materialKey(), claim, resolved.definitions(), skipped);
                done.complete(new Prepared(claim, item, skipped));
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Lootbox claim " + claim.claimId() + ": building the item failed; left for redelivery", e);
                done.complete(null);
            }
        }));
        return done;
    }

    /**
     * Main thread: gives a prepared item and confirms it, unless this server handed the claim over already or (a
     * redelivery) the player still holds its instance. Failed when the player is offline.
     */
    public Outcome handOver(Player player, Prepared prepared, boolean redelivery) {
        KnkLootboxClaimResult claim = prepared.claim();
        if (!player.isOnline()) {
            return Outcome.failed();
        }
        LootboxDeliveryMethod earlier = handedOver.get(claim.claimId());
        if (earlier != null) {
            // Given by this server already; its confirmation failed or is still on its way.
            acknowledge(claim, earlier, "already handed over on this server; not given again");
            return new Outcome(false, true, null, earlier, List.of());
        }
        if (redelivery && claim.itemInstanceId() != null && holdsInstance(player, claim.itemInstanceId())) {
            // Given before, but the confirmation never reached the API (crash between give and ACK).
            handedOver.put(claim.claimId(), LootboxDeliveryMethod.REDELIVERED);
            acknowledge(claim, LootboxDeliveryMethod.REDELIVERED, "already held on rejoin; not given again");
            return new Outcome(false, true, null, LootboxDeliveryMethod.REDELIVERED, List.of());
        }

        // The prepared stack itself goes in (the reel only ever showed copies of it); `shown` is for the messages.
        ItemStack item = prepared.item();
        ItemStack shown = item.clone();
        LootboxDeliveryMethod method = place(player, item, redelivery ? LootboxDeliveryMethod.REDELIVERED : LootboxDeliveryMethod.INVENTORY);
        handedOver.put(claim.claimId(), method);

        String note = null;
        if (!prepared.skipped().isEmpty()) {
            note = "enchantments the item couldn't take: " + String.join(", ", prepared.skipped());
            LOGGER.warning("Lootbox claim " + claim.claimId() + " (blueprint " + claim.itemBlueprintId() + ", instance "
                    + claim.itemInstanceId() + "): " + note);
        }
        acknowledge(claim, method, note);
        return new Outcome(true, false, shown, method, prepared.skipped());
    }

    /**
     * A look-alike of a pool item for the opening reel: the blueprint's material, name, lore and grade line; no
     * enchantments, no instance tag. Completes on the main thread; null when it can't be resolved.
     */
    public CompletableFuture<ItemStack> preview(int itemBlueprintId) {
        CompletableFuture<ItemStack> done = new CompletableFuture<>();
        blueprints.getByIdAsync(itemBlueprintId).thenCompose(result -> {
            KnkItemBlueprint blueprint = result != null ? result.value().orElse(null) : null;
            if (blueprint == null) {
                return CompletableFuture.<Map.Entry<KnkItemBlueprint, String>>completedFuture(null);
            }
            return KitGrantPlacer.resolveMaterialNamespaceKey(blueprint, materials)
                    .thenApply(key -> key == null || key.isBlank() ? null : Map.entry(blueprint, key));
        }).whenComplete((resolved, ex) -> mainThread.execute(() -> {
            if (ex != null || resolved == null) {
                done.complete(null);
                return;
            }
            try {
                ItemStack item = assembler.build(resolved.getKey(), resolved.getValue());
                done.complete(item);
            } catch (Exception e) {
                done.complete(null);
            }
        }));
        return done;
    }

    /** Whether this server already gave the claim (a replay or pending read must not show a second reel). */
    public boolean wasHandedOver(int claimId) {
        return handedOver.containsKey(claimId);
    }

    /** Main thread: the built item, enchanted and tagged, with the claim's quantity. */
    ItemStack build(
            KnkItemBlueprint blueprint,
            String materialKey,
            KnkLootboxClaimResult claim,
            Map<Integer, KnkEnchantmentDefinition> fetched,
            List<String> skipped
    ) {
        KnkItemBlueprint graded = withClaimGrade(blueprint, claim);
        ItemStack item = assembler.build(graded, materialKey);
        Requests requests = requests(graded, claim, fetched);
        skipped.addAll(assembler.enchant(item, graded, requests.defaults(), BlueprintItemAssembler.Options.DEFAULTS).skipped());
        skipped.addAll(assembler.enchant(item, graded, requests.rolled(), BlueprintItemAssembler.Options.DEFAULTS
                .withVanillaRules(true)
                .withMetaStamp(instanceStamp(claim))).skipped());

        int maxStack = blueprint.maxStackSize() != null && blueprint.maxStackSize() > 0 ? blueprint.maxStackSize() : item.getMaxStackSize();
        item.setAmount(Math.max(1, Math.min(claim.quantity(), Math.max(1, maxStack))));
        return item;
    }

    /** The last assembly step: the instance id for a non-stackable item, nothing for a stackable one. */
    static Consumer<ItemMeta> instanceStamp(KnkLootboxClaimResult claim) {
        Long instanceId = claim.itemInstanceId();
        return meta -> ItemInstanceTag.stamp(meta, instanceId);
    }

    /**
     * Splits the claim's final enchantment set: the ones the blueprint itself carries (same definition; the level may
     * be a higher roll merged in) and the rolled ones. Each uses the fetched definition when there is one, else one
     * described by the claim row itself.
     */
    static Requests requests(KnkItemBlueprint blueprint, KnkLootboxClaimResult claim, Map<Integer, KnkEnchantmentDefinition> fetched) {
        Set<Integer> defaultIds = new HashSet<>();
        if (blueprint.defaultEnchantments() != null) {
            for (KnkItemBlueprintDefaultEnchantment relation : blueprint.defaultEnchantments()) {
                if (relation != null && relation.enchantmentDefinitionId() != null) {
                    defaultIds.add(relation.enchantmentDefinitionId());
                }
            }
        }
        List<BlueprintItemAssembler.EnchantmentRequest> defaults = new ArrayList<>();
        List<BlueprintItemAssembler.EnchantmentRequest> rolled = new ArrayList<>();
        for (KnkLootboxClaimEnchantment enchantment : claim.enchantments()) {
            KnkEnchantmentDefinition definition = fetched != null && fetched.containsKey(enchantment.definitionId())
                    ? fetched.get(enchantment.definitionId())
                    : describe(enchantment);
            BlueprintItemAssembler.EnchantmentRequest request =
                    new BlueprintItemAssembler.EnchantmentRequest(enchantment.definitionId(), definition, enchantment.level());
            (defaultIds.contains(enchantment.definitionId()) ? defaults : rolled).add(request);
        }
        return new Requests(defaults, rolled);
    }

    /**
     * A definition described by the claim row: a {@code minecraft:*} key is its own base enchantment, a custom key is
     * resolved by the custom registry. The API already capped the level; {@code maxLevel} = the level keeps the
     * assembler's custom max check at the registry's own maximum.
     */
    static KnkEnchantmentDefinition describe(KnkLootboxClaimEnchantment enchantment) {
        String key = enchantment.key();
        boolean vanillaKey = key != null && key.startsWith("minecraft:");
        return new KnkEnchantmentDefinition(enchantment.definitionId(), key, key, null, enchantment.isCustom(),
                Math.max(1, enchantment.level()), null, !enchantment.isCustom() && vanillaKey ? key : null);
    }

    /** The blueprint as the claim graded it (a pool override or an ungraded special's ★5 can differ from its own). */
    static KnkItemBlueprint withClaimGrade(KnkItemBlueprint blueprint, KnkLootboxClaimResult claim) {
        if (claim.itemGradeStars() == null || claim.itemGradeStars() <= 0) {
            return blueprint;
        }
        KnkGrade own = blueprint.grade();
        if (own != null && Objects.equals(own.stars(), claim.itemGradeStars())) {
            return blueprint;
        }
        KnkGrade grade = new KnkGrade(claim.itemGradeId(), own != null && Objects.equals(own.id(), claim.itemGradeId()) ? own.name() : null,
                claim.itemGradeStars());
        return new KnkItemBlueprint(blueprint.id(), blueprint.name(), blueprint.description(), blueprint.iconMaterialRefId(),
                blueprint.iconNamespaceKey(), blueprint.defaultDisplayName(), blueprint.defaultDisplayDescription(),
                blueprint.defaultQuantity(), blueprint.maxStackSize(), blueprint.defaultEnchantments(),
                blueprint.defaultEnchantmentsCount(), grade, blueprint.tags(), blueprint.origins());
    }

    /**
     * Main thread: into the inventory, leftovers dropped at the player's feet owned by them. Returns
     * {@code whenAllFit} or {@link LootboxDeliveryMethod#DROPPED_OWNED}.
     */
    static LootboxDeliveryMethod place(Player player, ItemStack item, LootboxDeliveryMethod whenAllFit) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        if (leftovers == null || leftovers.isEmpty()) {
            return whenAllFit;
        }
        Location at = player.getLocation();
        for (ItemStack rest : leftovers.values()) {
            Item dropped = player.getWorld().dropItem(at, rest);
            dropped.setOwner(player.getUniqueId());
            dropped.setCanMobPickup(false);
        }
        return LootboxDeliveryMethod.DROPPED_OWNED;
    }

    /** Whether the instance is already in the player's inventory or ender chest (the redelivery dedupe scan). */
    static boolean holdsInstance(Player player, long instanceId) {
        return ItemInstanceTag.containsInstance(player.getInventory().getContents(), instanceId)
                || ItemInstanceTag.containsInstance(player.getEnderChest().getContents(), instanceId);
    }

    /** Confirms delivery, retrying twice (10 s, 20 s); after that the claim is redelivered on the next join. */
    public void acknowledge(KnkLootboxClaimResult claim, LootboxDeliveryMethod method, String note) {
        acknowledge(claim, method, note, 1);
    }

    private void acknowledge(KnkLootboxClaimResult claim, LootboxDeliveryMethod method, String note, int attempt) {
        commandApi.markDelivered(claim.claimId(), method, note, claim.userId()).whenComplete((ignored, ex) -> {
            if (ex == null) {
                return;
            }
            if (attempt >= ACK_ATTEMPTS) {
                LOGGER.warning("Lootbox claim " + claim.claimId() + ": delivery not confirmed ("
                        + LootboxRejectedException.unwrap(ex).getMessage() + "); it comes back on the player's next join");
                return;
            }
            CompletableFuture.delayedExecutor(10L * attempt, TimeUnit.SECONDS)
                    .execute(() -> acknowledge(claim, method, note, attempt + 1));
        });
    }

    private CompletableFuture<Resolved> resolve(KnkLootboxClaimResult claim) {
        return blueprints.getByIdAsync(claim.itemBlueprintId()).thenCompose(result -> {
            KnkItemBlueprint blueprint = result != null ? result.value().orElse(null) : null;
            if (blueprint == null) {
                return CompletableFuture.<Resolved>failedFuture(new IllegalStateException("ItemBlueprint " + claim.itemBlueprintId() + " not found"));
            }
            CompletableFuture<String> material = KitGrantPlacer.resolveMaterialNamespaceKey(blueprint, materials);
            return material.thenCombine(fetchUndescribable(claim), (key, defs) -> {
                if (key == null || key.isBlank()) {
                    throw new IllegalStateException("ItemBlueprint " + blueprint.id() + " has no material");
                }
                return new Resolved(blueprint, key, defs);
            });
        });
    }

    /**
     * Full definitions for the vanilla enchantments whose key isn't a {@code minecraft:*} key (a definition that
     * names its base enchantment separately); every other one is described by the claim row.
     */
    private CompletableFuture<Map<Integer, KnkEnchantmentDefinition>> fetchUndescribable(KnkLootboxClaimResult claim) {
        Map<Integer, KnkEnchantmentDefinition> fetched = new HashMap<>();
        if (definitions == null) {
            return CompletableFuture.completedFuture(fetched);
        }
        List<CompletableFuture<Void>> calls = new ArrayList<>();
        for (KnkLootboxClaimEnchantment enchantment : claim.enchantments()) {
            if (enchantment.isCustom() || (enchantment.key() != null && enchantment.key().startsWith("minecraft:"))) {
                continue;
            }
            calls.add(definitions.getByIdAsync(enchantment.definitionId())
                    .thenAccept(result -> result.value().ifPresent(def -> {
                        synchronized (fetched) {
                            fetched.put(enchantment.definitionId(), def);
                        }
                    }))
                    .exceptionally(ex -> null));
        }
        return CompletableFuture.allOf(calls.toArray(new CompletableFuture[0])).thenApply(ignored -> fetched);
    }
}
