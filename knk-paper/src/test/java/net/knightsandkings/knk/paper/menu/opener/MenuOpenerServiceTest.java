package net.knightsandkings.knk.paper.menu.opener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.menu.opener.MenuOpenerPlacement;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings.Audience;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings.Click;

/** PandiO/knk-workspace#160: the menu item is kept in place and opens /menu. */
class MenuOpenerServiceTest {

    /** Items by identity: openers are the stacks this fake made (or marked outdated). */
    static final class FakeItems implements MenuOpenerItems {
        final Set<ItemStack> openers = new HashSet<>();
        final Set<ItemStack> outdated = new HashSet<>();
        boolean usableMaterial = true;

        @Override
        public ItemStack create(MenuOpenerSettings settings) {
            if (!usableMaterial) {
                return null;
            }
            ItemStack item = mock(ItemStack.class);
            when(item.clone()).thenReturn(item);
            openers.add(item);
            return item;
        }

        ItemStack outdatedOpener() {
            ItemStack item = mock(ItemStack.class);
            outdated.add(item);
            return item;
        }

        @Override
        public boolean isOpener(ItemStack item) {
            return openers.contains(item) || outdated.contains(item);
        }

        @Override
        public MenuOpenerPlacement.Slot classify(ItemStack item, MenuOpenerSettings settings) {
            if (item == null) {
                return MenuOpenerPlacement.Slot.EMPTY;
            }
            if (openers.contains(item)) {
                return MenuOpenerPlacement.Slot.OPENER;
            }
            return outdated.contains(item) ? MenuOpenerPlacement.Slot.OPENER_OUTDATED : MenuOpenerPlacement.Slot.OTHER;
        }
    }

    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final UUID id = UUID.randomUUID();
    private final FakeItems items = new FakeItems();
    private final ItemStack[] contents = new ItemStack[41];
    private final List<Player> opened = new ArrayList<>();
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_000);
    private MenuOpenerSettings settings = MenuOpenerSettings.defaults();
    private boolean bedrock;
    private MenuOpenerService service;

    @BeforeEach
    void setUp() {
        when(player.getUniqueId()).thenReturn(id);
        when(player.getInventory()).thenReturn(inventory);
        when(player.isOnline()).thenReturn(true);
        when(inventory.getContents()).thenAnswer(inv -> contents.clone());
        org.mockito.Mockito.doAnswer(inv -> {
            contents[inv.<Integer>getArgument(0)] = inv.getArgument(1);
            return null;
        }).when(inventory).setItem(anyInt(), any());
        service = new MenuOpenerService(() -> settings, uuid -> bedrock, items, opened::add,
            (task, ticks) -> {
                scheduled.add(task);
                delays.add(ticks);
            }, clock::get, 3);
    }

    private long openers() {
        return java.util.Arrays.stream(contents).filter(items::isOpener).count();
    }

    @Test
    void aJoiningPlayerGetsItInTheConfiguredSlot() {
        service.ensure(player, true);
        assertTrue(items.isOpener(contents[8]));
        assertEquals(1, openers());
    }

    @Test
    void thePlayersOwnItemMovesAsideAndIsKept() {
        ItemStack sword = mock(ItemStack.class);
        contents[8] = sword;
        service.ensure(player, true);
        assertTrue(items.isOpener(contents[8]));
        assertEquals(sword, contents[9]);
    }

    @Test
    void copiesAreRemovedAndTheItemIsNotGivenTwice() {
        service.ensure(player, true);
        ItemStack copy = items.create(settings);
        contents[20] = copy;
        service.ensure(player, true);
        assertEquals(1, openers());
        assertTrue(items.isOpener(contents[8]));
    }

    @Test
    void anOutdatedItemIsReplaced() {
        ItemStack old = items.outdatedOpener();
        contents[8] = old;
        service.ensure(player, false);
        assertTrue(items.openers.contains(contents[8]));
    }

    @Test
    void withoutGiveOnJoinAMissingItemIsNotGiven() {
        service.ensure(player, false);
        assertEquals(0, openers());
    }

    @Test
    void javaPlayersLoseItWhenOnlyBedrockPlayersGetIt() {
        settings = new MenuOpenerSettings(true, Audience.BEDROCK, true, true, null, null, null, 8,
            EnumSet.allOf(Click.class), false, false);
        contents[8] = items.create(settings);
        service.ensure(player, true);
        assertEquals(0, openers());

        bedrock = true;
        service.ensure(player, true);
        assertEquals(1, openers());
    }

    @Test
    void switchedOffTakesItAway() {
        contents[3] = items.create(settings);
        settings = MenuOpenerSettings.disabled();
        service.ensure(player, true);
        assertEquals(0, openers());
    }

    @Test
    void aBadMaterialGivesNothingAndKeepsThePlayersItem() {
        items.usableMaterial = false;
        ItemStack sword = mock(ItemStack.class);
        contents[8] = sword;
        service.ensure(player, true);
        assertEquals(sword, contents[8]);
        assertEquals(0, openers());
    }

    @Test
    void aClickOpensTheMenuOnceWithinTheCooldown() {
        assertTrue(service.open(player, false));
        assertFalse(service.open(player, false));
        clock.addAndGet(MenuOpenerService.CLICK_COOLDOWN_MILLIS + 1);
        assertTrue(service.open(player, false));
        assertEquals(2, opened.size());
    }

    @Test
    void fromTheInventoryItClosesFirstAndOpensLater() {
        service.open(player, true);
        verify(player).closeInventory();
        assertTrue(opened.isEmpty());
        assertEquals(List.of(1L), delays);
        scheduled.get(0).run();
        assertEquals(List.of(player), opened);
    }

    @Test
    void bedrockPlayersWaitForTheirInventoryToClose() {
        bedrock = true;
        service.open(player, true);
        assertEquals(List.of(3L), delays);
    }

    @Test
    void aPlayerWhoLeftBeforeTheDelayedOpenGetsNothing() {
        service.open(player, true);
        when(player.isOnline()).thenReturn(false);
        scheduled.get(0).run();
        assertTrue(opened.isEmpty());
    }
}
