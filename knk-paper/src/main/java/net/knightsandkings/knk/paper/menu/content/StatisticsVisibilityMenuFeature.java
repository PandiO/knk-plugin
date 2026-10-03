package net.knightsandkings.knk.paper.menu.content;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.statistics.StatisticVisibility;
import net.knightsandkings.knk.core.domain.statistics.StatisticsVisibilityConflictException;
import net.knightsandkings.knk.core.domain.statistics.StatisticsVisibilitySettings;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.menu.ConditionOutcome;
import net.knightsandkings.knk.core.menu.MenuActionException;
import net.knightsandkings.knk.core.menu.MenuSession;
import net.knightsandkings.knk.core.ports.api.StatisticsApi;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuContentSourceContext;
import net.knightsandkings.knk.paper.menu.MenuFeature;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import net.knightsandkings.knk.paper.menu.MenuService;

/**
 * Player statistics privacy (KNG-34, IMPLEMENTATION_PLAN.md §5.3): {@code statistics.visibility},
 * opened by {@code /stats settings} and the profile's "Statistics privacy" tile. Registers
 * <ul>
 *   <li>root {@code statsvis} → {@link StatisticsVisibilityView} (selected group, counts, the pending
 *   group action's preview; never I/O);</li>
 *   <li>row source {@code statistics.visibility.rows} → {@link StatisticsVisibilityRow}: the selected
 *   group's settings from {@code GET api/statistics/users/{id}/visibility} (acting as the viewer), each
 *   followed by its contexts with an override or with data;</li>
 *   <li>actions {@code statistics.visibility.select-group} {@code {group}},
 *   {@code statistics.visibility.cycle} (the clicked row: one change with the shown value as
 *   expected), {@code statistics.visibility.group} {@code {value}} (stores a
 *   {@link MenuSession.PendingConfirmation} for {@code statistics.visibility.apply-group} whose preview
 *   lists every affected setting {@code current → proposed}) and {@code statistics.visibility.apply-group}
 *   (one atomic update of the group's metric-level settings; context overrides are left untouched);</li>
 *   <li>condition {@code statistics.visibility.pending} (Confirm/Cancel shown).</li>
 * </ul>
 * A 409 (changed on the web meanwhile) refreshes the menu from the API's current settings and tells
 * the player; nothing was written. Registered whatever {@code statistics.enabled} says - changing who
 * sees your statistics records nothing.
 */
public final class StatisticsVisibilityMenuFeature implements MenuFeature, org.bukkit.event.Listener {

    public static final String MENU_KEY = "statistics.visibility";
    public static final String ROOT = "statsvis";
    public static final String ROWS_SOURCE = "statistics.visibility.rows";
    public static final String SELECT_GROUP_ACTION = "statistics.visibility.select-group";
    public static final String CYCLE_ACTION = "statistics.visibility.cycle";
    public static final String GROUP_ACTION = "statistics.visibility.group";
    public static final String APPLY_GROUP_ACTION = "statistics.visibility.apply-group";
    public static final String PENDING_CONDITION = "statistics.visibility.pending";

    /** The API catalogue's menu groups, in header order. */
    public static final List<String> GROUPS = List.of("activity", "combat", "minigames", "exploration", "progression");
    /** Settings read this recently are reused for a repaint instead of a new GET. */
    static final long FRESH_MILLIS = 5_000L;

    private static final Logger LOGGER = Logger.getLogger(StatisticsVisibilityMenuFeature.class.getName());

    private record Cached(long fetchedAtMillis, StatisticsVisibilitySettings settings) {
    }

    private final StatisticsApi api;
    private final UserCache userCache;
    private final Executor mainThread;
    private final Clock clock;
    private final Map<UUID, String> selectedGroup = new ConcurrentHashMap<>();
    private final Map<UUID, Cached> settings = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> previews = new ConcurrentHashMap<>();

    public StatisticsVisibilityMenuFeature(StatisticsApi api, UserCache userCache, Executor mainThread, Clock clock) {
        this.api = api;
        this.userCache = userCache;
        this.mainThread = mainThread;
        this.clock = clock;
    }

    @Override
    public void registerMenuHandlers(MenuFeatureRegistries registries) {
        registries.variables().register(ROOT, StatisticsVisibilityView.class, (player, ctx) -> viewFor(player));
        registries.contentSources().registerRows(ROWS_SOURCE, StatisticsVisibilityRow.class,
                (context, params, query) -> fetchRows(context, query));
        registries.actions().register(SELECT_GROUP_ACTION, this::selectGroup);
        registries.actions().register(CYCLE_ACTION, this::cycle);
        registries.actions().register(GROUP_ACTION, this::requestGroup);
        registries.actions().register(APPLY_GROUP_ACTION, this::applyGroup);
        registries.conditions().register(PENDING_CONDITION, (context, params) -> pending(context));
    }

