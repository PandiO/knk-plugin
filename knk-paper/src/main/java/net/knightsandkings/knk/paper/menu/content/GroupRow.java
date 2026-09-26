package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.domain.permissions.PermissionGroupSummary;
import net.knightsandkings.knk.core.menu.MenuRowKey;

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
        return group.isPremiumTier() ? "GOLD_BLOCK" : (member ? "ENCHANTED_BOOK" : "BOOK");
    }

    public String getDisplayMode() {
        return member ? "HIGHLIGHT" : "NORMAL";
    }

    public List<String> getLoreLines() {
        List<String> lines = new ArrayList<>();
        lines.add("&7Weight: &f" + group.weight());
        if (group.isPremiumTier()) {
            lines.add("&6Premium tier");
        }
        lines.add(member ? "&aMember &7- click to remove" : "&7Not a member &7- click to add");
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
