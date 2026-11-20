package net.knightsandkings.knk.paper.menu.content;

import java.util.List;

import net.knightsandkings.knk.core.production.menu.StructureMenu;
import net.knightsandkings.knk.core.production.menu.StructureMenuText;

/**
 * Root {@code structure} of the structure menu (M1, M3, M5): the last {@code GET api/StructureMenus/{id}} read for the
 * viewer. The detail getters are empty unless the API sent the detail ({@code getSeesDetails}).
 */
public record StructureView(StructureMenu menu, String fallbackName) {

    public String getMaterial() {
        return menu == null ? "CLOCK" : StructureMenuText.material(menu.row());
    }

    public String getName() {
        if (menu != null && menu.row().name() != null && !menu.row().name().isBlank()) {
            return menu.row().name();
        }
        return fallbackName == null || fallbackName.isBlank() ? "Structure" : fallbackName;
    }

    public List<String> getSummaryLines() {
        return StructureMenuText.summaryLines(menu == null ? null : menu.row());
    }

    public boolean getSeesDetails() {
        return menu != null && menu.showsDetail();
    }

    public List<String> getRateLines() {
        return StructureMenuText.rateLines(menu);
    }

    public List<String> getWorkerLines() {
        return StructureMenuText.workerLines(menu);
    }

    public List<String> getWarningLines() {
        return StructureMenuText.warningLines(menu);
    }

    public String getOrdersLine() {
        return StructureMenuText.ordersLine(menu);
    }
}
