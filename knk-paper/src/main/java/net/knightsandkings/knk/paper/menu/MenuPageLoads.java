package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.domain.menu.KnkActionBinding;
import net.knightsandkings.knk.core.menu.RuntimeMenuItem;
import net.knightsandkings.knk.core.menu.RuntimeMenuSection;
import net.knightsandkings.knk.core.menu.SectionView;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * KNG-231: the page loads in flight, one per player, so a page click made while the previous one
 * is still loading is ignored instead of queueing another fetch. A load that never finishes (a
 * hung request) stops blocking after {@link #STUCK_MILLIS}. Plus the pure helpers
 * {@link MenuService} uses for the loading feedback: which slots are the section's pager buttons
 * and which page number the loading item names.
 */
final class MenuPageLoads {

    /** A load older than this no longer blocks new page clicks. */
    static final long STUCK_MILLIS = 15_000L;

    private record Load(long token, long startedAtMillis) {
    }

    private final Map<UUID, Load> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong tokens = new AtomicLong();

    /** Starts a load for {@code playerId}; empty while another one of theirs is still running. */
    OptionalLong tryBegin(UUID playerId, long nowMillis) {
        long token = tokens.incrementAndGet();
        Load next = new Load(token, nowMillis);
        Load winner = inFlight.compute(playerId, (id, running) ->
                running == null || nowMillis - running.startedAtMillis() >= STUCK_MILLIS ? next : running);
        return winner == next ? OptionalLong.of(token) : OptionalLong.empty();
    }

    /** Whether {@code token} is still the player's running load (not finished, cancelled or replaced). */
    boolean isCurrent(UUID playerId, long token) {
        Load load = inFlight.get(playerId);
        return load != null && load.token() == token;
    }

    /** Ends the load {@code token}; a newer load of the same player is left alone. */
    void finish(UUID playerId, long token) {
        inFlight.computeIfPresent(playerId, (id, load) -> load.token() == token ? null : load);
    }

    /** The menu closed or the player left: whatever is running no longer blocks. */
    void cancel(UUID playerId) {
        inFlight.remove(playerId);
    }

    boolean isLoading(UUID playerId) {
        return inFlight.containsKey(playerId);
    }

    /** The slots of {@code section}'s next/previous/first-page buttons in the open menu, in slot order. */
    static Set<Integer> pagerSlots(Map<Integer, RuntimeMenuItem> itemsBySlot,
                                   Map<Integer, RuntimeMenuSection> sectionsBySlot, RuntimeMenuSection section) {
        Set<Integer> slots = new TreeSet<>();
        itemsBySlot.forEach((slot, item) -> {
            RuntimeMenuSection owner = sectionsBySlot.get(slot);
            if (owner != null && Objects.equals(owner.id(), section.id()) && isPager(item)) {
                slots.add(slot);
            }
        });
        return slots;
    }

    private static boolean isPager(RuntimeMenuItem item) {
        for (KnkActionBinding action : item.actions()) {
            String type = action.actionTypeId();
            if (MenuActionHandlers.PAGE_NEXT.equals(type) || MenuActionHandlers.PAGE_PREV.equals(type)
                    || MenuActionHandlers.PAGE_FIRST.equals(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The 1-based page a load is fetching. {@code sessionPage} is the session's 0-based page after
     * the click - for a content-source section unclamped (-1, or one past the last page), which the
     * renderer wraps once the fetch returns; wrapped here with the page count of the last render.
     */
    static int displayPage(int sessionPage, SectionView lastView) {
        int pageCount = lastView != null ? lastView.getPageCount() : 0;
        if (pageCount > 0) {
            return Math.floorMod(sessionPage, pageCount) + 1;
        }
        return Math.max(sessionPage, 0) + 1;
    }

    /** The loading item's name and the action-bar line. */
    static String loadingText(int page) {
        return "&7Loading page &f" + page + "&7…";
    }
}
