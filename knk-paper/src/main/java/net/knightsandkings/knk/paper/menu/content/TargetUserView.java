package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.menu.MenuRowKey;

import java.util.ArrayList;
import java.util.List;

/**
 * The Player manager's target (CONTENT_PORT_PLAN.md CP8): both the {@code target} root and the one
 * row of {@code users.target} (the head in slot 0). Built from a fresh read of the user named by
 * {@code ctx.userId}/{@code ctx.name}; {@link #unknown} while that read hasn't happened.
 */
public final class TargetUserView implements MenuRowKey {

    private final UserSummary user;
    private final String fallbackName;

    TargetUserView(UserSummary user) {
        this.user = user;
        this.fallbackName = user != null ? user.username() : "?";
    }

    private TargetUserView(String name) {
        this.user = null;
        this.fallbackName = name == null || name.isBlank() ? "?" : name;
    }

    static TargetUserView unknown(String name) {
        return new TargetUserView(name);
    }

    UserSummary user() {
        return user;
    }

    public boolean isLoaded() {
        return user != null;
    }

    public int getUserId() {
        return user != null && user.id() != null ? user.id() : 0;
    }

    public String getName() {
        return fallbackName;
    }

    public int getCoins() {
        return user != null ? user.coins() : 0;
    }

    public int getGems() {
        return user != null ? user.gems() : 0;
    }

    public int getExperience() {
        return user != null ? user.experiencePoints() : 0;
    }

    public String getTitleName() {
        return user != null && user.titleName() != null ? user.titleName() : "-";
    }

    public String getPremiumTierName() {
        return user != null && user.premiumTierName() != null ? user.premiumTierName() : "none";
    }

    public String getMode() {
        return user != null ? modeName(user.activeMode()) : "-";
    }

    /** Where the mode toggle goes next: NONE → STAFF, anything else → NONE (owner mode is only set by its holder). */
    public String getNextMode() {
        ActiveMode mode = user != null ? user.activeMode() : ActiveMode.NONE;
        return mode == ActiveMode.NONE ? ActiveMode.STAFF.name() : ActiveMode.NONE.name();
    }

    public String getNextModeName() {
        return modeName(ActiveMode.valueOf(getNextMode()));
    }

    public boolean getIsFrozen() {
        return user != null && user.isFrozen();
    }

    public String getFreezeMaterial() {
        return getIsFrozen() ? "PACKED_ICE" : "ICE";
    }

    public String getFreezeName() {
        return getIsFrozen() ? "&bUnfreeze" : "&bFreeze";
    }

    /** Lore for the head: title, balances, premium tier, mode, frozen. */
    public List<String> getSummaryLines() {
        List<String> lines = new ArrayList<>();
        if (user == null) {
            lines.add("&cLoading...");
            return lines;
        }
        lines.add("&7Title: &f" + getTitleName());
        lines.add("&7Coins: &f" + getCoins() + " &7Gems: &f" + getGems());
        lines.add("&7Experience: &f" + getExperience());
        lines.add("&7Premium tier: &f" + getPremiumTierName());
        lines.add("&7Mode: &f" + getMode());
        if (user.isFrozen()) {
            lines.add("&cFrozen" + (user.frozenReason() != null ? ": " + user.frozenReason() : ""));
        }
        return lines;
    }

    static String modeName(ActiveMode mode) {
        return switch (mode == null ? ActiveMode.NONE : mode) {
            case OWNER -> "Owner";
            case STAFF -> "Staff";
            default -> "Normal";
        };
    }

    @Override
    public Object menuRowKey() {
        return user != null ? user : fallbackName;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TargetUserView other && menuRowKey().equals(other.menuRowKey());
    }

    @Override
    public int hashCode() {
        return menuRowKey().hashCode();
    }
}
