package net.knightsandkings.knk.paper.inventory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import net.knightsandkings.knk.core.nbt.NbtTag.CompoundTag;
import net.knightsandkings.knk.core.nbt.PlayerStorageNbt;
import net.knightsandkings.knk.core.nbt.PlayerStorageNbt.Equipment;
import net.knightsandkings.knk.paper.inventory.OfflinePlayerStorage.SaveResult;
import net.knightsandkings.knk.paper.inventory.OfflinePlayerStorage.Snapshot;
import net.kyori.adventure.text.Component;

/**
 * Offline {@code /inventory} and {@code /enderchest} (KNG-13): an offline player's saved inventory or
 * ender chest in a chest view, written back to their data file when the viewer closes it.
 * <ul>
 *   <li>Inventory view (5 rows) laid out like the player's own screen: rows 1-3 the main inventory
 *       (slots 9-35), row 4 the hotbar (0-8), row 5 helmet, chestplate, leggings, boots and off-hand.
 *       The last four slots of row 5 stay empty and take no items (a chest view comes in rows of nine;
 *       the player file has nowhere to keep a 42nd item): clicks and drags there are refused,
 *       shift-clicks from the viewer's inventory only fill the real slots, and anything that still
 *       ends up there is handed back to the viewer on close.</li>
 *   <li>Ender chest view: the 27 slots as they are.</li>
 *   <li><b>Read-only</b> when the file is from an older Minecraft version (the player hasn't joined
 *       since the update - rewriting it would skip the upgrade of everything else in it), or when an
 *       item couldn't be loaded (saving would delete it).</li>
 *   <li>Nothing is saved when the player joins while it's open (the view closes), when the file
 *       changed meanwhile (e.g. two admins editing at once - the second is refused), or when nothing
 *       changed.</li>
 * </ul>
 */
public class OfflineStorageViews implements Listener, OfflineStorageAccess {

    private static final Logger LOGGER = Logger.getLogger(OfflineStorageViews.class.getName());

    private static final int INVENTORY_VIEW_SIZE = 45;
    private static final int EQUIPMENT_ROW = 36;
    private static final List<Equipment> EQUIPMENT_ORDER =
            List.of(Equipment.HEAD, Equipment.CHEST, Equipment.LEGS, Equipment.FEET, Equipment.OFFHAND);
    private static final int FIRST_LOCKED = EQUIPMENT_ROW + EQUIPMENT_ORDER.size();

    private final OfflinePlayerStorage storage;
    private final Function<UUID, Player> onlinePlayer;
    private final List<View> openViews = new ArrayList<>();

    public OfflineStorageViews(OfflinePlayerStorage storage) {
        this(storage, Bukkit::getPlayer);
    }