    // ===== root =====

    StatisticsVisibilityView viewFor(Player player) {
        if (player == null) {
            return new StatisticsVisibilityView(GROUPS.get(0), groupLabel(GROUPS.get(0)), null, List.of());
        }
        UUID uuid = player.getUniqueId();
        String group = group(uuid);
        Cached cached = settings.get(uuid);
        return new StatisticsVisibilityView(group, groupLabel(group), cached == null ? null : cached.settings(),
                previews.getOrDefault(uuid, List.of()));
    }

    // ===== rows =====

    CompletableFuture<Page<StatisticsVisibilityRow>> fetchRows(MenuContentSourceContext context, PagedQuery query) {
        PagedQuery page = query == null ? new PagedQuery(1, 27, null, null, false, Map.of()) : query;
        Player player = context.player();
        Integer userId = player != null ? userId(player.getUniqueId()) : null;
        if (userId == null) {
            return CompletableFuture.completedFuture(new Page<>(List.of(), 0, page.pageNumber(), page.pageSize()));
        }
        UUID uuid = player.getUniqueId();
        return load(uuid, userId)
                .thenApply(loaded -> page(rows(loaded, group(uuid)), page))
                .exceptionally(ex -> {
                    LOGGER.log(Level.WARNING, ROWS_SOURCE + ": failed to load the visibility settings of user " + userId, ex);
                    return new Page<>(List.of(), 0, page.pageNumber(), page.pageSize());
                });
    }

    /** The remembered settings while fresh, else a new read (remembered on success). */
    private CompletableFuture<StatisticsVisibilitySettings> load(UUID uuid, int userId) {
        Cached cached = settings.get(uuid);
        if (cached != null && clock.millis() - cached.fetchedAtMillis() < FRESH_MILLIS) {
            return CompletableFuture.completedFuture(cached.settings());
        }
        CompletableFuture<StatisticsVisibilitySettings> read;
        try {
            read = api.getVisibility(userId, userId);
        } catch (RuntimeException e) {
            read = CompletableFuture.failedFuture(e);
        }
        return read.thenApply(loaded -> {
            remember(uuid, loaded);
            return loaded;
        });
    }

    /** The group's settings in API order, each followed by its context rows. */
    static List<StatisticsVisibilityRow> rows(StatisticsVisibilitySettings loaded, String group) {
        List<StatisticsVisibilityRow> rows = new ArrayList<>();
        if (loaded == null) {
            return rows;
        }
        for (StatisticsVisibilitySettings.Setting setting : loaded.settings()) {
            if (setting.group() == null || !setting.group().equalsIgnoreCase(group)) {
                continue;
            }
            rows.add(StatisticsVisibilityRow.of(setting, loaded.friendsAvailable()));
            if (setting.contextual()) {
                for (StatisticsVisibilitySettings.ContextValue value : setting.contexts()) {
                    rows.add(StatisticsVisibilityRow.of(setting, value, loaded.friendsAvailable()));
                }
            }
        }
        return rows;
    }

    private static Page<StatisticsVisibilityRow> page(List<StatisticsVisibilityRow> rows, PagedQuery query) {
        int size = Math.max(1, query.pageSize());
        int number = Math.max(1, query.pageNumber());
        int from = Math.min(rows.size(), (number - 1) * size);
        int to = Math.min(rows.size(), from + size);
        return new Page<>(List.copyOf(rows.subList(from, to)), rows.size(), number, size);
    }

    // ===== actions =====

    private void selectGroup(MenuActionContext context, Map<String, String> params) {
        String group = params.getOrDefault("group", "").trim().toLowerCase(Locale.ROOT);
        if (!GROUPS.contains(group)) {
            throw new MenuActionException(SELECT_GROUP_ACTION + " group must be one of " + GROUPS + ", got '" + group + "'");
        }
        UUID uuid = context.player().getUniqueId();
        selectedGroup.put(uuid, group);
        clearPending(context);
        refresh(context.menuService(), context.player());
    }

