package net.knightsandkings.knk.paper.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import net.knightsandkings.knk.core.ports.api.ItemInstancesApi;
import net.knightsandkings.knk.paper.mapper.ItemInstanceTag;

/**
 * {@code /knk item more} (PandiO/knk-workspace#175, admin node {@value #NODE}): more of the item in your main hand.
 * <ul>
 *   <li><b>Plain items</b> (no {@code knightsandkings:knk_item_instance}): below its max stack size the held stack is
 *       filled to it; already full, a new full stack goes into an empty inventory slot - "no room" when there is
 *       none. The copy keeps everything else (blueprint data, lore, enchantments).</li>
 *   <li><b>Items with an instance id</b>: a copy never shares an id (two items with one id are a provable dupe), so
 *       every copy is a separate item stamped with a <b>new</b> ItemInstance minted by knk-web-api
 *       ({@code POST api/ItemInstances/{id}/clone}, owned by you, audited under you). They come as
 *       single items: as many as the held stack lacks from its max size, or one max-size stack's worth when it is
 *       full - for the usual non-stackable instance item (max 1) that is exactly one copy - limited by your empty
 *       slots (each copy takes one). Without the API nothing is copied.</li>
 * </ul>
 * There is no soulbound model to honour (an instance's flags are copied as they are).
 * <p>
 * <b>{@code /knk item more <amount>}</b> (smoke test run 1, 2026-10-27): exactly {@code amount} more, whatever the
 * item's max stack size; any amount from 1 up (developer, 2026-10-27). Plain items are added like a pickup - topping
 * up matching stacks, then empty slots, in stacks of the item's max size (a max-1 blueprint item gives single items)
 * - until the inventory is full. Instance items get one new instance per copy and free slot, in one clone call. What
 * doesn't fit is not given: nothing is ever dropped, and no instance is minted for it; the reply says how many were.
 */
public final class ItemMoreCommand {

    public static final String NODE = "knk.admin.item.more";

    /** Tab completion for the amount. */
    public static final List<String> AMOUNT_SUGGESTIONS = List.of("1", "8", "16", "32", "64");

    private static final String USAGE = "Usage: /knk item more [amount]";

    private static final Logger LOGGER = Logger.getLogger(ItemMoreCommand.class.getName());

    private final Executor mainThread;
    private final Function<UUID, CompletableFuture<Integer>> userIdOf;
    private final ItemInstancesApi itemInstances;

    /**
     * @param userIdOf      a player's knk user id (null when unknown); owner and audited actor of minted copies
     * @param itemInstances null without the API client: instance items are then refused
     */
    public ItemMoreCommand(Executor mainThread, Function<UUID, CompletableFuture<Integer>> userIdOf, ItemInstancesApi itemInstances) {
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread must not be null");
        this.userIdOf = Objects.requireNonNull(userIdOf, "userIdOf must not be null");
        this.itemInstances = itemInstances;
    }

    /** {@code /knk item more}; the node was checked by the caller. */
    public void execute(CommandSender sender) {
        execute(sender, new String[0]);
    }

