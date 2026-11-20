package net.knightsandkings.knk.core.production.menu;

import java.util.List;

/**
 * The structure menu ({@code GET api/StructureMenus/{id}}, M1, M3): the public row for everyone; {@code detail} only when
 * {@code viewerSeesDetails} (members of the structure's town and staff). The API leaves the detail out otherwise, so the
 * plugin never holds stock it may not show.
 */
public record StructureMenu(StructureRow row, boolean viewerSeesDetails, boolean viewerIsStaff, Detail detail) {

    public record Detail(Integer workersPresent, Integer workersTotal, Integer workersAway, List<Rate> rates,
                         List<StorageView> storages, List<String> warnings, List<String> notProducingReasons,
                         int openOrderCount) {

        public Detail {
            rates = rates == null ? List.of() : List.copyOf(rates);
            storages = storages == null ? List.of() : List.copyOf(storages);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            notProducingReasons = notProducingReasons == null ? List.of() : List.copyOf(notProducingReasons);
        }
    }

    public record Rate(int productionLineId, int itemBlueprintId, String itemBlueprintName, double ratePerHour,
                       String status, List<String> notProducingReasons) {

        public Rate {
            notProducingReasons = notProducingReasons == null ? List.of() : List.copyOf(notProducingReasons);
        }
    }

    public record StorageView(int storageId, String name, String purpose, long totalUnits, Long maxUnits, List<Line> lines) {

        public StorageView {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    public record Line(int itemBlueprintId, String itemBlueprintName, long amount, Long itemInstanceId,
                       String customDisplayName) {
    }

    /** Whether the detail may be shown: the API says so and sent it. */
    public boolean showsDetail() {
        return viewerSeesDetails && detail != null;
    }
}
