package net.knightsandkings.knk.core.production.terminal;

import java.util.List;

/**
 * What a display container shows ({@code GET api/StorageContainers/{id}/display}): a sample proportional to the
 * storage's fill level; Σ stacks = {@code filledSlots}.
 */
public record DisplaySample(int containerId, int storageId, int slots, double fillRatio, int filledSlots, List<Item> items) {

    public DisplaySample {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public record Item(int itemBlueprintId, String itemBlueprintName, int stacks) {
    }
}
