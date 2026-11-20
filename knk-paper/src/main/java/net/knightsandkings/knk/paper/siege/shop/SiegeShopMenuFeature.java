package net.knightsandkings.knk.paper.siege.shop;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.menu.ConditionOutcome;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.siege.shop.ShopCategoryView;
import net.knightsandkings.knk.core.siege.shop.ShopEntryView;
import net.knightsandkings.knk.core.siege.shop.ShopHeaderView;
import net.knightsandkings.knk.core.siege.shop.ShopMenuIds;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuFeature;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * What the siege shop menus {@code siege.shop} and {@code siege.shop.category} need from the menu engine (siege shop
 * DESIGN § 7.2.3, P24; PandiO/knk-workspace#868):
 * <ul>
 *   <li>root {@code siegeShop} ({@link ShopHeaderView}: balance, the category of {@code ctx.categoryId}, footer);</li>
 *   <li>row sources {@code siege.shop-categories} and {@code siege.shop-entries} ({@code categoryId} param);</li>
 *   <li>actions {@code siege.shop.buy} ({@code entryId} param) and {@code siege.shop.open};</li>
 *   <li>condition {@code siege.shop-available} (the Shop button in {@code siege.information}).</li>
 * </ul>
 * Always registered, so the seeded menus validate also with {@code siege.shop.enabled} off; then the service is null,
 * the condition denies (the Shop button stays hidden), the sources are empty and the actions say the shop is off.
 */
public final class SiegeShopMenuFeature implements MenuFeature {

    private final Supplier<SiegeShopService> service;

    public SiegeShopMenuFeature(Supplier<SiegeShopService> service) {
        this.service = service;
    }

    @Override
    public void registerMenuHandlers(MenuFeatureRegistries registries) {
        registries.variables().register(ShopMenuIds.ROOT_SHOP, ShopHeaderView.class, (player, ctx) -> {
            SiegeShopService s = service.get();
            return s == null || player == null ? new ShopHeaderView(0, null, 0) : s.header(player, categoryOf(ctx));
        });

        var sources = registries.contentSources();
        sources.registerRows(ShopMenuIds.SOURCE_CATEGORIES, ShopCategoryView.class, (context, params, query) -> {
            SiegeShopService s = service.get();
            return page(s == null || context.player() == null ? List.of() : s.categories(context.player()));
        });
        sources.registerRows(ShopMenuIds.SOURCE_ENTRIES, ShopEntryView.class, (context, params, query) -> {
            SiegeShopService s = service.get();
            return page(s == null || context.player() == null ? List.of() : s.entries(context.player(), parse(params.get("categoryId"))));
        });

        var actions = registries.actions();
        actions.register(ShopMenuIds.ACTION_BUY, (context, params) -> {
            Player player = context.player();
            if (player == null) return;
            SiegeShopService s = service.get();
            Integer entryId = parse(params.get("entryId"));
            Component reply = s == null ? off() : entryId == null ? null : s.buy(player, entryId);
            if (reply != null) player.sendMessage(reply);
            if (context.menuService() != null) context.menuService().refreshOpenMenu(player);
        });
        actions.register(ShopMenuIds.ACTION_OPEN, (context, params) -> {
            Player player = context.player();
            if (player == null) return;
            SiegeShopService s = service.get();
            Component reply = s == null ? off() : s.open(player);
            if (reply != null) player.sendMessage(reply);
        });

        registries.conditions().register(ShopMenuIds.CONDITION_AVAILABLE, (context, params) -> {
            SiegeShopService s = service.get();
            Player player = context.player();
            return s != null && player != null && s.available(player.getUniqueId())
                    ? ConditionOutcome.allow()
                    : ConditionOutcome.deny("The siege shop is open only during a running siege match.");
        });
    }

    private static Component off() {
        return net.knightsandkings.knk.paper.siege.SiegeMessages.bad("The siege shop is not enabled on this server.");
    }

    private static Integer categoryOf(MenuContextParams ctx) {
        return ctx == null ? null : parse(ctx.get(ShopMenuIds.CTX_CATEGORY_ID));
    }

    static Integer parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static <R> CompletableFuture<Page<R>> page(List<R> rows) {
        return CompletableFuture.completedFuture(new Page<>(rows, rows.size(), 1, Math.max(1, rows.size())));
    }
}
