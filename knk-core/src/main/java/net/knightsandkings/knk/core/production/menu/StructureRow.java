package net.knightsandkings.knk.core.production.menu;

import java.util.List;

/**
 * One row of the structures overview ({@code POST api/StructureMenus/search}, PandiO/knk-workspace#74, M2): public for
 * every player. {@code structureType} is ProductionStructure or Warehouse; {@code status} Working, Stalled, NoRoad or Full.
 */
public record StructureRow(int id, String structureType, String name, Integer productionKindId, String kindName,
                           Integer townId, String townName, boolean rural, Integer level, List<String> products,
                           String status, String statusText) {

    public StructureRow {
        products = products == null ? List.of() : List.copyOf(products);
    }

    public boolean warehouse() {
        return "Warehouse".equalsIgnoreCase(structureType);
    }
}
