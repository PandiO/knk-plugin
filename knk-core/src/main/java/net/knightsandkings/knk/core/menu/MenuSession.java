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
    private volatile String currentMenuKey;
    private volatile boolean dirty;

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
     * Advances a section's page by one, clamped to {@code totalPages - 1}. Safe
     * to call with a stale/zero {@code totalPages} (e.g. before the section's
     * been rendered once) - it just clamps to page 0.
     */
    public int nextPage(Integer sectionTemplateId, int totalPages) {
        int clamped = Math.max(0, totalPages - 1);
        int next = Math.min(getPage(sectionTemplateId) + 1, clamped);
        setPage(sectionTemplateId, next);
        return next;
    }

    public int previousPage(Integer sectionTemplateId) {
        int previous = Math.max(0, getPage(sectionTemplateId) - 1);
        setPage(sectionTemplateId, previous);
        return previous;
    }

    public void markDirty() {
        dirty = true;
    }

    public void clearDirty() {
        dirty = false;
    }

    public boolean isDirty() {
        return dirty;
    }
}
