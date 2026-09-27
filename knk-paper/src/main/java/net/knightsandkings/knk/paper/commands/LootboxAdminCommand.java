package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.KnkLootboxSpawn;
import net.knightsandkings.knk.core.lootbox.KnkLootboxToken;
import net.knightsandkings.knk.core.lootbox.KnkLootboxType;
import net.knightsandkings.knk.core.lootbox.LootboxRejectedException;
import net.knightsandkings.knk.core.ports.api.LootboxesCommandApi;
import net.knightsandkings.knk.paper.lootbox.LootboxAnnouncer;
import net.knightsandkings.knk.paper.lootbox.LootboxDelivery;
import net.knightsandkings.knk.paper.lootbox.LootboxOpening;
import net.knightsandkings.knk.paper.lootbox.LootboxRuntime;
import net.knightsandkings.knk.paper.lootbox.LootboxTokenDelivery;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@code /knk lootbox} (docs/specs/lootboxes/DESIGN.md §3.4, D18): the admin side, registered in
 * {@link KnkAdminCommand}. {@code spawn <category> [stars]}, {@code despawn [id|nearest]}, {@code list [area]},
 * {@code tp <id>}, {@code give <player> <category> [stars]}, {@code token <player> <category> [stars|any] [amount]}
 * (lootbox token items, Phase 5), {@code reload} and {@code area ...} ({@link LootboxAreaCommand}). Each is gated on its own {@code knk.lootbox.admin.<action>} node through the in-house
 * permission model (the console passes). Spawns and gives are audited by the API with the staff member as actor.
 */
public final class LootboxAdminCommand {

    public static final String NODE_PREFIX = "knk.lootbox.admin.";
    static final double NEAREST_RADIUS = 16;

    static final int MAX_TOKENS = 64;

    private static final List<String> SUBCOMMANDS = List.of("spawn", "despawn", "list", "tp", "give", "token", "reload", "area");

    private final LootboxRuntime runtime;
    private final LootboxesCommandApi commandApi;
    private final LootboxDelivery delivery;
    private final LootboxAnnouncer announcer;
    private final LootboxAreaCommand areaCommand;
    private final BiPredicate<Player, String> permission;
    private final Function<Player, Integer> userIdOf;
    private final Function<String, Player> onlinePlayer;
    private final Runnable reload;
    private final Executor mainThread;
    private final LootboxTokenDelivery tokens;
    private final Predicate<UUID> inSiege;
    // The opening reel for /knk lootbox give (DESIGN.md §3.9); null = hand the item over at once.
    private LootboxOpening opening;
    // Online player names for tab completion (the target of give/token).
    private Supplier<? extends Collection<String>> onlinePlayerNames = List::of;

    public LootboxAdminCommand(
            LootboxRuntime runtime,
            LootboxesCommandApi commandApi,
            LootboxDelivery delivery,
            LootboxAnnouncer announcer,
            LootboxAreaCommand areaCommand,
            BiPredicate<Player, String> permission,
            Function<Player, Integer> userIdOf,
            Function<String, Player> onlinePlayer,
            Runnable reload,
            Executor mainThread
    ) {
        this(runtime, commandApi, delivery, announcer, areaCommand, permission, userIdOf, onlinePlayer, reload, mainThread, null,
                id -> false);
    }

    public LootboxAdminCommand(
            LootboxRuntime runtime,
            LootboxesCommandApi commandApi,
            LootboxDelivery delivery,
            LootboxAnnouncer announcer,
            LootboxAreaCommand areaCommand,
            BiPredicate<Player, String> permission,
            Function<Player, Integer> userIdOf,
            Function<String, Player> onlinePlayer,
            Runnable reload,
            Executor mainThread,
            LootboxTokenDelivery tokens,
            Predicate<UUID> inSiege
    ) {
        this.tokens = tokens;
        this.inSiege = inSiege != null ? inSiege : id -> false;
        this.runtime = runtime;
        this.commandApi = commandApi;
        this.delivery = delivery;
        this.announcer = announcer;
        this.areaCommand = areaCommand;
        this.permission = permission;
        this.userIdOf = userIdOf;
        this.onlinePlayer = onlinePlayer;
        this.reload = reload;
        this.mainThread = mainThread;
    }

