package net.knightsandkings.knk.paper.siege.survival.items;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Match item tracking (DESIGN §8b, link S7b): every kit, baseline, purchase and Mystery Box item carries the id of its
 * match in its persistent data (key {@code <plugin>:survival_match}, the Siege match token — the same convention as the
 * Siege enchant books' {@code siege_book}). Ground match items (purchase and box overflow) are tracked per match and
 * removed with it; a player's remaining match items are stripped at the end as a belt-and-braces to the vault restore.
 */
public final class MatchItems {

    private final NamespacedKey key;
    private final Map<String, Set<UUID>> groundByMatch = new LinkedHashMap<>();

    public MatchItems(Plugin plugin) {
        this(new NamespacedKey(plugin, "survival_match"));
    }

    public MatchItems(NamespacedKey key) {
        this.key = Objects.requireNonNull(key, "key");
    }

    public NamespacedKey key() {
        return key;
    }

    // ---- tags ----

    /** Air without the registry ({@code Material#isAir} needs a server). */
    static boolean isAir(ItemStack item) {
        if (item == null) return true;
        org.bukkit.Material type = item.getType();
        return type == null || type == org.bukkit.Material.AIR || type == org.bukkit.Material.CAVE_AIR || type == org.bukkit.Material.VOID_AIR;
    }

    /** The match id on an item, or null. */
    public String matchIdOf(ItemStack item) {
        if (isAir(item) || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    public boolean isMatchItem(ItemStack item, String matchId) {
        return matchId != null && matchId.equals(matchIdOf(item));
    }

    /** Tags the stack in place (no-op for air or a stack without meta). */
    public ItemStack tag(ItemStack item, String matchId) {
        if (isAir(item)) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        stamp(matchId).accept(meta);
        item.setItemMeta(meta);
        return item;
    }

    /** A meta stamp for the blueprint assembler's options ({@code Options.withMetaStamp}). */
    public Consumer<ItemMeta> stamp(String matchId) {
        Objects.requireNonNull(matchId, "matchId");
        return meta -> {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(key, PersistentDataType.STRING, matchId);
        };
    }

    // ---- ground items ----

    /**
     * Drops a match item that could not be placed: owned by the player (only they pick it up quickly, Paper's owner
     * rule), never for mobs, not persistent, tracked for the end-of-match sweep.
     */
    public Item drop(Location at, ItemStack stack, UUID owner, String matchId) {
        Objects.requireNonNull(at.getWorld(), "world");
        ItemStack tagged = tag(stack.clone(), matchId);
        Item dropped = at.getWorld().dropItem(at, tagged, item -> {
            if (owner != null) item.setOwner(owner);
            item.setCanMobPickup(false);
            item.setPersistent(false);
            item.setPickupDelay(10);
        });
        track(dropped, matchId);
        return dropped;
    }

    /** Remembers a ground item of a match (also for items dropped by other code paths). */
    public void track(Item item, String matchId) {
        groundByMatch.computeIfAbsent(matchId, k -> new LinkedHashSet<>()).add(item.getUniqueId());
    }

    public int trackedGroundItems(String matchId) {
        Set<UUID> ids = groundByMatch.get(matchId);
        return ids == null ? 0 : ids.size();
    }

    /**
     * Match end/abort: every tracked ground item of the match is removed, plus any loaded ground item still carrying the
     * tag (an item dropped in a way that was not tracked).
     *
     * @return the number of removed item entities
     */
    public int removeGround(String matchId, Collection<World> worlds) {
        int removed = 0;
        Set<UUID> ids = groundByMatch.remove(matchId);
        if (ids != null) {
            for (UUID id : ids) {
                Entity entity = Bukkit.getEntity(id);
                if (entity != null && entity.isValid()) {
                    entity.remove();
                    removed++;
                }
            }
        }
        for (World world : worlds) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (item.isValid() && matchId.equals(matchIdOf(item.getItemStack()))) {
                    item.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    /** Removes every ground item that carries any match tag (startup sweep after a crash). */
    public int sweep(Collection<? extends Entity> entities) {
        int removed = 0;
        for (Entity entity : entities) {
            if (entity instanceof Item item && item.isValid() && matchIdOf(item.getItemStack()) != null) {
                item.remove();
                removed++;
            }
        }
        return removed;
    }

    // ---- players ----

    /** Removes the match's items from a player's inventory (the vault restore replaces it anyway; this guards the gap). */
    public int stripFromPlayer(Player player, String matchId) {
        PlayerInventory inventory = player.getInventory();
        int removed = 0;
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isMatchItem(contents[i], matchId)) {
                inventory.setItem(i, null);
                removed++;
            }
        }
        if (isMatchItem(inventory.getItemInOffHand(), matchId)) {
            inventory.setItemInOffHand(null);
            removed++;
        }
        ItemStack cursor = player.getItemOnCursor();
        if (isMatchItem(cursor, matchId)) {
            player.setItemOnCursor(null);
            removed++;
        }
        return removed;
    }

    /** Counts a player's match items (status line). */
    public int countOnPlayer(Player player, String matchId) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isMatchItem(stack, matchId)) count += stack.getAmount();
        }
        return count;
    }

    /**
     * Gives tagged stacks to a player; what does not fit is dropped as a match item at their feet (owned by them).
     *
     * @return the number of stacks that were dropped
     */
    public int give(Player player, List<ItemStack> stacks, String matchId) {
        int dropped = 0;
        for (ItemStack stack : stacks) {
            ItemStack tagged = tag(stack.clone(), matchId);
            Map<Integer, ItemStack> rest = player.getInventory().addItem(tagged);
            for (ItemStack leftover : rest.values()) {
                drop(player.getLocation(), leftover, player.getUniqueId(), matchId);
                dropped++;
            }
        }
        return dropped;
    }

    /** The matches with tracked ground items (status). */
    public List<String> trackedMatches() {
        return new ArrayList<>(groundByMatch.keySet());
    }
}
