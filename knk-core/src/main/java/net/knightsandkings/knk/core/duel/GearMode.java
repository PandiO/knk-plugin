package net.knightsandkings.knk.core.duel;

/** How the duelists are geared (DESIGN D9, §8.4). Stored by the API as the constant's name. */
public enum GearMode {
    /** Own gear; the inventory is kept on death. */
    OWN_GEAR_KEEP,
    /** Own gear; the loser's inventory becomes spoils for the winner (§8.5). */
    OWN_GEAR_DROP,
    /** Both duelists get the kit the proposal names. */
    FIXED_KIT,
    /** Each duelist picks from the arena's eligible kits in warmup; the first is the default (O3). */
    KIT_CHOICE;

    /** The player's own gear is vaulted and a loaned kit granted (§8.4). */
    public boolean isKitMode() {
        return this == FIXED_KIT || this == KIT_CHOICE;
    }

    /** A decisive result moves the loser's inventory, so the duel is value-moving even without stakes. */
    public boolean dropsInventory() {
        return this == OWN_GEAR_DROP;
    }
}
