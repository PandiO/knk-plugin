package net.knightsandkings.knk.core.duel.view;

import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.duel.StakeItem;
import net.knightsandkings.knk.core.menu.MenuRowKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One tile under a duelist's card on {@code duel_match_detail} (DESIGN §9.2 "kit · stake items · coins"): their kit,
 * their coin stake, then each stake item. The Paper side shows a stake item as the real stack (its icon, name,
 * enchantments) through a subclass; here it is the material and amount.
 */
public class DuelStakeRowView implements MenuRowKey {

    public enum Kind { KIT, COINS, ITEM, NONE }

    private final String key;
    private final Kind kind;
    private final String material;
    private final int amount;
    private final String name;
    private final List<String> lines;

    protected DuelStakeRowView(String key, Kind kind, String material, int amount, String name, List<String> lines) {
        this.key = Objects.requireNonNull(key, "key");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.material = material;
        this.amount = Math.max(1, Math.min(64, amount));
        this.name = name;
        this.lines = List.copyOf(lines);
    }

    /** Copies another row (for the Paper subclass that adds the stack). */
    protected DuelStakeRowView(DuelStakeRowView row) {
        this(row.key, row.kind, row.material, row.amount, row.name, row.lines);
    }

    /** The side's kit, coins and items in that order; one "no stake" tile when there is nothing. */
    public static List<DuelStakeRowView> rows(DuelMatchSnapshot match, DuelSide side) {
        DuelMatchSnapshot.Side s = match.side(side);
        String prefix = match.token() + ":" + side + ":";
        List<DuelStakeRowView> rows = new ArrayList<>();
        if (s.kitName() != null) {
            rows.add(new DuelStakeRowView(prefix + "kit", Kind.KIT, "IRON_CHESTPLATE", 1, "&bKit: &f" + s.kitName(),
                    List.of("&7Loaned for the duel only.")));
        }
        if (s.coins() > 0) {
            rows.add(new DuelStakeRowView(prefix + "coins", Kind.COINS, "GOLD_NUGGET", 1,
                    "&6" + DuelViewText.coins(s.coins()) + (s.coins() == 1 ? " coin" : " coins"),
                    List.of("&7Staked by " + s.name())));
        }
        for (StakeItem item : s.items()) {
            rows.add(new DuelStakeRowView(prefix + "item:" + item.refId(), Kind.ITEM, item.material(), item.amount(),
                    "&f" + item.amount() + "x " + prettyMaterial(item.material()), List.of("&7Staked by " + s.name())));
        }
        if (rows.isEmpty()) {
            rows.add(new DuelStakeRowView(prefix + "none", Kind.NONE, "LIGHT_GRAY_STAINED_GLASS_PANE", 1,
                    "&7No stake", List.of()));
        }
        return rows;
    }

    /** {@code DIAMOND_SWORD} → "Diamond Sword". */
    static String prettyMaterial(String material) {
        if (material == null || material.isBlank()) return "Item";
        StringBuilder out = new StringBuilder();
        for (String word : material.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    @Override
    public Object menuRowKey() {
        return key;
    }

    public Kind kind() {
        return kind;
    }

    /** The stake item's ref id, or null for a kit/coins/none tile. */
    public String itemRefId() {
        int at = key.indexOf(":item:");
        return kind == Kind.ITEM && at >= 0 ? key.substring(at + ":item:".length()) : null;
    }

    public String getMaterial() {
        return material;
    }

    public String getAmount() {
        return String.valueOf(amount);
    }

    public String getName() {
        return name;
    }

    public List<String> getLines() {
        return lines;
    }
}
