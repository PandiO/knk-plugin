package net.knightsandkings.knk.paper.siege.unlocks;

import net.knightsandkings.knk.core.menu.ActionRegistry;
import net.knightsandkings.knk.core.menu.ConditionRegistry;
import net.knightsandkings.knk.core.menu.MenuContentSourceRegistry;
import net.knightsandkings.knk.core.menu.MenuVariableProviderRegistry;
import net.knightsandkings.knk.core.ports.api.SiegeWeaponUnlocksApi;
import net.knightsandkings.knk.core.siege.unlocks.SiegeUnlockMenuIds;
import net.knightsandkings.knk.core.siege.unlocks.SiegeUnlockOffer;
import net.knightsandkings.knk.core.siege.unlocks.SiegeUnlockPurchaseResult;
import net.knightsandkings.knk.core.siege.unlocks.SiegeUnlockRowView;
import net.knightsandkings.knk.core.siege.unlocks.SiegeUnlockText;
import net.knightsandkings.knk.core.siege.unlocks.SiegeUnlocksHeaderView;
import net.knightsandkings.knk.core.siege.unlocks.SiegeUnlocksOverview;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Siege unlocks menu (siege shop § 4.2, § 5.6; A27, A28; PandiO/knk-workspace#869): rows from the API, a purchase
 * through the API outside a match, refused during one, and a fast second click that sends nothing.
 */
class SiegeUnlocksMenuFeatureTest {

    private static final UUID PLAYER = new UUID(0, 1);

    private final SiegeWeaponUnlocksApi api = mock(SiegeWeaponUnlocksApi.class);
    private final AtomicBoolean inMatch = new AtomicBoolean();
    private final Player player = mock(Player.class);
    private final SiegeUnlocksMenuFeature feature = new SiegeUnlocksMenuFeature(api, uuid -> PLAYER.equals(uuid) ? 7 : null,
            uuid -> inMatch.get(), Runnable::run, Clock.systemUTC());
    private final MenuFeatureRegistries registries = new MenuFeatureRegistries(new ActionRegistry<>(), new ConditionRegistry<>(),
            new MenuContentSourceRegistry<>(), new MenuVariableProviderRegistry<>());

    SiegeUnlocksMenuFeatureTest() {
        when(player.getUniqueId()).thenReturn(PLAYER);
        feature.registerMenuHandlers(registries);
        when(api.overview(7)).thenReturn(CompletableFuture.completedFuture(new SiegeUnlocksOverview(7, 1200, "Peasant", List.of(
                new SiegeUnlockOffer(3, "Ballista", "Direct", 500, null, null, SiegeUnlockOffer.State.UNLOCKABLE),
                new SiegeUnlockOffer(4, "Trebuchet", "Indirect", 1000, 2, "Squire", SiegeUnlockOffer.State.NEEDS_TITLE)))));
    }

    private String lastMessage() {
        ArgumentCaptor<Component> message = ArgumentCaptor.forClass(Component.class);
        verify(player, atLeastOnce()).sendMessage(message.capture());
        String text = PlainTextComponentSerializer.plainText().serialize(message.getValue());
        return text.startsWith("[Siege] ") ? text.substring("[Siege] ".length()) : text;
    }

    @Test
    void everyIdTheSeedUsesIsRegistered() {
        assertTrue(registries.actions().registeredIds().contains(SiegeUnlockMenuIds.ACTION_BUY));
        assertTrue(registries.conditions().isRegistered(SiegeUnlockMenuIds.CONDITION_PURCHASE_PENDING));
        assertEquals(SiegeUnlockRowView.class, registries.contentSources().rowType(SiegeUnlockMenuIds.SOURCE_UNLOCKS));
        assertEquals(SiegeUnlocksHeaderView.class, registries.variables().declaredTypes().get(SiegeUnlockMenuIds.ROOT_UNLOCKS));
    }

    @Test
    void rowsListEveryTemplateWithTheViewersState_A27() {
        List<SiegeUnlockRowView> rows = feature.rows(player).join();
        assertEquals(List.of(3, 4), rows.stream().map(SiegeUnlockRowView::getTemplateId).toList());
        assertTrue(rows.get(0).getCanUnlock());
        assertEquals("&cNeeds title Squire", rows.get(1).getLines().get(3));
        assertEquals("&7Coins: &61200", feature.header(player).getCoinsLine());
        // Cached: the next render asks nothing.
        feature.rows(player).join();
        verify(api, times(1)).overview(7);
    }

    @Test
    void aPurchaseGoesToTheApi_andDropsTheCachedRows() {
        feature.rows(player).join();
        when(api.purchase(7, 3)).thenReturn(CompletableFuture.completedFuture(
                new SiegeUnlockPurchaseResult.Unlocked("Ballista", 500, false, 700)));

        feature.buy(player, 3).join();

        assertEquals("Unlocked the Ballista for 500 coins. You can buy it in siege matches now.", lastMessage());
        feature.rows(player).join();
        verify(api, times(2)).overview(7);
    }

    @Test
    void aRefusalIsShown_A27() {
        when(api.purchase(7, 4)).thenReturn(CompletableFuture.completedFuture(
                new SiegeUnlockPurchaseResult.Refused("NeedsTitle", "You need the title Squire to unlock the Trebuchet.")));
        feature.buy(player, 4).join();
        assertEquals("You need the title Squire to unlock the Trebuchet.", lastMessage());
    }

    @Test
    void duringAMatchNothingIsBought_andTheMenuSaysSo_A28() {
        inMatch.set(true);

        registries.actions().execute(SiegeUnlockMenuIds.ACTION_BUY,
                new MenuActionContext(player, null, Map.of(), null, null, null, null, null), Map.of("templateId", "3"));

        assertEquals(SiegeUnlockText.NOT_DURING_A_MATCH, lastMessage());
        verify(api, never()).purchase(anyInt(), anyInt());
        assertFalse(feature.rows(player).join().get(0).getCanUnlock());
        assertEquals("&c" + SiegeUnlockText.NOT_DURING_A_MATCH, feature.header(player).getMatchLine());
    }

    @Test
    void aSecondClickWhileTheFirstRunsSendsNothing_A27() {
        CompletableFuture<SiegeUnlockPurchaseResult> pending = new CompletableFuture<>();
        when(api.purchase(7, 3)).thenReturn(pending);

        CompletableFuture<Void> first = feature.buy(player, 3);
        feature.buy(player, 3);
        verify(api, times(1)).purchase(7, 3);

        pending.complete(new SiegeUnlockPurchaseResult.Unlocked("Ballista", 500, false, 700));
        first.join();
        assertTrue(first.isDone());
    }

    @Test
    void aFailedCallSaysSo() {
        when(api.purchase(7, 3)).thenReturn(CompletableFuture.failedFuture(new RuntimeException("timeout")));
        feature.buy(player, 3).join();
        assertEquals("The unlock could not be checked right now. Look at the menu again, then try once more.", lastMessage());
    }
}
