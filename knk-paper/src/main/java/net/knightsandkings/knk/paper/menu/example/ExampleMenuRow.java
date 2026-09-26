package net.knightsandkings.knk.paper.menu.example;

import net.knightsandkings.knk.core.menu.MenuRowKey;

import java.util.List;

/**
 * InventoryMenu Phase 9 demo row (content source {@code example.rows}): a
 * static, in-memory row type whose getters exercise every item-meta binding
 * (E6), null/list lore (E8) and a render condition (E5, {@link #isHidden()}).
 * Implements {@link MenuRowKey} so row bindings cache per logical row (E3).
 * Not game content - only the {@code example.domain} seeds use it.
 */
public final class ExampleMenuRow implements MenuRowKey {

    private final int id;
    private final String name;
    private final String materialKey;
    private final int count;
    private final String bannerPatterns;
    private final String skullOwner;
    private final String displayMode;
    private final boolean hidden;
    private final String tag;
    private final String note;
    private final List<String> detailLines;

    ExampleMenuRow(int id, String name, String materialKey, int count, String bannerPatterns, String skullOwner,
                   String displayMode, boolean hidden, String tag, String note, List<String> detailLines) {
        this.id = id;
        this.name = name;
        this.materialKey = materialKey;
        this.count = count;
        this.bannerPatterns = bannerPatterns;
        this.skullOwner = skullOwner;
        this.displayMode = displayMode;
        this.hidden = hidden;
        this.tag = tag;
        this.note = note;
        this.detailLines = List.copyOf(detailLines);
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** E6 {@code Material}: a namespace key or enum name - row 5's is deliberately invalid (fallback demo). */
    public String getMaterialKey() {
        return materialKey;
    }

    /** E6 {@code Amount}: row 2 (a banner, max stack 16) shows 20; row 5 asks for 70 and is clamped to 64. */
    public int getCount() {
        return count;
    }

    /** E6 {@code BannerPatterns}, or null (binding "not set"). */
    public String getBannerPatterns() {
        return bannerPatterns;
    }

    /** E6 {@code SkullOwner}, or null. */
    public String getSkullOwner() {
        return skullOwner;
    }

    /** E6 {@code DisplayMode}: NORMAL / HIGHLIGHT / DISABLED. */
    public String getDisplayMode() {
        return displayMode;
    }

    /** E5: the row template's Render condition hides rows where this is true. */
    public boolean isHidden() {
        return hidden;
    }

    public String getTag() {
        return tag;
    }

    /** E8: null drops the lore line. */
    public String getNote() {
        return note;
    }

    /** E8: expands into one lore line per element. */
    public List<String> getDetailLines() {
        return detailLines;
    }

    @Override
    public Object menuRowKey() {
        return id;
    }
}
