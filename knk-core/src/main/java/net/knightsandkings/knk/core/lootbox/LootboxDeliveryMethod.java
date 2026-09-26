package net.knightsandkings.knk.core.lootbox;

/** How a claimed item reached the player ({@code POST api/LootboxClaims/{id}/delivered}). */
public enum LootboxDeliveryMethod {
    /** Straight into the inventory. */
    INVENTORY("Inventory"),
    /** The inventory had no room: dropped at the player's feet, owner-locked. */
    DROPPED_OWNED("DroppedOwned"),
    /** Given on a later join because the first delivery wasn't confirmed. */
    REDELIVERED("Redelivered");

    private final String wireValue;

    LootboxDeliveryMethod(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