    OfflineStorageViews(OfflinePlayerStorage storage, Function<UUID, Player> onlinePlayer) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.onlinePlayer = Objects.requireNonNull(onlinePlayer, "onlinePlayer");
    }

    /** One open view; the inventory's holder, so the listeners can recognise it. */
    static final class View implements InventoryHolder {
        final Kind kind;
        final UUID target;
        final String targetName;
        final Snapshot snapshot;
        final boolean writable;
        final Map<Integer, CompoundTag> original;
        Inventory inventory;
        boolean aborted;

        View(Kind kind, UUID target, String targetName, Snapshot snapshot, boolean writable, Map<Integer, CompoundTag> original) {
            this.kind = kind;
            this.target = target;
            this.targetName = targetName;
            this.snapshot = snapshot;
            this.writable = writable;
            this.original = original;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    @Override
    public void open(Player viewer, UUID target, String targetName, Kind kind) {
        Player online = onlinePlayer.apply(target);
        if (online != null) { // came online while the rank check ran
            viewer.openInventory(kind == Kind.ENDER_CHEST ? online.getEnderChest() : online.getInventory());
            return;
        }
        Snapshot snapshot = loadOrReport(viewer, target, targetName);
        if (snapshot == null) {
            return;
        }

        Map<Integer, CompoundTag> slots = kind == Kind.ENDER_CHEST
                ? PlayerStorageNbt.enderChest(snapshot.root())
                : toViewSlots(PlayerStorageNbt.inventory(snapshot.root()), PlayerStorageNbt.equipment(snapshot.root()));
        int size = kind == Kind.ENDER_CHEST ? PlayerStorageNbt.ENDER_CHEST_SIZE : INVENTORY_VIEW_SIZE;

        ItemStack[] contents = new ItemStack[size];
        boolean allLoaded = true;
        for (Map.Entry<Integer, CompoundTag> entry : slots.entrySet()) {
            try {
                contents[entry.getKey()] = storage.toItemStack(entry.getValue(), snapshot.dataVersion());
            } catch (RuntimeException e) {
                allLoaded = false;
                LOGGER.log(Level.WARNING, "Couldn't load an item in slot " + entry.getKey() + " of " + targetName + "'s saved " + kind.label(), e);
            }
        }
        boolean writable = snapshot.sameVersionAsServer() && allLoaded;

        View view = new View(kind, target, targetName, snapshot, writable, slots);
        String title = targetName + "'s " + kind.label() + (writable ? " (offline)" : " (offline, read-only)");
        Inventory inventory = Bukkit.createInventory(view, size, Component.text(title));
        view.inventory = inventory;
        inventory.setContents(contents);
        openViews.add(view);
        viewer.openInventory(inventory);

        if (!snapshot.sameVersionAsServer()) {
            viewer.sendMessage(ChatColor.YELLOW + targetName + " hasn't joined since the last Minecraft update - view only.");
        } else if (!allLoaded) {
            viewer.sendMessage(ChatColor.YELLOW + "Some items couldn't be loaded (see the console) - view only, so they aren't lost.");
        } else {
            viewer.sendMessage(ChatColor.GRAY + targetName + " is offline - changes are saved when you close this.");
        }
    }

    @Override
    public void clearInventory(CommandSender sender, UUID target, String targetName) {
        Player online = onlinePlayer.apply(target);
        if (online != null) { // came online while the rank check ran
            online.getInventory().clear();
            sender.sendMessage(ChatColor.GREEN + "Cleared " + ChatColor.WHITE + targetName + ChatColor.GREEN + "'s inventory.");
            online.sendMessage(ChatColor.RED + "Your inventory was cleared by " + ChatColor.WHITE + sender.getName() + ChatColor.RED + ".");
            return;
        }
        Snapshot snapshot = loadOrReport(sender, target, targetName);
        if (snapshot == null) {
            return;
        }
        CompoundTag root = snapshot.root().deepCopy();
        PlayerStorageNbt.setInventory(root, Map.of(), Map.of());
        report(sender, targetName, save(snapshot, root), "Cleared " + targetName + "'s saved inventory.");
    }

    // ===== listeners =====

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof View view)) {
            return;
        }
        if (!view.writable || view.aborted) {
            event.setCancelled(true);
            return;
        }
        if (view.kind != Kind.INVENTORY) {
            return;
        }
        if (event.getClickedInventory() == view.inventory && isLocked(event.getSlot())) {
            event.setCancelled(true);
            return;
        }
        // Shift-click from the viewer's own inventory: vanilla would drop it into the first free slot,
        // which can be a locked one - place it ourselves, real slots only.
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY && event.getClickedInventory() != view.inventory) {
            event.setCancelled(true);
            ItemStack moving = event.getCurrentItem();
            if (moving == null || moving.isEmpty()) {
                return;
            }
            ItemStack[] contents = view.inventory.getContents();
            int left = moveInto(contents, FIRST_LOCKED, moving);
            view.inventory.setContents(contents);
            if (left <= 0) {
                event.setCurrentItem(null);
            } else {
                ItemStack rest = moving.clone();
                rest.setAmount(left);
                event.setCurrentItem(rest);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof View view)) {
            return;
        }
        if (!view.writable || view.aborted) {
            event.setCancelled(true);
            return;
        }
        if (view.kind == Kind.INVENTORY && event.getRawSlots().stream().anyMatch(slot -> slot < INVENTORY_VIEW_SIZE && isLocked(slot))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof View view)) {
            return;
        }
        openViews.remove(view);
        if (!view.writable || view.aborted) {
            return;
        }
        HumanEntity viewer = event.getPlayer();
        if (view.kind == Kind.INVENTORY) {
            returnLockedSlotItems(view, viewer);
        }
        if (onlinePlayer.apply(view.target) != null) {
            viewer.sendMessage(ChatColor.RED + view.targetName + " came online - your changes were not saved.");
            return;
        }

        Map<Integer, CompoundTag> edited = new TreeMap<>();
        ItemStack[] contents = view.inventory.getContents();
        int end = view.kind == Kind.INVENTORY ? FIRST_LOCKED : contents.length;
        for (int slot = 0; slot < end; slot++) {
            ItemStack item = contents[slot];
            if (item != null && !item.isEmpty()) {
                edited.put(slot, storage.toNbt(item));
            }
        }
        if (sameItems(edited, view.original, view.snapshot.dataVersion())) {
            return;
        }

        CompoundTag root = view.snapshot.root().deepCopy();
        if (view.kind == Kind.ENDER_CHEST) {
            PlayerStorageNbt.setEnderChest(root, edited);
        } else {
            Map<Integer, CompoundTag> main = new TreeMap<>();
            Map<Equipment, CompoundTag> equipment = new EnumMap<>(Equipment.class);
            fromViewSlots(edited, main, equipment);
            PlayerStorageNbt.setInventory(root, main, equipment);
        }
        report(viewer, view.targetName, save(view.snapshot, root), "Saved " + view.targetName + "'s " + view.kind.label() + ".");
    }

    /** The target joined: the server has just loaded their file, so the open views must not write it. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        UUID joined = event.getPlayer().getUniqueId();
        for (View view : List.copyOf(openViews)) {
            if (view.target.equals(joined)) {
                view.aborted = true;
                for (HumanEntity viewer : List.copyOf(view.inventory.getViewers())) {
                    viewer.sendMessage(ChatColor.RED + view.targetName + " came online - the offline view was closed and nothing was saved.");
                    viewer.closeInventory();
                }
            }
        }
    }

    // ===== helpers =====

    private Snapshot loadOrReport(CommandSender sender, UUID target, String targetName) {
        try {
            Snapshot snapshot = storage.load(target).orElse(null);
            if (snapshot == null) {
                sender.sendMessage(ChatColor.RED + targetName + " has no saved data on this server.");
            }
            return snapshot;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Couldn't read " + targetName + "'s player data", e);
            sender.sendMessage(ChatColor.RED + "Couldn't read " + targetName + "'s saved data (see the console).");
            return null;
        }
    }

    private SaveResult save(Snapshot snapshot, CompoundTag root) {
        try {
            return storage.save(snapshot, root);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Couldn't write player data " + snapshot.file(), e);
            return null;
        }
    }

    private static void report(CommandSender sender, String targetName, SaveResult result, String success) {
        if (result == SaveResult.SAVED) {
            sender.sendMessage(ChatColor.GREEN + success);
        } else if (result == SaveResult.FILE_CHANGED) {
            sender.sendMessage(ChatColor.RED + targetName + "'s saved data changed meanwhile (they joined, or someone else saved) - nothing was saved. Try again.");
        } else if (result == SaveResult.VERSION_MISMATCH) {
            sender.sendMessage(ChatColor.RED + targetName + " hasn't joined since the last Minecraft update - their saved data can't be changed until they do.");
        } else {
            sender.sendMessage(ChatColor.RED + "Saving " + targetName + "'s data failed (see the console) - nothing was changed.");
        }
    }

    /** Whether the edited items are the ones that were loaded (compared after one serialization round trip). */
    private boolean sameItems(Map<Integer, CompoundTag> edited, Map<Integer, CompoundTag> original, int dataVersion) {
        if (!edited.keySet().equals(original.keySet())) {
            return false;
        }
        for (Map.Entry<Integer, CompoundTag> entry : original.entrySet()) {
            CompoundTag reloaded = storage.toNbt(storage.toItemStack(entry.getValue(), dataVersion));
            if (!reloaded.equals(edited.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** Player inventory slots → view slots: rows 1-3 main (9-35), row 4 hotbar (0-8), row 5 equipment. */
    static Map<Integer, CompoundTag> toViewSlots(Map<Integer, CompoundTag> main, Map<Equipment, CompoundTag> equipment) {
        Map<Integer, CompoundTag> view = new TreeMap<>();
        main.forEach((slot, item) -> view.put(slot < 9 ? slot + 27 : slot - 9, item));
        for (int i = 0; i < EQUIPMENT_ORDER.size(); i++) {
            CompoundTag item = equipment.get(EQUIPMENT_ORDER.get(i));
            if (item != null) {
                view.put(EQUIPMENT_ROW + i, item);
            }
        }
        return view;
    }

    /** The reverse of {@link #toViewSlots}; the locked slots are ignored. */
    static void fromViewSlots(Map<Integer, CompoundTag> view, Map<Integer, CompoundTag> main, Map<Equipment, CompoundTag> equipment) {
        view.forEach((slot, item) -> {
            if (slot < 27) {
                main.put(slot + 9, item);
            } else if (slot < EQUIPMENT_ROW) {
                main.put(slot - 27, item);
            } else if (slot < FIRST_LOCKED) {
                equipment.put(EQUIPMENT_ORDER.get(slot - EQUIPMENT_ROW), item);
            }
        });
    }

    /** The four unused slots at the end of the inventory view's last row. */
    static boolean isLocked(int viewSlot) {
        return viewSlot >= FIRST_LOCKED && viewSlot < INVENTORY_VIEW_SIZE;
    }

    /**
     * Puts {@code moving} into {@code contents[0..end)} the way a shift-click would: first onto
     * matching stacks, then into empty slots. Returns how many didn't fit; {@code moving} is untouched.
     */
    static int moveInto(ItemStack[] contents, int end, ItemStack moving) {
        int remaining = moving.getAmount();
        int max = Math.max(1, moving.getMaxStackSize());
        for (int slot = 0; slot < end && remaining > 0; slot++) {
            ItemStack existing = contents[slot];
            if (existing != null && !existing.isEmpty() && existing.isSimilar(moving) && existing.getAmount() < max) {
                int added = Math.min(remaining, max - existing.getAmount());
                existing.setAmount(existing.getAmount() + added);
                remaining -= added;
            }
        }
        for (int slot = 0; slot < end && remaining > 0; slot++) {
            if (contents[slot] == null || contents[slot].isEmpty()) {
                ItemStack placed = moving.clone();
                int amount = Math.min(remaining, max);
                placed.setAmount(amount);
                contents[slot] = placed;
                remaining -= amount;
            }
        }
        return remaining;
    }

    /** Safety net: anything in a locked slot would be lost on save, so the viewer gets it back. */
    private static void returnLockedSlotItems(View view, HumanEntity viewer) {
        for (int slot = FIRST_LOCKED; slot < INVENTORY_VIEW_SIZE; slot++) {
            ItemStack item = view.inventory.getItem(slot);
            if (item != null && !item.isEmpty()) {
                view.inventory.setItem(slot, null);
                viewer.getInventory().addItem(item).values()
                        .forEach(left -> viewer.getWorld().dropItemNaturally(viewer.getLocation(), left));
            }
        }
    }
}
