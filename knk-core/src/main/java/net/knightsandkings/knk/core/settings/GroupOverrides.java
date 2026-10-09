package net.knightsandkings.knk.core.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkGroupOverride;
import net.knightsandkings.knk.core.domain.settings.KnkRespawnPolicy;
import net.knightsandkings.knk.core.domain.settings.KnkSpawnReference;
import net.knightsandkings.knk.core.domain.users.PermissionGroupRef;

/**
 * Which of a player's groups supplies each Game Settings override (KNG-52,
 * docs/specs/game-settings/DESIGN.md §3.8). The player's groups arrive from the API already in
 * precedence order (the teleport fee order, KNG-41: weight first, each group followed by its parents); each setting is decided on its own - the first
 * group with an override for <em>that</em> setting wins, so one group can set the join message and
 * another the spawn.
 */
public final class GroupOverrides {

    /** An override and the group it came from. */
    public record Pick<T>(T value, PermissionGroupRef group) {
    }

    private GroupOverrides() {
    }

    public static Optional<Pick<String>> joinAnnouncement(KnkGameSettings settings, List<PermissionGroupRef> groups) {
        return first(settings, groups, KnkGroupOverride::joinAnnouncement);
    }

    public static Optional<Pick<String>> leaveAnnouncement(KnkGameSettings settings, List<PermissionGroupRef> groups) {
        return first(settings, groups, KnkGroupOverride::leaveAnnouncement);
    }

    /**
     * The spot of the player's first group that overrides the join spawn - empty when that group has
     * its members join where they logged out ({@link #joinsAtLastLocation}), or when no group overrides it.
     */
    public static Optional<Pick<KnkSpawnReference>> joinSpawn(KnkGameSettings settings, List<PermissionGroupRef> groups) {
        return joinSpawnOverride(settings, groups)
            .filter(pick -> !pick.value().joinAtLastLocation())
            .map(pick -> new Pick<>(pick.value().joinSpawnReference(), pick.group()));
    }

    /** Whether the player's first group that overrides the join spawn has them join where they logged out (round 4). */
    public static boolean joinsAtLastLocation(KnkGameSettings settings, List<PermissionGroupRef> groups) {
        return joinSpawnOverride(settings, groups).map(pick -> pick.value().joinAtLastLocation()).orElse(false);
    }

    /** A chosen spot and "where they logged out" are one setting: the first group with either wins. */
    private static Optional<Pick<KnkGroupOverride>> joinSpawnOverride(KnkGameSettings settings, List<PermissionGroupRef> groups) {
        return first(settings, groups, o -> o.overridesJoinSpawn() ? o : null);
    }

    public static Optional<Pick<KnkRespawnPolicy>> respawnPolicy(KnkGameSettings settings, List<PermissionGroupRef> groups) {
        return first(settings, groups, KnkGroupOverride::respawnPolicy);
    }

    /** The name {@code {group}} shows when no group supplied the text: the player's first group, else blank. */
    public static String primaryGroupName(List<PermissionGroupRef> groups) {
        if (groups == null) {
            return "";
        }
        return groups.stream().filter(Objects::nonNull).map(PermissionGroupRef::name).filter(Objects::nonNull)
            .findFirst().orElse("");
    }

    private static <T> Optional<Pick<T>> first(KnkGameSettings settings, List<PermissionGroupRef> groups,
                                               Function<KnkGroupOverride, T> field) {
        if (settings == null || groups == null || settings.groupOverrides().isEmpty()) {
            return Optional.empty();
        }
        for (PermissionGroupRef group : groups) {
            if (group == null) {
                continue;
            }
            Optional<T> value = settings.groupOverride(group.id()).map(field);
            if (value.isPresent()) {
                return Optional.of(new Pick<>(value.get(), group));
            }
        }
        return Optional.empty();
    }
}
