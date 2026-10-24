package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Clicks with the navigation compass (KNG-234), above all the click on the sky. */
class NavigationCompassListenerTest {

    private final NavigationCompassService compass = mock(NavigationCompassService.class);
    private final NavigationCompassListener listener = new NavigationCompassListener(compass);
    private final PlayerInteractEvent event = mock(PlayerInteractEvent.class);
    private final Player player = mock(Player.class);
    private final ItemStack item = mock(ItemStack.class);

    @BeforeEach
    void setUp() {
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getPlayer()).thenReturn(player);
        when(event.getItem()).thenReturn(item);
        when(event.useItemInHand()).thenReturn(Event.Result.DEFAULT);
        when(compass.isCompass(item)).thenReturn(true);
        when(compass.use(player, false)).thenReturn(true);
    }

    @Test
    void aRightClickOnTheSkyWorksAlthoughBukkitReportsItAsCancelled() {
        // no block to interact with: Bukkit's isCancelled() is true for every click in the air
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.isCancelled()).thenReturn(true);

        listener.onInteract(event);

        verify(compass).use(player, false);
        verify(event).setUseItemInHand(Event.Result.DENY);
    }

    @Test
    void theHandlerMustNotIgnoreCancelledEventsOrSkyClicksAreLostAgain() throws Exception {
        EventHandler handler = NavigationCompassListener.class
            .getMethod("onInteract", PlayerInteractEvent.class).getAnnotation(EventHandler.class);

        assertFalse(handler.ignoreCancelled());
    }

    @Test
    void aClickWhoseItemUseSomebodyDeniedIsLeftAlone() {
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.useItemInHand()).thenReturn(Event.Result.DENY);

        listener.onInteract(event);

        verify(compass, never()).use(player, false);
    }

    @Test
    void sneakingMakesItTheShiftClick() {
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(player.isSneaking()).thenReturn(true);
        when(compass.use(player, true)).thenReturn(true);

        listener.onInteract(event);

        verify(compass).use(player, true);
    }

    @Test
    void theOffHandLeftClicksAndOtherItemsAreIgnored() {
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        listener.onInteract(event);

        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.LEFT_CLICK_AIR);
        listener.onInteract(event);

        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(compass.isCompass(item)).thenReturn(false);
        listener.onInteract(event);

        verify(compass, never()).use(player, false);
    }
}
