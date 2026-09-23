package net.knightsandkings.knk.core.menu;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player runtime menu state (IMPLEMENTATION_PLAN.md Entities section):
 * the current menu/navigation stack and per-section pagination state live
 * here, deliberately Bukkit-free (DESIGN_REVIEW.md "Portability" - no
 * {@code Player}/{@code Inventory} reference; those live in knk-paper's
 * paper-side open-menu context, keyed by the same player id).
 * <p>
 * Lifecycle is owned by {@link MenuSessionRegistry}: created on menu open,
 * explicitly removed on {@code PlayerQuitEvent} (knk-paper side) - this is
 * the fix for reconciliation gap #4 (v1's static per-player maps that were
 * never cleared). This class itself has no static state, so simply dropping
 * the reference is enough to make an instance collectible once the registry
 * forgets it.
 */
public final class MenuSession {

    private final UUID playerId;
    private final Instant createdAt;
    private final Deque<String> menuKeyHistory = new ArrayDeque<>();
    private final Map<Integer, Integer> sectionPages = new ConcurrentHashMap<>();
    private final Map<Integer, CachedVariable> variableCache = new ConcurrentHashMap<>();
    private final Map<Integer, MenuContentQuery> contentQueries = new ConcurrentHashMap<>();
    private volatile String currentMenuKey;
    private volatile boolean dirty;
    private volatile PendingConfirmation pendingConfirmation;
    private volatile DoubleClickArm doubleClickArm;
    private volatile int lastClickedSlot = -1;

    MenuSession(UUID playerId) {
        this.playerId = playerId;
        this.createdAt = Instant.now();
    }

