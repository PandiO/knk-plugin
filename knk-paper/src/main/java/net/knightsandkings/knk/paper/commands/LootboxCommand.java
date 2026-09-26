package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.lootbox.KnkLootboxOdds;
import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import net.knightsandkings.knk.core.lootbox.KnkLootboxType;
import net.knightsandkings.knk.core.lootbox.LootboxRejectedException;
import net.knightsandkings.knk.core.ports.api.LootboxesQueryApi;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * {@code /lootbox} ({@code /lb}), the <b>player</b> command (docs/specs/lootboxes/DESIGN.md §3.4, D18): help and
 * {@code odds <category> [stars]}, a read-only preview from the same code the real roll uses. Every admin action is
 * under {@code /knk lootbox} instead. {@code odds} needs {@code knk.lootbox.odds} (seeded on the Default group).
 */
public final class LootboxCommand implements TabExecutor {

    public static final String ODDS_NODE = "knk.lootbox.odds";
    private static final Logger LOGGER = Logger.getLogger(LootboxCommand.class.getName());
    private static final int MAX_ITEMS_SHOWN = 10;

    private final Supplier<KnkLootboxRuntimeConfig> config;
    private final LootboxesQueryApi queryApi;
    private final BiPredicate<Player, String> permission;
    private final Executor mainThread;

    public LootboxCommand(Supplier<KnkLootboxRuntimeConfig> config, LootboxesQueryApi queryApi,
                          BiPredicate<Player, String> permission, Executor mainThread) {
        this.config = config;
        this.queryApi = queryApi;
        this.permission = permission;
        this.mainThread = mainThread;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "odds" -> odds(player, Arrays.copyOfRange(args, 1, args.length));
            default -> help(player);
        }
        return true;
    }

    private void help(Player player) {
        KnkLootboxRuntimeConfig current = config.get();
        player.sendMessage(ChatColor.GOLD + "Lootboxes" + ChatColor.GRAY + " appear around the world - right-click one to open it.");
        if (current.maxClaimsPerPlayerPerDay() != null) {
            player.sendMessage(ChatColor.GRAY + "You can open " + current.maxClaimsPerPlayerPerDay()
                    + " a day; the count resets at 00:00 UTC.");
        }
        player.sendMessage(ChatColor.YELLOW + "/lootbox odds <category> [stars]" + ChatColor.GRAY + " - what a box can give");
        if (!current.types().isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "Categories: " + ChatColor.WHITE + String.join(", ",
                    current.types().stream().map(KnkLootboxType::categoryName).sorted().toList()));
        }
    }

    private void odds(Player player, String[] args) {
        if (!permission.test(player, ODDS_NODE)) {
            player.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return;
        }
        if (args.length == 0) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /lootbox odds <category> [stars]");
            return;
        }
        Integer stars = null;
        String[] nameParts = args;
        if (args.length > 1 && args[args.length - 1].matches("\\d+")) {
            stars = Integer.parseInt(args[args.length - 1]);
            nameParts = Arrays.copyOf(args, args.length - 1);
        }
        String category = String.join(" ", nameParts);
        Optional<KnkLootboxType> type = config.get().typeByCategory(category);
        if (type.isEmpty()) {
            player.sendMessage(ChatColor.RED + "No lootbox for \"" + category + "\".");
            return;
        }
        KnkLootboxType found = type.get();
        if (stars != null && (stars < found.minBoxStars() || stars > found.maxBoxStars())) {
            player.sendMessage(ChatColor.RED + found.name() + " boxes are ★" + found.minBoxStars() + "-★" + found.maxBoxStars() + ".");
            return;
        }

        queryApi.getOdds(found.id(), stars).whenComplete((odds, ex) -> mainThread.execute(() -> {
            if (ex != null || odds == null) {
                LOGGER.warning("Lootbox odds for type " + found.id() + " failed: "
                        + (ex == null ? "no result" : LootboxRejectedException.unwrap(ex).getMessage()));
                player.sendMessage(ChatColor.RED + "Couldn't load the odds - try again in a moment.");
                return;
            }
            print(player, odds);
        }));
    }

    static void print(Player player, KnkLootboxOdds odds) {
        player.sendMessage(ChatColor.GOLD + odds.lootboxTypeName() + " " + "★".repeat(Math.max(0, odds.boxStars()))
                + ChatColor.GRAY + " - what it can give:");
        for (KnkLootboxOdds.Grade grade : odds.itemGrades()) {
            player.sendMessage(ChatColor.GRAY + "  " + ChatColor.AQUA + "★".repeat(Math.max(0, grade.stars())) + " " + grade.name()
                    + ChatColor.GRAY + ": " + ChatColor.WHITE + percent(grade.percent())
                    + (grade.itemCount() != null ? ChatColor.GRAY + " (" + grade.itemCount() + " items)" : ""));
        }
        odds.items().stream()
                .sorted((a, b) -> Double.compare(b.percent(), a.percent()))
                .limit(MAX_ITEMS_SHOWN)
                .forEach(item -> player.sendMessage(ChatColor.GRAY + "  - " + ChatColor.WHITE
                        + ChatColor.translateAlternateColorCodes('&', item.name() == null ? "?" : item.name())
                        + ChatColor.GRAY + " " + percent(item.percent())));
        for (KnkLootboxOdds.Special special : odds.specials()) {
            player.sendMessage(ChatColor.LIGHT_PURPLE + "  Special: " + ChatColor.WHITE
                    + ChatColor.translateAlternateColorCodes('&', special.name() == null ? "?" : special.name())
                    + ChatColor.GRAY + " " + percent(special.percent()));
        }
    }

    static String percent(double value) {
        if (value > 0 && value < 0.1) {
            return String.format(Locale.ROOT, "%.3f%%", value);
        }
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("help", "odds"), args[0]);
        }
        if (args.length == 2 && "odds".equalsIgnoreCase(args[0])) {
            return filter(config.get().types().stream()
                    .map(t -> t.categoryName() == null ? "" : t.categoryName().replace(' ', '_').toLowerCase(Locale.ROOT))
                    .toList(), args[1]);
        }
        return Collections.emptyList();
    }

    static List<String> filter(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