    private void cycle(MenuActionContext context, Map<String, String> params) {
        Player player = context.player();
        String settingKey;
        String settingContext;
        StatisticVisibility expected;
        if (context.row() instanceof StatisticsVisibilityRow row) {
            settingKey = row.getSettingKey();
            settingContext = row.getContext();
            expected = row.visibility();
        } else {
            settingKey = params.get("settingKey");
            settingContext = params.getOrDefault("context", "");
            expected = parseVisibility(params.get("expected"), CYCLE_ACTION);
        }
        if (settingKey == null || settingKey.isBlank()) {
            throw new MenuActionException(CYCLE_ACTION + " needs a setting row");
        }
        Integer userId = userId(player.getUniqueId());
        if (userId == null) {
            player.sendMessage(ChatColor.RED + "Your account isn't loaded yet - try again in a moment.");
            return;
        }
        StatisticVisibility next = expected.next();
        String label = labelOf(player.getUniqueId(), settingKey)
                + (settingContext == null || settingContext.isBlank() ? "" : " (" + StatisticsVisibilityRow.contextLabel(settingContext) + ")");
        update(context.menuService(), player, userId,
                List.of(new StatisticsVisibilitySettings.Change(settingKey, settingContext, expected, next)),
                ChatColor.GRAY + label + ": now visible to " + plain(next) + "."
                        + (next == StatisticVisibility.FRIENDS ? ChatColor.DARK_GRAY + " (Friends-only shows nothing until the friends system exists.)" : ""));
    }

