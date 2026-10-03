package net.knightsandkings.knk.paper.menu.content;

import java.util.ArrayList;
import java.util.List;

import net.knightsandkings.knk.core.domain.statistics.TitleChange;
import net.knightsandkings.knk.core.statistics.StatisticsLines;

/** One title change of {@code statistics.main} (row source {@code statistics.main.title-history}). Read-only. */
public final class TitleHistoryRow {

    private final TitleChange change;

    TitleHistoryRow(TitleChange change) {
        this.change = change;
    }

    public String getMaterial() {
        return change.isPromotion() ? "EXPERIENCE_BOTTLE" : "REDSTONE";
    }

    public String getName() {
        return change.isPromotion()
                ? "&aPromoted to &f" + change.toTitleName()
                : "&cDemoted to &f" + change.toTitleName();
    }

    public List<String> getLoreLines() {
        List<String> lore = new ArrayList<>();
        if (change.fromTitleName() != null) {
            lore.add("&7From: &f" + change.fromTitleName());
        }
        lore.add("&7On: &f" + StatisticsLines.day(change.changedAt()));
        return lore;
    }
}
