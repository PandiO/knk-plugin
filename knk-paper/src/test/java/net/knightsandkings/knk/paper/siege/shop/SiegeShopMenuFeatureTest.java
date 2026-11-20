package net.knightsandkings.knk.paper.siege.shop;

import net.knightsandkings.knk.core.menu.ActionRegistry;
import net.knightsandkings.knk.core.menu.ConditionRegistry;
import net.knightsandkings.knk.core.menu.MenuContentSourceRegistry;
import net.knightsandkings.knk.core.menu.MenuVariableProviderRegistry;
import net.knightsandkings.knk.core.siege.shop.ShopCategoryView;
import net.knightsandkings.knk.core.siege.shop.ShopEntryView;
import net.knightsandkings.knk.core.siege.shop.ShopHeaderView;
import net.knightsandkings.knk.core.siege.shop.ShopMenuIds;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The shop's menu ids (siege shop § 7.2.3, PandiO/knk-workspace#868): registered also with {@code siege.shop.enabled}
 * off, so the seeded {@code siege.information} keeps validating; then its Shop button's condition denies (the menu looks
 * as before), the rows are empty and the actions only say the shop is off.
 */
class SiegeShopMenuFeatureTest {

    private final AtomicReference<SiegeShopService> service = new AtomicReference<>();
    private final MenuFeatureRegistries registries = new MenuFeatureRegistries(new ActionRegistry<>(), new ConditionRegistry<>(),
            new MenuContentSourceRegistry<>(), new MenuVariableProviderRegistry<>());
    private final Player player = mock(Player.class);
    private final MenuActionContext context = new MenuActionContext(player, null, Map.of(), null, null, null, null, null);

    SiegeShopMenuFeatureTest() {
        when(player.getUniqueId()).thenReturn(new UUID(0, 1));
        new SiegeShopMenuFeature(service::get).registerMenuHandlers(registries);
    }

    private String lastMessage() {
        ArgumentCaptor<Component> message = ArgumentCaptor.forClass(Component.class);
        verify(player, org.mockito.Mockito.atLeastOnce()).sendMessage(message.capture());
        String text = PlainTextComponentSerializer.plainText().serialize(message.getValue());
        return text.startsWith("[Siege] ") ? text.substring("[Siege] ".length()) : text;
    }

    @Test
    void everyIdTheSeedsUseIsRegistered() {
        assertTrue(registries.actions().registeredIds().containsAll(java.util.List.of(ShopMenuIds.ACTION_BUY, ShopMenuIds.ACTION_OPEN)));
        assertTrue(registries.conditions().isRegistered(ShopMenuIds.CONDITION_AVAILABLE));
        assertEquals(ShopCategoryView.class, registries.contentSources().rowType(ShopMenuIds.SOURCE_CATEGORIES));
        assertEquals(ShopEntryView.class, registries.contentSources().rowType(ShopMenuIds.SOURCE_ENTRIES));
        assertEquals(ShopHeaderView.class, registries.variables().declaredTypes().get(ShopMenuIds.ROOT_SHOP));
    }

    @Test
    void withTheShopOffTheButtonIsHiddenAndTheActionsSaySo() {
        assertFalse(registries.conditions().test(ShopMenuIds.CONDITION_AVAILABLE, context, Map.of()).allowed());
        registries.actions().execute(ShopMenuIds.ACTION_OPEN, context, Map.of());
        assertEquals("The siege shop is not enabled on this server.", lastMessage());
        registries.actions().execute(ShopMenuIds.ACTION_BUY, context, Map.of("entryId", "11"));
        assertEquals("The siege shop is not enabled on this server.", lastMessage());
    }

    @Test
    void withTheShopOnTheButtonFollowsTheMatch_andBuyingReachesTheService() {
        SiegeShopService shop = mock(SiegeShopService.class);
        service.set(shop);
        when(shop.available(new UUID(0, 1))).thenReturn(true);
        assertTrue(registries.conditions().test(ShopMenuIds.CONDITION_AVAILABLE, context, Map.of()).allowed());

        when(shop.buy(player, 11)).thenReturn(Component.text("Bought Ender Pearl for 300 siege points. Balance: 100."));
        registries.actions().execute(ShopMenuIds.ACTION_BUY, context, Map.of("entryId", "11"));
        assertEquals("Bought Ender Pearl for 300 siege points. Balance: 100.", lastMessage());
        registries.actions().execute(ShopMenuIds.ACTION_OPEN, context, Map.of());
        verify(shop).open(player);
    }
}
