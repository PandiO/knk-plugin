package net.knightsandkings.knk.core.duel.view;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaSpectatorArea;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code arena} root of {@code arena_main} (DESIGN §9.3): the arena's info, its queue and battleground use, the
 * spectate and challenge tiles, the viewer's pending claims and the arena's rules (allowed gear modes, fee, limits).
 */
public final class ArenaMenuView {

    private final KnkArena arena;
    private final DuelHudView.QueueInfo queue;
    private final int liveMatches;
    private final int pendingClaims;
    private final boolean viewerInside;

    private ArenaMenuView(KnkArena arena, DuelHudView.QueueInfo queue, int liveMatches, int pendingClaims,
                          boolean viewerInside) {
        this.arena = arena;
        this.queue = queue == null ? DuelHudView.QueueInfo.NONE : queue;
        this.liveMatches = liveMatches;
        this.pendingClaims = pendingClaims;
        this.viewerInside = viewerInside;
    }

    /**
     * @param arena         null when there is no arena (yet)
     * @param pendingClaims the viewer's unclaimed deliveries ({@code /duel claim})
     * @param viewerInside  the viewer stands inside the arena's region (challenging is only offered there)
     */
    public static ArenaMenuView of(KnkArena arena, DuelHudView.QueueInfo queue, int liveMatches, int pendingClaims,
                                   boolean viewerInside) {
        return new ArenaMenuView(arena, queue, liveMatches, pendingClaims, viewerInside);
    }

    /** The first spectator area with a warp, or null. */
    public static KnkArenaSpectatorArea spectatorWarp(KnkArena arena) {
        if (arena == null) return null;
        return arena.spectatorAreas().stream().filter(KnkArenaSpectatorArea::teleportEnabled).findFirst().orElse(null);
    }

    public String getKnown() {
        return String.valueOf(arena != null);
    }

    public String getArenaId() {
        return arena == null ? "0" : String.valueOf(arena.id());
    }

    public String getName() {
        return arena == null ? "&cNo arena" : "&6" + displayName(arena);
    }

    public List<String> getInfoLines() {
        if (arena == null) return List.of("&7There is no arena yet.");
        List<String> lines = new ArrayList<>();
        lines.add(arena.duelEnabled() ? "&aDuels are open." : "&cDuels are closed here right now.");
        lines.add("&7Battlegrounds: &f" + arena.usableBattlegrounds().size());
        lines.add("&7Live duels: &f" + liveMatches);
        if (!arena.spectatorAreas().isEmpty()) {
            lines.add("&7Spectator area: &f" + arena.spectatorAreas().getFirst().name());
        }
        lines.add("");
        lines.add("&7Challenge a player with &f/duel <player>");
        lines.add("&7while you stand in the arena.");
        return lines;
    }

    public List<String> getQueueLines() {
        return List.of(
                "&7Waiting: &f" + queue.queued() + "/" + queue.maxQueued(),
                "&7Battlegrounds busy: &f" + queue.busy() + "/" + queue.total());
    }

    public String getQueueAmount() {
        return String.valueOf(Math.max(1, Math.min(64, queue.queued())));
    }

    public List<String> getSpectateLines() {
        if (arena == null) return List.of("&7There is no arena yet.");
        KnkArenaSpectatorArea warp = spectatorWarp(arena);
        if (warp != null) {
            return List.of("&7Watch the duels from", "&f" + warp.name() + "&7.", "",
                    "&7Uses the warp: its title, premium,", "&7discovery and gem price apply.", "",
                    "&eClick to teleport");
        }
        if (!arena.spectatorAreas().isEmpty()) {
            return List.of("&7Walk to &f" + arena.spectatorAreas().getFirst().name() + " &7to watch.",
                    "&8It has no warp.");
        }
        return List.of("&7This arena has no spectator area.");
    }

    public String getSpectateDisplayMode() {
        return spectatorWarp(arena) != null ? "NORMAL" : "DISABLED";
    }

    public List<String> getChallengeLines() {
        if (arena == null) return List.of("&7There is no arena yet.");
        if (!arena.duelEnabled()) return List.of("&cDuels are closed here right now.");
        if (!viewerInside) return List.of("&7Stand inside the arena", "&7to challenge a player.");
        return List.of("&7Pick an online player and", "&7propose the duel's terms.", "", "&eClick to choose");
    }

    public String getChallengeDisplayMode() {
        return arena != null && arena.duelEnabled() && viewerInside ? "NORMAL" : "DISABLED";
    }

    public List<String> getClaimsLines() {
        if (pendingClaims <= 0) return List.of("&7Nothing to collect.");
        return List.of("&7Waiting for you: &f" + pendingClaims + (pendingClaims == 1 ? " delivery" : " deliveries"),
                "", "&eClick to collect");
    }

    public String getClaimsDisplayMode() {
        return pendingClaims > 0 ? "HIGHLIGHT" : "NORMAL";
    }

    /** The rules a challenger needs (DESIGN §4.2): gear modes, stakes, fee, limits, timing. */
    public List<String> getRulesLines() {
        if (arena == null) return List.of("&7There is no arena yet.");
        List<String> lines = new ArrayList<>();
        lines.add("&7Gear: &f" + gearModes(arena));
        lines.add("&7Offers per negotiation: &f" + arena.maxOffers() + " &7(" + arena.offerSeconds() + " s each)");
        lines.add(arena.allowCoinStakes()
                ? "&7Coin stakes: &fup to " + DuelViewText.coins(arena.maxCoinStake()) + " a side"
                : "&7Coin stakes: &coff");
        lines.add(arena.allowItemStakes()
                ? "&7Item stakes: &fup to " + arena.maxItemStacks() + " stacks a side"
                : "&7Item stakes: &coff");
        if (arena.feePercent() > 0) lines.add("&7Fee: &f" + arena.feePercent() + "% &7of the coins, paid by the winner");
        if (arena.minStakeRatioPercent() > 0) {
            lines.add("&7Stakes must be close: the smaller one");
            lines.add("&7at least &f" + arena.minStakeRatioPercent() + "% &7of the larger");
        }
        lines.add("&7Same opponent: &f" + arena.maxStakedDuelsPerPairPerDay() + " &7staked duels a day, &f"
                + arena.pairCooldownMinutes() + " min &7apart");
        if (arena.dailyCoinWinCap() > 0) {
            lines.add("&7Daily winnings cap: &f" + DuelViewText.coins(arena.dailyCoinWinCap()) + " coins");
        }
        lines.add("&7Warmup &f" + arena.warmupSeconds() + " s&7, countdown &f" + arena.countdownSeconds() + " s");
        lines.add(arena.fightTimeLimitSeconds() > 0
                ? "&7Time limit: &f" + DuelViewText.clock(arena.fightTimeLimitSeconds()) + " &7(then a draw)"
                : "&7Time limit: &fnone");
        return lines;
    }

    static String gearModes(KnkArena arena) {
        List<String> modes = new ArrayList<>();
        if (arena.allowOwnGearKeep()) modes.add("own gear");
        if (arena.allowOwnGearDrop()) modes.add("own gear, loser drops all");
        if (arena.allowFixedKit()) modes.add("fixed kit");
        if (arena.allowKitChoice()) modes.add("kit choice");
        return modes.isEmpty() ? "none" : String.join(", ", modes);
    }

    public static String displayName(KnkArena arena) {
        if (arena.name() != null && !arena.name().isBlank()) return arena.name();
        return arena.key() != null ? arena.key() : "Arena #" + arena.id();
    }
}
