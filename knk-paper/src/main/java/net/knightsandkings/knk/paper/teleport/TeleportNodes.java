package net.knightsandkings.knk.paper.teleport;

import net.knightsandkings.knk.core.teleport.BackKind;

/**
 * Permission nodes of the teleport feature (docs/specs/teleport/DESIGN.md §3.3). All resolve
 * through {@code KnkPermissible} (the REST-backed permission model; ops pass); the plugin.yml
 * declarations are documentation only, like {@code knk.kit.*}.
 */
public final class TeleportNodes {

    /** {@code /tp <player>}, {@code /tp <x> <y> <z>}. */
    public static final String STAFF = "knk.teleport.staff";
    /** Move other players: {@code /tp <a> <b>}, {@code /tphere}, {@code /spawn <player>}, {@code /warp <d> <player>}. */
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

    /** {@code /warp <destination>}, {@code /warps} - domain teleports (granted to the Default group). */
    public static final String WARP = "knk.teleport.warp";
    /** Warp without meeting a destination's title / premium tier / discovery requirements. */
    public static final String BYPASS_REQUIREMENTS = "knk.teleport.bypass.requirements";
    /** Warp without paying a destination's gem price. */
    public static final String BYPASS_COST = "knk.teleport.bypass.cost";

    /** {@code /back} - to where you last died, for a few minutes (Dragon Blood, developer decision Q5). */
    public static final String BACK = "knk.teleport.back";
    /** {@code /back} - to where you were before your last {@code /warp} or menu warp (KNG-42). */
    public static final String BACK_WARPS = "knk.teleport.back.warps";
    /** {@code /back} - to where you were before your last {@code /tpa}/{@code /tpahere} or own staff {@code /tp} (KNG-42). */
    public static final String BACK_TELEPORT = "knk.teleport.back.teleport";
    /** {@code /back} - to where you were before your last {@code /spawn} (KNG-42). */
    public static final String BACK_SPAWN = "knk.teleport.back.spawn";
    /**
     * {@code /back} for every {@link BackKind} (KNG-42). Granting {@code knk.teleport.back.*} works too
     * (the API's wildcard matching covers every kind's node).
     */
    public static final String BACK_ALL = "knk.teleport.back.all";
    /** {@code /back <player> [-s]} - send someone back to their latest {@code /back} place (KNG-42). */
    public static final String STAFF_BACK_OTHERS = "knk.teleport.staff.back.others";

    /** 3 s instead of 5 s warmup (v1: any donator rank). */
    public static final String WARMUP_SHORT = "knk.teleport.warmup.short";
    public static final String BYPASS_WARMUP = "knk.teleport.bypass.warmup";
    public static final String BYPASS_COOLDOWN = "knk.teleport.bypass.cooldown";
    public static final String BYPASS_COMBAT = "knk.teleport.bypass.combat";

    /** Skip Domain AllowEntry/AllowExit denials, walking and teleporting (DESIGN §4 D11). */
    public static final String REGION_BYPASS = "knk.region.bypass";

    private TeleportNodes() {
    }

    /** The node that lets a player {@code /back} to a place of {@code kind} (KNG-42). */
    public static String backNode(BackKind kind) {
        return switch (kind) {
            case DEATH -> BACK;
            case WARPS -> BACK_WARPS;
            case TELEPORT -> BACK_TELEPORT;
            case SPAWN -> BACK_SPAWN;
        };
    }
}
