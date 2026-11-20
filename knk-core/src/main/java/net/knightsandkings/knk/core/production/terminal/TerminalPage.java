package net.knightsandkings.knk.core.production.terminal;

import java.util.List;

/**
 * One page of a terminal ({@code GET api/StorageContainers/{id}/terminal}): stock lines by name, then unique items.
 * {@code page} is 1-based.
 */
public record TerminalPage(int containerId, int storageId, String storageName, long totalUnits, Long maxUnits,
                           boolean locked, int page, int pageSize, int totalCount, List<Entry> entries) {

    public TerminalPage {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** A stock line ({@code itemInstanceId} null) or one unique item. */
    public record Entry(int itemBlueprintId, String itemBlueprintName, long amount, Long itemInstanceId,
                        String customDisplayName) {

        public boolean unique() {
            return itemInstanceId != null;
        }

        public String name() {
            if (customDisplayName != null && !customDisplayName.isBlank()) {
                return customDisplayName;
            }
            return itemBlueprintName != null && !itemBlueprintName.isBlank() ? itemBlueprintName : "Item #" + itemBlueprintId;
        }
    }

    public int pageCount() {
        return pageSize <= 0 ? 1 : Math.max(1, (totalCount + pageSize - 1) / pageSize);
    }

    public boolean hasNext() {
        return page < pageCount();
    }

    public boolean hasPrevious() {
        return page > 1;
    }
}
