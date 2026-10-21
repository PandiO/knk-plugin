package net.knightsandkings.knk.core.duel.view;

import net.knightsandkings.knk.core.duel.DuelPhase;
import net.knightsandkings.knk.core.duel.DuelSide;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The duel sidebar and boss bar (arena duels DESIGN §9.3, §5.1 "Waiting for a free battleground (#2 in line)"), as
 * plain text with {@code &} colours. Shown to the duelists and to the players standing in the arena's spectator
 * area; the Paper {@code DuelHud} renders them.
 */
public final class DuelHudView {

    /** A sidebar holds at most 15 lines. */
    public static final int MAX_LINES = 15;
    /** Matches listed on the sidebar before "+N more". */
    static final int MAX_MATCHES = 4;

    /** The arena's queue: "2/3 waiting", "5/8 busy". */
    public record QueueInfo(int queued, int maxQueued, int busy, int total) {
        public static final QueueInfo NONE = new QueueInfo(0, 0, 0, 0);
    }

    /** A boss bar: its text, its fill (0..1) and its colour (a Bukkit/Adventure colour name). */
    public record BossBar(String text, float progress, String color) {
        public BossBar {
            progress = Math.max(0f, Math.min(1f, progress));
        }
    }

    private DuelHudView() {
    }

    public static String sidebarTitle(String arenaName) {
        return "&6&l⚔ " + (arenaName == null || arenaName.isBlank() ? "Arena" : arenaName);
    }

    /**
     * The arena's sidebar for one viewer.
     *
     * @param viewer   the viewer's id; their own match comes first
     * @param matches  the arena's running matches, oldest first; the viewer's own is shown in any phase, the others
     *                 while listed (WARMUP → ENDING)
     * @param queue    the arena's queue and battleground use
     */
    public static List<String> sidebar(UUID viewer, List<DuelMatchSnapshot> matches, QueueInfo queue) {
        List<String> lines = new ArrayList<>();
        DuelMatchSnapshot own = viewer == null ? null
                : matches.stream().filter(m -> m.involves(viewer)).findFirst().orElse(null);
        if (own != null) {
            DuelSide side = own.challenger().playerId().equals(viewer) ? DuelSide.CHALLENGER : DuelSide.OPPONENT;
            lines.add("&7Your duel: &fvs " + own.side(side.other()).name());
            lines.add("&7" + DuelViewText.phase(own.phase(), own.waitingForBattleground()) + timerSuffix(own));
            lines.add("&7Gear: &f" + DuelAnnouncements.gearOf(own));
            lines.add("&7Stakes: &f" + DuelAnnouncements.stakesOf(own));
            lines.add("");
        }
        List<DuelMatchSnapshot> others = matches.stream().filter(m -> m != own && m.isListed()).toList();
        lines.add("&7Live duels: &f" + matches.stream().filter(DuelMatchSnapshot::isListed).count());
        int shown = 0;
        for (DuelMatchSnapshot m : others) {
            if (shown == MAX_MATCHES) {
                lines.add("&8+" + (others.size() - shown) + " more (/duels)");
                break;
            }
            lines.add("&e" + m.title());
            lines.add(" &7" + DuelViewText.phase(m.phase(), m.waitingForBattleground()) + timerSuffix(m));
            shown++;
        }
        lines.add("");
        lines.add("&7Queue: &f" + queue.queued() + "/" + queue.maxQueued() + " waiting");
        lines.add("&7Battlegrounds: &f" + queue.busy() + "/" + queue.total() + " busy");
        return lines.size() > MAX_LINES ? List.copyOf(lines.subList(0, MAX_LINES)) : List.copyOf(lines);
    }

    /** The match a spectator's boss bar follows: the newest one on a battleground, else the newest listed one. */
    public static Optional<DuelMatchSnapshot> followed(List<DuelMatchSnapshot> matchesOldestFirst) {
        DuelMatchSnapshot listed = null;
        DuelMatchSnapshot fighting = null;
        for (DuelMatchSnapshot m : matchesOldestFirst) {
            if (!m.isListed()) continue;
            listed = m;
            if (m.phase() == DuelPhase.BATTLE_COUNTDOWN || m.phase() == DuelPhase.FIGHT) fighting = m;
        }
        return Optional.ofNullable(fighting != null ? fighting : listed);
    }

    /** The boss bar of {@code m}. */
    public static BossBar bossBar(DuelMatchSnapshot m) {
        String title = m.title();
        return switch (m.phase()) {
            case LOCKING -> new BossBar("&fLocking in: " + title, 1f, "WHITE");
            case TRAVELLING -> new BossBar("&fTravelling to " + m.arenaName() + ": " + title, 1f, "WHITE");
            case WARMUP -> m.waitingForBattleground()
                    ? new BossBar("&eWaiting for a free battleground"
                            + (m.queuePosition() > 0 ? " (#" + m.queuePosition() + " in line)" : ""), 1f, "YELLOW")
                    : new BossBar("&bWarmup " + DuelViewText.clock(m.secondsRemaining()) + " &7- &f" + title,
                            fill(m), "BLUE");
            case BATTLE_COUNTDOWN -> new BossBar("&cGet ready: " + m.secondsRemaining() + " &7- &f" + title, fill(m), "RED");
            case FIGHT -> new BossBar("&c" + title + " &7- &f"
                    + (m.phaseSeconds() == 0 ? "no time limit" : DuelViewText.clock(m.secondsRemaining()) + " left"),
                    m.phaseSeconds() == 0 ? 1f : fill(m), "RED");
            case ENDING -> new BossBar(endText(m), fill(m), "GREEN");
            case CLOSED -> new BossBar("&7" + title + " is over", 0f, "WHITE");
        };
    }

    private static String endText(DuelMatchSnapshot m) {
        if (m.winner() != null) {
            return "&a" + m.side(m.winner()).name() + " won" + (m.held() ? " &7(pending review)" : "!");
        }
        if (m.endReason() != null && m.endReason().isDraw()) {
            return "&f" + m.title() + ": draw";
        }
        return "&7" + m.title() + ": cancelled";
    }

    private static float fill(DuelMatchSnapshot m) {
        return m.phaseSeconds() <= 0 ? 1f : (float) m.secondsRemaining() / m.phaseSeconds();
    }

    private static String timerSuffix(DuelMatchSnapshot m) {
        String timer = DuelViewText.timer(m);
        return timer.isEmpty() ? "" : " &f" + timer;
    }
}