    /** The opening reel for {@code give}: the target watches their gift open (DESIGN.md §3.9). */
    public void setOpening(LootboxOpening opening) {
        this.opening = opening;
    }

    /** Online player names, suggested for {@code give}/{@code token}'s player argument. */
    public void setOnlinePlayerNames(Supplier<? extends Collection<String>> names) {
        this.onlinePlayerNames = names != null ? names : List::of;
    }

    public static String usage() {
        return "/knk lootbox spawn <category> [stars] | despawn [id|nearest] | list [area] | tp <id> | "
                + "give <player> <category> [stars] | token <player> <category> [stars|any] [amount] | reload | "
                + "area create|list|info|delete";
    }

    public boolean execute(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length);
        if (!SUBCOMMANDS.contains(sub)) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: " + usage());
            return true;
        }
        if (!allowed(sender, sub)) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return true;
        }
        switch (sub) {
            case "spawn" -> spawn(sender, rest);
            case "despawn" -> despawn(sender, rest);
            case "list" -> list(sender, rest);
            case "tp" -> tp(sender, rest);
            case "give" -> give(sender, rest);
            case "token" -> token(sender, rest);
            case "reload" -> {
                reload.run();
                sender.sendMessage(ChatColor.GREEN + "Lootbox settings reloaded; refreshing boxes and areas from the API.");
            }
            case "area" -> areaCommand.execute(sender, rest);
            default -> sender.sendMessage(ChatColor.YELLOW + "Usage: " + usage());
        }
        return true;
    }

    /** {@code knk.lootbox.admin.<action>} for a player (ops pass through the in-house model's op bypass); console passes. */
    boolean allowed(CommandSender sender, String action) {
        return !(sender instanceof Player player) || permission.test(player, NODE_PREFIX + action);
    }

    // ===== spawn / despawn =====

    private void spawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can spawn a box (it appears where you stand).");
            return;
        }
        TypeAndStars parsed = parseTypeAndStars(sender, args, "/knk lootbox spawn <category> [stars]");
        if (parsed == null) {
            return;
        }
        Location at = player.getLocation();
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        commandApi.adminSpawn(userIdOf.apply(player), parsed.type().id(), parsed.stars(), world.getName(),
                        at.getBlockX(), at.getBlockY(), at.getBlockZ(), runtime.settings().serverId())
                .whenComplete((spawn, ex) -> mainThread.execute(() -> {
                    if (ex != null || spawn == null) {
                        sender.sendMessage(ChatColor.RED + "Could not spawn: " + describe(ex));
                        return;
                    }
                    runtime.added(spawn);
                    sender.sendMessage(ChatColor.GREEN + "Spawned " + label(spawn) + ChatColor.GREEN + " (#" + spawn.id() + ").");
                }));
    }

    private void despawn(CommandSender sender, String[] args) {
        int id;
        if (args.length == 0 || "nearest".equalsIgnoreCase(args[0])) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /knk lootbox despawn <id>");
                return;
            }
            Optional<KnkLootboxSpawn> target = nearest(player);
            if (target.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "No lootbox within " + (int) NEAREST_RADIUS + " blocks.");
                return;
            }
            id = target.get().id();
        } else {
            Integer parsed = parseInt(args[0]);
            if (parsed == null) {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /knk lootbox despawn [id|nearest]");
                return;
            }
            id = parsed; // Not necessarily cached here: another server's box can be despawned too.
        }
        Integer actor = sender instanceof Player player ? userIdOf.apply(player) : null;
        commandApi.despawn(actor, id).whenComplete((spawn, ex) -> mainThread.execute(() -> {
            if (ex != null) {
                sender.sendMessage(ChatColor.RED + "Could not despawn #" + id + ": " + describe(ex));
                return;
            }
            runtime.gone(id);
            sender.sendMessage(ChatColor.GREEN + "Lootbox #" + id + " removed.");
        }));
    }

    Optional<KnkLootboxSpawn> nearest(Player player) {
        Location at = player.getLocation();
        String world = at.getWorld() == null ? null : at.getWorld().getName();
        return runtime.cache().all().stream()
                .filter(s -> s.world().equals(world))
                .filter(s -> distanceSquared(at, s) <= NEAREST_RADIUS * NEAREST_RADIUS)
                .min(Comparator.comparingDouble(s -> distanceSquared(at, s)));
    }

    private static double distanceSquared(Location at, KnkLootboxSpawn spawn) {
        double dx = at.getX() - (spawn.x() + 0.5);
        double dy = at.getY() - spawn.y();
        double dz = at.getZ() - (spawn.z() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    // ===== list / tp =====

    private void list(CommandSender sender, String[] args) {
        String areaFilter = args.length > 0 ? args[0] : null;
        List<KnkLootboxSpawn> boxes = runtime.cache().all().stream()
                .filter(s -> areaFilter == null || (s.spawnAreaName() != null && s.spawnAreaName().equalsIgnoreCase(areaFilter)))
                .sorted(Comparator.comparingInt(KnkLootboxSpawn::id))
                .toList();
        if (boxes.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No active lootboxes" + (areaFilter != null ? " in " + areaFilter : "") + ".");
            return;
        }
        Instant now = runtime.clock().instant();
        sender.sendMessage(ChatColor.GOLD + "Active lootboxes (" + boxes.size() + "):");
        for (KnkLootboxSpawn box : boxes) {
            long minutes = box.expiresAt() == null ? -1 : Math.max(0, Duration.between(now, box.expiresAt()).toMinutes());
            sender.sendMessage(ChatColor.WHITE + "#" + box.id() + " " + label(box) + ChatColor.GRAY
                    + " | " + (box.spawnAreaName() != null ? box.spawnAreaName() : "admin") + " | " + box.world()
                    + " " + box.x() + " " + box.y() + " " + box.z()
                    + (minutes >= 0 ? " | " + minutes + " min left" : ""));
        }
    }

    private void tp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can teleport.");
            return;
        }
        Integer id = args.length == 1 ? parseInt(args[0]) : null;
        if (id == null) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk lootbox tp <id>");
            return;
        }
        Optional<KnkLootboxSpawn> box = runtime.cache().byId(id);
        World world = box.map(b -> org.bukkit.Bukkit.getWorld(b.world())).orElse(null);
        if (box.isEmpty() || world == null) {
            sender.sendMessage(ChatColor.RED + "No active lootbox #" + id + " on this server.");
            return;
        }
        player.teleport(new Location(world, box.get().x() + 0.5, box.get().y(), box.get().z() + 1.5));
    }

    /**
     * A player in a siege (hub or match) has the siege inventory, and the saved one replaces it afterwards: anything
     * given now would be wiped. Refused for everyone (console included) before any API call.
     */
    private boolean refuseInSiege(CommandSender sender, Player target) {
        if (!inSiege.test(target.getUniqueId())) {
            return false;
        }
        sender.sendMessage(ChatColor.RED + target.getName() + " is in a siege - their inventory is restored afterwards, so the item "
                + "would be lost. Try again once the siege is over.");
        return true;
    }

    // ===== give =====

    private void give(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk lootbox give <player> <category> [stars]");
            return;
        }
        Player target = onlinePlayer.apply(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found or not online: " + args[0]);
            return;
        }
        if (refuseInSiege(sender, target)) {
            return;
        }
        Integer targetUserId = userIdOf.apply(target);
        if (targetUserId == null) {
            sender.sendMessage(ChatColor.RED + "That player's account isn't loaded yet - try again in a moment.");
            return;
        }
        TypeAndStars parsed = parseTypeAndStars(sender, Arrays.copyOfRange(args, 1, args.length),
                "/knk lootbox give <player> <category> [stars]");
        if (parsed == null) {
            return;
        }
        Integer actor = sender instanceof Player player ? userIdOf.apply(player) : null;
        // One key per command: a request the HTTP client resends (a dropped connection) replays this give instead of
        // rolling a second item.
        String key = "admin-give:" + UUID.randomUUID().toString().replace("-", "");
        commandApi.adminGive(actor, targetUserId, parsed.type().id(), parsed.stars(), key)
                .whenComplete((claim, ex) -> mainThread.execute(() -> {
                    if (ex != null || claim == null) {
                        sender.sendMessage(ChatColor.RED + "Could not give: " + describe(ex));
                        return;
                    }
                    deliverGive(sender, target, claim);
                }));
    }

    private void deliverGive(CommandSender sender, Player target, KnkLootboxClaimResult claim) {
        if (!target.isOnline()) {
            sender.sendMessage(ChatColor.YELLOW + "Rolled claim #" + claim.claimId() + "; " + target.getName()
                    + " left - it is delivered on their next join.");
            return;
        }
        String box = ChatColor.translateAlternateColorCodes('&', runtime.settings().coloredLabel(claim.boxLabel(), claim.boxStars()));
        String staff = sender instanceof Player player ? player.getName() : "The server";
        String done = ChatColor.GREEN + "Gave " + target.getName() + " a " + box + ChatColor.GREEN + " (claim #" + claim.claimId()
                + (claim.itemInstanceId() != null ? ", item instance " + claim.itemInstanceId() : "") + ").";
        if (opening != null) {
            // The target sees who gave it and watches it open; the item arrives when the reel stops.
            target.sendMessage(ChatColor.GREEN + staff + " gave you a " + box + ChatColor.GREEN + " - opening it now!");
            opening.open(target, claim, staff);
            sender.sendMessage(done);
            return;
        }
        delivery.deliver(target, claim, false).thenAccept(outcome -> {
            if (!outcome.given()) {
                sender.sendMessage(ChatColor.YELLOW + "Rolled claim #" + claim.claimId()
                        + " but couldn't hand it over now; it is delivered on " + target.getName() + "'s next join.");
                return;
            }
            announcer.opened(target, claim, outcome.item(), runtime.settings(), runtime.config(), staff);
            sender.sendMessage(done);
        });
    }

    // ===== token (Phase 5) =====

    /**
     * {@code token <player> <category> [stars|any] [amount]}: the API issues {@code amount} token items (audited
     * LootboxGranted), then they go into the player's inventory (leftovers dropped owner-locked) and are confirmed.
     */
    private void token(CommandSender sender, String[] args) {
        String usage = "/knk lootbox token <player> <category> [stars|any] [amount]";
        if (tokens == null) {
            sender.sendMessage(ChatColor.RED + "Lootbox tokens aren't available on this server.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: " + usage);
            return;
        }
        Player target = onlinePlayer.apply(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found or not online: " + args[0]);
            return;
        }
        if (refuseInSiege(sender, target)) {
            return;
        }
        Integer targetUserId = userIdOf.apply(target);
        if (targetUserId == null) {
            sender.sendMessage(ChatColor.RED + "That player's account isn't loaded yet - try again in a moment.");
            return;
        }
        TokenArgs parsed = parseTokenArgs(sender, Arrays.copyOfRange(args, 1, args.length), usage);
        if (parsed == null) {
            return;
        }
        Integer actor = sender instanceof Player player ? userIdOf.apply(player) : null;
        // One key per command: a retried request returns the same tokens instead of issuing more.
        String key = "admin-token:" + UUID.randomUUID().toString().replace("-", "");
        commandApi.issueTokens(actor, targetUserId, parsed.type().type().id(), parsed.type().stars(), parsed.amount(),
                        KnkLootboxToken.REASON_ADMIN, key)
                .whenComplete((issued, ex) -> mainThread.execute(() -> {
                    if (ex != null || issued == null || issued.isEmpty()) {
                        sender.sendMessage(ChatColor.RED + "Could not issue: " + describe(ex));
                        return;
                    }
                    if (!target.isOnline()) {
                        sender.sendMessage(ChatColor.YELLOW + "Issued " + issued.size() + " token(s); " + target.getName()
                                + " left - they get them on their next join.");
                        return;
                    }
                    int given = tokens.give(target, targetUserId, issued);
                    KnkLootboxToken first = issued.get(0);
                    String staff = sender instanceof Player player ? player.getName() : "The server";
                    target.sendMessage(ChatColor.GREEN + staff + " gave you " + issued.size() + "x "
                            + ChatColor.translateAlternateColorCodes('&', runtime.settings().coloredLabel(first.boxLabel(), first.boxStars()))
                            + ChatColor.GREEN + " - right-click to open.");
                    target.playSound(target.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.8f, 0.9f);
                    sender.sendMessage(ChatColor.GREEN + "Gave " + target.getName() + " " + given + " lootbox token(s) (ids "
                            + issued.stream().map(t -> String.valueOf(t.id())).reduce((a, b) -> a + ", " + b).orElse("") + ").");
                }));
    }

    record TokenArgs(TypeAndStars type, int amount) {
    }

    /** {@code <category words...> [stars|any] [amount]}: one trailing number is the stars, two are stars and amount. */
    TokenArgs parseTokenArgs(CommandSender sender, String[] args, String usage) {
        String[] rest = args;
        int amount = 1;
        if (rest.length >= 2 && isInt(rest[rest.length - 1])
                && (isInt(rest[rest.length - 2]) || "any".equalsIgnoreCase(rest[rest.length - 2]))) {
            amount = Integer.parseInt(rest[rest.length - 1]);
            rest = Arrays.copyOf(rest, rest.length - 1);
        }
        if (rest.length >= 1 && "any".equalsIgnoreCase(rest[rest.length - 1])) {
            rest = Arrays.copyOf(rest, rest.length - 1);
        }
        if (amount < 1 || amount > MAX_TOKENS) {
            sender.sendMessage(ChatColor.RED + "Amount is 1-" + MAX_TOKENS + ".");
            return null;
        }
        TypeAndStars type = parseTypeAndStars(sender, rest, usage);
        return type == null ? null : new TokenArgs(type, amount);
    }

    private static boolean isInt(String raw) {
        return raw != null && raw.matches("\\d{1,4}");
    }

    // ===== helpers =====

    record TypeAndStars(KnkLootboxType type, Integer stars) {
    }

    /** {@code <category words...> [stars]}; tells the sender and returns null when it doesn't parse. */
    TypeAndStars parseTypeAndStars(CommandSender sender, String[] args, String usage) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: " + usage);
            return null;
        }
        Integer stars = null;
        String[] nameParts = args;
        if (args.length > 1 && args[args.length - 1].matches("\\d+")) {
            stars = Integer.parseInt(args[args.length - 1]);
            nameParts = Arrays.copyOf(args, args.length - 1);
        }
        String category = String.join(" ", nameParts);
        Optional<KnkLootboxType> type = runtime.config().typeByCategory(category);
        if (type.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No enabled lootbox type for \"" + category + "\".");
            return null;
        }
        int maxStars = runtime.config().maxBoxStars();
        if (stars != null && (stars < 1 || stars > maxStars)) {
            sender.sendMessage(ChatColor.RED + "Box stars are 1-" + maxStars + ".");
            return null;
        }
        return new TypeAndStars(type.get(), stars);
    }

    private String label(KnkLootboxSpawn spawn) {
        return ChatColor.translateAlternateColorCodes('&', runtime.settings().coloredLabel(spawn.boxLabel(), spawn.boxStars()));
    }

    private static Integer parseInt(String raw) {
        try {
            return Integer.parseInt(raw.startsWith("#") ? raw.substring(1) : raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String describe(Throwable ex) {
        if (ex == null) {
            return "no result";
        }
        LootboxRejectedException rejected = LootboxRejectedException.find(ex);
        if (rejected != null) {
            return rejected.getMessage() + (rejected.code() != null ? " (" + rejected.code() + ")" : "");
        }
        return LootboxRejectedException.unwrap(ex).getMessage();
    }

    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return LootboxCommand.filter(SUBCOMMANDS.stream().filter(s -> allowed(sender, s)).toList(), args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if ("area".equals(sub)) {
            return areaCommand.tabComplete(Arrays.copyOfRange(args, 1, args.length));
        }
        List<String> categories = runtime.config().types().stream()
                .map(t -> t.categoryName() == null ? "" : t.categoryName().replace(' ', '_').toLowerCase(Locale.ROOT))
                .toList();
        if ("spawn".equals(sub) && args.length == 2) {
            return LootboxCommand.filter(categories, args[1]);
        }
        if (("give".equals(sub) || "token".equals(sub)) && args.length == 2) {
            return LootboxCommand.filter(List.copyOf(onlinePlayerNames.get()), args[1]);
        }
        if (("give".equals(sub) || "token".equals(sub)) && args.length == 3) {
            return LootboxCommand.filter(categories, args[2]);
        }
        if (("despawn".equals(sub) || "tp".equals(sub)) && args.length == 2) {
            return LootboxCommand.filter(runtime.cache().all().stream().map(s -> String.valueOf(s.id())).toList(), args[1]);
        }
        return List.of();
    }
}
