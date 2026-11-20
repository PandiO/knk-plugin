package net.knightsandkings.knk.paper.menu.content;

import java.util.ArrayList;
import java.util.List;

import net.knightsandkings.knk.core.production.menu.StructureMenuText;
import net.knightsandkings.knk.core.production.menu.StructureRow;

/** A row of {@code structures.overview} (PandiO/knk-workspace#74, M2): a structure's public fields. */
public record StructureOverviewRow(StructureRow row) {

    public int getId() {
        return row.id();
    }

    public String getName() {
        return row.name() == null || row.name().isBlank() ? "Structure #" + row.id() : row.name();
    }

    public String getMaterial() {
        return StructureMenuText.material(row);
    }

    public List<String> getLoreLines() {
        return new ArrayList<>(StructureMenuText.summaryLines(row));
    }
}
