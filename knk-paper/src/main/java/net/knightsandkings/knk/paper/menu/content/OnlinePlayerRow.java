package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.domain.users.UserListItem;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.menu.MenuRowKey;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One row of the Player manager list (CONTENT_PORT_PLAN.md CP8): a player the viewer may edit.
 * Normally an online player the viewer outranks, from the plugin's user cache; with
 * {@code knk.admin.user.manage.all} (menu follow-up 2026-09-26) any account from the paged
 * {@code GET /api/users} search - offline players and the viewer included - showing the cached
 * summary when there is one, else what the search returned. v1's kills/deaths/skill points/houses
 * have no v3 equivalent and are dropped. {@link #none()} is the disabled "nobody to manage" row.
 */
public final class OnlinePlayerRow implements MenuRowKey {

    private final UserSummary user;
    private final UserListItem listItem;
    private final boolean online;
    private final boolean self;

    private OnlinePlayerRow(UserSummary user, UserListItem listItem, boolean online, boolean self) {
        this.user = user;
        this.listItem = listItem;
        this.online = online;
        this.self = self;
    }

    static OnlinePlayerRow of(UserSummary user) {
        return new OnlinePlayerRow(user, null, true, false);
    }

    /** A search result; {@code cached} is the richer cached summary when the plugin has one. */
    static OnlinePlayerRow of(UserListItem item, UserSummary cached, boolean online, boolean self) {
        return new OnlinePlayerRow(cached, item, online, self);
    }

    static OnlinePlayerRow none() {
        return new OnlinePlayerRow(null, null, false, false);
    }

    private boolean isNone() {
        return user == null && listItem == null;
    }

    public int getUserId() {
        if (user != null && user.id() != null) {
            return user.id();
        }
        return listItem != null && listItem.id() != null ? listItem.id() : 0;
    }

    /** The raw username - the editor's {@code ctx.name} (read back by name). */
    public String getName() {
        if (user != null) {
            return user.username();
        }
        return listItem != null ? listItem.username() : "&7No players you can manage are online";
    }

    /** The item name: the username with an online dot and "(you)". */
    public String getDisplayName() {
        if (isNone()) {
            return getName();
        }
        return (online ? "&a● &f" : "&7● &f") + getName() + (self ? " &d(you)" : "");
    }

    public UUID getUuid() {
        if (user != null) {
            return user.uuid();
        }
        return listItem != null ? listItem.uuid() : null;
    }

    public String getMaterial() {
        return isNone() ? "BARRIER" : "PLAYER_HEAD";
    }

    public String getDisplayMode() {
        return isNone() ? "DISABLED" : "NORMAL";
    }

    public boolean isOnline() {
        return online;
    }

    public List<String> getLoreLines() {
        List<String> lines = new ArrayList<>();
        if (isNone()) {
            lines.add("&7Only players ranked below you are listed.");
            return lines;
        }
        lines.add(online ? "&aOnline" : "&7Offline");
        if (user != null) {
            lines.add("&7Title: &f" + (user.titleName() != null ? user.titleName() : "-"));
            lines.add("&7Coins: &f" + user.coins() + " &7Gems: &f" + user.gems());
            lines.add("&7Experience: &f" + user.experiencePoints());
            lines.add("&7Premium tier: &f" + (user.premiumTierName() != null ? user.premiumTierName() : "none"));
            lines.add("&7Mode: &f" + TargetUserView.modeName(user.activeMode()));
            if (user.isFrozen()) {
                lines.add("&cFrozen");
            }
        } else {
            lines.add("&7Coins: &f" + (listItem.coins() != null ? listItem.coins() : 0));
            if (listItem.uuid() == null) {
                lines.add("&8No Minecraft account linked");
            }
        }
        lines.add("");
        lines.add("&eClick to edit");
        return lines;
    }

    @Override
    public Object menuRowKey() {
        if (isNone()) {
            return "none";
        }
        return List.of(user != null ? user : listItem, online, self);
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
