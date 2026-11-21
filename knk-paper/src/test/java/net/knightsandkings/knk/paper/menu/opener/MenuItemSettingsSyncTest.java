package net.knightsandkings.knk.paper.menu.opener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.settings.KnkMenuItemSettings;
import net.knightsandkings.knk.core.domain.settings.KnkMenuItemSettings.Action;
import net.knightsandkings.knk.core.menu.opener.MenuItemAction;
import net.knightsandkings.knk.core.menu.opener.MenuItemActionResolver;
import net.knightsandkings.knk.core.menu.opener.MenuItemSituations;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings.Click;

/**
 * A29 / A31 (PandiO/knk-workspace#871, DESIGN § 4.3.5, P26): {@code config.yml} until the API answers, then the
 * game settings page's menu item, applied to online players without a restart.
 */
class MenuItemSettingsSyncTest {

    private final MenuOpenerServiceTest.FakeItems items = new MenuOpenerServiceTest.FakeItems();
    private final MenuItemSituations situations = new MenuItemSituations();
    private final List<Runnable> mainThread = new ArrayList<>();
    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final ItemStack[] contents = new ItemStack[41];
    private final MenuOpenerSettings file = MenuOpenerSettings.defaults();
    private MenuItemSettingsSync sync;
    private MenuOpenerService service;
    private long now = 1_000;

    @BeforeEach
    void setUp() {
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getInventory()).thenReturn(inventory);
        when(player.isOnline()).thenReturn(true);
        when(inventory.getContents()).thenAnswer(inv -> contents.clone());
        org.mockito.Mockito.doAnswer(inv -> {
            contents[inv.<Integer>getArgument(0)] = inv.getArgument(1);
            return null;
        }).when(inventory).setItem(anyInt(), any());
        sync = new MenuItemSettingsSync(file, situations, mainThread::add, () -> List.of(player));
        service = new MenuOpenerService(sync, uuid -> false, items, new MenuItemActionResolver(situations),
            new MenuItemActionRunner((p, key) -> false), (task, ticks) -> task.run(), () -> now, 3);
        sync.attach(service);
        situations.provide(MenuItemSituations.SIEGE_MATCH, id -> true); // every test player is in a match
    }

    private static KnkMenuItemSettings api(String name, Map<String, Action> clicks, Map<String, Map<String, Action>> overrides) {
        return new KnkMenuItemSettings(true, "all", true, true, "NETHER_STAR", name, List.of(), 8, false, false, clicks,
            overrides);
    }

    private static Action open(String key) {
        return new Action("OpenMenu", key, null);
    }

    @Test
    void withTheApiDownAtStartupTheConfigFileSettingsStay_AndEveryClickRunsSlashMenu_A31() {
        assertSame(file, sync.settings());
        assertFalse(sync.fromApi());
        for (Click click : Click.values()) {
            now += 1_000;
            assertTrue(service.click(player, click, false), click.name());
        }
        verify(player, org.mockito.Mockito.times(Click.values().length)).performCommand("menu");
        org.mockito.Mockito.verify(player, org.mockito.Mockito.never()).performCommand("siege shop");
    }

    @Test
    void theApiSettingsReplaceTheFile_AndTheClicksFollowThem_A29() {
        sync.accept(api(null, Map.of("LEFT_CLICK", open("main")),
            Map.of("siege.match", Map.of("SHIFT_RIGHT_CLICK", open("siege.shop")))));

        assertTrue(sync.fromApi());
        assertEquals(MenuItemAction.openMenu("siege.shop"), service.actionFor(player, Click.SHIFT_RIGHT_CLICK));
        service.click(player, Click.SHIFT_RIGHT_CLICK, false);
        verify(player).performCommand("siege shop");
        assertTrue(service.actionFor(player, Click.RIGHT_CLICK).isNone(), "not in the new table any more");
    }

    @Test
    void aNewLookReachesOnlinePlayersWithoutARestart_A29() {
        ItemStack old = items.outdatedOpener();
        contents[8] = old;
        sync.accept(api("&bNew name", Map.of("LEFT_CLICK", open("main")), Map.of()));
        assertEquals(1, mainThread.size(), "applied on the main thread");
        mainThread.get(0).run();
        assertTrue(items.openers.contains(contents[8]));
        assertEquals("&bNew name", sync.settings().name());
    }

    @Test
    void theSameSettingsAgainChangeNothing_AndNullIsIgnored() {
        KnkMenuItemSettings a = api("x", Map.of("LEFT_CLICK", open("main")), Map.of());
        sync.accept(a);
        sync.accept(a);
        sync.accept(null);
        assertEquals(1, mainThread.size());
    }
}
