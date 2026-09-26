package net.knightsandkings.knk.paper.lootbox;

import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import net.knightsandkings.knk.core.lootbox.LootboxRejectedException;
import org.bukkit.ChatColor;

/**
 * Player-facing lootbox text (docs/specs/lootboxes/DESIGN.md §3.4 "Messages / UX"), in the plugin's legacy
 * {@link ChatColor} style.
 */
public final class LootboxMessages {

    public static final String INVENTORY_FULL = ChatColor.RED + "Your inventory is full — make room to open this lootbox.";
    public static final String STUCK = ChatColor.RED + "The lootbox is stuck — try again in a moment.";
    public static final String TOO_FAR = ChatColor.RED + "Get closer to open this lootbox.";
    public static final String NO_PERMISSION = ChatColor.RED + "You can't open lootboxes.";
    public static final String STAFF_MODE = ChatColor.RED + "You can't open lootboxes in staff or owner mode.";
    public static final String NO_ACCOUNT = ChatColor.RED + "Your account isn't loaded yet - try again in a moment.";

    private LootboxMessages() {
    }

    /** What a refused claim tells the player. {@code categoryName} names the box's category for a per-type limit. */
    public static String rejection(LootboxRejectedException rejected, String categoryName) {
        if (rejected.isDailyLimit()) {
            String count = rejected.limit() != null ? String.valueOf(rejected.limit()) : "your";
            boolean perType = LootboxRejectedException.SCOPE_TYPE.equalsIgnoreCase(rejected.scope());
            return ChatColor.RED + "You've opened " + count + " " + (perType && categoryName != null ? categoryName + " " : "")
                    + "lootboxes today — the limit resets at 00:00 UTC.";
        }
        String code = rejected.code() == null ? "" : rejected.code();
        return switch (code) {
            case LootboxRejectedException.ALREADY_CLAIMED -> ChatColor.RED + "Someone else got there first.";
            case LootboxRejectedException.EXPIRED, LootboxRejectedException.REMOVED, LootboxRejectedException.TOKEN_MISMATCH ->
                    ChatColor.RED + "This lootbox has crumbled away.";
            case LootboxRejectedException.DISABLED -> ChatColor.RED + "Lootboxes are switched off right now.";
            case LootboxRejectedException.FROZEN -> ChatColor.RED + "You can't open lootboxes while frozen.";
            case LootboxRejectedException.USER_INACTIVE -> ChatColor.RED + "Your account can't open lootboxes.";
            case LootboxRejectedException.EMPTY_POOL -> ChatColor.RED + "This lootbox is empty - please tell a staff member.";
            default -> STUCK;
        };
    }

    /** Whether the refusal means the box is gone for good (take it down locally). */
    public static boolean boxIsGone(LootboxRejectedException rejected) {
        return rejected.is(LootboxRejectedException.ALREADY_CLAIMED)
                || rejected.is(LootboxRejectedException.EXPIRED)
                || rejected.is(LootboxRejectedException.REMOVED)
                || rejected.is(LootboxRejectedException.TOKEN_MISMATCH);
    }

    /** The category a type's per-type limit names, from the runtime config. */
    public static String categoryOf(KnkLootboxRuntimeConfig config, int typeId) {
        return config.typeById(typeId).map(t -> t.categoryName()).orElse(null);
    }
}
