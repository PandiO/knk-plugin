package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.domain.menu.KnkActionBinding;
import net.knightsandkings.knk.core.domain.menu.KnkMenuItemTemplate;
import net.knightsandkings.knk.core.domain.menu.KnkMenuSectionTemplate;
import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplate;
import net.knightsandkings.knk.core.domain.menu.KnkVariableBinding;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioPlayer;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An InventoryMenu opened and clicked by a player (simulated players, PandiO/knk-workspace#540; InventoryMenu
 * Phases 4, 6 and 9): two fake menu templates are primed into the live template cache (fake data instead of the
 * API) and the first is opened through the live menu runtime. The player's clicks are container-click packets, so
 * the server's click handling, {@code InventoryClickEvent} and the plugin's {@code MenuClickListener} run as for a
 * real client. A button whose {@code actionPermission} the player lacks does nothing; the open button opens the
 * second menu ({@code menu.open}) without the item landing on the cursor or in the player's inventory; the close
 * button there closes the menu ({@code menu.close}).
 */
public final class PlayerMenuClickScenario implements Scenario<ScenarioScene> {

    static final String TITLE_A = "Scenario menu A";
    static final String TITLE_B = "Scenario menu B";
    static final String SECRET_NODE = "knk.scenario.menu.secret";

    @Override
    public String name() {
        return "player-menu-click";
    }

    @Override
    public String description() {
        return "A player's clicks in an InventoryMenu open the next menu and close it; a locked button does nothing";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        String keyA = scene.uniqueName("menu_a");
        String keyB = scene.uniqueName("menu_b");
        ScenarioPlayer player = scene.player("ScnMenuUser", p -> p.account(scene.fakeId(1)));
        AtomicInteger lockedSlot = new AtomicInteger(-1);
        AtomicInteger openSlot = new AtomicInteger(-1);
        AtomicInteger closeSlot = new AtomicInteger(-1);
        plan.act("prime two fake menu templates", () -> {
                scene.services().menuTemplates().prime(menu(scene.fakeId(10), keyA, TITLE_A, List.of(
                    button(scene.fakeId(11), 11, "Locked", SECRET_NODE, action(scene.fakeId(12), "menu.open", keyB)),
                    button(scene.fakeId(13), 15, "Open B", null, action(scene.fakeId(14), "menu.open", keyB)))));
                scene.services().menuTemplates().prime(menu(scene.fakeId(20), keyB, TITLE_B, List.of(
                    button(scene.fakeId(21), 13, "Close", null, new KnkActionBinding(scene.fakeId(22), "menu.close", "{}", 0,
                        List.of())))));
            })
            .cleanup("forget the fake templates", () -> {
                scene.services().menuTemplates().evict(keyA);
                scene.services().menuTemplates().evict(keyB);
            })
            .act("the player joins", player::join)
            .await("they are online", 200, player::online)
            .await("they have left the join's loading hold", 200, () -> player.received("You're all set!"))
            .act("menu A is opened for them", () -> scene.services().menus().openMenu(player.player(), keyA))
            .await("they see menu A", 100, () -> title(player).equals(TITLE_A))
            .check("menu A shows the locked and the open button", () -> {
                lockedSlot.set(slotNamed(player, "Locked"));
                openSlot.set(slotNamed(player, "Open B"));
                return lockedSlot.get() >= 0 && openSlot.get() >= 0 ? null : "buttons in menu A: " + names(player);
            })
            .act("they click the locked button", () -> player.click(lockedSlot.get()))
            .waitTicks(10)
            .check("menu A is still open", () -> title(player).equals(TITLE_A) ? null : "they see '" + title(player) + "'")
            .act("they click the open button", () -> player.click(openSlot.get()))
            .await("they see menu B", 100, () -> title(player).equals(TITLE_B))
            .check("nothing landed on their cursor", () -> {
                ItemStack cursor = player.player().getItemOnCursor();
                return cursor.getType() == Material.AIR ? null : "the cursor holds " + cursor;
            })
            .check("no menu button landed in their inventory", () -> {
                for (ItemStack item : player.inventory().getContents()) {
                    if (item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                            && PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName()).contains("Open B")) {
                        return "their inventory holds " + item;
                    }
                }
                return null;
            })
            .check("menu B shows the close button", () -> {
                closeSlot.set(slotNamed(player, "Close"));
                return closeSlot.get() >= 0 ? null : "buttons in menu B: " + names(player);
            })
            .act("they click the close button", () -> player.click(closeSlot.get()))
            .await("the menu is closed", 100,
                () -> player.openInventory().getTopInventory().getType() == InventoryType.CRAFTING);
    }

    private static KnkMenuTemplate menu(int id, String key, String title, List<KnkMenuItemTemplate> items) {
        KnkMenuSectionTemplate section = new KnkMenuSectionTemplate(id - 1, "buttons", "StaticButtons", 0, 0, 9, 3,
            "Static", "Top", "Left", "Hide", "Default", "Medium", null, null, items, List.of(), null, null);
        return new KnkMenuTemplate(id, key, title, "scenario fake menu", 3, "Static", null, List.of(section));
    }

    private static KnkMenuItemTemplate button(int id, int slot, String name, String actionPermission, KnkActionBinding action) {
        return new KnkMenuItemTemplate(id, slot, slot, null, 1, null, null, "Normal", null, actionPermission,
            List.of(new KnkVariableBinding(id - 1, "Name", 0, name, "Static", null)), List.of(action), List.of());
    }

    private static KnkActionBinding action(int id, String type, String menuKey) {
        return new KnkActionBinding(id, type, "{\"key\":\"" + menuKey + "\"}", 0, List.of());
    }

    private static String title(ScenarioPlayer player) {
        return PlainTextComponentSerializer.plainText().serialize(player.openInventory().title());
    }

    /** The top-inventory slot whose item is named {@code name}, or -1. */
    private static int slotNamed(ScenarioPlayer player, String name) {
        Inventory top = player.openInventory().getTopInventory();
        for (int slot = 0; slot < top.getSize(); slot++) {
            ItemStack item = top.getItem(slot);
            if (item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                    && PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName()).contains(name)) {
                return slot;
            }
        }
        return -1;
    }

    private static List<String> names(ScenarioPlayer player) {
        Inventory top = player.openInventory().getTopInventory();
        List<String> names = new java.util.ArrayList<>();
        for (int slot = 0; slot < top.getSize(); slot++) {
            ItemStack item = top.getItem(slot);
            if (item != null && item.getType() != Material.AIR) {
                names.add(slot + "=" + (item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                    ? PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName()) : item.getType()));
            }
        }
        return names;
    }
}
