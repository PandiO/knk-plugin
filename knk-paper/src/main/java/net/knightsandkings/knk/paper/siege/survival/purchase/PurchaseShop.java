package net.knightsandkings.knk.paper.siege.survival.purchase;

import net.knightsandkings.knk.core.survival.SurvivalPoint;
import net.knightsandkings.knk.core.survival.economy.LedgerResult;
import net.knightsandkings.knk.core.survival.purchase.PurchaseRules;
import net.knightsandkings.knk.paper.siege.SiegeMessages;
import net.knightsandkings.knk.paper.siege.survival.MatchHook;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.OfferSpec;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.PurchasePointSpec;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.WorldPoint;
import net.knightsandkings.knk.paper.siege.survival.SurvivalMatch;
import net.knightsandkings.knk.paper.siege.survival.items.MatchItems;
import net.knightsandkings.knk.paper.siege.survival.items.SurvivalItemFactory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * The purchase points of one survival match (DESIGN §8; S44, S58; S4-16): a living member within range right-clicks the
 * point's block (or uses {@code /siege shop} in range) and gets a plain inventory menu of the offers with their prices
 * (the Siege enchant chooser's convention, §11). Buying charges the ledger (idempotent charge id), assembles the blueprint
 * and gives {@code quantity} match items; overflow drops as match-scoped items. Allowed during waves and breaks; never
 * for ghosts; no stock or limits (v1). The offers' samples are assembled once at match start.
 */
public final class PurchaseShop implements MatchHook {

    /** The open shop menu's holder. */
    public static final class Holder implements InventoryHolder {
        private final int lobbyId;
        private final int pointId;
        private final Map<Integer, Integer> offerBySlot = new LinkedHashMap<>();
        private Inventory inventory;

        Holder(int lobbyId, int pointId) {
            this.lobbyId = lobbyId;
            this.pointId = pointId;
        }

        public int lobbyId() {
            return lobbyId;
        }

        public int pointId() {
            return pointId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record Prepared(OfferSpec offer, ItemStack sample, int maxStackSize) { }

    private final SurvivalMatch match;
    private final SurvivalItemFactory factory;
    private final MatchItems items;
    private final Executor mainThread;
    private final Logger logger;
    private final Map<String, Prepared> prepared = new LinkedHashMap<>();
    private final Map<Integer, Location> blocks = new LinkedHashMap<>();
    private int purchases;
    private long spent;
    private boolean ended;

    public PurchaseShop(SurvivalMatch match, SurvivalItemFactory factory, Executor mainThread, Logger logger) {
        this.match = match;
        this.factory = factory;
        this.items = factory.items();
        this.mainThread = mainThread;
        this.logger = logger;
        for (PurchasePointSpec point : match.config().purchasePoints()) {
            point.location().flatMap(PurchaseShop::toLocation).ifPresent(at -> blocks.put(point.id(), at));
        }
    }

    /** Resolves every offer's sample item off the main thread; a shop opens with what is ready. */
    public void prepare() {
        for (PurchasePointSpec point : match.config().purchasePoints()) {
            for (OfferSpec offer : point.offers()) {
                String key = key(point.id(), offer.id());
                factory.blueprintItem(offer.itemBlueprintId(), 1, List.of(), match.matchKey()).whenComplete((stack, ex) -> mainThread.execute(() -> {
                    if (ended) return;
                    if (ex != null || stack == null || stack.isEmpty()) {
                        if (logger != null) logger.warning("[Survival] Purchase offer " + offer.id() + " (" + offer.itemBlueprintName()
                            + ", blueprint " + offer.itemBlueprintId() + ") at point " + point.id() + " cannot be sold: the item could not be assembled");
                        return;
                    }
                    ItemStack sample = stack.get();
                    prepared.put(key, new Prepared(offer, sample, Math.max(1, sample.getMaxStackSize())));
                }));
            }
        }
    }

    public int preparedOffers() {
        return prepared.size();
    }

    // ---- lookups ----

    /** The point whose block was clicked. */
    public Optional<PurchasePointSpec> pointAt(Block block) {
        if (block == null) return Optional.empty();
        for (Map.Entry<Integer, Location> e : blocks.entrySet()) {
            Location at = e.getValue();
            if (at.getWorld() != null && at.getWorld().equals(block.getWorld()) && at.getBlockX() == block.getX()
                && at.getBlockY() == block.getY() && at.getBlockZ() == block.getZ()) {
                return match.config().purchasePoint(e.getKey());
            }
        }
        return Optional.empty();
    }

    /** The nearest point within its interaction range of the player (the command path). */
    public Optional<PurchasePointSpec> pointNear(Player player) {
        Location at = player.getLocation();
        SurvivalPoint me = new SurvivalPoint(at.getX(), at.getY(), at.getZ());
        PurchasePointSpec best = null;
        double bestDistance = Double.MAX_VALUE;
        for (PurchasePointSpec point : match.config().purchasePoints()) {
            Location loc = blocks.get(point.id());
            if (loc == null || loc.getWorld() == null || !loc.getWorld().equals(at.getWorld())) continue;
            SurvivalPoint p = new SurvivalPoint(loc.getX(), loc.getY(), loc.getZ());
            if (!PurchaseRules.withinRange(p, me, point.interactionRange())) continue;
            double d = p.distanceTo(me);
            if (d < bestDistance) {
                best = point;
                bestDistance = d;
            }
        }
        return Optional.ofNullable(best);
    }

    // ---- menu ----

    /** Opens the shop; a message instead when the player may not buy here. */
    public Component open(Player player, PurchasePointSpec point) {
        Component denial = denial(player, point);
        if (denial != null) return denial;
        Holder holder = new Holder(match.lobby().id(), point.id());
        int size = Math.min(54, Math.max(9, ((point.offers().size() + 8) / 9) * 9));
        Inventory gui = Bukkit.createInventory(holder, size,
            Component.text(point.name() + " · " + match.economy().balance(player.getUniqueId()) + " coins", NamedTextColor.DARK_AQUA));
        holder.inventory = gui;
        int slot = 0;
        for (OfferSpec offer : point.offers()) {
            if (slot >= size) break;
            Prepared p = prepared.get(key(point.id(), offer.id()));
            gui.setItem(slot, p == null ? unavailable(offer) : listing(p, match.economy().canAfford(player.getUniqueId(), offer.price())));
            holder.offerBySlot.put(slot, offer.id());
            slot++;
        }
        player.openInventory(gui);
        return null;
    }

    /** A click in the shop: buy the offer, re-render. */
    public void click(Player player, Holder holder, int slot) {
        Integer offerId = holder.offerBySlot.get(slot);
        if (offerId == null) return;
        Optional<PurchasePointSpec> point = match.config().purchasePoint(holder.pointId());
        if (point.isEmpty()) return;
        Component result = buy(player, point.get(), offerId);
        player.sendActionBar(result);
        if (!ended && player.getOpenInventory().getTopInventory().getHolder(false) == holder) open(player, point.get());
    }

    /** Buys one offer: charge (idempotent id), give the items, drop the overflow. */
    public Component buy(Player player, PurchasePointSpec point, int offerId) {
        Component denial = denial(player, point);
        if (denial != null) return denial;
        Optional<OfferSpec> offer = point.offers().stream().filter(o -> o.id() == offerId).findFirst();
        if (offer.isEmpty()) return SiegeMessages.bad("That offer does not exist.");
        Prepared p = prepared.get(key(point.id(), offerId));
        if (p == null) return SiegeMessages.bad("That offer is not available (its item could not be prepared).");
        String chargeId = PurchaseRules.chargeId(point.id(), offerId, UUID.randomUUID());
        LedgerResult charge = match.economy().charge(chargeId, player.getUniqueId(), p.offer().price(), "purchase:" + offerId);
        if (charge instanceof LedgerResult.Rejected rejected) {
            return switch (rejected.reason()) {
                case INSUFFICIENT_BALANCE -> SiegeMessages.bad("Not enough match coins: " + p.offer().price() + " needed, you have "
                    + match.economy().balance(player.getUniqueId()) + ".");
                case PAYER_IS_GHOST -> SiegeMessages.bad("Ghosts cannot buy anything.");
                case MATCH_ENDED -> SiegeMessages.bad("The match is over.");
                case AMOUNT_NOT_POSITIVE -> giveAndReport(player, p, 0); // a free offer: no charge needed
                default -> SiegeMessages.bad("Purchase refused: " + rejected.reason());
            };
        }
        return giveAndReport(player, p, ((LedgerResult.Accepted) charge).balanceAfter());
    }

    private Component giveAndReport(Player player, Prepared p, long balanceAfter) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int amount : PurchaseRules.stacks(p.offer().quantity(), p.maxStackSize())) {
            ItemStack stack = p.sample().clone();
            stack.setAmount(amount);
            stacks.add(stack);
        }
        int dropped = items.give(player, stacks, match.matchKey());
        purchases++;
        spent += p.offer().price();
        if (logger != null) logger.info("[Survival] " + player.getName() + " bought " + p.offer().quantity() + "× " + p.offer().itemBlueprintName()
            + " for " + p.offer().price() + " (balance " + balanceAfter + ", dropped stacks " + dropped + ")");
        return Component.text("Bought " + p.offer().quantity() + "× " + name(p.sample(), p.offer()) + " for " + p.offer().price()
            + " · balance " + balanceAfter + (dropped > 0 ? " (inventory full: dropped at your feet)" : ""), SiegeMessages.GOOD);
    }

    private Component denial(Player player, PurchasePointSpec point) {
        if (ended || match.ending()) return SiegeMessages.bad("The match is over.");
        if (!match.economy().isMember(player.getUniqueId())) return SiegeMessages.bad("You are not in this match.");
        if (match.ghosts().isGhost(player.getUniqueId())) return SiegeMessages.bad("Ghosts cannot buy anything.");
        Location at = blocks.get(point.id());
        if (at != null && at.getWorld() != null) {
            Location me = player.getLocation();
            if (!at.getWorld().equals(me.getWorld()) || !PurchaseRules.withinRange(new SurvivalPoint(at.getX(), at.getY(), at.getZ()),
                new SurvivalPoint(me.getX(), me.getY(), me.getZ()), point.interactionRange())) {
                return SiegeMessages.bad("You are too far from " + point.name() + ".");
            }
        }
        return null;
    }

    // ---- hook ----

    @Override
    public void ended() {
        ended = true;
        prepared.clear();
    }

    @Override
    public List<String> statusLines() {
        return List.of("Shops: " + match.config().purchasePoints().size() + " points, " + prepared.size() + " offers ready, "
            + purchases + " purchases, " + spent + " spent");
    }

    // ---- helpers ----

    static String key(int pointId, int offerId) {
        return pointId + ":" + offerId;
    }

    static Optional<Location> toLocation(WorldPoint point) {
        if (point == null || point.world().isBlank()) return Optional.empty();
        var world = Bukkit.getWorld(point.world());
        return world == null ? Optional.empty() : Optional.of(new Location(world, point.x(), point.y(), point.z()));
    }

    private static ItemStack listing(Prepared p, boolean affordable) {
        ItemStack shown = p.sample().clone();
        shown.setAmount(Math.max(1, Math.min(p.offer().quantity(), p.maxStackSize())));
        ItemMeta meta = shown.getItemMeta();
        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Component.text("Price: " + p.offer().price() + " match coins", affordable ? NamedTextColor.GOLD : NamedTextColor.RED)
            .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Quantity: " + p.offer().quantity(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(affordable ? "Click to buy." : "You cannot afford this yet.", affordable ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        shown.setItemMeta(meta);
        return shown;
    }

    private static ItemStack unavailable(OfferSpec offer) {
        ItemStack stack = new ItemStack(org.bukkit.Material.BARRIER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(offer.itemBlueprintName(), NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Not available: the item could not be prepared.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        stack.setItemMeta(meta);
        return stack;
    }

    private static String name(ItemStack sample, OfferSpec offer) {
        ItemMeta meta = sample.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }
        return offer.itemBlueprintName();
    }
}