    /** A group action: preview + pending confirmation; nothing is written yet. */
    private void requestGroup(MenuActionContext context, Map<String, String> params) {
        Player player = context.player();
        StatisticVisibility value = parseVisibility(params.get("value"), GROUP_ACTION);
        if (context.session() == null) {
            throw new MenuActionException(GROUP_ACTION + " needs a menu session to confirm");
        }
        UUID uuid = player.getUniqueId();
        Cached cached = settings.get(uuid);
        if (cached == null) {
            player.sendMessage(ChatColor.RED + "Still loading your settings - try again in a moment.");
            return;
        }
        String group = group(uuid);
        List<StatisticsVisibilitySettings.Change> changes = groupChanges(cached.settings(), group, value);
        if (changes.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "Every " + groupLabel(group) + " setting is already visible to " + plain(value) + ".");
            return;
        }
        List<String> preview = previewLines(cached.settings(), group, value);
        previews.put(uuid, preview);
        String prompt = "Set " + changes.size() + " " + groupLabel(group) + " setting(s) to " + plain(value)
                + "? Click Confirm or Cancel.";
        context.session().setPendingConfirmation(new MenuSession.PendingConfirmation(APPLY_GROUP_ACTION,
                Map.of("group", group, "value", value.apiName()), prompt));
        player.sendMessage(ChatColor.YELLOW + prompt);
        for (String line : preview) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "  " + line));
        }
        refresh(context.menuService(), player);
    }

    /** Confirmed group action: one atomic update with the values the preview showed as expected. */
    private void applyGroup(MenuActionContext context, Map<String, String> params) {
        Player player = context.player();
        UUID uuid = player.getUniqueId();
        String group = params.getOrDefault("group", group(uuid)).toLowerCase(Locale.ROOT);
        StatisticVisibility value = parseVisibility(params.get("value"), APPLY_GROUP_ACTION);
        previews.remove(uuid);
        Integer userId = userId(uuid);
        Cached cached = settings.get(uuid);
        if (userId == null || cached == null) {
            player.sendMessage(ChatColor.RED + "Your settings aren't loaded - reopen the menu.");
            return;
        }
        List<StatisticsVisibilitySettings.Change> changes = groupChanges(cached.settings(), group, value);
        if (changes.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "Nothing to change.");
            refresh(context.menuService(), player);
            return;
        }
        update(context.menuService(), player, userId, changes,
                ChatColor.GRAY + "Set " + changes.size() + " " + groupLabel(group) + " setting(s) to " + plain(value) + ".");
    }

    private void update(MenuService menuService, Player player, int userId, List<StatisticsVisibilitySettings.Change> changes,
                        String successMessage) {
        UUID uuid = player.getUniqueId();
        CompletableFuture<StatisticsVisibilitySettings> call;
        try {
            call = api.updateVisibility(userId, userId, changes);
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        call.whenComplete((updated, error) -> mainThread.execute(() -> {
            if (error == null && updated != null) {
                remember(uuid, updated);
                player.sendMessage(successMessage);
            } else {
                StatisticsVisibilityConflictException conflict = conflict(error);
                if (conflict != null) {
                    if (conflict.current() != null) {
                        remember(uuid, conflict.current());
                    } else {
                        settings.remove(uuid);
                    }
                    player.sendMessage(ChatColor.YELLOW + "Your settings were changed elsewhere meanwhile, so nothing was changed."
                            + " The menu shows them as they are now - try again.");
                } else {
                    LOGGER.log(Level.WARNING, "statistics.visibility: update failed for user " + userId, error);
                    player.sendMessage(ChatColor.RED + "Couldn't save your settings right now - try again later.");
                }
            }
            if (player.isOnline()) {
                refresh(menuService, player);
            }
        }));
    }

    // ===== condition =====

    private static ConditionOutcome pending(MenuActionContext context) {
        MenuSession session = context.session();
        boolean pending = session != null && session.getPendingConfirmation()
                .map(p -> p.actionTypeId() != null && p.actionTypeId().startsWith("statistics.visibility."))
                .orElse(false);
        return pending ? ConditionOutcome.allow() : ConditionOutcome.deny("Nothing to confirm.");
    }

    // ===== pure helpers =====

    /** The metric-level changes a group action makes: every setting of the group not already at {@code value}. */
    static List<StatisticsVisibilitySettings.Change> groupChanges(StatisticsVisibilitySettings loaded, String group,
                                                                  StatisticVisibility value) {
        List<StatisticsVisibilitySettings.Change> changes = new ArrayList<>();
        for (StatisticsVisibilitySettings.Setting setting : loaded.settings()) {
            if (setting.group() != null && setting.group().equalsIgnoreCase(group) && setting.visibility() != value) {
                changes.add(new StatisticsVisibilitySettings.Change(setting.settingKey(), "", setting.visibility(), value));
            }
        }
        return changes;
    }

    /** "Logins: Nobody → Everyone" per change, then the context overrides that stay as they are. */
    static List<String> previewLines(StatisticsVisibilitySettings loaded, String group, StatisticVisibility value) {
        List<String> lines = new ArrayList<>();
        List<String> untouched = new ArrayList<>();
        for (StatisticsVisibilitySettings.Setting setting : loaded.settings()) {
            if (setting.group() == null || !setting.group().equalsIgnoreCase(group)) {
                continue;
            }
            if (setting.visibility() != value) {
                lines.add("&f" + setting.label() + "&7: " + StatisticsVisibilityRow.colored(setting.visibility())
                        + " &7→ " + StatisticsVisibilityRow.colored(value));
            }
            for (StatisticsVisibilitySettings.ContextValue context : setting.contexts()) {
                if (context.isOverride()) {
                    untouched.add("&8" + setting.label() + " — " + StatisticsVisibilityRow.contextLabel(context.context())
                            + " stays " + plain(context.visibility()));
                }
            }
        }
        lines.addAll(untouched);
        if (value == StatisticVisibility.FRIENDS && !loaded.friendsAvailable()) {
            lines.add(StatisticsVisibilityRow.FRIENDS_NOTE);
        }
        return lines;
    }

    static String groupLabel(String group) {
        return group == null || group.isEmpty() ? "" : group.substring(0, 1).toUpperCase(Locale.ROOT) + group.substring(1);
    }

    private static String plain(StatisticVisibility value) {
        return value.apiName();
    }

    private static StatisticVisibility parseVisibility(String raw, String action) {
        if (raw != null) {
            for (StatisticVisibility value : StatisticVisibility.values()) {
                if (value.apiName().equalsIgnoreCase(raw.trim())) {
                    return value;
                }
            }
        }
        throw new MenuActionException(action + " value must be Nobody, Friends or Everyone, got '" + raw + "'");
    }

    private static StatisticsVisibilityConflictException conflict(Throwable error) {
        Throwable t = error;
        while (t != null) {
            if (t instanceof StatisticsVisibilityConflictException conflict) {
                return conflict;
            }
            if (t.getCause() == t) {
                return null;
            }
            t = t.getCause();
        }
        return null;
    }

    // ===== state =====

    private String group(UUID uuid) {
        return selectedGroup.getOrDefault(uuid, GROUPS.get(0));
    }

    private String labelOf(UUID uuid, String settingKey) {
        Cached cached = settings.get(uuid);
        return cached == null ? settingKey : cached.settings().setting(settingKey)
                .map(StatisticsVisibilitySettings.Setting::label).orElse(settingKey);
    }

    private void remember(UUID uuid, StatisticsVisibilitySettings loaded) {
        if (loaded != null) {
            settings.put(uuid, new Cached(clock.millis(), loaded));
        }
    }

    private void clearPending(MenuActionContext context) {
        if (context.session() != null && context.session().getPendingConfirmation()
                .map(p -> p.actionTypeId() != null && p.actionTypeId().startsWith("statistics.visibility.")).orElse(false)) {
            context.session().clearPendingConfirmation();
        }
        previews.remove(context.player().getUniqueId());
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    /** The viewer left: forget their remembered state. */
    public void forget(UUID uuid) {
        selectedGroup.remove(uuid);
        settings.remove(uuid);
        previews.remove(uuid);
    }

    private Integer userId(UUID uuid) {
        return userCache.getStale(uuid).map(UserSummary::id).orElse(null);
    }

    private static void refresh(MenuService menuService, Player player) {
        if (menuService != null && player != null) {
            menuService.refreshOpenMenu(player);
        }
    }
}