    /** {@code /knk item more [amount]}; {@code args} come after "more". The node was checked by the caller. */
    public void execute(CommandSender sender, String[] args) {
        Integer requested = null;
        if (args != null && args.length > 0) {
            requested = parseAmount(args.length == 1 ? args[0] : null);
            if (requested == null) {
                sender.sendMessage(ChatColor.RED + USAGE);
                return;
            }
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack held = inventory.getItemInMainHand();
        if (isAir(held)) {
            sender.sendMessage(ChatColor.RED + "You must hold an item in your main hand.");
            return;
        }
        int max = Math.max(1, held.getMaxStackSize());
        int amount = held.getAmount();
        Long instanceId = ItemInstanceTag.read(held).orElse(null);
        if (instanceId != null) {
            moreInstances(player, held, instanceId, requested != null ? requested : amount < max ? max - amount : max,
                requested != null);
            return;
        }
        if (requested != null) {
            givePlain(player, held, max, requested);
            return;
        }
        if (amount < max) {
            held.setAmount(max);
            inventory.setItemInMainHand(held);
            player.sendMessage(ChatColor.GREEN + "Filled your stack to " + max + ".");
            return;
        }
        int slot = inventory.firstEmpty();
        if (slot < 0) {
            player.sendMessage(ChatColor.RED + "Your stack is already full and your inventory has no room for another.");
            return;
        }
        ItemStack copy = held.clone();
        copy.setAmount(max);
        inventory.setItem(slot, copy);
        player.sendMessage(ChatColor.GREEN + "Gave you another stack of " + max + ".");
    }

    /**
     * {@code amount} more of a plain item: in stacks of its max size, added like a pickup (matching stacks first, then
     * empty slots); the part that doesn't fit is not given.
     */
    private static void givePlain(Player player, ItemStack held, int max, int amount) {
        // Never build more stacks than the inventory can take, so a huge amount stays cheap.
        int toGive = (int) Math.min(amount, roomFor(player.getInventory(), held, max));
        if (toGive <= 0) {
            player.sendMessage(ChatColor.RED + "Your inventory has no room for any more of this item.");
            return;
        }
        List<ItemStack> stacks = new ArrayList<>();
        for (int left = toGive; left > 0; left -= max) {
            ItemStack stack = held.clone();
            stack.setAmount(Math.min(max, left));
            stacks.add(stack);
        }
        Map<Integer, ItemStack> rest = player.getInventory().addItem(stacks.toArray(ItemStack[]::new));
        int notGiven = amount - toGive;
        if (rest != null) {
            for (ItemStack item : rest.values()) {
                notGiven += item == null ? 0 : item.getAmount();
            }
        }
        int given = amount - notGiven;
        if (given <= 0) {
            player.sendMessage(ChatColor.RED + "Your inventory has no room for any more of this item.");
        } else if (notGiven > 0) {
            player.sendMessage(ChatColor.YELLOW + "Gave you " + given + " of " + amount + "; your inventory had no room for "
                + notGiven + " more (not given).");
        } else {
            player.sendMessage(ChatColor.GREEN + "Gave you " + amount + " more.");
        }
    }

    private void moreInstances(Player player, ItemStack held, long sourceId, int wanted, boolean amountGiven) {
        if (itemInstances == null) {
            player.sendMessage(ChatColor.RED + "This item has an instance id and new ones can't be made right now (no API).");
            return;
        }
        int room = emptySlots(player.getInventory());
        int count = Math.min(wanted, room);
        if (count <= 0) {
            player.sendMessage(ChatColor.RED + "Your inventory has no room for a copy.");
            return;
        }
        ItemStack template = held.clone();
        player.sendMessage(ChatColor.GRAY + "Minting " + count + " new item instance" + (count == 1 ? "" : "s")
            + (amountGiven && count < wanted ? " (room for " + count + " of " + wanted + "; the rest is not made)" : "")
            + "...");
        userIdOf.apply(player.getUniqueId()).exceptionally(ex -> null)
            .thenCompose(userId -> itemInstances.cloneAsync(sourceId, count, userId, userId))
            .whenComplete((ids, ex) -> mainThread.execute(() -> {
                if (ex != null || ids == null || ids.isEmpty()) {
                    LOGGER.log(Level.WARNING, "[KnK Item] /knk item more: cloning instance " + sourceId + " failed", ex);
                    if (player.isOnline()) {
                        player.sendMessage(ChatColor.RED + "Couldn't make new item instances: "
                            + (ex != null ? rootMessage(ex) : "the API returned none") + ". Nothing was given.");
                    }
                    return;
                }
                give(player, template, ids, sourceId);
            }));
    }

    /**
     * One single item per new id. Nothing is dropped: if the inventory filled up while the API answered, the copies
     * that don't fit are not handed out (their minted ids are logged and named in the reply).
     */
    private static void give(Player player, ItemStack template, List<Long> ids, long sourceId) {
        List<ItemStack> copies = new ArrayList<>();
        for (Long id : ids) {
            ItemStack copy = template.clone();
            copy.setAmount(1);
            ItemMeta meta = copy.getItemMeta();
            ItemInstanceTag.stamp(meta, id);
            copy.setItemMeta(meta);
            copies.add(copy);
        }
        if (!player.isOnline()) {
            LOGGER.warning("[KnK Item] /knk item more: " + player.getName() + " left before copies of instance " + sourceId
                + " arrived; minted ids " + ids + " were not handed out");
            return;
        }
        Map<Integer, ItemStack> left = player.getInventory().addItem(copies.toArray(ItemStack[]::new));
        int notFit = left == null ? 0 : left.size();
        int given = ids.size() - notFit;
        if (notFit > 0) {
            List<Long> lost = new ArrayList<>();
            left.values().forEach(item -> ItemInstanceTag.read(item).ifPresent(lost::add));
            LOGGER.warning("[KnK Item] /knk item more: " + player.getName() + "'s inventory filled up while copies of instance "
                + sourceId + " were minted; ids " + lost + " were not handed out");
        }
        player.sendMessage((notFit > 0 ? ChatColor.YELLOW : ChatColor.GREEN) + "Gave you " + given + " cop"
            + (given == 1 ? "y" : "ies") + " with new instance id" + (ids.size() == 1 ? "" : "s") + " (" + joinIds(ids) + ")."
            + (notFit > 0 ? " " + notFit + " no longer fit and " + (notFit == 1 ? "was" : "were") + " not given." : ""));
    }

    /** Any whole number from 1 up (very large values are capped at {@link Integer#MAX_VALUE}), else null. */
    static Integer parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (!trimmed.matches("\\d+")) {
            return null;
        }
        try {
            long value = new java.math.BigInteger(trimmed).min(java.math.BigInteger.valueOf(Integer.MAX_VALUE)).longValue();
            return value >= 1 ? (int) value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** How many of {@code held} the storage slots can still take: free space on similar stacks plus empty slots. */
    static long roomFor(PlayerInventory inventory, ItemStack held, int max) {
        long room = 0;
        for (ItemStack slot : inventory.getStorageContents()) {
            if (isAir(slot)) {
                room += max;
            } else if (slot.isSimilar(held)) {
                room += Math.max(0, max - slot.getAmount());
            }
        }
        return room;
    }

    private static int emptySlots(PlayerInventory inventory) {
        ItemStack[] storage = inventory.getStorageContents();
        int empty = 0;
        if (storage != null) {
            for (ItemStack item : storage) {
                if (isAir(item)) {
                    empty++;
                }
            }
        }
        return empty;
    }

    /** Air or nothing; by constant, so it needs no server registry (Material.isAir() does). */
    private static boolean isAir(ItemStack item) {
        if (item == null || item.getType() == null) {
            return true;
        }
        return switch (item.getType()) {
            case AIR, CAVE_AIR, VOID_AIR -> true;
            default -> false;
        };
    }

    private static String joinIds(List<Long> ids) {
        List<String> parts = new ArrayList<>();
        for (Long id : ids) {
            parts.add(String.valueOf(id));
        }
        return String.join(", ", parts);
    }

    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }
}
