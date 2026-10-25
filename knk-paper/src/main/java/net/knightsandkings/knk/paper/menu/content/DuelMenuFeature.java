package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaArea;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.statistics.PlayerStatistics;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.duel.view.ArenaMenuView;
import net.knightsandkings.knk.core.duel.view.DuelChallengeRowView;
import net.knightsandkings.knk.core.duel.view.DuelHudView;
import net.knightsandkings.knk.core.duel.view.DuelMatchDetailView;
import net.knightsandkings.knk.core.duel.view.DuelMatchRowView;
import net.knightsandkings.knk.core.duel.view.DuelMatchSnapshot;
import net.knightsandkings.knk.core.duel.view.DuelMenuIds;
import net.knightsandkings.knk.core.duel.view.DuelServerView;
import net.knightsandkings.knk.core.duel.view.DuelSideCardView;
import net.knightsandkings.knk.core.duel.view.DuelStakeRowView;
import net.knightsandkings.knk.core.menu.ConditionOutcome;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.ports.api.StatisticsApi;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuFeature;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import net.knightsandkings.knk.paper.menu.MenuItemStackRow;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Arena duels menus (DESIGN §9.2–§9.3, plan Phase 6): {@code duel_matches} ({@code /duels}),
 * {@code duel_match_detail}, {@code arena_main} ({@code /arena}) and its {@code duel_challenge} picker, seeded by
 * knk-web-api's {@code MenuTemplateSeed.Duel.cs}. Registers
 * <ul>
 *   <li>roots {@code duelMatch} ({@link DuelMatchDetailView} of {@code ctx.token}), {@code arena}
 *       ({@link ArenaMenuView} of {@code ctx.arena}: its id; empty = the arena the viewer stands in, else the
 *       first arena) and {@code duels} ({@link DuelServerView});</li>
 *   <li>row sources {@code duel.matches} (two heads per live match, {@code arenaId} narrows it), {@code duel.match-side}
 *       (a duelist's card with their statistics as the API shows them to the viewer), {@code duel.stakes} (the kit,
 *       coins and the real stake stacks) and {@code duel.challenge-candidates};</li>
 *   <li>actions {@code duel.spectate} - closes the menu and starts the <b>spectate teleport</b> (rev. 6: an engine
 *       teleport of kind {@code ARENA} to the spectator area's arrival, after its own title and gem conditions,
 *       {@code DuelSpectateTeleports}); {@code duel.challenge}
 *       ({@code /duel <name>}, P5's proposal editor) and {@code duel.claim} ({@code /duel claim});</li>
 *   <li>conditions {@code duel.matches-empty} and {@code duel.in-arena}.</li>
 * </ul>
 * The duel runtime and the teleport engine start after the menu registries are locked, so the feature reads them
 * through a {@link Supplier}; while that supplies null the menus show "Duels aren't running".
 */
public final class DuelMenuFeature implements MenuFeature {

    static final String UNAVAILABLE = "Duels aren't running right now.";
    static final long STATS_FRESH_MILLIS = 10_000L;
    static final int MAX_REMEMBERED = 256;

    private static final Logger LOGGER = Logger.getLogger(DuelMenuFeature.class.getName());

    /** What the duel menus read from the runtime (an adapter over {@code DuelService}). Main thread. */
    public interface Data {
        /** Every running match, oldest first. */
        List<DuelMatchSnapshot> matches();

        /** The side's stake stacks as taken at lock-in, in the contract's item order. */
        List<ItemStack> stakeStacks(UUID token, DuelSide side);

        /** The arena's queue and battleground use. */
        DuelHudView.QueueInfo queue(KnkArena arena);

        /** Deliveries waiting for {@code /duel claim}. */
        int pendingClaims(UUID playerId);

        /** {@code /duel claim}: hands them over; the message for the player. */
        String claim(Player player);

        /** Why {@code playerId} can't be challenged right now ("in a duel"), or empty. */
        Optional<String> busy(UUID playerId);
    }

    /** Rev. 6: the spectate teleport ({@code DuelSpectateTeleports.spectate}) to an arena's spectator area. */
    @FunctionalInterface
    public interface SpectatorTeleport {
        void spectate(Player player, KnkArena arena, KnkArenaArea area);
    }

    /** Where the viewer is: the arena they stand in (or watch from), and whether they are inside its region. */
    public interface Places {
        Optional<KnkArena> arenaAt(Player player);

        boolean isInside(Player player, KnkArena arena);
    }

    /** Everything the menus use; any part may be null while it isn't running. */
    public record Parts(
            Data data,
            Supplier<List<KnkArena>> arenas,
            Places places,
            Supplier<SpectatorTeleport> spectate,
            StatisticsApi statistics,
            java.util.function.Function<UUID, Integer> userIds,
            Supplier<Collection<? extends Player>> online
    ) {
    }

    /** A viewer's read of a duelist's statistics. */
    private record Loaded(long atMillis, PlayerStatistics statistics) {
    }

    private final Supplier<Parts> parts;
    private final Clock clock;
    private final Map<String, Loaded> statistics = new ConcurrentHashMap<>();

    public DuelMenuFeature(Supplier<Parts> parts, Clock clock) {
        this.parts = parts;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Override
    public void registerMenuHandlers(MenuFeatureRegistries registries) {
        var variables = registries.variables();
        variables.register(DuelMenuIds.ROOT_MATCH, DuelMatchDetailView.class, (player, ctx) ->
                DuelMatchDetailView.of(match(tokenOf(ctx, null)).orElse(null)));
        variables.register(DuelMenuIds.ROOT_ARENA, ArenaMenuView.class, (player, ctx) -> arenaView(player, ctx));
        variables.register(DuelMenuIds.ROOT_SERVER, DuelServerView.class, (player, ctx) -> serverView());

        var sources = registries.contentSources();
        sources.registerRows(DuelMenuIds.SOURCE_MATCHES, DuelMatchRowView.class, (context, params, query) ->
                page(DuelMatchRowView.rows(listed(arenaFilter(context.player(), context.menuContext(), params)))));
        sources.registerRows(DuelMenuIds.SOURCE_MATCH_SIDE, DuelSideCardView.class, (context, params, query) ->
                sideCard(context.player(), tokenOf(context.menuContext(), params), sideOf(params)));
        sources.registerRows(DuelMenuIds.SOURCE_STAKES, DuelStakeRowView.class, (context, params, query) ->
                page(stakeRows(tokenOf(context.menuContext(), params), sideOf(params))));
        sources.registerRows(DuelMenuIds.SOURCE_CHALLENGE, DuelChallengeRowView.class, (context, params, query) ->
                page(challengeRows(context.player())));

        var actions = registries.actions();
        actions.register(DuelMenuIds.ACTION_SPECTATE, this::spectateAction);
        actions.register(DuelMenuIds.ACTION_CHALLENGE, this::challenge);
        actions.register(DuelMenuIds.ACTION_CLAIM, this::claim);

        var conditions = registries.conditions();
        conditions.register(DuelMenuIds.CONDITION_MATCHES_EMPTY, (context, params) ->
                listed(arenaFilter(context.player(), context.menuContext(), params)).isEmpty()
                        ? ConditionOutcome.allow() : ConditionOutcome.deny());
        conditions.register(DuelMenuIds.CONDITION_IN_ARENA, (context, params) -> {
            Parts p = parts();
            Optional<KnkArena> arena = arenaOf(context.player(), context.menuContext(), params);
            if (p == null || p.places() == null || arena.isEmpty()) return ConditionOutcome.deny(UNAVAILABLE);
            if (!arena.get().duelEnabled()) return ConditionOutcome.deny("Duels are closed in this arena right now.");
            return p.places().isInside(context.player(), arena.get()) ? ConditionOutcome.allow()
                    : ConditionOutcome.deny("Stand inside the arena to challenge a player.");
        });
    }

    // ===== spectating =====

    /**
     * Starts {@code arenaId}'s spectate teleport for {@code player} ({@code [Spectate]}, the menus' buttons and
     * {@code /duels spectate}): to the first enabled spectator area with an arrival location, after its title and gem
     * conditions (rev. 6). Main thread.
     */
    public void spectate(Player player, int arenaId) {
        Parts p = parts();
        if (p == null || p.arenas() == null) {
            player.sendMessage(ChatColor.RED + UNAVAILABLE);
            return;
        }
        KnkArena arena = arenas(p).stream().filter(a -> a.id() == arenaId).findFirst().orElse(null);
        if (arena == null) {
            player.sendMessage(ChatColor.RED + "That arena doesn't exist any more.");
            return;
        }
        KnkArenaArea area = ArenaMenuView.spectateTarget(arena);
        if (area == null) {
            player.sendMessage(ChatColor.YELLOW + (arena.spectatorAreas().isEmpty()
                    ? ArenaMenuView.displayName(arena) + " has no spectator area."
                    : "Walk to " + arena.spectatorAreas().getFirst().displayName() + " to watch; it has no teleport arrival."));
            return;
        }
        SpectatorTeleport teleport = p.spectate() != null ? p.spectate().get() : null;
        if (teleport == null) {
            player.sendMessage(ChatColor.RED + "Teleports aren't available right now.");
            return;
        }
        teleport.spectate(player, arena, area);
    }

    private void spectateAction(MenuActionContext context, Map<String, String> params) {
        Player player = context.player();
        Optional<KnkArena> arena = arenaOf(player, context.menuContext(), params)
                .or(() -> match(tokenOf(context.menuContext(), params)).flatMap(m -> arenaById(m.arenaId())));
        player.closeInventory();
        if (arena.isEmpty()) {
            player.sendMessage(ChatColor.RED + UNAVAILABLE);
            return;
        }
        spectate(player, arena.get().id());
    }

    // ===== challenge and claims =====

    private void challenge(MenuActionContext context, Map<String, String> params) {
        Player player = context.player();
        String name = params.get("name");
        player.closeInventory();
        if (name == null || name.isBlank() || !name.matches("[A-Za-z0-9_]{1,16}")) {
            player.sendMessage(ChatColor.RED + "Pick a player to challenge.");
            return;
        }
        // P5's /duel <player>: the invite rules (inside an enabled arena, invites on, ...) and the proposal editor.
        player.performCommand("duel " + name);
    }

    private void claim(MenuActionContext context, Map<String, String> params) {
        Parts p = parts();
        Player player = context.player();
        if (p == null || p.data() == null) {
            player.sendMessage(ChatColor.RED + UNAVAILABLE);
            return;
        }
        String message = p.data().claim(player);
        if (message != null && !message.isBlank()) player.sendMessage(message);
        if (context.menuService() != null) context.menuService().refreshOpenMenu(player);
    }

    // ===== roots =====

    DuelServerView serverView() {
        Parts p = parts();
        if (p == null || p.data() == null) return DuelServerView.off();
        return DuelServerView.of(listed(null).size(), arenas(p).size());
    }

    ArenaMenuView arenaView(Player player, MenuContextParams ctx) {
        Parts p = parts();
        KnkArena arena = arenaOf(player, ctx, Map.of()).orElse(null);
        if (p == null || p.data() == null || arena == null) return ArenaMenuView.of(arena, null, 0, 0, false);
        boolean inside = p.places() != null && p.places().isInside(player, arena);
        return ArenaMenuView.of(arena, p.data().queue(arena), listed(arena.id()).size(),
                p.data().pendingClaims(player.getUniqueId()), inside);
    }

    // ===== rows =====

    /** The listed matches, of one arena or (null) of all. */
    List<DuelMatchSnapshot> listed(Integer arenaId) {
        Parts p = parts();
        if (p == null || p.data() == null) return List.of();
        return p.data().matches().stream().filter(DuelMatchSnapshot::isListed)
                .filter(m -> arenaId == null || m.arenaId() == arenaId).toList();
    }

    List<DuelStakeRowView> stakeRows(UUID token, DuelSide side) {
        Parts p = parts();
        Optional<DuelMatchSnapshot> match = match(token);
        if (p == null || match.isEmpty() || side == null) return List.of();
        List<ItemStack> stacks = p.data().stakeStacks(token, side);
        List<DuelStakeRowView> rows = new ArrayList<>();
        int item = 0;
        for (DuelStakeRowView row : DuelStakeRowView.rows(match.get(), side)) {
            if (row.kind() == DuelStakeRowView.Kind.ITEM) {
                ItemStack stack = item < stacks.size() ? stacks.get(item) : null;
                item++;
                rows.add(stack != null ? new StakeStackRow(row, stack) : row);
            } else {
                rows.add(row);
            }
        }
        return rows;
    }

    /** One card: completes once the viewer's read of the duelist's statistics is in (cached a few seconds). */
    CompletableFuture<Page<DuelSideCardView>> sideCard(Player viewer, UUID token, DuelSide side) {
        Parts p = parts();
        Optional<DuelMatchSnapshot> match = match(token);
        if (p == null || match.isEmpty() || side == null) return CompletableFuture.completedFuture(pageOf(List.of()));
        DuelMatchSnapshot m = match.get();
        int target = m.side(side).userId();
        Integer viewerId = p.userIds() != null ? p.userIds().apply(viewer.getUniqueId()) : null;
        String key = (viewerId == null ? "-" : viewerId) + ">" + target;
        Loaded cached = statistics.get(key);
        long now = clock.millis();
        if (cached != null && now - cached.atMillis() < STATS_FRESH_MILLIS) {
            return CompletableFuture.completedFuture(pageOf(List.of(DuelSideCardView.of(m, side, cached.statistics()))));
        }
        if (p.statistics() == null) {
            return CompletableFuture.completedFuture(pageOf(List.of(DuelSideCardView.of(m, side, null))));
        }
        CompletableFuture<PlayerStatistics> read;
        try {
            read = p.statistics().getUserStatistics(target, viewerId, "lifetime", null);
        } catch (RuntimeException e) {
            read = CompletableFuture.failedFuture(e);
        }
        return read.handle((stats, error) -> {
            if (error != null) {
                LOGGER.log(Level.FINE, "duel.match-side: statistics of user " + target + " failed", error);
            }
            PlayerStatistics value = error == null ? stats : null;
            if (value != null) {
                long at = clock.millis();
                if (statistics.size() >= MAX_REMEMBERED) statistics.values().removeIf(l -> at - l.atMillis() >= STATS_FRESH_MILLIS);
                statistics.put(key, new Loaded(at, value));
            }
            return pageOf(List.of(DuelSideCardView.of(m, side, value)));
        });
    }

    List<DuelChallengeRowView> challengeRows(Player viewer) {
        Parts p = parts();
        if (p == null || p.online() == null || p.data() == null) return List.of(DuelChallengeRowView.none());
        List<DuelChallengeRowView> rows = new ArrayList<>();
        p.online().get().stream()
                .filter(other -> !other.getUniqueId().equals(viewer.getUniqueId()))
                .sorted(Comparator.comparing(other -> other.getName().toLowerCase(Locale.ROOT)))
                .forEach(other -> rows.add(DuelChallengeRowView.of(other.getName(),
                        p.data().busy(other.getUniqueId()).orElse(null))));
        return rows.isEmpty() ? List.of(DuelChallengeRowView.none()) : rows;
    }

    // ===== lookups =====

    private Parts parts() {
        return parts != null ? parts.get() : null;
    }

    private static List<KnkArena> arenas(Parts p) {
        List<KnkArena> list = p.arenas() != null ? p.arenas().get() : null;
        return list == null ? List.of() : list;
    }

    Optional<DuelMatchSnapshot> match(UUID token) {
        Parts p = parts();
        if (token == null || p == null || p.data() == null) return Optional.empty();
        return p.data().matches().stream().filter(m -> m.token().equals(token)).findFirst();
    }

    private Optional<KnkArena> arenaById(int id) {
        Parts p = parts();
        return p == null ? Optional.empty() : arenas(p).stream().filter(a -> a.id() == id).findFirst();
    }

    /**
     * The arena a menu is about: {@code params.arenaId}, else {@code ctx.arena} (its id), else the one the viewer
     * stands in or watches from, else the first arena.
     */
    Optional<KnkArena> arenaOf(Player player, MenuContextParams ctx, Map<String, String> params) {
        Parts p = parts();
        if (p == null) return Optional.empty();
        Integer id = positive(params.get("arenaId"));
        if (id == null && ctx != null) id = positive(ctx.get(DuelMenuIds.CTX_ARENA));
        if (id != null) return arenaById(id);
        if (p.places() != null && player != null) {
            Optional<KnkArena> here = p.places().arenaAt(player);
            if (here.isPresent()) return here;
        }
        return arenas(p).stream().findFirst();
    }

    /** {@code duel.matches} / {@code duel.matches-empty}: every arena unless an arena is named. */
    private Integer arenaFilter(Player player, MenuContextParams ctx, Map<String, String> params) {
        boolean named = params.containsKey("arenaId");
        if (!named) return null;
        return arenaOf(player, ctx, params).map(KnkArena::id).orElse(-1);
    }

    static UUID tokenOf(MenuContextParams ctx, Map<String, String> params) {
        String raw = params != null ? params.get("token") : null;
        if ((raw == null || raw.isBlank()) && ctx != null) raw = ctx.get(DuelMenuIds.CTX_TOKEN);
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static DuelSide sideOf(Map<String, String> params) {
        String raw = params.get("side");
        if (raw == null) return null;
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case DuelMenuIds.SIDE_CHALLENGER -> DuelSide.CHALLENGER;
            case DuelMenuIds.SIDE_OPPONENT -> DuelSide.OPPONENT;
            default -> null;
        };
    }

    private static Integer positive(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            int id = Integer.parseInt(raw.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static <T> CompletableFuture<Page<T>> page(List<T> rows) {
        return CompletableFuture.completedFuture(pageOf(rows));
    }

    private static <T> Page<T> pageOf(List<T> rows) {
        return new Page<>(rows, rows.size(), 1, Math.max(1, rows.size()));
    }

    /** A stake item shown as the very stack that was staked (icon, name, enchantments; menu follow-up 2026-09-26). */
    static final class StakeStackRow extends DuelStakeRowView implements MenuItemStackRow {
        private final ItemStack stack;

        StakeStackRow(DuelStakeRowView row, ItemStack stack) {
            super(row);
            this.stack = stack;
        }

        @Override
        public ItemStack menuItemStack() {
            return stack;
        }
    }
}
