package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.discovery.DiscoverySpool;
import net.knightsandkings.knk.core.discovery.DiscoveryTracker;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryProgressRow;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySummary;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryTypeCount;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.DiscoveriesApi;
import net.knightsandkings.knk.paper.user.UserAdminService;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@code /knk discovery list <player> [page] | reset <player> <domainId> | status} (domain-discovery
 * DESIGN.md §3.6, KNG-20). Gated by {@code knk.admin.discovery}: the plugin.yml node (the
 * {@code knk.admin} umbrella grants it) or the same node granted in the web app's permission system
 * ({@code KnkPermissible}); the console always passes. The API checks it again: reset carries the
 * plugin's service key and names the staff member in {@code X-Acting-User-Id} for the audit row.
 * <ul>
 *   <li>{@code list} - the player's per-type counts, lifetime rewards and discovered places (with
 *       their domain ids, for {@code reset}), newest first, 10 per page;</li>
 *   <li>{@code reset} - forgets one discovery so the place can be discovered and rewarded again
 *       (no claw-back). An online player's known set is reloaded so it counts this session;</li>
 *   <li>{@code status} - tracker and spool sizes on this server.</li>
 * </ul>
 */
public class DiscoveryAdminCommand implements SubcommandExecutor {

    public static final String NODE = "knk.admin.discovery";
    public static final String USAGE = "/knk discovery list <player> [page] | reset <player> <domainId> | status";
    static final int PAGE_SIZE = 10;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final DiscoveriesApi discoveriesApi;
    private final UserAdminService userAdminService;
    private final Executor mainThread;
    private final Function<Player, Integer> cachedUserId;
    private final BiPredicate<Player, String> knkPermission;
    private final Supplier<DiscoveryTracker> tracker;
    private final Supplier<DiscoverySpool> spool;
    private final Function<UUID, Player> onlinePlayer;
    private final Consumer<UUID> afterReset;

    /**
     * @param cachedUserId  a player's knk user id from the plugin's cache (the reset's audit actor), or null
     * @param knkPermission the in-house permission check ({@code KnkPermissible::hasPermission})
     * @param tracker       discovery's tracker, null while discovery is disabled
     * @param spool         discovery's spool, null while discovery is disabled
     * @param onlinePlayer  an online player by UUID, or null
     * @param afterReset    runs (main thread) with the target's UUID after a reset, e.g. to drop cached menu data
     */
    public DiscoveryAdminCommand(DiscoveriesApi discoveriesApi, UserAdminService userAdminService, Executor mainThread,
                                 Function<Player, Integer> cachedUserId, BiPredicate<Player, String> knkPermission,
                                 Supplier<DiscoveryTracker> tracker, Supplier<DiscoverySpool> spool,
                                 Function<UUID, Player> onlinePlayer, Consumer<UUID> afterReset) {
        this.discoveriesApi = discoveriesApi;
        this.userAdminService = userAdminService;
        this.mainThread = mainThread;
        this.cachedUserId = cachedUserId;
        this.knkPermission = knkPermission;
        this.tracker = tracker;
        this.spool = spool;
        this.onlinePlayer = onlinePlayer;
        this.afterReset = afterReset;
    }

