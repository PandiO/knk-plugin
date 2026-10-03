package net.knightsandkings.knk.core.analytics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch.MenuStep;

/**
 * Menu funnel steps of one window (KNG-34 link 7, DESIGN.md D11, IMPLEMENTATION_PLAN.md §1.4):
 * {@code opened}, {@code action:<actionTypeId>} by outcome, {@code back}, {@code closed} per menu
 * key. Anonymous counts. Not thread-safe: {@link WorldAnalyticsWindow} guards it.
 */
public final class MenuFunnelCounter {

    public static final String OPENED = "opened";
    public static final String BACK = "back";
    public static final String CLOSED = "closed";
    public static final String ACTION_PREFIX = "action:";

    public static final String INFO = "info";
    public static final String SUCCEEDED = "succeeded";
    public static final String DENIED = "denied";
    public static final String FAILED = "failed";

    /** The API's column limits (MenuKey varchar(191), Step varchar(96)). */
    static final int MAX_MENU_KEY = 191;
    static final int MAX_ACTION_ID = 96 - ACTION_PREFIX.length();

    private record Key(String menuKey, String step, String outcome) {
    }

    private final Map<Key, int[]> counts = new LinkedHashMap<>();

    public void opened(String menuKey) {
        add(menuKey, OPENED, INFO);
    }

    public void back(String menuKey) {
        add(menuKey, BACK, INFO);
    }

    public void closed(String menuKey) {
        add(menuKey, CLOSED, INFO);
    }

    /** @param outcome {@link #SUCCEEDED}, {@link #DENIED} or {@link #FAILED} */
    public void action(String menuKey, String actionTypeId, String outcome) {
        if (actionTypeId == null || actionTypeId.isBlank()) {
            return;
        }
        String id = actionTypeId.trim().toLowerCase(Locale.ROOT);
        if (id.length() > MAX_ACTION_ID) {
            id = id.substring(0, MAX_ACTION_ID);
        }
        add(menuKey, ACTION_PREFIX + id, outcome);
    }

    private void add(String menuKey, String step, String outcome) {
        if (menuKey == null || menuKey.isBlank() || menuKey.length() > MAX_MENU_KEY) {
            return;
        }
        int[] count = counts.computeIfAbsent(new Key(menuKey, step, outcome), k -> new int[1]);
        if (count[0] < Integer.MAX_VALUE) {
            count[0]++;
        }
    }

    public int size() {
        return counts.size();
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }

    /** All steps (first-seen order) and clears the counter. */
    public List<MenuStep> drain() {
        List<MenuStep> out = new ArrayList<>(counts.size());
        counts.forEach((key, count) -> out.add(new MenuStep(key.menuKey(), key.step(), key.outcome(), count[0])));
        counts.clear();
        return out;
    }
}
