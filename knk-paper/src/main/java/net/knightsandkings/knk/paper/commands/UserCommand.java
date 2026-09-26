package net.knightsandkings.knk.paper.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.TitleBracketsDataAccess;
import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.TitleBracket;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.UsersQueryApi;
import net.knightsandkings.knk.paper.menu.content.ProfileView;

/**
 * {@code /user statistics|stats [player]} - port of v2's {@code /user statistics}
 * (docs/specs/legacy/commands-v2.md §1, KNG-9). Open to everyone, no permission node.
 * <ul>
 *   <li>Your own statistics (or anyone's, from the console): title, place on the title ladder and
 *       progress to the next title, coins, gems, XP, prestige XP, premium tier, account type and
 *       staff/owner mode.</li>
 *   <li>Another player's: only the public part - title, ladder place, XP, prestige XP and premium
 *       tier. Balances and account details stay private; staff use {@code /knk user <player> info}.</li>
 * </ul>
 * The numbers come from {@link ProfileView}, the same view the Profile menu renders, over a fresh
 * API read (cache fallback) - so command and menu never disagree.
 * <p>
 * v2's {@code UserStatistics} was a different thing: ~20 gameplay counters (kills, deaths, siege
 * wins, damage dealt, distance travelled, ...). v3 doesn't track any of those yet; that gap is its
 * own issue (KNG-14), and this command should grow those lines once the data exists.
 */
public class UserCommand implements TabExecutor {

    private static final Logger LOGGER = Logger.getLogger(UserCommand.class.getName());
    private static final List<String> SUBCOMMANDS = List.of("statistics", "stats");

    private final Executor mainThread;
    private final UsersQueryApi usersQueryApi;
    private final UsersDataAccess usersDataAccess;
    private final UserCache userCache;
    private final TitleBracketsDataAccess titleBrackets;
    private final Supplier<List<String>> onlinePlayerNames;

    public UserCommand(Executor mainThread, UsersQueryApi usersQueryApi, UsersDataAccess usersDataAccess,
                       UserCache userCache, TitleBracketsDataAccess titleBrackets,
                       Supplier<List<String>> onlinePlayerNames) {
        this.mainThread = mainThread;
        this.usersQueryApi = usersQueryApi;
        this.usersDataAccess = usersDataAccess;
        this.userCache = userCache;
        this.titleBrackets = titleBrackets;
        this.onlinePlayerNames = onlinePlayerNames;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !SUBCOMMANDS.contains(args[0].toLowerCase(Locale.ROOT)) || args.length > 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /user statistics [player]" + ChatColor.GRAY + " (alias: /user stats)");
            return true;
        }

        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.YELLOW + "Usage from the console: /user statistics <player>");
                return true;
            }
            showOwn(sender, player.getUniqueId());
            return true;
        }

        String targetName = args[1];
        if (sender instanceof Player player && player.getName().equalsIgnoreCase(targetName)) {
            showOwn(sender, player.getUniqueId());
            return true;
        }
        showOther(sender, targetName, !(sender instanceof Player));
        return true;
    }

    private void showOwn(CommandSender sender, UUID uuid) {
        CompletableFuture<UserSummary> user = usersQueryApi.getByUuid(uuid)
                .exceptionally(ex -> {
                    LOGGER.log(Level.FINE, "user stats: fresh read failed, using the cache", ex);
                    return null;
                })
                .thenApply(fresh -> fresh != null ? fresh : userCache.getStale(uuid).orElse(null));
        user.thenCombine(brackets(), (summary, brackets) -> {
            mainThread.execute(() -> {
                if (summary == null) {
                    sender.sendMessage(ChatColor.RED + "Your account isn't loaded yet - try again in a moment.");
                    return;
                }
                send(sender, statisticsLines(summary, brackets, true));
            });
            return null;
        });
    }

    private void showOther(CommandSender sender, String targetName, boolean full) {
        usersDataAccess.getByUsernameAsync(targetName)
                .thenCombine(brackets(), (result, brackets) -> {
                    mainThread.execute(() -> {
                        if (result == null || !result.isSuccess() || result.value().isEmpty()) {
                            sender.sendMessage(ChatColor.RED + "No player found named '" + targetName + "'.");
                            return;
                        }
                        send(sender, statisticsLines(result.value().get(), brackets, full));
                    });
                    return null;
                })
                .exceptionally(ex -> {
                    mainThread.execute(() -> sender.sendMessage(ChatColor.RED + "Failed to look up '" + targetName + "'."));
                    return null;
                });
    }

    /** Title brackets for the ladder lines; the cached list (possibly empty) if the read fails. */
    private CompletableFuture<List<TitleBracket>> brackets() {
        return titleBrackets.listAsync()
                .exceptionally(ex -> {
                    LOGGER.log(Level.FINE, "user stats: couldn't load title brackets", ex);
                    return titleBrackets.cachedOrEmpty();
                });
    }

    private static void send(CommandSender sender, List<String> lines) {
        for (String line : lines) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
        }
    }

    /**
     * The lines {@code /user statistics} prints, with {@code &} colour codes. {@code full} adds the
     * private part: balances, account type and staff/owner mode.
     */
    static List<String> statisticsLines(UserSummary user, List<TitleBracket> brackets, boolean full) {
        ProfileView view = ProfileView.of(user, brackets);
        List<String> lines = new ArrayList<>();
        lines.add("&6--- Statistics: &f" + user.username() + " &6---");
        if (full) {
            lines.addAll(view.getQuickStatsLines());
            lines.add("&7Account: &f" + (user.isFullAccount() ? "web account linked" : "Minecraft only &7(/account create)"));
            if (user.activeMode() != null && user.activeMode() != ActiveMode.NONE) {
                lines.add("&7Mode: &f" + user.activeMode().toWireValue());
            }
            return lines;
        }
        lines.add(view.getTitleLine());
        addIfPresent(lines, view.getTitleRankLine());
        lines.add("&7Experience: &f" + user.experiencePoints());
        addIfPresent(lines, view.getPrestigeLine());
        addIfPresent(lines, view.getPremiumLine());
        return lines;
    }

    private static void addIfPresent(List<String> lines, String line) {
        if (line != null) {
            lines.add(line);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && SUBCOMMANDS.contains(args[0].toLowerCase(Locale.ROOT))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return onlinePlayerNames.get().stream()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
        return Collections.emptyList();
    }
}