    public static CommandMetadata metadata() {
        // No metadata permission: execute checks the node through both permission systems itself.
        return new CommandMetadata("discovery", "List, reset or inspect players' domain discoveries", USAGE, null,
                List.of("/knk discovery list Steve", "/knk discovery list Steve 2", "/knk discovery reset Steve 12",
                        "/knk discovery status"));
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!hasNode(sender)) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "list" -> list(sender, args);
            case "reset" -> reset(sender, args);
            case "status" -> status(sender);
            default -> sender.sendMessage(ChatColor.YELLOW + "Usage: " + USAGE);
        }
        return true;
    }

    private boolean hasNode(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        return player.hasPermission(NODE) || (knkPermission != null && knkPermission.test(player, NODE));
    }

    // ===== list =====

    private void list(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk discovery list <player> [page]");
            return;
        }
        int page = 1;
        if (args.length >= 3) {
            try {
                page = Math.max(1, Integer.parseInt(args[2]));
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Page must be a number.");
                return;
            }
        }
        int pageNumber = page;
        userAdminService.resolveTarget(sender, args[1], target -> {
            if (target.id() == null) {
                sender.sendMessage(ChatColor.RED + "'" + args[1] + "' has no knk account.");
                return;
            }
            PagedQuery query = new PagedQuery(pageNumber, PAGE_SIZE, null, "discoveredAt", true, Map.of("status", "discovered"));
            CompletableFuture<DiscoverySummary> summary = discoveriesApi.summary(target.id());
            CompletableFuture<Page<DiscoveryProgressRow>> rows = discoveriesApi.progress(target.id(), query);
            summary.thenCombine(rows, (s, r) -> listLines(target.username(), s, r, pageNumber))
                    .whenComplete((lines, ex) -> mainThread.execute(() -> {
                        if (ex != null) {
                            sender.sendMessage(ChatColor.RED + "Failed to load " + target.username() + "'s discoveries: "
                                    + UserAdminService.describeError(ex));
                            return;
                        }
                        lines.forEach(sender::sendMessage);
                    }));
        });
    }

    static List<String> listLines(String username, DiscoverySummary summary, Page<DiscoveryProgressRow> rows, int page) {
        List<String> lines = new ArrayList<>();
        List<String> counts = new ArrayList<>();
        if (summary != null) {
            for (DiscoveryTypeCount count : summary.byType()) {
                if (count.total() > 0) {
                    counts.add(count.domainType() + " " + count.discovered() + "/" + count.total());
                }
            }
        }
        lines.add(ChatColor.GOLD + "Discoveries of " + username + ChatColor.GRAY + " - "
                + (counts.isEmpty() ? "nothing to discover yet" : String.join(", ", counts)));
        if (summary != null && summary.totalDiscovered() > 0) {
            lines.add(ChatColor.GRAY + "Earned: " + ChatColor.WHITE + summary.totalCoins() + " coins, "
                    + summary.totalGems() + " gems, " + summary.totalExp() + " XP");
        }
        List<DiscoveryProgressRow> items = rows != null && rows.items() != null ? rows.items() : List.of();
        if (items.isEmpty()) {
            lines.add(ChatColor.GRAY + (page > 1 ? "No discoveries on page " + page + "." : "No discoveries yet."));
            return lines;
        }
        for (DiscoveryProgressRow row : items) {
            String where = row.parentName() != null && !row.parentName().isBlank() ? " in " + row.parentName() : "";
            String when = row.discoveredAt() != null ? " - " + DATE.format(row.discoveredAt()) : "";
            lines.add(ChatColor.GRAY + " #" + row.domainId() + " " + ChatColor.WHITE + row.name()
                    + ChatColor.GRAY + " (" + row.domainType() + where + ")" + when);
        }
        int pages = Math.max(1, (int) Math.ceil(rows.totalCount() / (double) PAGE_SIZE));
        lines.add(ChatColor.GRAY + "Page " + page + "/" + pages
                + (page < pages ? " - /knk discovery list " + username + " " + (page + 1) : ""));
        return lines;
    }

    // ===== reset =====

    private void reset(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk discovery reset <player> <domainId>");
            return;
        }
        int domainId;
        try {
            domainId = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Domain id must be a number (see /knk discovery list " + args[1] + ").");
            return;
        }
        Integer actorUserId = sender instanceof Player player ? cachedUserId.apply(player) : null;
        userAdminService.resolveTarget(sender, args[1], target -> {
            if (target.id() == null) {
                sender.sendMessage(ChatColor.RED + "'" + args[1] + "' has no knk account.");
                return;
            }
            discoveriesApi.reset(actorUserId, target.id(), domainId).whenComplete((ok, ex) -> mainThread.execute(() -> {
                if (ex != null) {
                    sender.sendMessage(ChatColor.RED + resetError(target.username(), domainId, ex));
                    return;
                }
                sender.sendMessage(ChatColor.GREEN + "Reset " + target.username() + "'s discovery of domain #" + domainId
                        + ChatColor.GRAY + " - they can discover it again (the reward isn't taken back).");
                if (target.uuid() != null) {
                    reloadKnown(target.uuid(), target.id());
                    if (afterReset != null) {
                        afterReset.accept(target.uuid());
                    }
                }
            }));
        });
    }

    static String resetError(String username, int domainId, Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof ApiException api && api.getStatusCode() == 404) {
                return username + " hasn't discovered domain #" + domainId + ".";
            }
            cause = cause.getCause();
        }
        return "Failed to reset the discovery: " + UserAdminService.describeError(ex);
    }

    /** Main thread. An online target's tracker forgets the reset domain, so re-entering it counts now. */
    private void reloadKnown(UUID uuid, int userId) {
        DiscoveryTracker current = tracker.get();
        if (current == null || onlinePlayer.apply(uuid) == null || !current.hasSession(uuid)) {
            return;
        }
        discoveriesApi.known(userId).whenComplete((known, ex) -> {
            if (ex == null && known != null) {
                mainThread.execute(() -> current.replaceKnown(uuid, known));
            }
        });
    }

    // ===== status =====

    private void status(CommandSender sender) {
        DiscoveryTracker current = tracker.get();
        DiscoverySpool currentSpool = spool.get();
        if (current == null) {
            sender.sendMessage(ChatColor.GOLD + "Domain discovery: " + ChatColor.RED + "disabled"
                    + ChatColor.GRAY + " (discovery.enabled in config.yml)");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "Domain discovery: " + ChatColor.GREEN + "enabled");
        sender.sendMessage(ChatColor.GRAY + "Tracked players: " + ChatColor.WHITE + current.sessionCount()
                + ChatColor.GRAY + ", pending places: " + ChatColor.WHITE + current.pendingCount());
        if (currentSpool != null) {
            sender.sendMessage(ChatColor.GRAY + "Spooled (API unreachable): " + ChatColor.WHITE + currentSpool.entryCount()
                    + ChatColor.GRAY + " place(s) in " + currentSpool.directory());
        }
    }
}
