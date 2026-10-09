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
 * @param joinAtLastLocation the group's members join where they logged out, like owners (round 4); it is
 *                           their spawn override instead of {@code joinSpawnReference}, and {@code /spawn}
 *                           still uses the server spawn
 */
public record KnkGroupOverride(int permissionGroupId, String groupName, int precedence, String joinAnnouncement,
                               String leaveAnnouncement, KnkSpawnReference joinSpawnReference,
                               KnkRespawnPolicy respawnPolicy, boolean joinAtLastLocation) {

    /** Without "join where they logged out" (round 3 shape). */
    public KnkGroupOverride(int permissionGroupId, String groupName, int precedence, String joinAnnouncement,
                            String leaveAnnouncement, KnkSpawnReference joinSpawnReference,
                            KnkRespawnPolicy respawnPolicy) {
        this(permissionGroupId, groupName, precedence, joinAnnouncement, leaveAnnouncement, joinSpawnReference,
            respawnPolicy, false);
    }

    /** Without a leave message override (round 2 shape). */
    public KnkGroupOverride(int permissionGroupId, String groupName, int precedence, String joinAnnouncement,
                            KnkSpawnReference joinSpawnReference, KnkRespawnPolicy respawnPolicy) {
        this(permissionGroupId, groupName, precedence, joinAnnouncement, null, joinSpawnReference, respawnPolicy, false);
    }

    /** Whether this group overrides the join spawn: a chosen spot, or where the player logged out. */
    public boolean overridesJoinSpawn() {
        return joinAtLastLocation || joinSpawnReference != null;
    }
}
