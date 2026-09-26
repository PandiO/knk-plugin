package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.ActionRegistry;
import net.knightsandkings.knk.core.menu.MenuActionException;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.menu.MenuParams;
import net.knightsandkings.knk.core.menu.MenuSession;
import net.knightsandkings.knk.core.menu.RuntimeMenuSection;
import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Concrete {@code ActionRegistry} handlers. Phase 6 shipped the small
 * starting library ({@code menu.close}/{@code menu.open}); IMPLEMENTATION_PLAN.md
 * Phase 7 (folding in QOL_BUGFIX_BACKLOG.md item 8) adds the real preset
 * library for click-driven pagination, search, filter, and confirmation -
 * DESIGN_REVIEW.md §2.5's UI-first interaction model actually wired up.
 * <p>
 * Every section-scoped handler here ({@code menu.page.*}, {@code menu.search.*},
 * {@code menu.filter.*}) delegates to the same {@link MenuService} methods
 * {@code /knk menu ...} already calls (QOL_BUGFIX_BACKLOG.md item 8, open
 * question 6) - a click and a command drive the exact same pagination/search/
 * filter code path, never a second parallel implementation. They resolve
 * their target section from {@link MenuActionContext#section()} rather than a
 * paramsJson-carried section name - see that record's javadoc for why.
 */
public final class MenuActionHandlers {

    public static final String CLOSE = "menu.close";
    public static final String OPEN = "menu.open";
    /** InventoryMenu Phase 9 (E9): pop the nav stack (key + ctx), or close when there is nothing to go back to. */
    public static final String BACK = "menu.back";
    public static final String PAGE_NEXT = "menu.page.next";
    public static final String PAGE_PREV = "menu.page.prev";
    public static final String PAGE_FIRST = "menu.page.first";
    public static final String SEARCH_PROMPT = "menu.search.prompt";
    public static final String SEARCH_CLEAR = "menu.search.clear";
    public static final String FILTER_PROMPT = "menu.filter.prompt";
    public static final String FILTER_CYCLE = "menu.filter.cycle";
    public static final String FILTER_CLEAR = "menu.filter.clear";
    public static final String CONFIRM_REQUEST = "menu.confirm.request";
    public static final String CONFIRM_ACCEPT = "menu.confirm.accept";
    public static final String CONFIRM_CANCEL = "menu.confirm.cancel";
    public static final String DOUBLECLICK_CONFIRM = "menu.confirm.doubleclick";
    /** Content port CP6 (G1): {@code {key, value}} - see {@link #stateSet}. */
    public static final String STATE_SET = "menu.state.set";
    /** Content port CP6 (G1): {@code {key, values}} - see {@link #stateCycle}. */
    public static final String STATE_CYCLE = "menu.state.cycle";
    /** {@code menu.open} params with this prefix set session state keys if unset (G1). */
    public static final String STATE_PARAM_PREFIX = "state.";
    private static final int DEFAULT_DOUBLECLICK_WINDOW_TICKS = 60;

    private MenuActionHandlers() {
    }

    public static void registerDefaults(ActionRegistry<MenuActionContext> registry) {
        registry.register(CLOSE, MenuActionHandlers::close);
        registry.register(OPEN, MenuActionHandlers::open);
        registry.register(BACK, (context, params) -> context.menuService().goBack(context.player()));
        registry.register(PAGE_NEXT, (context, params) ->
                context.menuService().nextPage(context.player(), requireSection(context, PAGE_NEXT).name()));
        registry.register(PAGE_PREV, (context, params) ->
                context.menuService().previousPage(context.player(), requireSection(context, PAGE_PREV).name()));
        // Post-Phase-8 QOL follow-up: shift-click-on-pagination-button
        // shortcut - never bound directly to a menu item's own actions list,
        // only invoked by MenuClickListener's shift-click check (mirroring
        // SEARCH_CLEAR's own shift-click-only wiring) on a PAGE_NEXT/PAGE_PREV
        // item, so it's registered here for that lookup even though no seed
        // template ever references it by name.
        registry.register(PAGE_FIRST, (context, params) ->
                context.menuService().firstPage(context.player(), requireSection(context, PAGE_FIRST).name()));
        registry.register(SEARCH_PROMPT, (context, params) ->
                context.menuService().promptSearch(context.player(), requireSection(context, SEARCH_PROMPT).name()));
        registry.register(SEARCH_CLEAR, (context, params) ->
                context.menuService().clearSearch(context.player(), requireSection(context, SEARCH_CLEAR).name()));
        registry.register(FILTER_PROMPT, MenuActionHandlers::filterPrompt);
        registry.register(FILTER_CYCLE, MenuActionHandlers::filterCycle);
        registry.register(FILTER_CLEAR, MenuActionHandlers::filterClear);
        registry.register(CONFIRM_REQUEST, MenuActionHandlers::confirmRequest);
        // Captures `registry` itself so accepting a pending confirmation can
        // re-invoke whatever action it names - see #confirmAccept.
        registry.register(CONFIRM_ACCEPT, (context, params) -> confirmAccept(context, registry));
        registry.register(CONFIRM_CANCEL, MenuActionHandlers::confirmCancel);
        // Captures `registry` for the same reason CONFIRM_ACCEPT does.
        registry.register(DOUBLECLICK_CONFIRM, (context, params) -> doubleClickConfirm(context, params, registry));
        registry.register(STATE_SET, MenuActionHandlers::stateSet);
        registry.register(STATE_CYCLE, MenuActionHandlers::stateCycle);
    }

    private static void close(MenuActionContext context, Map<String, String> params) {
        context.player().closeInventory();
    }

    /**
     * Requires a {@code key} param naming the {@code MenuTemplate.Key} to navigate
     * to. InventoryMenu Phase 9 (E1): every {@code ctx.}-prefixed param becomes a
     * context parameter of the opened menu ({@code "ctx.lobbyId": "$row.getLobbyId$"}
     * arrives here already interpolated). Opened from inside a menu, so the
     * current menu is pushed onto the back stack.
     */
    private static void open(MenuActionContext context, Map<String, String> params) {
        String key = requireParam(params, "key", OPEN);
        applyStateDefaults(context, params);
        context.menuService().openMenu(context.player(), key, MenuContextParams.fromPrefixedParams(params));
    }

    /**
     * Content port CP6 (G1): every {@code state.}-prefixed {@code menu.open} param sets that
     * session state key (prefix stripped) <em>only if it is unset</em> - how a template declares
     * the default step sizes of the menu it opens without resetting a step the player already
     * changed ({@code "state.pm.coinStep": "100"}). Values are interpolated like any param.
     */
    static void applyStateDefaults(MenuActionContext context, Map<String, String> params) {
        MenuSession session = context.session();
        if (session == null) {
            return;
        }
        params.forEach((name, value) -> {
            if (name.startsWith(STATE_PARAM_PREFIX) && name.length() > STATE_PARAM_PREFIX.length()) {
                session.setStateIfAbsent(name.substring(STATE_PARAM_PREFIX.length()), value);
            }
        });
    }

    /**
     * Content port CP6 (G1): {@code {"key": "pm.coinStep", "value": "1000"}} - stores the value in
     * the session state, marks the session dirty and repaints the open menu. An empty
     * {@code value} unsets the key.
     */
    private static void stateSet(MenuActionContext context, Map<String, String> params) {
        String key = requireParam(params, "key", STATE_SET);
        String value = params.get("value");
        requireSession(context, STATE_SET).setState(key, value == null || value.isEmpty() ? null : value);
        repaint(context);
    }

    /**
     * Content port CP6 (G1): {@code {"key": "pm.coinStep", "values": "1,10,100,1000"}} - advances
     * the key to the next value of the comma list (trimmed), wrapping after the last; an unset key
     * (or one not in the list) becomes the first value. Marks dirty and repaints - the "click the
     * value to change the step" of v1's steppers.
     */
    private static void stateCycle(MenuActionContext context, Map<String, String> params) {
        String key = requireParam(params, "key", STATE_CYCLE);
        List<String> values = new ArrayList<>();
        for (String value : requireParam(params, "values", STATE_CYCLE).split(",")) {
            if (!value.isBlank()) {
                values.add(value.trim());
            }
        }
        if (values.isEmpty()) {
            throw new MenuActionException(STATE_CYCLE + " action has no values to cycle through");
        }
        requireSession(context, STATE_CYCLE).cycleState(key, values);
        repaint(context);
    }

    private static MenuSession requireSession(MenuActionContext context, String actionTypeId) {
        if (context.session() == null) {
            throw new MenuActionException(actionTypeId + " action requires a menu session");
        }
        return context.session();
    }

    private static void repaint(MenuActionContext context) {
        context.session().markDirty();
        if (context.menuService() != null && context.player() != null) {
            context.menuService().refreshOpenMenu(context.player());
        }
    }

    private static void filterPrompt(MenuActionContext context, Map<String, String> params) {
        RuntimeMenuSection section = requireSection(context, FILTER_PROMPT);
        String facetKey = requireParam(params, "facetKey", FILTER_PROMPT);
        context.menuService().promptFilter(context.player(), section.name(), facetKey);
    }

    private static void filterClear(MenuActionContext context, Map<String, String> params) {
        RuntimeMenuSection section = requireSection(context, FILTER_CLEAR);
        String facetKey = requireParam(params, "facetKey", FILTER_CLEAR);
        context.menuService().clearFilter(context.player(), section.name(), facetKey);
    }

    /**
     * Cycles a FilterBar facet through an author-supplied, comma-separated
     * value list ({@code facetKey}, {@code values} params) - not a schema-
     * derived enumeration, since no such thing exists yet for any real
     * content field (ACTIVE_SESSIONS.md's Phase 5 entry, open question 4).
     * Reads the section's current {@code MenuContentQuery} for
     * {@code facetKey} to find where in the list the player currently is,
     * and advances to the next entry, wrapping back to "no filter" after the
     * last value - so repeated clicks visit every author-supplied value plus
     * an explicit "off" state, with no extra session state needed beyond
     * what Phase 5 already tracks.
     */
    private static void filterCycle(MenuActionContext context, Map<String, String> params) {
        RuntimeMenuSection section = requireSection(context, FILTER_CYCLE);
        String facetKey = requireParam(params, "facetKey", FILTER_CYCLE);
        String valuesParam = requireParam(params, "values", FILTER_CYCLE);
        List<String> values = List.of(valuesParam.split(","));

        String current = context.session().getContentQuery(section.id()).filterValues().get(facetKey);
        int currentIndex = current == null ? -1 : values.indexOf(current);
        int nextIndex = currentIndex + 1;

        if (nextIndex >= values.size() || values.get(nextIndex).isBlank()) {
            context.menuService().clearFilter(context.player(), section.name(), facetKey);
            context.player().sendMessage(ChatColor.GRAY + facetKey + ": " + ChatColor.WHITE + "all");
        } else {
            context.menuService().filter(context.player(), section.name(), facetKey, values.get(nextIndex));
            context.player().sendMessage(ChatColor.GRAY + facetKey + ": " + ChatColor.WHITE + values.get(nextIndex));
        }
    }

    /**
     * IMPLEMENTATION_PLAN.md Phase 7 / DESIGN_REVIEW.md §2.5 (Confirmations),
     * open question 4: doesn't run anything itself - stores what to run
     * ({@code actionTypeId} + its own {@code actionParamsJson}, defaulting to
     * "{}") as the session's one {@link MenuSession.PendingConfirmation}, and
     * tells the player. The originating action (e.g. a "close the menu"
     * button gated by a confirm step) is never invoked here; only
     * {@code menu.confirm.accept} actually re-triggers it.
     */
    private static void confirmRequest(MenuActionContext context, Map<String, String> params) {
        String actionTypeId = requireParam(params, "actionTypeId", CONFIRM_REQUEST);
        String actionParamsJson = params.getOrDefault("actionParamsJson", "{}");
        String prompt = params.getOrDefault("prompt", "Are you sure? Click Confirm or Cancel.");

        context.session().setPendingConfirmation(
                new MenuSession.PendingConfirmation(actionTypeId, MenuParams.parse(actionParamsJson), prompt));
        context.player().sendMessage(ChatColor.YELLOW + prompt);
    }

    /**
     * Re-invokes whatever action {@code menu.confirm.request} stored, via the
     * very same {@code registry} instance this handler is registered on -
     * simpler than threading the registry through {@link MenuActionContext}
     * for the one action that needs it. Throwing when nothing is pending is
     * the same "fail loudly, don't silently no-op" policy every handler in
     * this class follows; in practice this is unreachable through the normal
     * UI path since a Confirm button is expected to also carry a
     * {@code has-pending-confirmation} condition (see {@code example.presets}'
     * seed), so reaching this with nothing pending means that condition was
     * left off, not a legitimate empty click.
     */
    private static void confirmAccept(MenuActionContext context, ActionRegistry<MenuActionContext> registry) {
        MenuSession.PendingConfirmation pending = context.session().getPendingConfirmation()
                .orElseThrow(() -> new MenuActionException(CONFIRM_ACCEPT + " action has no pending confirmation to accept"));
        context.session().clearPendingConfirmation();
        registry.execute(pending.actionTypeId(), context, pending.actionParams());
    }

    private static void confirmCancel(MenuActionContext context, Map<String, String> params) {
        MenuSession.PendingConfirmation pending = context.session().getPendingConfirmation()
                .orElseThrow(() -> new MenuActionException(CONFIRM_CANCEL + " action has no pending confirmation to cancel"));
        context.session().clearPendingConfirmation();
        context.player().sendMessage(ChatColor.YELLOW + "Cancelled.");
    }

    /**
     * Post-Phase-8 QOL follow-up (replaces the separate Confirm/Cancel-button
     * style for cases that want it): the first click on a
     * {@code menu.confirm.doubleclick} item arms it (starts a {@code
     * windowTicks}-tick window on {@link MenuSession#armDoubleClick}); a
     * second click on the <em>same</em> item within that window (checked via
     * {@link MenuSession#isDoubleClickArmed}) actually re-invokes the wrapped
     * {@code actionTypeId} and clears the arm. A click after the window
     * expires (or on a different armed item - {@code isDoubleClickArmed}
     * checks the item id) is treated as a fresh first click, not an error -
     * same "just re-arm, don't fail" spirit as {@code filterCycle} wrapping
     * back to "off" rather than throwing. {@link MenuRenderer} shows the
     * live armed/expired state in this item's own lore (see its
     * {@code appendPresetStateLore}), so the player sees the countdown
     * without needing a chat message on every click.
     */
    private static void doubleClickConfirm(MenuActionContext context, Map<String, String> params,
                                            ActionRegistry<MenuActionContext> registry) {
        String actionTypeId = requireParam(params, "actionTypeId", DOUBLECLICK_CONFIRM);
        String actionParamsJson = params.getOrDefault("actionParamsJson", "{}");
        int windowTicks = parsePositiveIntOrDefault(params.get("windowTicks"), DEFAULT_DOUBLECLICK_WINDOW_TICKS);
        int itemId = requireItemId(context, DOUBLECLICK_CONFIRM);
        long currentTick = currentTick();

        if (context.session().isDoubleClickArmed(itemId, currentTick, windowTicks)) {
            context.session().clearDoubleClickArm();
            registry.execute(actionTypeId, context, MenuParams.parse(actionParamsJson));
            String confirmMessage = params.get("confirmMessage");
            if (confirmMessage != null && !confirmMessage.isBlank()) {
                context.player().sendMessage(ChatColor.GREEN + confirmMessage);
            }
        } else {
            context.session().armDoubleClick(itemId, currentTick);
            String armMessage = params.getOrDefault("armMessage",
                    "Click again within " + (windowTicks / 20) + "s to confirm.");
            context.player().sendMessage(ChatColor.YELLOW + armMessage);
        }
    }

    private static int requireItemId(MenuActionContext context, String actionTypeId) {
        if (context.item() == null || context.item().id() == null) {
            throw new MenuActionException(actionTypeId + " action requires a persisted item with an id, but the clicked item has none");
        }
        return context.item().id();
    }

    private static int parsePositiveIntOrDefault(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Matches {@code MenuRenderer#currentTick} - 1 tick = 50ms, monotonic clock for TTL-style windows. */
    private static long currentTick() {
        return System.currentTimeMillis() / 50L;
    }

    private static RuntimeMenuSection requireSection(MenuActionContext context, String actionTypeId) {
        RuntimeMenuSection section = context.section();
        if (section == null) {
            throw new MenuActionException(actionTypeId + " action requires a section context, but the clicked item has none");
        }
        return section;
    }

    private static String requireParam(Map<String, String> params, String key, String actionTypeId) {
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            throw new MenuActionException(actionTypeId + " action is missing its required '" + key + "' param");
        }
        return value;
    }
}
