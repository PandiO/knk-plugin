package net.knightsandkings.knk.paper.menu.content;

import java.util.List;

import net.knightsandkings.knk.core.production.menu.StructureMenu;
import net.knightsandkings.knk.core.production.menu.StructureMenuText;

/** A row of {@code structures.storages} (V2 {@code StorageOverview}, M5): one storage of the structure and its contents. */
public record StructureStorageRow(StructureMenu.StorageView storage) {

    public String getName() {
        return storage.name() == null || storage.name().isBlank() ? "storage #" + storage.storageId() : storage.name();
    }

    public String getFillLine() {
        return StructureMenuText.fillLine(storage);
    }

    public List<String> getContentLines() {
        return StructureMenuText.contentLines(storage);
    }
}
