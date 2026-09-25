package net.knightsandkings.knk.core.menu;

/**
 * InventoryMenu Phase 9 (E9): the engine root {@code $section$} - paging facts
 * for the section being rendered (or the section a clicked item lives in), for
 * pager lore such as {@code &7Page $section.getPage$/$section.getPageCount$}.
 * <p>
 * {@code getPage} is <b>1-based</b> (it is display text); {@code getPageCount}
 * is at least 1 so an empty section reads "Page 1/1", not "Page 1/0".
 */
public final class SectionView {

    private final String name;
    private final int pageIndex;
    private final int totalPages;

    /**
     * @param pageIndex  0-based current page (the {@link MenuSession} convention)
     * @param totalPages total pages as computed by the render (0 when empty)
     */
    public SectionView(String name, int pageIndex, int totalPages) {
        this.name = name;
        this.pageIndex = Math.max(0, pageIndex);
        this.totalPages = Math.max(0, totalPages);
    }

    public static SectionView of(RuntimeMenuSection section, SectionSlotAssignment assignment) {
        return new SectionView(section.name(), assignment.page(), assignment.totalPages());
    }

    public String getName() {
        return name;
    }

    public int getPage() {
        return pageIndex + 1;
    }

    public int getPageCount() {
        return Math.max(1, totalPages);
    }

    public boolean hasNextPage() {
        return pageIndex + 1 < totalPages;
    }

    public boolean hasPreviousPage() {
        return pageIndex > 0;
    }

    @Override
    public String toString() {
        return "SectionView[" + name + " " + getPage() + "/" + getPageCount() + "]";
    }
}
