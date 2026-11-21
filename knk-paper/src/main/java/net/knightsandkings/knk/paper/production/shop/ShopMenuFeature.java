package net.knightsandkings.knk.paper.production.shop;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.menu.ConditionOutcome;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.production.shop.ShopMenuKeys;
import net.knightsandkings.knk.core.production.shop.ShopPurchaseRequest;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuFeature;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;

/**
 * What the seeded shop menus need from the menu engine (PandiO/knk-workspace#687, shops DESIGN § 8; knk-web-api
 * {@code MenuTemplateSeed.Shops.cs}):
 * <ol>
 *   <li>root {@value ShopMenuKeys#ROOT} ({@link ShopView});</li>
 *   <li>row sources {@value ShopMenuKeys#SOURCE_OFFER} (the catalogue of {@code ctx.shopId}) and
 *       {@value ShopMenuKeys#SOURCE_ITEM} (the item of {@code ctx.item});</li>
 *   <li>actions {@value ShopMenuKeys#ACTION_AMOUNT}, {@value ShopMenuKeys#ACTION_PURCHASE} ({@code deliverTo}
 *       {@code Inventory} or {@code Inbox}) and {@value ShopMenuKeys#ACTION_SELL};</li>
 *   <li>conditions {@value ShopMenuKeys#CONDITION_BUYS} (the Sell chest) and {@value ShopMenuKeys#CONDITION_BUYABLE}
 *       (the buy and confirm buttons).</li>
 * </ol>
 * Always registered, so the seeded menus validate also with {@code production.enabled} off; then the services are null,
 * the menus are blocked (KnKPlugin), the sources are empty, the conditions deny and the actions say production is off.
 */
public final class ShopMenuFeature implements MenuFeature {

    private final Supplier<ShopService> service;
    private final Supplier<SellModule> sell;

    public ShopMenuFeature(Supplier<ShopService> service, Supplier<SellModule> sell) {
        this.service = service;
        this.sell = sell;
    }

    @Override
    public void registerMenuHandlers(MenuFeatureRegistries registries) {
        registries.variables().register(ShopMenuKeys.ROOT, ShopView.class, (player, ctx) -> {
            ShopService s = service.get();
            return s == null ? new ShopView(0, null, null, 1, null, null, null) : s.view(player, ctx);
        });

        var sources = registries.contentSources();
        sources.registerRows(ShopMenuKeys.SOURCE_OFFER, ShopOfferRow.class, (context, params, query) -> {
            ShopService s = service.get();
            return s == null ? empty() : s.offerRows(context.player(), context.menuContext(), query);
        });
        sources.registerRows(ShopMenuKeys.SOURCE_ITEM, ShopOfferRow.class, (context, params, query) -> {
            ShopService s = service.get();
            return s == null ? empty() : s.itemRow(context.player(), context.menuContext());
        });

        var actions = registries.actions();
        actions.register(ShopMenuKeys.ACTION_AMOUNT, (context, params) -> {
            ShopService s = service.get();
            if (s != null) {
                s.cycleAmount(context.player(), context.menuContext());
            }
        });
        actions.register(ShopMenuKeys.ACTION_PURCHASE, (context, params) -> {
            Player player = context.player();
            ShopService s = service.get();
            if (player == null) {
                return;
            }
            if (s == null) {
                off(player);
                return;
            }
            s.purchase(player, context.menuContext(), deliverTo(params.get("deliverTo")));
        });
        actions.register(ShopMenuKeys.ACTION_SELL, (context, params) -> {
            Player player = context.player();
            SellModule module = sell.get();
            Integer shopId = ShopService.shopId(context.menuContext());
            if (player == null || shopId == null) {
                return;
            }
            if (module == null) {
                off(player);
                return;
            }
            module.open(player, shopId);
        });

        registries.conditions().register(ShopMenuKeys.CONDITION_BUYS, (context, params) -> {
            ShopService s = service.get();
            return s != null && s.buys(menuContext(context)) ? ConditionOutcome.allow()
                    : ConditionOutcome.deny("This shop doesn't buy items from players.");
        });
        registries.conditions().register(ShopMenuKeys.CONDITION_BUYABLE, (context, params) -> {
            ShopService s = service.get();
            if (s == null) {
                return ConditionOutcome.deny("Production is switched off on this server.");
            }
            String why = s.notBuyable(menuContext(context));
            return why == null ? ConditionOutcome.allow() : ConditionOutcome.deny(why);
        });
    }

    static ShopPurchaseRequest.DeliverTo deliverTo(String value) {
        return value != null && value.trim().equalsIgnoreCase("Inbox")
                ? ShopPurchaseRequest.DeliverTo.INBOX : ShopPurchaseRequest.DeliverTo.INVENTORY;
    }

    private static MenuContextParams menuContext(MenuActionContext context) {
        return context == null ? MenuContextParams.EMPTY : context.menuContext();
    }

    private static void off(Player player) {
        player.sendMessage(ChatColor.RED + "Shops are switched off on this server (production.enabled: false).");
    }

    private static CompletableFuture<Page<ShopOfferRow>> empty() {
        return CompletableFuture.completedFuture(new Page<>(List.<ShopOfferRow>of(), 0, 1, 1));
    }
}
