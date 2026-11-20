package net.knightsandkings.knk.paper.siege.shop;

import net.knightsandkings.knk.core.ports.api.SiegeShopApi;
import net.knightsandkings.knk.core.siege.economy.SiegePointsEconomy;
import net.knightsandkings.knk.core.siege.shop.ShopCatalogue;
import net.knightsandkings.knk.core.siege.shop.ShopCategory;
import net.knightsandkings.knk.core.siege.shop.ShopCategoryView;
import net.knightsandkings.knk.core.siege.shop.ShopEntry;
import net.knightsandkings.knk.core.siege.shop.ShopEntryKind;
import net.knightsandkings.knk.core.siege.shop.ShopEntryView;
import net.knightsandkings.knk.core.siege.shop.ShopHeaderView;
import net.knightsandkings.knk.core.siege.shop.ShopPurchaseHandler;
import net.knightsandkings.knk.core.siege.shop.ShopPurchaseResult;
import net.knightsandkings.knk.core.siege.shop.ShopPurchaseService;
import net.knightsandkings.knk.core.siege.shop.ShopRefusal;
import net.knightsandkings.knk.core.siege.shop.SiegePointsShopWallet;
import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import net.knightsandkings.knk.paper.siege.SiegeMatch;
import net.knightsandkings.knk.paper.siege.SiegeMatchObserver;
import net.knightsandkings.knk.paper.siege.SiegeMessages;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The siege shop in PvP Siege (siege shop DESIGN § 4.1, § 5.3–5.5, § 7.2.3–7.2.4, P02, P22–P25, P27;
 * PandiO/knk-workspace#868). Registered only with {@code siege.shop.enabled} (default false) and siege points on;
 * without it nothing here exists and Siege behaves as before.
 * <ul>
 *   <li><b>Frozen catalogue (P25):</b> at the match start the effective catalogue of the mode and scenario is read
 *       once from the API (off the main thread, two retries 5 s apart), weapon entries dropped while
 *       {@code siege.weapons.enabled} is off, the entries' blueprint items built, and a {@link ShopPurchaseService}
 *       made for the match. Admin edits apply from the next match (A23). The API is never on the purchase path.</li>
 *   <li><b>Buy anywhere, any time (P02):</b> {@code /siege shop}, the Shop button in {@code siege.information} and
 *       (with #871) the menu item open the {@code siege.shop} menu for a member of a running match.</li>
 *   <li><b>Purchases:</b> check, charge, give ({@link ShopPurchaseService}); items through {@link ItemPurchaseHandler},
 *       siege weapons through the handler #784 registers with {@link #registerHandler} (refused until then); weapon
 *       unlocks (W32) are read per member at the start.</li>
 *   <li><b>Survival hook (P21):</b> a Siege Survival match offers its own {@link ShopPurchaseService} through
 *       {@link #addMatchSource}; {@code /siege shop} then opens the same shop on Survival's balance. Survival's in-range
 *       {@code /siege shop} for purchase points becomes {@code /siege buy} at its merge.</li>
 * </ul>
 * Main thread, except the API reads.
 */
public final class SiegeShopService implements SiegeMatchObserver {

    /** The PvP mode's name in the catalogue API. */
    public static final String MODE_SIEGE = "Siege";

    /** Retries of the catalogue read at match start, and their spacing in ticks. */
    static final int CATALOGUE_RETRIES = 2;
    static final long RETRY_DELAY_TICKS = 100;

    /** The match of a player; {@code SiegeService::runningMatchOf}. */
    @FunctionalInterface
    public interface MatchLookup {
        Optional<SiegeMatch> runningMatchOf(UUID playerId);
    }

    /** Another mode's shop for a player (Siege Survival at its merge); empty when the player isn't in one. */
    @FunctionalInterface
    public interface MatchSource {
        Optional<ShopPurchaseService> shopOf(UUID playerId);
    }

    /** Builds a blueprint's item off the main thread (null when it can't be built). */
    @FunctionalInterface
    public interface ItemSource {
        CompletableFuture<ItemStack> item(int itemBlueprintId);
    }

    /** Opens the {@code siege.shop} menu; false when the menu is unavailable. */
    @FunctionalInterface
    public interface Menus {
        boolean openShop(Player player);
    }

    /** Runs a task on the main thread after some ticks. */
    @FunctionalInterface
    public interface Later {
        void run(long ticks, Runnable task);
    }

    /** One PvP match's shop. */
    private static final class Session {
        final SiegeLobbyRuntime lobby;
        final ShopPurchaseService shop;
        /** Each item entry's material, for entries without their own icon. */
        final Map<Integer, String> icons;
        final Map<UUID, Set<Integer>> unlocked = new ConcurrentHashMap<>();

        Session(SiegeLobbyRuntime lobby, ShopPurchaseService shop, Map<Integer, String> icons) {
            this.lobby = lobby;
            this.shop = shop;
            this.icons = Map.copyOf(icons);
        }
    }

    private enum Loading { LOADING, FAILED }

    private final Logger logger;
    private final SiegeShopApi api;
    private final MatchLookup matches;
    private final Function<SiegeMatch, Optional<SiegePointsEconomy>> economies;
    private final Function<UUID, Integer> userIds;
    private final Function<UUID, Player> players;
    private final ItemSource itemSource;
    private final boolean weaponsEnabled;
    private final SiegeShopTelemetry telemetry;
    private final Executor mainThread;
    private final Later later;
    private final LongSupplier clock;
    private Menus menus = player -> false;

    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, Loading> loading = new HashMap<>();
    private final Set<String> live = new HashSet<>();
    private final Map<ShopEntryKind, ShopPurchaseHandler> extraHandlers = new LinkedHashMap<>();
    private final List<MatchSource> matchSources = new CopyOnWriteArrayList<>();

    /**
     * @param economies the match's siege points ({@code SiegePointsService::economyOf})
     * @param userIds   a member's user id (for the weapon unlocks), null when unknown
     * @param telemetry null without diagnostic telemetry
     */
    public SiegeShopService(Logger logger, SiegeShopApi api, MatchLookup matches,
                            Function<SiegeMatch, Optional<SiegePointsEconomy>> economies, Function<UUID, Integer> userIds,
                            Function<UUID, Player> players, ItemSource itemSource, boolean weaponsEnabled,
                            SiegeShopTelemetry telemetry, Executor mainThread, Later later, LongSupplier clock) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.api = Objects.requireNonNull(api, "api");
        this.matches = Objects.requireNonNull(matches, "matches");
        this.economies = Objects.requireNonNull(economies, "economies");
        this.userIds = userIds == null ? id -> null : userIds;
        this.players = Objects.requireNonNull(players, "players");
        this.itemSource = Objects.requireNonNull(itemSource, "itemSource");
        this.weaponsEnabled = weaponsEnabled;
        this.telemetry = telemetry;
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.later = Objects.requireNonNull(later, "later");
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    public void setMenus(Menus menus) {
        this.menus = menus == null ? player -> false : menus;
    }

    /**
     * The handler for a kind (siege weapons phase 3, #784: {@code SiegeWeaponPurchaseHandler}); used by every shop from
     * now on, running matches included.
     */
    public void registerHandler(ShopPurchaseHandler handler) {
        extraHandlers.put(handler.kind(), handler);
        sessions.values().forEach(s -> s.shop.handler(handler));
    }

    /** The Survival hook: another mode's shops (P21). */
    public void addMatchSource(MatchSource source) {
        matchSources.add(Objects.requireNonNull(source, "source"));
    }

    // ==================== Match lifecycle ====================

    @Override
    public void matchStarted(SiegeLobbyRuntime lobby, SiegeMatch match) {
        if (economies.apply(match).isEmpty()) return;
        String token = match.matchToken();
        live.add(token);
        loading.put(token, Loading.LOADING);
        load(lobby, match, 0);
    }

    private void load(SiegeLobbyRuntime lobby, SiegeMatch match, int attempt) {
        String token = match.matchToken();
        Integer scenarioId = match.scenario() == null ? null : match.scenario().id();
        CompletableFuture<ShopCatalogue> catalogue;
        try {
            catalogue = api.catalogue(MODE_SIEGE, scenarioId);
        } catch (RuntimeException e) {
            catalogue = CompletableFuture.failedFuture(e);
        }
        catalogue.thenCompose(c -> {
                    ShopCatalogue frozen = weaponsEnabled ? c : c.withoutWeapons();
                    return items(frozen).thenApply(items -> Map.entry(frozen, items));
                })
                .whenComplete((loaded, error) -> mainThread.execute(() -> {
                    if (!live.contains(token)) return; // the match ended meanwhile
                    if (error != null || loaded == null) {
                        if (attempt < CATALOGUE_RETRIES) {
                            later.run(RETRY_DELAY_TICKS, () -> {
                                if (live.contains(token)) load(lobby, match, attempt + 1);
                            });
                        } else {
                            loading.put(token, Loading.FAILED);
                            logger.log(Level.WARNING, "[Siege shop] The catalogue of match " + token + " (scenario "
                                    + scenarioId + ") couldn't be read; no shop in this match", error);
                        }
                        return;
                    }
                    install(lobby, match, loaded.getKey(), loaded.getValue());
                }));
    }

    /** The entries' blueprint items, built once per match; an entry whose item can't be built is not sold. */
    private CompletableFuture<Map<Integer, ItemStack>> items(ShopCatalogue catalogue) {
        Map<Integer, ItemStack> built = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> work = new ArrayList<>();
        for (ShopEntry entry : catalogue.entries()) {
            if (entry.kind() != ShopEntryKind.ITEM || entry.itemBlueprintId() == null) continue;
            work.add(itemSource.item(entry.itemBlueprintId())
                    .handle((item, error) -> {
                        if (item != null) built.put(entry.id(), item);
                        else logger.warning("[Siege shop] Entry " + entry.id() + " (" + entry.name() + "): blueprint "
                                + entry.itemBlueprintId() + " couldn't be built" + (error == null ? "" : ": " + error));
                        return null;
                    }));
        }
        return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).thenApply(v -> Map.copyOf(built));
    }

    private void install(SiegeLobbyRuntime lobby, SiegeMatch match, ShopCatalogue catalogue, Map<Integer, ItemStack> items) {
        Optional<SiegePointsEconomy> economy = economies.apply(match);
        String token = match.matchToken();
        if (economy.isEmpty()) {
            loading.put(token, Loading.FAILED);
            return;
        }
        ItemPurchaseHandler itemHandler = new ItemPurchaseHandler(players, items);
        ShopPurchaseService shop = new ShopPurchaseService(token, catalogue, new SiegePointsShopWallet(economy.get()),
                player -> membership(token, player), clock, (player, bought) -> purchased(lobby, player, bought));
        Map<Integer, String> icons = new HashMap<>();
        items.forEach((entryId, item) -> icons.put(entryId, item.getType().name()));
        Session session = new Session(lobby, shop, icons);
        shop.handler(itemHandler);
        extraHandlers.values().forEach(shop::handler);
        shop.unlocks((player, entry) -> entry.siegeWeaponTemplateId() != null
                && session.unlocked.getOrDefault(player, Set.of()).contains(entry.siegeWeaponTemplateId()));
        sessions.put(token, session);
        loading.remove(token);
        if (catalogue.entries().stream().anyMatch(e -> e.kind() == ShopEntryKind.SIEGE_WEAPON)) {
            match.roster().playerIds().forEach(id -> loadUnlocks(session, id));
        }
        logger.info("[Siege shop] Match " + token + ": " + catalogue.entries().size() + " offers in "
                + catalogue.categories().size() + " categories (scenario " + catalogue.scenarioId() + ")");
    }

    private void loadUnlocks(Session session, UUID playerId) {
        Integer userId = userIds.apply(playerId);
        if (userId == null) return;
        api.unlockedWeaponTemplates(userId).whenComplete((ids, error) -> {
            if (ids != null) session.unlocked.put(playerId, Set.copyOf(ids));
            else logger.log(Level.FINE, "[Siege shop] Weapon unlocks of user " + userId + " couldn't be read", error);
        });
    }

    @Override
    public void memberJoinedMatch(SiegeLobbyRuntime lobby, SiegeMatch match, Player player) {
        Session session = sessions.get(match.matchToken());
        if (session != null && session.shop.catalogue().entries().stream().anyMatch(e -> e.kind() == ShopEntryKind.SIEGE_WEAPON)) {
            loadUnlocks(session, player.getUniqueId());
        }
    }

    /** The match ended: nothing more is sold (bought items go with the gear restore, P23). */
    @Override
    public void matchEnded(SiegeLobbyRuntime lobby, SiegeMatch match) {
        String token = match.matchToken();
        live.remove(token);
        loading.remove(token);
        Session session = sessions.get(token);
        if (session != null) session.shop.end();
    }

    /** Everyone was released: the match's shop and its counters go (A22). */
    @Override
    public void roundReleased(SiegeLobbyRuntime lobby) {
        lobby.match().ifPresent(m -> {
            sessions.remove(m.matchToken());
            live.remove(m.matchToken());
            loading.remove(m.matchToken());
        });
    }

    @Override
    public void shutdown() {
        sessions.clear();
        loading.clear();
        live.clear();
    }

    // ==================== Buying ====================

    /** Check 1 for PvP: the match still runs, the player is its member, online and alive. */
    private Optional<ShopRefusal> membership(String token, UUID playerId) {
        Optional<SiegeMatch> match = matches.runningMatchOf(playerId);
        if (match.isEmpty() || !match.get().matchToken().equals(token) || !live.contains(token)) {
            return Optional.of(ShopRefusal.notInMatch());
        }
        Player player = players.apply(playerId);
        if (player == null) return Optional.of(ShopRefusal.notInMatch());
        if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) return Optional.of(ShopRefusal.notAlive());
        return Optional.empty();
    }

    private void purchased(SiegeLobbyRuntime lobby, UUID player, ShopPurchaseResult.Bought bought) {
        if (telemetry == null) return;
        try {
            telemetry.purchased(lobby.id(), player, bought);
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "[Siege shop] purchase telemetry failed", e);
        }
    }

    /** The shop of the player's match: another mode's first (Survival), else their PvP match's. */
    public Optional<ShopPurchaseService> shopOf(UUID playerId) {
        for (MatchSource source : matchSources) {
            try {
                Optional<ShopPurchaseService> shop = source.shopOf(playerId);
                if (shop.isPresent()) return shop;
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "[Siege shop] match source failed", e);
            }
        }
        return matches.runningMatchOf(playerId).map(m -> sessions.get(m.matchToken())).map(s -> s.shop);
    }

    /** Whether the player is in a running match whose shop exists or is still opening (the Shop button). */
    public boolean available(UUID playerId) {
        if (shopOf(playerId).isPresent()) return true;
        return matches.runningMatchOf(playerId).map(m -> loading.get(m.matchToken()) == Loading.LOADING).orElse(false);
    }

    /** {@code /siege shop} and the Shop button (A17): the shop menu, or why not. Null when it opened. */
    public Component open(Player player) {
        UUID id = player.getUniqueId();
        if (shopOf(id).isEmpty()) {
            Optional<SiegeMatch> match = matches.runningMatchOf(id);
            if (match.isEmpty()) return SiegeMessages.bad(ShopRefusal.notInMatch().message());
            Loading state = loading.get(match.get().matchToken());
            if (state == Loading.LOADING) return SiegeMessages.bad("The siege shop is still opening; try again in a moment.");
            return SiegeMessages.bad("The siege shop isn't available in this match.");
        }
        if (!menus.openShop(player)) return SiegeMessages.bad("The siege shop menu isn't available right now; please tell an admin.");
        return null;
    }

    /** The {@code siege.shop.buy} action: buys the entry and says how it went. */
    public Component buy(Player player, int entryId) {
        Optional<ShopPurchaseService> shop = shopOf(player.getUniqueId());
        if (shop.isEmpty()) return SiegeMessages.bad(ShopRefusal.notInMatch().message());
        ShopPurchaseResult result;
        try {
            result = shop.get().purchase(player.getUniqueId(), entryId);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "[Siege shop] Purchase of entry " + entryId + " by " + player.getName() + " failed", e);
            return SiegeMessages.bad("That purchase failed; nothing was charged.");
        }
        return message(result);
    }

    /** The player's message for a purchase result; null for a started weapon flow (its handler talks). */
    public static Component message(ShopPurchaseResult result) {
        if (result instanceof ShopPurchaseResult.Bought b) {
            return SiegeMessages.good("Bought " + b.entry().name() + (b.entry().quantity() > 1 ? " ×" + b.entry().quantity() : "")
                    + " for " + b.price() + " siege points. Balance: " + b.balanceAfter() + ".");
        }
        if (result instanceof ShopPurchaseResult.Refused r) return SiegeMessages.bad(r.refusal().message());
        return null; // Started: the handler's own flow talks to the player
    }

    // ==================== Menu views ====================

    public ShopHeaderView header(Player player, Integer categoryId) {
        Optional<ShopPurchaseService> shop = shopOf(player.getUniqueId());
        if (shop.isEmpty()) return new ShopHeaderView(0, null, 0);
        String name = categoryId == null ? null : shop.get().catalogue().category(categoryId).map(ShopCategory::name).orElse(null);
        return new ShopHeaderView(shop.get().balance(player.getUniqueId()), name, shop.get().catalogue().categories().size());
    }

    public List<ShopCategoryView> categories(Player player) {
        return shopOf(player.getUniqueId())
                .map(s -> s.catalogue().categories().stream().map(ShopCategoryView::new).toList())
                .orElse(List.of());
    }

    public List<ShopEntryView> entries(Player player, Integer categoryId) {
        UUID id = player.getUniqueId();
        Optional<ShopPurchaseService> found = shopOf(id);
        if (found.isEmpty() || categoryId == null) return List.of();
        ShopPurchaseService shop = found.get();
        long balance = shop.balance(id);
        Map<Integer, String> icons = iconsOf(id);
        return shop.catalogue().category(categoryId)
                .map(c -> c.entries().stream()
                        .map(e -> new ShopEntryView(e, shop.state(id, e), balance,
                                e.iconMaterial() != null ? e.iconMaterial() : icons.get(e.id()),
                                shop.blocked(id, e).orElse(null)))
                        .toList())
                .orElse(List.of());
    }

    /** The material of each item entry's built item, for entries without their own icon. */
    private Map<Integer, String> iconsOf(UUID playerId) {
        Session session = matches.runningMatchOf(playerId).map(m -> sessions.get(m.matchToken())).orElse(null);
        return session == null ? Map.of() : session.icons;
    }
}
