package net.knightsandkings.knk.core.domain.siege;

/**
 * One level's choice on whether siege players get the navigation compass at the hub (KNG-234): a scenario's
 * or a lobby's own value, or {@link #INHERIT} to defer to the next level up. The levels are resolved by
 * {@link net.knightsandkings.knk.core.siege.SiegeNavigationItem}.
 */
public enum SiegeNavigationItemMode {
    INHERIT,
    GIVE,
    DO_NOT_GIVE;

    /** Parses "Inherit"/"Give"/"DoNotGive"; anything else (including null) is {@link #INHERIT}. */
    public static SiegeNavigationItemMode fromApi(String value) {
        return SiegeEnums.parse(SiegeNavigationItemMode.class, value, INHERIT);
    }
}
