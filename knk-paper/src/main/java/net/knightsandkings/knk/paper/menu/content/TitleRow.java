package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.domain.users.TitleBracket;
import net.knightsandkings.knk.core.menu.MenuRowKey;

import java.util.ArrayList;
import java.util.List;

/**
 * One row of {@code titles.brackets} (CONTENT_PORT_PLAN.md CP3; CP8's title picker reuses it):
 * a title bracket seen by one user. {@code HIGHLIGHT} for the bracket they hold, {@code NORMAL}
 * for brackets they passed, {@code DISABLED} for the ones still ahead (v1 glowed reached titles).
 */
public final class TitleRow implements MenuRowKey {

    private final int bracketId;
    private final String name;
    private final int minExperience;
    private final int salary;
    private final List<String> loreLines;
    private final String displayMode;
    private final int order;

    private TitleRow(int bracketId, String name, int minExperience, int salary, List<String> loreLines, String displayMode,
                     int order) {
        this.bracketId = bracketId;
        this.name = name;
        this.minExperience = minExperience;
        this.salary = salary;
        this.loreLines = List.copyOf(loreLines);
        this.displayMode = displayMode;
        this.order = order;
    }

    /** Rows for every bracket as seen by a user of {@code gender} holding {@code progress}. */
    static List<TitleRow> rows(List<TitleBracket> brackets, TitleProgress progress, String gender, int experience) {
        int currentIndex = progress != null ? progress.currentIndex(brackets) : -1;
        List<TitleRow> rows = new ArrayList<>(brackets.size());
        for (int i = 0; i < brackets.size(); i++) {
            TitleBracket bracket = brackets.get(i);
            String mode = i == currentIndex ? "HIGHLIGHT" : (i < currentIndex ? "NORMAL" : "DISABLED");
            List<String> lore = new ArrayList<>();
            lore.add("&7Title &f" + (i + 1) + " &7of &f" + brackets.size());
            lore.add("&7Required XP: &f" + bracket.minExperience());
            lore.add("&7Salary: &f" + bracket.salary() + " coins");
            List<String> bonuses = new ArrayList<>();
            if (bracket.coinBonus() > 0) bonuses.add(bracket.coinBonus() + " coins");
            if (bracket.gemBonus() > 0) bonuses.add(bracket.gemBonus() + " gems");
            if (bracket.expBonus() > 0) bonuses.add(bracket.expBonus() + " XP");
            if (!bonuses.isEmpty()) {
                lore.add("&7Promotion bonus: &f" + String.join(", ", bonuses));
            }
            String other = "Female".equalsIgnoreCase(gender) ? bracket.maleName() : bracket.femaleName();
            String own = bracket.nameFor(gender);
            if (other != null && !other.isBlank() && !other.equals(own)) {
                lore.add("&8Also known as " + other);
            }
            if (i == currentIndex) {
                lore.add("&aYour current title");
            } else if (i < currentIndex) {
                lore.add("&aReached");
            } else {
                lore.add("&c" + Math.max(0, bracket.minExperience() - experience) + " more XP needed");
            }
            rows.add(new TitleRow(bracket.id(), own, bracket.minExperience(), bracket.salary(), lore, mode, i + 1));
        }
        return rows;
    }

    public int getBracketId() {
        return bracketId;
    }

    public String getName() {
        return name;
    }

    public int getMinExperience() {
        return minExperience;
    }

    public int getSalary() {
        return salary;
    }

    public List<String> getLoreLines() {
        return loreLines;
    }

    public String getDisplayMode() {
        return displayMode;
    }

    /** 1-based position on the title ladder - bound to the stack Amount so the order reads at a glance (max 64). */
    public int getOrder() {
        return Math.min(order, 64);
    }

    /**
     * Menu follow-up 2026-09-26 ("show more clearly which titles have been reached"): lime for
     * reached, gold for the current title, gray for the ones still ahead.
     */
    public String getMaterial() {
        return switch (displayMode) {
            case "HIGHLIGHT" -> "GOLDEN_HELMET";
            case "NORMAL" -> "LIME_STAINED_GLASS_PANE";
            default -> "GRAY_STAINED_GLASS_PANE";
        };
    }

    /** The name with a state marker: "&a✔ Squire", "&6&l» Knight «", "&7Lord". */
    public String getMarkedName() {
        return switch (displayMode) {
            case "HIGHLIGHT" -> "&6&l» " + name + " «";
            case "NORMAL" -> "&a✔ " + name;
            default -> "&7" + name;
        };
    }

    /**
     * For the Player manager's title picker (CP8): the current bracket HIGHLIGHTed, every other one
     * NORMAL - {@link #getDisplayMode()}'s DISABLED would make brackets above the target unclickable.
     */
    public String getPickerDisplayMode() {
        return "HIGHLIGHT".equals(displayMode) ? "HIGHLIGHT" : "NORMAL";
    }

    @Override
    public Object menuRowKey() {
        return List.of(bracketId, name, loreLines, displayMode);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TitleRow other && menuRowKey().equals(other.menuRowKey());
    }

    @Override
    public int hashCode() {
        return menuRowKey().hashCode();
    }
}
