package net.knightsandkings.knk.core.domain.settings;

/**
 * One PermissionGroup's overrides of the global Game Settings (knk-web-api
 * {@code PermissionGroupGameSettingsDto}, KNG-52; docs/specs/game-settings/DESIGN.md §3.8). A null
 * field means "no override": the player's next group, else the global/world setting, applies.
 *
 * @param permissionGroupId  the group
 * @param groupName          its name, for logs and {@code {group}}
 * @param precedence         1 = considered first (the API's order: the teleport fee order, KNG-41)
 * @param joinAnnouncement   join broadcast for the group's members; blank = no broadcast
 * @param leaveAnnouncement  quit broadcast for the group's members; blank = no broadcast
 * @param joinSpawnReference where the group's members join and {@code /spawn}
 * @param respawnPolicy      the group's respawn policy, in every world
 */
public record KnkGroupOverride(int permissionGroupId, String groupName, int precedence, String joinAnnouncement,
                               String leaveAnnouncement, KnkSpawnReference joinSpawnReference,
                               KnkRespawnPolicy respawnPolicy) {

    /** Without a leave message override (round 2 shape). */
    public KnkGroupOverride(int permissionGroupId, String groupName, int precedence, String joinAnnouncement,
                            KnkSpawnReference joinSpawnReference, KnkRespawnPolicy respawnPolicy) {
        this(permissionGroupId, groupName, precedence, joinAnnouncement, null, joinSpawnReference, respawnPolicy);
    }
}
