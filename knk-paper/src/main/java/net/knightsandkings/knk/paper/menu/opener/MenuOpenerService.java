package net.knightsandkings.knk.paper.menu.opener;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import net.knightsandkings.knk.core.menu.opener.MenuOpenerPlacement;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerPlacement.Plan;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings;

/**
 * The menu item (alpha test run 1, PandiO/knk-workspace#160): keeps it in the inventories of the players who should
 * have it ({@link #ensure}) and opens {@code /menu} on a click ({@link #open}). Main thread only.
 * <p>
 * The item is a pointer to {@code /menu}, not a game item: it never drops on the ground, never goes into another
 * inventory, is no crafting ingredient and doesn't count for kits ({@code KitScanTaskHandler} skips it). Siege
 * snapshots ({@code SiegePlayerVault}) keep it with the rest of the inventory, so it is back after a match.
 */
public final class MenuOpenerService {

    private static final Logger LOGGER = Logger.getLogger(MenuOpenerService.class.getName());

    /** Ignore a second click this soon after the last (a client can send one click twice, Bedrock air + block). */
    static final long CLICK_COOLDOWN_MILLIS = 250;

    private final Supplier<MenuOpenerSettings> settings;
    private final Predicate<UUID> bedrock;
    private final MenuOpenerItems items;
    private final Consumer<Player> openMenu;
    private final BiConsumer<Runnable, Long> later;
    private final LongSupplier clock;
    private final int bedrockDelayTicks;
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private boolean warnedMaterial;

    /**
     * @param settings          {@code menus.menu-item}
     * @param bedrock           whether a player is a Bedrock (Floodgate) player
     * @param items             makes and recognises the item
     * @param openMenu          opens the hub menu, as {@code /menu} does (permission checks included)
     * @param later             runs a task on the main thread after the given number of ticks
     * @param clock             milliseconds, for the double-click guard
     * @param bedrockDelayTicks {@code menus.bedrock-transition-delay-ticks}: how long a Bedrock player's inventory
     *                          gets to close before the menu opens (Geyser closes the next form otherwise)
     */
    public MenuOpenerService(Supplier<MenuOpenerSettings> settings, Predicate<UUID> bedrock, MenuOpenerItems items,
                             Consumer<Player> openMenu, BiConsumer<Runnable, Long> later, LongSupplier clock,
                             int bedrockDelayTicks) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.bedrock = Objects.requireNonNull(bedrock, "bedrock");
        this.items = Objects.requireNonNull(items, "items");
        this.openMenu = Objects.requireNonNull(openMenu, "openMenu");
        this.later = Objects.requireNonNull(later, "later");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.bedrockDelayTicks = Math.max(1, bedrockDelayTicks);
    }

    public MenuOpenerSettings settings() {
        return settings.get();
    }

    public boolean isOpener(ItemStack item) {
        return item != null && items.isOpener(item);
    }

    /**
     * Brings the player's inventory in line with the settings: copies removed, an outdated item replaced, and, when
     * {@code giveIfMissing}, a missing one placed in the configured slot (a player's item there moves to a free slot;
     * with no free slot nothing is given). A player who should not have it loses every copy.
     */
    public void ensure(Player player, boolean giveIfMissing) {
        MenuOpenerSettings s = settings.get();
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        if (contents == null || contents.length <= s.slot()) {
            return;
        }
        MenuOpenerPlacement.Slot[] slots = new MenuOpenerPlacement.Slot[contents.length];
        for (int i = 0; i < contents.length; i++) {
            slots[i] = items.classify(contents[i], s);
        }
        boolean wanted = s.wantedFor(bedrock.test(player.getUniqueId()));
        Plan plan = MenuOpenerPlacement.plan(slots, wanted, s.slot(), !s.movable());
        if (plan.changesNothing()) {
            return;
        }
        ItemStack fresh = null;
        if (plan.refresh() >= 0 || plan.place() >= 0) {
            fresh = items.create(s);
            if (fresh == null && !warnedMaterial) {
                warnedMaterial = true;
                LOGGER.warning("[MenuItem] menus.menu-item.material '" + s.material() + "' is no item; the menu item is not given");
            }
        }
        for (int i : plan.remove()) {
            inventory.setItem(i, null);
        }
        if (plan.refresh() >= 0 && fresh != null) {
            inventory.setItem(plan.refresh(), fresh.clone());
        }
        // a pinned item found in another slot is moved back, whether or not missing ones are given now
        boolean give = giveIfMissing || (wanted && !plan.remove().isEmpty());
        if (plan.place() >= 0 && give && fresh != null) {
            if (plan.moveFrom() >= 0) {
                ItemStack moving = contents[plan.moveFrom()];
                inventory.setItem(plan.moveTo(), moving);
                inventory.setItem(plan.moveFrom(), null);
            }
            inventory.setItem(plan.place(), fresh.clone());
        }
    }

    /**
     * Opens the menu for a click with the item. From the player's open inventory the inventory closes first and the
     * menu opens a tick later (Bedrock: after {@code bedrock-transition-delay-ticks}), so the client's own close does
     * not close the menu. Returns false when the click came within the double-click guard.
     */
    public boolean open(Player player, boolean fromInventory) {
        long now = clock.getAsLong();
        Long previous = lastClick.put(player.getUniqueId(), now);
        if (previous != null && now - previous < CLICK_COOLDOWN_MILLIS) {
            return false;
        }
        if (!fromInventory) {
            openMenu.accept(player);
            return true;
        }
        player.closeInventory();
        long delay = bedrock.test(player.getUniqueId()) ? bedrockDelayTicks : 1L;
        later.accept(() -> {
            if (player.isOnline()) {
                openMenu.accept(player);
            }
        }, delay);
        return true;
    }

    /** Runs {@link #ensure} a tick later, when the player is still online (after join or respawn settled). */
    public void ensureLater(Player player, boolean giveIfMissing) {
        later.accept(() -> {
            if (player.isOnline()) {
                ensure(player, giveIfMissing);
            }
        }, 1L);
    }

    public void forget(UUID playerId) {
        lastClick.remove(playerId);
    }
}
