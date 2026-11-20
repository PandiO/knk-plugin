package net.knightsandkings.knk.core.siege.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One shop entry for one viewer, a row of {@code siege.shop-entries} (siege shop § 4.1.3, A26;
 * PandiO/knk-workspace#868): icon, name, quantity, the <b>current</b> price after increases, cooldown left, "bought
 * 2/8", and why it can't be bought now (locked, not available yet, limit, not enough points). A value object: any
 * change makes the menu re-resolve the row.
 */
public final class ShopEntryView {

    private final ShopEntry entry;
    private final ShopPurchaseService.EntryState state;
    private final long balance;
    private final String material;
    private final ShopRefusal blocked;

    /**
     * @param material the icon material key (the entry's, else the blueprint's), null for a default
     * @param blocked  a refusal that doesn't depend on the brakes or the balance (locked, not available yet), or null
     */
    public ShopEntryView(ShopEntry entry, ShopPurchaseService.EntryState state, long balance, String material,
                         ShopRefusal blocked) {
        this.entry = Objects.requireNonNull(entry, "entry");
        this.state = Objects.requireNonNull(state, "state");
        this.balance = balance;
        this.material = material;
        this.blocked = blocked;
    }

    /** The {@code siege.shop.buy} param. */
    public int getEntryId() {
        return entry.id();
    }

    public String getMaterial() {
        if (material != null && !material.isBlank()) return material;
        return entry.kind() == ShopEntryKind.SIEGE_WEAPON ? "CROSSBOW" : "CHEST";
    }

    /** The stack size shown: the quantity, 1 to 64. */
    public int getAmount() {
        return Math.max(1, Math.min(64, entry.quantity()));
    }

    public String getName() {
        return "&e" + entry.name() + (entry.quantity() > 1 ? " &7×" + entry.quantity() : "");
    }

    /** DISABLED while it can't be bought now, else NORMAL. */
    public String getDisplayMode() {
        return canBuy() ? "NORMAL" : "DISABLED";
    }

    public long getPrice() {
        return state.price();
    }

    public List<String> getLines() {
        List<String> lines = new ArrayList<>();
        lines.add("&7Price: &6" + state.price() + " siege points");
        if (entry.capped()) {
            lines.add("&7Bought: &f" + state.purchases() + "/" + entry.maxPerMatch());
        } else if (state.purchases() > 0) {
            lines.add("&7Bought: &f" + state.purchases());
        }
        if (state.cooldownMillisLeft() > 0) lines.add("&cCooldown: " + ShopText.seconds(state.cooldownMillisLeft()));
        lines.add("");
        if (blocked != null) {
            lines.add("&c" + blocked.message());
        } else if (state.capReached()) {
            lines.add("&cLimit reached: " + entry.maxPerMatch() + " per match");
        } else if (balance < state.price()) {
            lines.add("&cNot enough siege points: " + balance + "/" + state.price());
        } else if (state.cooldownMillisLeft() <= 0) {
            lines.add(entry.kind() == ShopEntryKind.SIEGE_WEAPON ? "&aClick to choose a spot" : "&aClick to buy");
        }
        return lines;
    }

    private boolean canBuy() {
        return blocked == null && !state.capReached() && state.cooldownMillisLeft() <= 0 && balance >= state.price();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ShopEntryView v && v.entry.equals(entry) && v.state.equals(state) && v.balance == balance
                && Objects.equals(v.material, material) && Objects.equals(v.blocked, blocked);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entry, state, balance, material, blocked);
    }
}