    public UUID playerId() {
        return playerId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Optional<String> currentMenuKey() {
        return Optional.ofNullable(currentMenuKey);
    }

    /** Opens {@code menuKey}, pushing whatever was open before onto the back-navigation stack. */
    public void navigateTo(String menuKey) {
        if (currentMenuKey != null) {
            menuKeyHistory.push(currentMenuKey);
        }
        currentMenuKey = menuKey;
    }

    /** Pops the back-navigation stack and makes it current, or does nothing if there's no history. */
    public Optional<String> goBack() {
        String previous = menuKeyHistory.poll();
        if (previous != null) {
            currentMenuKey = previous;
        }
        return Optional.ofNullable(previous);
    }

    /** Clears navigation state entirely (e.g. when the top-level menu is closed, not just navigated away from). */
    public void resetNavigation() {
        currentMenuKey = null;
        menuKeyHistory.clear();
    }

    /** Current page for a section (keyed by its template id), defaulting to 0. */
    public int getPage(Integer sectionTemplateId) {
        if (sectionTemplateId == null) {
            return 0;
        }
        return sectionPages.getOrDefault(sectionTemplateId, 0);
    }

    public void setPage(Integer sectionTemplateId, int page) {
        if (sectionTemplateId == null) {
            return;
        }
        sectionPages.put(sectionTemplateId, Math.max(0, page));
    }

    /**
     * Post-Phase-8 QOL follow-up: advances/retreats a content-source-backed
     * section's page by {@code delta} with no clamping at all - unlike
     * {@link #setPage}, a negative result is allowed and preserved rather
     * than floored to 0. A content-source section's true page count isn't
     * known until {@code MenuRenderer}'s paged fetch reveals it, so
     * {@code MenuService.changePage} uses this (never {@link #setPage}) to
     * step such a section's page in either direction; the resulting
     * out-of-range value (negative, or past the last page) is a deliberate
     * signal {@code MenuRenderer.resolveContentSourceAssignment} wraps back
     * in-bounds once that fetch completes (see its own javadoc).
     * {@link #nextPage}/{@link #previousPage} remain the right call for the
     * non-content-source (in-memory list) case, where totalPages is already
     * known up front and wrapping can happen immediately.
     */
    public void stepPage(Integer sectionTemplateId, int delta) {
        if (sectionTemplateId == null) {
            return;
        }
        sectionPages.put(sectionTemplateId, getPage(sectionTemplateId) + delta);
    }

    /**
     * Post-Phase-8 QOL follow-up: advances a section's page by one, wrapping
     * back to page 0 from the last page instead of clamping there (the
     * developer's explicit ask: "next and previous buttons cycle, instead of
     * stop when at the beginning or end"). Safe to call with a stale/zero
     * {@code totalPages} (e.g. before the section's been rendered once) - it
     * just stays at page 0.
     */
    public int nextPage(Integer sectionTemplateId, int totalPages) {
        int next = totalPages > 0 ? Math.floorMod(getPage(sectionTemplateId) + 1, totalPages) : 0;
        setPage(sectionTemplateId, next);
        return next;
    }

    /** @see #nextPage(Integer, int) - same wraparound, the other direction (last page from page 0). */
    public int previousPage(Integer sectionTemplateId, int totalPages) {
        int previous = totalPages > 0 ? Math.floorMod(getPage(sectionTemplateId) - 1, totalPages) : 0;
        setPage(sectionTemplateId, previous);
        return previous;
    }

    /**
     * Post-Phase-8 QOL follow-up: jumps a section straight to page 0 -
     * backing the shift-click-on-pagination-button shortcut, the same
     * "shift-click resets/jumps" pattern already used for search.
     */
    public void firstPage(Integer sectionTemplateId) {
        setPage(sectionTemplateId, 0);
    }

    /**
     * IMPLEMENTATION_PLAN.md Phase 5 / DESIGN_REVIEW.md §2.1 §2.3: the
     * active search/filter content query for one searchable section
     * (keyed by its template id, same as {@link #sectionPages}), or
     * {@link MenuContentQuery#EMPTY} if none is set.
     */
    public MenuContentQuery getContentQuery(Integer sectionTemplateId) {
        if (sectionTemplateId == null) {
            return MenuContentQuery.EMPTY;
        }
        return contentQueries.getOrDefault(sectionTemplateId, MenuContentQuery.EMPTY);
    }

    /**
     * Sets (or, if {@code query} is empty, clears) the active content query
     * for a section, and marks the session dirty - DESIGN_REVIEW.md §1 names
     * "a filter/search updated" explicitly as an {@code ON_DIRTY} trigger, so
     * this is the one place that state transition happens.
     */
    public void setContentQuery(Integer sectionTemplateId, MenuContentQuery query) {
        if (sectionTemplateId == null) {
            return;
        }
        if (query == null || query.isEmpty()) {
            contentQueries.remove(sectionTemplateId);
        } else {
            contentQueries.put(sectionTemplateId, query);
        }
        markDirty();
    }

    public void markDirty() {
        dirty = true;
    }

    /**
     * Called once per render pass (by {@code MenuRenderer}, after every
     * item's variables have had a chance to observe {@link #isDirty()} as
     * true) - not by {@link VariableResolver} itself, since a single dirty
     * flag must stay true for every binding in the pass that triggered it,
     * not be consumed by whichever binding happens to check it first.
     */
    public void clearDirty() {
        dirty = false;
    }

    public boolean isDirty() {
        return dirty;
    }

    /**
     * A resolved {@link VariableResolver} result, cached here (not on the
     * {@code KnkVariableBinding}/{@code RuntimeMenuItem} instance that
     * produced it) because those are rebuilt fresh from the database on
     * every menu open/page turn - an instance-held cache would never survive
     * past a single render. Keyed by the binding's stable persisted id, which
     * does survive reassembly.
     */
    public record CachedVariable(String value, long resolvedAtTick) {
    }

    public Optional<CachedVariable> getCachedVariable(int bindingId) {
        return Optional.ofNullable(variableCache.get(bindingId));
    }

    public void cacheVariable(int bindingId, CachedVariable value) {
        variableCache.put(bindingId, value);
    }

    /**
     * IMPLEMENTATION_PLAN.md Phase 7 / DESIGN_REVIEW.md §2.5 (Confirmations):
     * an action awaiting confirmation before it actually runs - set by
     * {@code menu.confirm.request}, consumed (re-executed via
     * {@code ActionRegistry}) by {@code menu.confirm.accept}, or discarded by
     * {@code menu.confirm.cancel}. Deliberately not persisted and holds only
     * the same plain-String {@code actionTypeId}/params shape every other
     * ActionBinding already carries - a confirm dialog re-triggers whatever
     * action prompted it, it doesn't invent a second action mechanism.
     * <p>
     * One pending confirmation per session (not per-section/per-item): a
     * player can only be looking at one confirm prompt at a time, since
     * requesting a new one simply overwrites whatever was pending before.
     */
    public record PendingConfirmation(String actionTypeId, Map<String, String> actionParams, String promptMessage) {
        public PendingConfirmation {
            actionParams = actionParams == null ? Map.of() : Map.copyOf(actionParams);
        }
    }

    public Optional<PendingConfirmation> getPendingConfirmation() {
        return Optional.ofNullable(pendingConfirmation);
    }

    public void setPendingConfirmation(PendingConfirmation pending) {
        this.pendingConfirmation = pending;
    }

    public void clearPendingConfirmation() {
        this.pendingConfirmation = null;
    }

    /**
     * Post-Phase-8 QOL follow-up: the "click again within N ticks to confirm"
     * pattern the developer asked for in place of {@code menu.confirm.request}
     * / {@code .accept} / {@code .cancel}'s separate-button style - a real,
     * independent alternative kept alongside that mechanism (not a
     * replacement for it - a future screen may still want the two-button
     * style for a genuinely destructive action a player shouldn't be able to
     * trigger with two accidental clicks in a row). Keyed by the arming
     * item's own persisted id (knk-paper's {@code MenuActionContext.item().id()})
     * rather than a session-wide flag, since a menu could plausibly have more
     * than one double-click-armed button and a click on one must not
     * accidentally confirm a different one.
     */
    public record DoubleClickArm(int itemId, long armedAtTick) {
    }

    public Optional<DoubleClickArm> getDoubleClickArm() {
        return Optional.ofNullable(doubleClickArm);
    }

    public void armDoubleClick(int itemId, long currentTick) {
        this.doubleClickArm = new DoubleClickArm(itemId, currentTick);
    }

    public void clearDoubleClickArm() {
        this.doubleClickArm = null;
    }

    /**
     * Whether {@code itemId} currently has a live (unexpired) double-click
     * arm - the single check both {@code menu.confirm.doubleclick}'s click
     * handler and the render pass's lore feedback share, so "is this armed
     * right now" can never drift between the two.
     */
    public boolean isDoubleClickArmed(int itemId, long currentTick, int windowTicks) {
        DoubleClickArm arm = doubleClickArm;
        return arm != null && arm.itemId() == itemId && (currentTick - arm.armedAtTick()) < windowTicks;
    }

    /**
     * Post-Phase-8 QOL follow-up: the last menu slot a normal (non-double-
     * click-type) click resolved to a real {@code RuntimeMenuItem} in. Exists
     * specifically so {@code MenuClickListener} can correlate a Bukkit
     * {@code ClickType.DOUBLE_CLICK} event - which a genuinely fast physical
     * double-click produces as ONE event, with an unreliable/absent slot of
     * its own (Bukkit's {@code getClickedInventory()}/{@code getSlot()} don't
     * reliably identify a location for that click type, since it represents
     * a "gather" gesture rather than a single-slot interaction) - back to the
     * item the player actually meant to double-click. Without this, a fast
     * double-click would only ever register as a single ordinary click,
     * which can arm a {@code menu.confirm.doubleclick} button but never
     * confirm it on its own.
     */
    public int getLastClickedSlot() {
        return lastClickedSlot;
    }

    public void setLastClickedSlot(int slot) {
        this.lastClickedSlot = slot;
    }
}
