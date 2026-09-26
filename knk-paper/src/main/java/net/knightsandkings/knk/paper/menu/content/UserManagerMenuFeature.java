package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.PermissionGroupsDataAccess;
import net.knightsandkings.knk.core.dataaccess.TitleBracketsDataAccess;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.permissions.PermissionGroupSummary;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.GroupMembershipSummary;
import net.knightsandkings.knk.core.domain.users.TitleBracket;
import net.knightsandkings.knk.core.domain.users.UserListItem;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.menu.ConditionOutcome;
import net.knightsandkings.knk.core.menu.MenuActionException;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.menu.MenuSession;
import net.knightsandkings.knk.core.ports.api.UsersQueryApi;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuContentSourceContext;
import net.knightsandkings.knk.paper.menu.MenuFeature;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import net.knightsandkings.knk.paper.user.UserAdminService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Content port CP8 ({@code docs/specs/inventory-menu/CONTENT_PORT_PLAN.md} §10): the in-game Player
 * manager ({@code users.manager}, {@code .edit}, {@code .titles}, {@code .groups}) - v1's
 * Online-players Manager + Edit statistics, as a front end over {@link UserAdminService} (the same
 * methods {@code /knk user} and {@code /freeze} call).
 * <p>
 * <b>Reads</b> (row sources do the I/O; roots and conditions only read what the last fetch left
 * behind, because they run on the main thread and must not block):
 * <ul>
 *   <li>{@code users.online} → {@link OnlinePlayerRow}: online players the viewer outranks
 *       ({@code RankHierarchy}), from the user cache; one disabled row when there is nobody. With
 *       {@code knk.admin.user.manage.all} (owner, menu follow-up 2026-09-26): every account,
 *       online or offline, the viewer included, paged + searchable ({@link #fetchEveryone});</li>
 *   <li>{@code users.target {userId, name}} → {@link TargetUserView} (one row, the head): a fresh
 *       read of the target plus whether the viewer outranks them - cached per (viewer, target) for
 *       the root, the condition and the actions;</li>
 *   <li>{@code users.titles {userId, name}} → {@link TitleRow} as seen by the target;
 *       {@code users.groups {userId, name}} → {@link GroupRow} (every group, target's HIGHLIGHT);</li>
 *   <li>roots {@code target} → {@link TargetUserView} for {@code $ctx.userId$}, {@code usersManager}
 *       → online count.</li>
 * </ul>
 * <b>Conditions</b>: {@code users.outranks-target {userId}} (Click; "You can only manage players
 * ranked below you"; always allows {@code knk.admin.user.manage.all}) and {@code users.pending} (a pending confirmation is a {@code users.*} action,
 * so another menu's abandoned confirmation never shows here).
 * <p>
 * <b>Actions</b> (each re-checks its permission node, then calls the service, then repaints):
 * {@code users.adjust {userId, field: coins|gems|xp, delta}}, {@code users.set-title {userId,
 * bracketId}}, {@code users.group {userId, groupId, op: add|remove}}, {@code users.mode {userId,
 * mode}}, {@code users.salary-payout {userId}}, {@code users.freeze {userId, op: freeze|unfreeze}},
 * {@code users.kick {userId}} / {@code users.ban {userId}} (Paper's own commands, run as the viewer).
 */
public final class UserManagerMenuFeature implements MenuFeature {

    public static final String MANAGE_NODE = "knk.admin.user.manage";
    public static final String FREEZE_REASON = "Frozen by a member of staff";

    private static final Logger LOGGER = Logger.getLogger(UserManagerMenuFeature.class.getName());
    private static final int MAX_CACHED = 256;
    /** {@code users.group} op a premium-tier click confirms into: replace the target's tier. */
    static final String SET_TIER_OP = "set-tier";

    private record TargetState(UserSummary user, Boolean outranks) {
    }

    private final UserAdminService admin;
    private final UsersQueryApi usersQueryApi;
    private final UserCache userCache;
    private final TitleBracketsDataAccess titleBrackets;
    private final PermissionGroupsDataAccess groups;
    private final Supplier<Collection<? extends Player>> onlinePlayers;
    private final Map<String, TargetState> targets = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TargetState> eldest) {
            return size() > MAX_CACHED;
        }
    });

    public UserManagerMenuFeature(UserAdminService admin, UsersQueryApi usersQueryApi, UserCache userCache,
                                  TitleBracketsDataAccess titleBrackets, PermissionGroupsDataAccess groups,
                                  Supplier<Collection<? extends Player>> onlinePlayers) {
        this.admin = admin;
        this.usersQueryApi = usersQueryApi;
        this.userCache = userCache;
        this.titleBrackets = titleBrackets;
        this.groups = groups;
        this.onlinePlayers = onlinePlayers;
    }

    @Override
    public void registerMenuHandlers(MenuFeatureRegistries registries) {
        registries.contentSources().registerRows("users.online", OnlinePlayerRow.class, (context, params, query) -> fetchOnline(context, query));
        registries.contentSources().registerRows("users.target", TargetUserView.class,
                (context, params, query) -> fetchTarget(context, params).thenApply(view -> page(List.of(view))));
        registries.contentSources().registerRows("users.titles", TitleRow.class, (context, params, query) -> fetchTitles(context, params));
        registries.contentSources().registerRows("users.groups", GroupRow.class, (context, params, query) -> fetchGroups(context, params));

        registries.variables().register("target", TargetUserView.class, (player, ctx) -> targetView(player, ctx));
        registries.variables().register("usersManager", UsersManagerView.class, (player, ctx) -> new UsersManagerView(onlinePlayers.get().size()));

        registries.conditions().register("users.outranks-target", (context, params) -> outranksTarget(context, params));
        registries.conditions().register("users.pending", (context, params) -> pending(context));

        registries.actions().register("users.adjust", this::adjust);
        registries.actions().register("users.set-title", this::setTitle);
        registries.actions().register("users.group", this::group);
        registries.actions().register("users.mode", this::mode);
        registries.actions().register("users.salary-payout", this::salaryPayout);
        registries.actions().register("users.freeze", this::freeze);
        registries.actions().register("users.kick", (context, params) -> kickOrBan(context, params, true));
        registries.actions().register("users.ban", (context, params) -> kickOrBan(context, params, false));
    }

    // ===== reads =====

    CompletableFuture<Page<OnlinePlayerRow>> fetchOnline(MenuContentSourceContext context, PagedQuery query) {
        Player viewer = context.player();
        if (viewer != null && viewer.hasPermission(MANAGE_NODE) && viewer.hasPermission(UserAdminService.MANAGE_ALL_NODE)) {
            return fetchEveryone(viewer, query);
        }
        Integer viewerId = viewer != null ? viewerId(viewer) : null;
        if (viewer == null || viewerId == null || !viewer.hasPermission(MANAGE_NODE)) {
            return CompletableFuture.completedFuture(page(List.of(OnlinePlayerRow.none())));
        }
        String search = query != null && query.searchTerm() != null ? query.searchTerm().trim().toLowerCase(Locale.ROOT) : "";
        List<UserSummary> candidates = new ArrayList<>();
        for (Player online : onlinePlayers.get()) {
            if (online.getUniqueId().equals(viewer.getUniqueId())) {
                continue;
            }
            userCache.getStale(online.getUniqueId())
                    .filter(user -> user.id() != null)
                    .filter(user -> search.isEmpty() || user.username().toLowerCase(Locale.ROOT).contains(search))
                    .ifPresent(candidates::add);
        }
        Map<UserSummary, CompletableFuture<Boolean>> checks = new LinkedHashMap<>();
        for (UserSummary candidate : candidates) {
            checks.put(candidate, admin.outranks(viewerId, candidate.id()).exceptionally(ex -> false));
        }
        return CompletableFuture.allOf(checks.values().toArray(new CompletableFuture[0])).thenApply(done -> {
            List<OnlinePlayerRow> rows = checks.entrySet().stream()
                    .filter(entry -> Boolean.TRUE.equals(entry.getValue().join()))
                    .map(Map.Entry::getKey)
                    .sorted(Comparator.comparing(user -> user.username().toLowerCase(Locale.ROOT)))
                    .map(OnlinePlayerRow::of)
                    .collect(Collectors.toCollection(ArrayList::new));
            if (rows.isEmpty()) {
                rows.add(OnlinePlayerRow.none());
            }
            return page(rows);
        });
    }

    /**
     * Menu follow-up 2026-09-26 ({@code knk.admin.user.manage.all}): every account, online or not,
     * the viewer included - one page of {@code UsersQueryApi.search} (the section's search term and
     * page), sorted by username, online players marked from the server's player list.
     */
    CompletableFuture<Page<OnlinePlayerRow>> fetchEveryone(Player viewer, PagedQuery query) {
        PagedQuery base = query != null ? query : new PagedQuery(1, 36, null, null, false, Map.of());
        PagedQuery byName = new PagedQuery(base.pageNumber(), base.pageSize(), base.searchTerm(), "username", false, base.filters());
        Set<UUID> online = onlinePlayers.get().stream().map(Player::getUniqueId).collect(Collectors.toSet());
        return usersQueryApi.search(byName).thenApply(result -> {
            List<OnlinePlayerRow> rows = new ArrayList<>();
            if (result != null && result.items() != null) {
                for (UserListItem item : result.items()) {
                    if (item == null || item.id() == null) {
                        continue;
                    }
                    UserSummary cached = item.uuid() != null ? userCache.getStale(item.uuid()).orElse(null) : null;
                    boolean isOnline = item.uuid() != null && online.contains(item.uuid());
                    rows.add(OnlinePlayerRow.of(item, cached, isOnline, viewer.getUniqueId().equals(item.uuid())));
                }
            }
            if (rows.isEmpty()) {
                return page(List.of(OnlinePlayerRow.none()));
            }
            int total = result != null ? result.totalCount() : rows.size();
            return new Page<>(rows, total, byName.pageNumber(), byName.pageSize());
        }).exceptionally(ex -> {
            LOGGER.log(Level.WARNING, "users.online: player search failed", ex);
            return page(List.of(OnlinePlayerRow.none()));
        });
    }

    /** Fresh read of the target (by name, like /knk user) + the viewer's rank over them; cached for the root/condition/actions. */
    CompletableFuture<TargetUserView> fetchTarget(MenuContentSourceContext context, Map<String, String> params) {
        Player viewer = context.player();
        Integer userId = parseId(params.get("userId"));
        String name = params.get("name");
        if (viewer == null || userId == null || name == null || name.isBlank() || !viewer.hasPermission(MANAGE_NODE)) {
            return CompletableFuture.completedFuture(TargetUserView.unknown(name));
        }
        Integer viewerId = viewerId(viewer);
        return usersQueryApi.getByUsername(name.trim())
                .exceptionally(ex -> {
                    LOGGER.log(Level.FINE, "users.target: fresh read of " + name + " failed", ex);
                    return null;
                })
                .thenCompose(user -> {
                    if (user == null || !Objects.equals(user.id(), userId)) {
                        return CompletableFuture.completedFuture(TargetUserView.unknown(name));
                    }
                    CompletableFuture<Boolean> outranks = viewer.hasPermission(UserAdminService.MANAGE_ALL_NODE)
                            ? CompletableFuture.completedFuture(true)
                            : viewerId != null
                            ? admin.outranks(viewerId, userId).exceptionally(ex -> null)
                            : CompletableFuture.completedFuture((Boolean) null);
                    return outranks.thenApply(result -> {
                        targets.put(key(viewer.getUniqueId(), userId), new TargetState(user, result));
                        return new TargetUserView(user);
                    });
                });
    }

    CompletableFuture<Page<TitleRow>> fetchTitles(MenuContentSourceContext context, Map<String, String> params) {
        return titleBrackets.listAsync().thenCombine(fetchTarget(context, params), (brackets, target) -> {
            UserSummary user = target.user();
            if (user == null) {
                return page(TitleRow.rows(brackets, null, null, 0));
            }
            TitleProgress progress = TitleProgress.of(brackets, user.titleBracketId(), user.experiencePoints());
            return page(TitleRow.rows(brackets, progress, user.gender(), user.experiencePoints()));
        }).exceptionally(ex -> {
            LOGGER.log(Level.WARNING, "users.titles: failed to load", ex);
            return page(List.of());
        });
    }

    CompletableFuture<Page<GroupRow>> fetchGroups(MenuContentSourceContext context, Map<String, String> params) {
        Integer userId = parseId(params.get("userId"));
        CompletableFuture<Set<Integer>> memberships = userId == null
                ? CompletableFuture.completedFuture(Set.of())
                : usersQueryApi.getGroupMemberships(userId).thenApply(list -> list.stream()
                        .filter(GroupMembershipSummary::isActive)
                        .map(GroupMembershipSummary::groupId)
                        .collect(Collectors.toSet()));
        return fetchTarget(context, params)
                .thenCombine(groups.listAsync(), (target, all) -> all)
                .thenCombine(memberships, (all, memberIds) -> page(GroupRow.rows(all, memberIds)))
                .exceptionally(ex -> {
                    LOGGER.log(Level.WARNING, "users.groups: failed to load", ex);
                    return page(List.of());
                });
    }

    /** Main thread, no I/O: the target as the last {@code users.*} fetch left it. */
    TargetUserView targetView(Player viewer, MenuContextParams ctx) {
        Integer userId = parseId(ctx.get("userId"));
        TargetState state = viewer != null && userId != null ? targets.get(key(viewer.getUniqueId(), userId)) : null;
        return state != null ? new TargetUserView(state.user()) : TargetUserView.unknown(ctx.get("name"));
    }

    // ===== conditions =====

    ConditionOutcome outranksTarget(MenuActionContext context, Map<String, String> params) {
        if (context.player() != null && context.player().hasPermission(UserAdminService.MANAGE_ALL_NODE)) {
            return ConditionOutcome.allow();
        }
        Integer userId = parseId(params.get("userId"));
        TargetState state = context.player() != null && userId != null
                ? targets.get(key(context.player().getUniqueId(), userId)) : null;
        if (state == null || state.outranks() == null) {
            return ConditionOutcome.deny("Still checking your rank - try again in a moment.");
        }
        return state.outranks() ? ConditionOutcome.allow() : ConditionOutcome.deny("You can only manage players ranked below you.");
    }

    private static ConditionOutcome pending(MenuActionContext context) {
        MenuSession session = context.session();
        boolean pending = session != null && session.getPendingConfirmation()
                .map(p -> p.actionTypeId() != null && p.actionTypeId().startsWith("users."))
                .orElse(false);
        return pending ? ConditionOutcome.allow() : ConditionOutcome.deny("Nothing to confirm.");
    }

    // ===== actions =====

    private void adjust(MenuActionContext context, Map<String, String> params) {
        String field = require(params, "field", "users.adjust").toLowerCase(Locale.ROOT);
        if (!Set.of("coins", "gems", "xp").contains(field)) {
            throw new MenuActionException("users.adjust field must be coins, gems or xp, got '" + field + "'");
        }
        int delta = parseDelta(context.player(), params.get("delta"));
        if (delta == 0) {
            return;
        }
        Player player = context.player();
        if (!admin.requireProperty(player, field)) {
            return;
        }
        withTarget(context, params, target -> admin.adjustBalance(player, target, field, delta,
                "Player manager (" + player.getName() + ")"));
    }

    private void setTitle(MenuActionContext context, Map<String, String> params) {
        Player player = context.player();
        Integer bracketId = parseId(params.get("bracketId"));
        TitleBracket bracket = titleBrackets.cachedOrEmpty().stream()
                .filter(b -> bracketId != null && b.id() == bracketId)
                .findFirst().orElse(null);
        if (bracket == null) {
            player.sendMessage(ChatColor.RED + "That title is not available - reopen the menu.");
            return;
        }
        if (!admin.requireProperty(player, "xp")) {
            return;
        }
        withTarget(context, params, target -> admin.setTitle(player, target, bracket));
    }

    private void group(MenuActionContext context, Map<String, String> params) {
        Player player = context.player();
        Integer groupId = parseId(params.get("groupId"));
        String op = require(params, "op", "users.group").toLowerCase(Locale.ROOT);
        PermissionGroupSummary group = groups.cachedOrEmpty().stream()
                .filter(g -> groupId != null && g.id() == groupId)
                .findFirst().orElse(null);
        if (group == null || !Set.of("add", "remove", SET_TIER_OP).contains(op)) {
            player.sendMessage(ChatColor.RED + "That group is not available - reopen the menu.");
            return;
        }
        if (!admin.requireProperty(player, "group")) {
            return;
        }
        if (group.isPremiumTier() && op.equals("add")) {
            // A player holds one premium tier here: adding one is a switch, confirmed first.
            requestTierSwitch(context, params, group);
            return;
        }
        if (op.equals(SET_TIER_OP)) {
            if (!group.isPremiumTier()) {
                player.sendMessage(ChatColor.RED + group.name() + " is not a premium tier - reopen the menu.");
                return;
            }
            withTarget(context, params, target -> switchTier(player, target, group));
            return;
        }
        withTarget(context, params, target -> admin.changeGroup(player, target, group, op.equals("add"), null));
    }

    /**
     * Clicking a premium tier the target doesn't hold: stores a {@code users.group set-tier}
     * confirmation (shown by the header's Confirm/Cancel, condition {@code users.pending}) and
     * repaints so those buttons appear right away. The seed binds the row to a plain "add"; doing
     * the switch here keeps existing menu templates working without a re-seed.
     */
    private void requestTierSwitch(MenuActionContext context, Map<String, String> params, PermissionGroupSummary tier) {
        Player player = context.player();
        Integer userId = parseId(params.get("userId"));
        TargetState state = userId != null ? targets.get(key(player.getUniqueId(), userId)) : null;
        if (state == null || state.user() == null) {
            player.sendMessage(ChatColor.RED + "That player isn't loaded - reopen the menu.");
            return;
        }
        if (context.session() == null) {
            throw new MenuActionException("users.group needs a menu session to confirm a premium tier switch");
        }
        UserSummary target = state.user();
        String current = target.premiumTierName();
        String prompt = "Set " + target.username() + "'s premium tier to " + tier.name()
                + (current != null && !current.equalsIgnoreCase(tier.name()) ? " (replaces " + current + ")" : "")
                + "? Click Confirm or Cancel.";
        context.session().setPendingConfirmation(new MenuSession.PendingConfirmation("users.group",
                Map.of("userId", String.valueOf(userId), "groupId", String.valueOf(tier.id()), "op", SET_TIER_OP), prompt));
        player.sendMessage(ChatColor.YELLOW + prompt);
        if (context.menuService() != null) {
            context.menuService().refreshOpenMenu(player);
        }
    }

    /** Confirmed switch: {@code tier} replaces every other active premium membership the target holds. */
    private CompletableFuture<Boolean> switchTier(Player player, UserSummary target, PermissionGroupSummary tier) {
        return usersQueryApi.getGroupMemberships(target.id())
                .thenCompose(memberships -> admin.setPremiumTier(player, target, tier, memberships.stream()
                        .filter(m -> m.isActive() && m.isPremiumTier() && m.groupId() != tier.id())
                        .map(m -> new PermissionGroupSummary(m.groupId(), m.groupName(), m.weight(), true))
                        .toList()))
                .exceptionally(ex -> {
                    LOGGER.log(Level.WARNING, "users.group set-tier: failed to load memberships", ex);
                    player.sendMessage(ChatColor.RED + "Failed to load " + target.username() + "'s groups: "
                            + UserAdminService.describeError(ex));
                    return false;
                });
    }

    private void mode(MenuActionContext context, Map<String, String> params) {
        Player player = context.player();
        ActiveMode mode;
        try {
            mode = ActiveMode.valueOf(require(params, "mode", "users.mode").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new MenuActionException("users.mode needs mode NONE, STAFF or OWNER, got '" + params.get("mode") + "'");
        }
        if (!admin.requireProperty(player, "mode")) {
            return;
        }
        withTarget(context, params, target -> admin.setMode(player, target, mode));
    }

    private void salaryPayout(MenuActionContext context, Map<String, String> params) {
        Player player = context.player();
        if (!admin.requireProperty(player, "salary")) {
            return;
        }
        withTarget(context, params, target -> admin.payOutSalary(player, target));
    }

    private void freeze(MenuActionContext context, Map<String, String> params) {
        Player player = context.player();
        boolean freezing = !"unfreeze".equalsIgnoreCase(params.get("op"));
        String node = freezing ? "knk.freeze" : "knk.unfreeze";
        if (!player.hasPermission(node)) {
            player.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return;
        }
        withTarget(context, params, target -> admin.setFrozen(player, target, freezing, freezing ? FREEZE_REASON : null));
    }

    private void kickOrBan(MenuActionContext context, Map<String, String> params, boolean kick) {
        Player player = context.player();
        withTarget(context, params, target -> {
            boolean ran = kick ? admin.kick(player, target) : admin.ban(player, target);
            if (!ran) {
                player.sendMessage(ChatColor.RED + "The /" + (kick ? "kick" : "ban") + " command isn't available.");
            }
            return CompletableFuture.completedFuture(ran);
        });
    }

    /**
     * Runs {@code mutation} against the cached target of {@code params.userId}, then drops the
     * cached state (the next render reads the target fresh) and repaints.
     */
    private void withTarget(MenuActionContext context, Map<String, String> params,
                            java.util.function.Function<UserSummary, CompletableFuture<Boolean>> mutation) {
        Player player = context.player();
        Integer userId = parseId(params.get("userId"));
        TargetState state = userId != null ? targets.get(key(player.getUniqueId(), userId)) : null;
        if (state == null || state.user() == null) {
            player.sendMessage(ChatColor.RED + "That player isn't loaded - reopen the menu.");
            return;
        }
        mutation.apply(state.user()).whenComplete((ok, ex) -> {
            if (context.menuService() != null) {
                context.menuService().refreshOpenMenu(player);
            }
        });
    }

    // ===== helpers =====

    private Integer viewerId(Player viewer) {
        return userCache.getStale(viewer.getUniqueId()).map(UserSummary::id).orElse(null);
    }

    private static String key(UUID viewer, int userId) {
        return viewer + ":" + userId;
    }

    static Integer parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "$state.pm.coinStep$" / "-$state.pm.coinStep$" after interpolation: "100" / "-100"; blank → no step picked. */
    static int parseDelta(Player player, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.equals("-")) {
            player.sendMessage(ChatColor.RED + "Pick a step first - click the value item.");
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new MenuActionException("users.adjust delta must be a whole number, got '" + raw + "'");
        }
    }

    private static String require(Map<String, String> params, String name, String action) {
        String value = params.get(name);
        if (value == null || value.isBlank()) {
            throw new MenuActionException(action + " action is missing its required '" + name + "' param");
        }
        return value;
    }

    private static <T> Page<T> page(List<T> rows) {
        return new Page<>(rows, rows.size(), 1, Math.max(1, rows.size()));
    }

    /** The {@code usersManager} root. */
    public static final class UsersManagerView {
        private final int onlineCount;

        UsersManagerView(int onlineCount) {
            this.onlineCount = onlineCount;
        }

        public int getOnlineCount() {
            return onlineCount;
        }
    }

}
