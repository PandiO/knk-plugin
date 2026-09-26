package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.domain.permissions.PermissionGroupSummary;
import net.knightsandkings.knk.core.menu.MenuRowKey;
import net.knightsandkings.knk.paper.user.PlayerRanks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One row of {@code users.groups} (CONTENT_PORT_PLAN.md CP8): a permission group and whether the
 * Player manager's target is an active member ({@code HIGHLIGHT}). Premium tiers are groups too.
 */
public final class GroupRow implements MenuRowKey {

    private final PermissionGroupSummary group;
    private final boolean member;

    private GroupRow(PermissionGroupSummary group, boolean member) {
        this.group = group;
        this.member = member;
    }

    static List<GroupRow> rows(List<PermissionGroupSummary> groups, Set<Integer> memberGroupIds) {
        List<GroupRow> rows = new ArrayList<>(groups.size());
        for (PermissionGroupSummary group : groups) {
            rows.add(new GroupRow(group, memberGroupIds.contains(group.id())));
        }
        return rows;
    }

    public int getGroupId() {
        return group.id();
    }

    public String getName() {
        return group.name();
    }

    public boolean getIsMember() {
        return member;
    }

    public String getMaterial() {
        if (group.isPremiumTier()) {
            return "GOLD_BLOCK";
        }
        if (PlayerRanks.isDefault(group)) {
            return "IRON_BLOCK";
        }
        return member ? "ENCHANTED_BOOK" : "BOOK";
    }

    public String getDisplayMode() {
        return member ? "HIGHLIGHT" : "NORMAL";
    }

    public List<String> getLoreLines() {
        List<String> lines = new ArrayList<>();
        lines.add("&7Weight: &f" + group.weight());
        boolean isDefault = PlayerRanks.isDefault(group);
        if (group.isPremiumTier()) {
            lines.add("&6Premium rank");
        } else if (isDefault) {
            lines.add("&7Free rank");
        }
        // Ranks (Default + premium tiers): one per player - clicking one switches to it
        // (UserManagerMenuFeature); removing a paid rank drops back to Default.
        if (!PlayerRanks.isRank(group)) {
            lines.add(member ? "&aMember &7- click to remove" : "&7Not a member &7- click to add");
        } else if (member && isDefault) {
            lines.add("&aCurrent rank &7- pick another rank to replace it");
        } else if (member) {
            lines.add("&aCurrent rank &7- click to drop back to Default");
        } else {
            lines.add("&7Click to make this their rank");
            lines.add("&8Replaces their current rank, after confirmation");
        }
        return lines;
    }

    @Override
    public Object menuRowKey() {
        return List.of(group, member);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GroupRow other && menuRowKey().equals(other.menuRowKey());
    }

    @Override
    public int hashCode() {
        return menuRowKey().hashCode();
    }
}
