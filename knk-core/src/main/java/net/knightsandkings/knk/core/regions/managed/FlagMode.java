package net.knightsandkings.knk.core.regions.managed;

/** How a {@link FlagRule} treats a flag that a region already carries. */
public enum FlagMode {
    /**
     * Set the flag only while the region doesn't carry it. A value an admin set on purpose (e.g. {@code pvp allow} on a
     * town, the documented KNG-11 override) survives every restart.
     */
    SEED_IF_ABSENT,
    /** The category is defined by this value: any other value is corrected. */
    ENFORCE
}
