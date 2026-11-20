package net.knightsandkings.knk.paper.siege.unlocks;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.menu.ConditionOutcome;
import net.knightsandkings.knk.core.menu.MenuSession;
import net.knightsandkings.knk.core.ports.api.SiegeWeaponUnlocksApi;
import net.knightsandkings.knk.core.siege.unlocks.SiegeUnlockMenuIds;
import net.knightsandkings.knk.core.siege.unlocks.SiegeUnlockPurchaseResult;
import net.knightsandkings.knk.core.siege.unlocks.SiegeUnlockRowView;
import net.knightsandkings.knk.core.siege.unlocks.SiegeUnlockText;
import net.knightsandkings.knk.core.siege.unlocks.SiegeUnlocksHeaderView;
import net.knightsandkings.knk.core.siege.unlocks.SiegeUnlocksOverview;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuFeature;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import net.knightsandkings.knk.paper.siege.SiegeMessages;
import org.bukkit.entity.Player;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Siege unlocks menu (siege shop DESIGN § 4.2, § 5.6, § 7.2.5; P08, P09; A27, A28; PandiO/knk-workspace#869):
 * every siege weapon type with its coin price, minimum title and the viewer's state, read from
 * {@code GET api/SiegeWeaponUnlocks/by-user/{userId}} off the main thread (cached per viewer for
 * {@link #OVERVIEW_TTL_MILLIS}, dropped after a purchase). An unlockable row asks for a confirmation
 * ({@code menu.confirm.request} wrapping {@code siege.unlocks.buy}); the purchase is the API's (title level and coins
 * checked before charging, once per player and type). Refused while the player plays in a running siege match (A28):
 * the menu opens, says so, and buys nothing. Registers root {@code siegeUnlocks}, row source {@code siege.unlocks},
 * action {@code siege.unlocks.buy} ({@code templateId}) and condition {@code siege.unlocks.purchase-pending}. Always
 * registered; no flag (the menu only reads and sells data the API owns).
 */
public final class SiegeUnlocksMenuFeature implements MenuFeature {

    static final long OVERVIEW_TTL_MILLIS = 5_000L;
    private static final Logger LOGGER = Logger.getLogger(SiegeUnlocksMenuFeature.class.getName());

    private record Cached(long fetchedAtMillis, SiegeUnlocksOverview overview) {
    }

    private final SiegeWeaponUnlocksApi api;
    private final Function<UUID, Integer> userIds;
    private final Predicate<UUID> inMatch;
    private final Executor mainThread;
    private final Clock clock;
    private final Map<Integer, Cached> overviews = new ConcurrentHashMap<>();
    private final Set<UUID> buying = ConcurrentHashMap.newKeySet();

    /**
     * @param userIds   the player's knk user id, null while their account isn't cached
     * @param inMatch   true while the player plays in a running siege match
     * @param mainThread where API answers are handled (messages, menu refresh)
     */
    public SiegeUnlocksMenuFeature(SiegeWeaponUnlocksApi api, Function<UUID, Integer> userIds, Predicate<UUID> inMatch,
                                   Executor mainThread, Clock clock) {
        this.api = api;
        this.userIds = userIds;
        this.inMatch = inMatch;
        this.mainThread = mainThread;
        this.clock = clock;
    }

    @Override
    public void registerMenuHandlers(MenuFeatureRegistries registries) {
        registries.variables().register(SiegeUnlockMenuIds.ROOT_UNLOCKS, SiegeUnlocksHeaderView.class,
                (player, ctx) -> header(player));
        registries.contentSources().registerRows(SiegeUnlockMenuIds.SOURCE_UNLOCKS, SiegeUnlockRowView.class,
                (context, params, query) -> rows(context.player()).thenApply(SiegeUnlocksMenuFeature::page));
        registries.actions().register(SiegeUnlockMenuIds.ACTION_BUY, (context, params) -> {
            Player player = context.player();
            if (player == null) return;
            buy(player, parse(params.get("templateId"))).whenComplete((ok, ex) -> {
                if (context.menuService() != null) context.menuService().refreshOpenMenu(player);
            });
        });
        registries.conditions().register(SiegeUnlockMenuIds.CONDITION_PURCHASE_PENDING,
                (context, params) -> purchasePending(context));
    }

    // ===== reads =====

    SiegeUnlocksHeaderView header(Player player) {
        if (player == null) return new SiegeUnlocksHeaderView(null, null, false);
        Integer userId = userIds.apply(player.getUniqueId());
        Cached cached = userId == null ? null : overviews.get(userId);
        boolean playing = inMatch.test(player.getUniqueId());
        return cached == null
                ? new SiegeUnlocksHeaderView(null, null, playing)
                : new SiegeUnlocksHeaderView(cached.overview().coins(), cached.overview().titleName(), playing);
    }

    /** Main thread. Completed at once when the viewer's overview is cached, else when the API answers. */
    CompletableFuture<List<SiegeUnlockRowView>> rows(Player player) {
        if (player == null) return CompletableFuture.completedFuture(List.of());
        Integer userId = userIds.apply(player.getUniqueId());
        if (userId == null) return CompletableFuture.completedFuture(List.of());
        boolean playing = inMatch.test(player.getUniqueId());
        return overview(userId)
                .thenApply(o -> o.offers().stream().map(offer -> new SiegeUnlockRowView(offer, o.coins(), playing)).toList())
                .exceptionally(ex -> {
                    LOGGER.log(Level.WARNING, "siege.unlocks: failed to load the unlocks of user " + userId, ex);
                    return List.of();
                });
    }

    private CompletableFuture<SiegeUnlocksOverview> overview(int userId) {
        Cached cached = overviews.get(userId);
        if (cached != null && clock.millis() - cached.fetchedAtMillis() < OVERVIEW_TTL_MILLIS) {
            return CompletableFuture.completedFuture(cached.overview());
        }
        return api.overview(userId).thenApply(o -> {
            overviews.put(userId, new Cached(clock.millis(), o));
            return o;
        });
    }

    // ===== purchase =====

    /** The {@code siege.unlocks.buy} action: refused in a match (A28), else the API's purchase. Completes on the main thread. */
    CompletableFuture<Void> buy(Player player, Integer templateId) {
        UUID id = player.getUniqueId();
        if (templateId == null) return CompletableFuture.completedFuture(null);
        if (inMatch.test(id)) {
            player.sendMessage(SiegeMessages.bad(SiegeUnlockText.NOT_DURING_A_MATCH));
            return CompletableFuture.completedFuture(null);
        }
        Integer userId = userIds.apply(id);
        if (userId == null) {
            player.sendMessage(SiegeMessages.bad("Your account isn't loaded yet - try again in a moment."));
            return CompletableFuture.completedFuture(null);
        }
        if (!buying.add(id)) return CompletableFuture.completedFuture(null); // a fast second click: the first one answers
        CompletableFuture<Void> done = new CompletableFuture<>();
        api.purchase(userId, templateId).whenComplete((result, ex) -> mainThread.execute(() -> {
            buying.remove(id);
            overviews.remove(userId);
            if (ex != null) {
                LOGGER.log(Level.WARNING, "siege.unlocks: unlocking template " + templateId + " for user " + userId + " failed", ex);
                player.sendMessage(SiegeMessages.bad("The unlock could not be checked right now. Look at the menu again, then try once more."));
            } else if (result instanceof SiegeUnlockPurchaseResult.Unlocked) {
                player.sendMessage(SiegeMessages.good(SiegeUnlockText.result(result)));
            } else {
                player.sendMessage(SiegeMessages.bad(SiegeUnlockText.result(result)));
            }
            done.complete(null);
        }));
        return done;
    }

    private static ConditionOutcome purchasePending(MenuActionContext context) {
        MenuSession session = context.session();
        boolean pending = session != null && session.getPendingConfirmation()
                .map(p -> SiegeUnlockMenuIds.ACTION_BUY.equals(p.actionTypeId()))
                .orElse(false);
        return pending ? ConditionOutcome.allow() : ConditionOutcome.deny("Nothing to confirm.");
    }

    static Integer parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static <R> Page<R> page(List<R> rows) {
        return new Page<>(rows, rows.size(), 1, Math.max(1, rows.size()));
    }
}
