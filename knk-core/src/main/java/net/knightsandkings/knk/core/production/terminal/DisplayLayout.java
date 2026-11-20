package net.knightsandkings.knk.core.production.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where a display container's sample goes (production DESIGN § 6.2 Display mode): one stack per slot, item after item in
 * the API's order, spread over the container so a half-full chest looks half full rather than packed at the top.
 */
public final class DisplayLayout {

    private DisplayLayout() {
    }

    /**
     * The blueprint id per slot ({@code null} = empty) for a container of {@code size} slots. At most {@code size} stacks;
     * the filled slots are spread evenly over the container.
     */
    public static List<Integer> slots(DisplaySample sample, int size) {
        List<Integer> stacks = new ArrayList<>();
        if (sample != null) {
            for (DisplaySample.Item item : sample.items()) {
                for (int i = 0; i < item.stacks() && stacks.size() < size; i++) {
                    stacks.add(item.itemBlueprintId());
                }
            }
        }
        List<Integer> result = new ArrayList<>(Collections.nCopies(Math.max(0, size), (Integer) null));
        int n = stacks.size();
        for (int i = 0; i < n; i++) {
            int slot = (int) ((long) i * size / n);
            result.set(slot, stacks.get(i));
        }
        return result;
    }
}
