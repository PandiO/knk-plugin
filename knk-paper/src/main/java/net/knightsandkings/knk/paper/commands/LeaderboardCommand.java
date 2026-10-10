package net.knightsandkings.knk.paper.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.leaderboards.LeaderboardBoard;
import net.knightsandkings.knk.core.domain.leaderboards.LeaderboardView;
import net.knightsandkings.knk.core.ports.api.LeaderboardsApi;
import net.knightsandkings.knk.core.statistics.StatisticsLines;

/**
 * {@code /leaderboard [board] [weekly|monthly|lifetime]}, alias {@code /lb} (KNG-34, DESIGN.md §F.11).
 * No permission node.
 * <ul>
 *   <li>No board: a player gets the {@code statistics.leaderboards} menu (when menus are available),
 *   the console the board keys.</li>
 *   <li>A board: a player gets that board's {@code statistics.leaderboard} menu on the period
 *   (default weekly); without menus, for an unknown board and for the console, the top 10 in chat
 *   plus the viewer's own rank. The console reads as an anonymous visitor, so only always-public
 *   boards answer there.</li>
 * </ul>
 */
public final class LeaderboardCommand implements TabExecutor {

    private static final Logger LOGGER = Logger.getLogger(LeaderboardCommand.class.getName());
    static final List<String> PERIODS = List.of("weekly", "monthly", "lifetime");
    static final int TOP = 10;

    private final LeaderboardsApi api;
    private final Supplier<java.util.concurrent.CompletableFuture<List<LeaderboardBoard>>> boards;
    private final Supplier<List<LeaderboardBoard>> cachedBoards;
    private final Function<UUID, Integer> userIdOf;
    private final Executor mainThread;
    /** Opens the board list menu; null when menus aren't available. */
    private volatile Consumer<Player> menuOpener;
    /** Opens one board's menu; null when menus aren't available. */
    private volatile BoardMenuOpener boardMenuOpener;

    /** Opens {@code statistics.leaderboard} on a board and period; false when it couldn't (no menus). */
    @FunctionalInterface
    public interface BoardMenuOpener {
        boolean open(Player player, String boardKey, String period);
    }

    public LeaderboardCommand(LeaderboardsApi api, Supplier<java.util.concurrent.CompletableFuture<List<LeaderboardBoard>>> boards,
                              Supplier<List<LeaderboardBoard>> cachedBoards, Function<UUID, Integer> userIdOf, Executor mainThread) {
        this.api = api;
        this.boards = boards;
        this.cachedBoards = cachedBoards;
        this.userIdOf = userIdOf;
        this.mainThread = mainThread;
    }

    public void setMenuOpener(Consumer<Player> opener) {
        this.menuOpener = opener;
    }

    public void setBoardMenuOpener(BoardMenuOpener opener) {
        this.boardMenuOpener = opener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " [board] [weekly|monthly|lifetime]");
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player player && menuOpener != null) {
                menuOpener.accept(player);
            } else {
                listBoards(sender, label);
            }
            return true;
        }
        String period = args.length == 2 ? args[1].toLowerCase(Locale.ROOT) : "weekly";
        if (!PERIODS.contains(period)) {
            sender.sendMessage(ChatColor.RED + "Period must be weekly, monthly or lifetime.");
            return true;
        }
        String board = args[0];
        // Smoke test 2026-10-10 (developer): a player gets the board's menu, like plain /lb gets the list.
        BoardMenuOpener opener = boardMenuOpener;
        if (sender instanceof Player player && opener != null) {
            String known = knownBoard(board);
            if (known != null && opener.open(player, known, period)) {
                return true;
            }
        }
        Integer viewer = sender instanceof Player player ? userIdOf.apply(player.getUniqueId()) : null;
        if (sender instanceof Player && viewer == null) {
            sender.sendMessage(ChatColor.RED + "Your account isn't loaded yet - try again in a moment.");
            return true;
        }
        api.getBoard(board, period, TOP, viewer).whenComplete((view, error) -> mainThread.execute(() -> {
            if (error != null || view == null) {
                int status = net.knightsandkings.knk.paper.menu.content.StatisticsMenuFeature.status(error);
                if (status == 404) {
                    sender.sendMessage(ChatColor.RED + "No leaderboard '" + board + "'. " + ChatColor.GRAY + "/" + label + " lists them.");
                } else if (status == 401) {
                    sender.sendMessage(ChatColor.RED + "Only players can see that leaderboard.");
                } else {
                    LOGGER.log(Level.WARNING, "/leaderboard " + board + " failed", error);
                    sender.sendMessage(ChatColor.RED + "Couldn't load the leaderboard - try again later.");
                }
                return;
            }
            for (String line : lines(view, viewer != null)) {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
            }
        }));
        return true;
    }

    /** The board key as the API names it, or null when it isn't a known board (the chat path explains). */
    private String knownBoard(String board) {
        for (LeaderboardBoard known : cachedBoards.get()) {
            if (known.boardKey().equalsIgnoreCase(board)) {
                return known.boardKey();
            }
        }
        return null;
    }

    private void listBoards(CommandSender sender, String label) {
        boards.get().whenComplete((list, error) -> mainThread.execute(() -> {
            if (error != null || list == null) {
                sender.sendMessage(ChatColor.RED + "Couldn't load the leaderboards - try again later.");
                return;
            }
            sender.sendMessage(ChatColor.GOLD + "--- Leaderboards --- " + ChatColor.GRAY + "/" + label + " <board> [weekly|monthly|lifetime]");
            for (LeaderboardBoard board : list) {
                sender.sendMessage(ChatColor.WHITE + board.boardKey() + ChatColor.GRAY + " - " + board.label());
            }
        }));
    }

    /** The chat lines of one board: header, top entries, the viewer's position. */
    static List<String> lines(LeaderboardView view, boolean player) {
        List<String> lines = new ArrayList<>();
        String period = switch (view.period() == null ? "" : view.period()) {
            case "weekly" -> "this week";
            case "monthly" -> "this month";
            default -> "all time";
        };
        lines.add("&6--- " + view.label() + " &7(" + period + ") &6---");
        String metric = view.boardKey() == null ? "" : view.boardKey().split("@", 2)[0];
        if (view.entries().isEmpty()) {
            lines.add(view.generatedAt() == null ? "&7Not computed yet - check back in a few minutes." : "&7Nobody is ranked yet.");
        }
        for (LeaderboardView.Entry entry : view.entries()) {
            lines.add("&e#" + entry.rank() + " &f" + entry.username() + " &7- &f" + StatisticsLines.format(metric, view.unit(), entry.value()));
        }
        if (player) {
            lines.add(view.viewer() != null
                    ? "&7You: &e#" + view.viewer().rank() + " &7of " + view.totalRanked() + " - &f"
                            + StatisticsLines.format(metric, view.unit(), view.viewer().value())
                    : "&8You're not ranked here (only players who show this statistic to everyone rank).");
        }
        return lines;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return cachedBoards.get().stream().map(LeaderboardBoard::boardKey).filter(k -> k.startsWith(prefix)).toList();
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return PERIODS.stream().filter(p -> p.startsWith(prefix)).toList();
        }
        return Collections.emptyList();
    }
}
