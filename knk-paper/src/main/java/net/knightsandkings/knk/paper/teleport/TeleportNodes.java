package net.knightsandkings.knk.paper.teleport;

/**
 * Permission nodes of the teleport feature (docs/specs/teleport/DESIGN.md §3.3). All resolve
 * through {@code KnkPermissible} (the REST-backed permission model; ops pass); the plugin.yml
 * declarations are documentation only, like {@code knk.kit.*}.
 */
public final class TeleportNodes {

    /** {@code /tp <player>}, {@code /tp <x> <y> <z>}. */
    public static final String STAFF = "knk.teleport.staff";
    /** Move other players: {@code /tp <a> <b>}, {@code /tphere}, {@code /spawn <player>}. */
    public static final String STAFF_OTHERS = "knk.teleport.staff.others";
    /** The {@code -s} flag (no message to the moved/visited player). */
    public static final String STAFF_SILENT = "knk.teleport.staff.silent";
    /** The pre-teleport-feature node for {@code /knk tp}, still accepted for {@code /tp <player>}. */
    public static final String LEGACY_ADMIN_TP = "knk.admin.tp";

    /** {@code /tpa <player>} - ask to go to a player (granted to the Default group). */
    public static final String REQUEST = "knk.teleport.request";
    /** {@code /tpahere <player>} - ask a player to come to you (Dragon Blood, developer decision Q2). */
    public static final String REQUEST_HERE = "knk.teleport.request.here";

    /** {@code /spawn} - to the server spawn (granted to the Default group). */
    public static final String SPAWN = "knk.teleport.spawn";

    /** 3 s instead of 5 s warmup (v1: any donator rank). */
    public static final String WARMUP_SHORT = "knk.teleport.warmup.short";
    public static final String BYPASS_WARMUP = "knk.teleport.bypass.warmup";
    public static final String BYPASS_COOLDOWN = "knk.teleport.bypass.cooldown";
    public static final String BYPASS_COMBAT = "knk.teleport.bypass.combat";

    /** Skip Domain AllowEntry/AllowExit denials, walking and teleporting (DESIGN §4 D11). */
    public static final String REGION_BYPASS = "knk.region.bypass";

    private TeleportNodes() {
    }
}
