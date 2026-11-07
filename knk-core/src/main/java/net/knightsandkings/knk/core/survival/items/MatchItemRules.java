package net.knightsandkings.knk.core.survival.items;

/**
 * Who may take a match item (DESIGN §8b): every kit, baseline, purchase and Mystery Box item carries the id of its match;
 * only members of that match pick it up, nothing else (other players, other matches, mobs, hoppers, containers) does.
 */
public final class MatchItemRules {

    private MatchItemRules() {
    }

    /**
     * @param itemMatchId   the tag on the item (null = not a match item)
     * @param playerMatchId the running match of the player (null = not in a match)
     * @return whether the player may pick the item up; a non-match item is left to the ordinary rules
     */
    public static Decision pickup(String itemMatchId, String playerMatchId) {
        if (itemMatchId == null || itemMatchId.isBlank()) return Decision.NOT_A_MATCH_ITEM;
        return itemMatchId.equals(playerMatchId) ? Decision.ALLOW : Decision.DENY;
    }

    /** Mobs, hoppers, minecarts and other non-player takers never get a match item. */
    public static boolean deniedToNonPlayers(String itemMatchId) {
        return itemMatchId != null && !itemMatchId.isBlank();
    }

    public enum Decision {
        /** No match tag: the ordinary Siege rules apply. */
        NOT_A_MATCH_ITEM,
        ALLOW,
        DENY
    }
}
