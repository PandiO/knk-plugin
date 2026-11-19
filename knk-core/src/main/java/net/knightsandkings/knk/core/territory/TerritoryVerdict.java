package net.knightsandkings.knk.core.territory;

import java.util.List;

/**
 * What owns a position (territory DESIGN § 1-3): its {@code owner}, or none; {@code contesting} lists the holders of the
 * highest rank whose range reaches it (one: the owner; two or more: contested, unless an admin awarded the area to one of
 * them). Mirrors the API's {@code TerritoryVerdict}.
 */
public record TerritoryVerdict(TerritoryHolder owner, List<TerritoryHolder> contesting) {

    /** No holder reaches the position. */
    public static final TerritoryVerdict WILDERNESS = new TerritoryVerdict(null, List.of());

    public TerritoryVerdict {
        contesting = List.copyOf(contesting);
    }

    /** Two or more holders of equal rank reach it and none was awarded it: nobody owns it. */
    public boolean isContested() {
        return owner == null && contesting.size() > 1;
    }

    public boolean isWilderness() {
        return owner == null && contesting.isEmpty();
    }

    /**
     * What a player crossing a border notices: the owner, or the contested holders, or nothing. Two verdicts with the same
     * area are the same land for entry and exit messages (an award keeps its owner even though others reach it too).
     */
    public Object area() {
        return owner != null ? owner : contesting;
    }
}
