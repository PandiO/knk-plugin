package net.knightsandkings.knk.paper.lootbox;

import net.knightsandkings.knk.core.lootbox.KnkLootboxToken;
import net.knightsandkings.knk.core.lootbox.LootboxDeliveryMethod;
import net.knightsandkings.knk.core.lootbox.LootboxRejectedException;
import net.knightsandkings.knk.core.ports.api.LootboxesCommandApi;
import net.knightsandkings.knk.core.ports.api.LootboxesQueryApi;
import net.knightsandkings.knk.paper.mapper.LootboxTokenTag;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Lootbox token items in players' hands (docs/specs/lootboxes/IMPLEMENTATION_PLAN.md Phase 5): builds the item for a
 * token the API issued (tagged {@code knightsandkings:knk_lootbox_token}), hands tokens over and confirms them
 * ({@code delivered}), and removes spent copies.
 * <p>
 * Tokens the API issued on its own (a premium tier, a kit) or that were never confirmed are fetched with
 * {@code undelivered} on join and on a {@code LootboxTokensIssued} notification. Every hand-over first scans the
 * inventory and ender chest for the token, so a token already held is only confirmed, never given twice. A copy that
 * slips through (dropped at the player's feet, then a second delivery) is harmless: only the first open of a token
 * works.
 */
public final class LootboxTokenDelivery {

    private static final Logger LOGGER = Logger.getLogger(LootboxTokenDelivery.class.getName());
    private static final int ACK_ATTEMPTS = 3;

    private final Executor mainThread;
    private final LootboxesQueryApi queryApi;
    private final LootboxesCommandApi commandApi;
    private final Supplier<LootboxSettings> settings;
    private final Function<Player, Integer> userIdOf;
    private final Function<KnkLootboxToken, ItemStack> itemFactory;

    // Players with an undelivered-token fetch in flight → whether another was asked for meanwhile (main thread only).
    private final Map<UUID, Boolean> fetching = new HashMap<>();

    public LootboxTokenDelivery(
            Executor mainThread,
            LootboxesQueryApi queryApi,
            LootboxesCommandApi commandApi,
            Supplier<LootboxSettings> settings,
            Function<Player, Integer> userIdOf
    ) {
        this(mainThread, queryApi, commandApi, settings, userIdOf, null);
    }

    LootboxTokenDelivery(
            Executor mainThread,
            LootboxesQueryApi queryApi,
            LootboxesCommandApi commandApi,
            Supplier<LootboxSettings> settings,
            Function<Player, Integer> userIdOf,
            Function<KnkLootboxToken, ItemStack> itemFactory
    ) {
        this.mainThread = mainThread;
        this.queryApi = queryApi;
        this.commandApi = commandApi;
        this.settings = settings;
        this.userIdOf = userIdOf;
        this.itemFactory = itemFactory != null ? itemFactory : token -> build(token, settings.get());
    }

    /** Main thread: the token item, e.g. "&amp;dLegendary Weapons Lootbox ★★★★★", identified by its PDC tag only. */
    public static ItemStack build(KnkLootboxToken token, LootboxSettings settings) {
        Material material = Material.matchMaterial(settings.tokenMaterial());
        if (material == null || !material.isItem() || material.isAir()) {
            material = Material.matchMaterial(LootboxSettings.DEFAULT_TOKEN_MATERIAL);
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', settings.coloredLabel(token.boxLabel(), token.boxStars())));
            meta.setLore(List.of(
                    ChatColor.GRAY + "Right-click to open.",
                    ChatColor.DARK_GRAY + "Lootbox token"));
            LootboxTokenTag.stamp(meta, token.token());
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Main thread: hands over each token the player doesn't hold yet (inventory first, leftovers dropped at their feet
     * owner-locked), then confirms all of them. Returns how many items were given now.
     */
    public int give(Player player, int userId, List<KnkLootboxToken> tokens) {
        if (tokens == null || tokens.isEmpty() || !player.isOnline()) {
            return 0;
        }
        int given = 0;
        List<UUID> confirmed = new ArrayList<>();
        for (KnkLootboxToken token : tokens) {
            if (token == null || token.token() == null) {
                continue;
            }
            if (!holds(player, token.token())) {
                LootboxDelivery.place(player, itemFactory.apply(token), LootboxDeliveryMethod.INVENTORY);
                given++;
            }
            confirmed.add(token.token());
        }
        if (!confirmed.isEmpty()) {
            acknowledge(userId, confirmed, 1);
        }
        return given;
    }

    /**
     * Main thread: fetches the player's undelivered tokens and hands them over. A call while a fetch for the same player
     * is in flight runs once more after it (tokens issued in between aren't missed, and never fetched twice at once).
     */
    public void deliverUndelivered(Player player) {
        Integer userId = userIdOf.apply(player);
        if (userId == null) {
            return;
        }
        UUID id = player.getUniqueId();
        if (fetching.containsKey(id)) {
            fetching.put(id, true);
            return;
        }
        fetching.put(id, false);
        queryApi.getUndeliveredTokens(userId).whenComplete((tokens, ex) -> mainThread.execute(() -> {
            boolean again = Boolean.TRUE.equals(fetching.remove(id));
            if (ex != null) {
                LOGGER.warning("Could not read undelivered lootbox tokens for user " + userId + ": "
                        + LootboxRejectedException.unwrap(ex).getMessage());
            } else if (player.isOnline()) {
                int given = give(player, userId, tokens);
                if (given > 0) {
                    player.sendMessage(ChatColor.GREEN + "You received " + given + " lootbox" + (given == 1 ? "" : "es")
                            + " - right-click to open.");
                }
            }
            if (again && player.isOnline()) {
                deliverUndelivered(player);
            }
        }));
    }

    /** Whether the token is already in the player's inventory or ender chest. */
    public static boolean holds(Player player, UUID token) {
        return LootboxTokenTag.contains(player.getInventory().getContents(), token)
                || (player.getEnderChest() != null && LootboxTokenTag.contains(player.getEnderChest().getContents(), token));
    }

    /**
     * Main thread: takes one copy of the token out of the player's inventory (the held slot first) after the API
     * consumed it. False when they no longer have one (dropped or moved it while the open was on its way).
     */
    public static boolean removeOne(Player player, UUID token) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        int held = inventory.getHeldItemSlot();
        int slot = -1;
        if (held >= 0 && held < contents.length && LootboxTokenTag.read(contents[held]).filter(token::equals).isPresent()) {
            slot = held;
        } else {
            for (int i = 0; i < contents.length; i++) {
                if (LootboxTokenTag.read(contents[i]).filter(token::equals).isPresent()) {
                    slot = i;
                    break;
                }
            }
        }
        if (slot < 0) {
            return false;
        }
        ItemStack item = contents[slot];
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
            inventory.setItem(slot, item);
        } else {
            inventory.setItem(slot, null);
        }
        return true;
    }

    /** Main thread: removes every copy of a spent (opened elsewhere, revoked) token from the inventory; returns how many items. */
    public static int removeAll(Player player, UUID token) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        int removed = 0;
        for (int i = 0; i < contents.length; i++) {
            if (LootboxTokenTag.read(contents[i]).filter(token::equals).isPresent()) {
                removed += contents[i].getAmount();
                inventory.setItem(i, null);
            }
        }
        return removed;
    }

    /** Confirms, retrying twice (10 s, 20 s); an unconfirmed token is fetched again on the next join and only confirmed. */
    private void acknowledge(int userId, List<UUID> tokens, int attempt) {
        commandApi.markTokensDelivered(userId, tokens).whenComplete((ignored, ex) -> {
            if (ex == null) {
                return;
            }
            if (attempt >= ACK_ATTEMPTS) {
                LOGGER.warning("Lootbox tokens " + tokens + ": delivery not confirmed ("
                        + LootboxRejectedException.unwrap(ex).getMessage() + "); confirmed on the player's next join");
                return;
            }
            CompletableFuture.delayedExecutor(10L * attempt, TimeUnit.SECONDS).execute(() -> acknowledge(userId, tokens, attempt + 1));
        });
    }
}
