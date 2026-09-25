package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.menu.MenuRowKey;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One row of {@code users.online} (CONTENT_PORT_PLAN.md CP8): an online player the viewer outranks,
 * from the plugin's user cache. v1's kills/deaths/skill points/houses have no v3 equivalent and
 * are dropped. {@link #none()} is the disabled "nobody to manage" row.
 */
public final class OnlinePlayerRow implements MenuRowKey {

    private final UserSummary user;

    private OnlinePlayerRow(UserSummary user) {
        this.user = user;
    }

    static OnlinePlayerRow of(UserSummary user) {
        return new OnlinePlayerRow(user);
    }

    static OnlinePlayerRow none() {
        return new OnlinePlayerRow(null);
    }

    public int getUserId() {
        return user != null && user.id() != null ? user.id() : 0;
    }

    public String getName() {
        return user != null ? user.username() : "&7No players you can manage are online";
    }

    public UUID getUuid() {
        return user != null ? user.uuid() : null;
    }

    public String getMaterial() {
        return user != null ? "PLAYER_HEAD" : "BARRIER";
    }

    public String getDisplayMode() {
        return user != null ? "NORMAL" : "DISABLED";
    }

    public List<String> getLoreLines() {
        List<String> lines = new ArrayList<>();
        if (user == null) {
            lines.add("&7Only players ranked below you are listed.");
            return lines;
        }
        lines.add("&7Title: &f" + (user.titleName() != null ? user.titleName() : "-"));
        lines.add("&7Coins: &f" + user.coins() + " &7Gems: &f" + user.gems());
        lines.add("&7Experience: &f" + user.experiencePoints());
        lines.add("&7Premium tier: &f" + (user.premiumTierName() != null ? user.premiumTierName() : "none"));
        lines.add("&7Mode: &f" + TargetUserView.modeName(user.activeMode()));
        if (user.isFrozen()) {
            lines.add("&cFrozen");
        }
        lines.add("");
        lines.add("&eClick to edit");
        return lines;
    }

    @Override
    public Object menuRowKey() {
        return user != null ? user : "none";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof OnlinePlayerRow other && menuRowKey().equals(other.menuRowKey());
    }

    @Override
    public int hashCode() {
        return menuRowKey().hashCode();
    }
}
