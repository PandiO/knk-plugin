package net.knightsandkings.knk.paper.locations;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.location.LocationOrphanEntry;
import net.knightsandkings.knk.core.domain.location.LocationOrphanPage;
import net.knightsandkings.knk.core.domain.location.LocationTeleportTarget;
import net.knightsandkings.knk.core.ports.api.LocationRetentionApi;
import net.knightsandkings.knk.paper.commands.CommandMetadata;
import net.knightsandkings.knk.paper.commands.SubcommandExecutor;
import net.knightsandkings.knk.paper.teleport.TeleportPlan;
import net.knightsandkings.knk.paper.teleport.TeleportService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * {@code /knk location here|tp <id>|orphans [page]} (KNG-80). Registered with no top-level node,
 * like {@code /knk currency}: each action checks its own node before anything else.
 * <ul>
 *   <li>{@code here} - knk.admin.location, unchanged.</li>
 *   <li>{@code tp <id>} - knk.admin.location.tp: any Location, through the KNG-17 teleport engine as a
 *   STAFF teleport, so the freeze/region/siege guards apply, it is audit-logged, and it is silent
 *   while the staff member is vanished.</li>
 *   <li>{@code orphans [page]} - knk.admin.location.orphans: the open orphaned Locations, each with a
 *   clickable [tp]. Keep/Delete happen in the web panel only.</li>
 * </ul>
 */
public final class LocationAdminCommand implements SubcommandExecutor {
    private static final Logger LOGGER = Logger.getLogger(LocationAdminCommand.class.getName());

    public static final String HERE_NODE = "knk.admin.location";
    public static final String TELEPORT_NODE = "knk.admin.location.tp";
    public static final String ORPHANS_NODE = "knk.admin.location.orphans";
    /** Every action node, asked for before a /knk listing reads them from the cache (KNG-107). */
    public static final List<String> NODES = List.of(HERE_NODE, TELEPORT_NODE, ORPHANS_NODE);
    static final int PAGE_SIZE = 8;

    /** CommandPermissions' check-then-run, as a seam for tests. */
    @FunctionalInterface
    public interface PermissionGate {
        void whenAllowed(CommandSender sender, String node, Runnable onAllowed);
    }

    private final LocationRetentionApi api;
    private final PermissionGate permissions;
    private final java.util.function.BiPredicate<CommandSender, String> holdsCached;
    private final Supplier<TeleportService> teleports;
    private final Function<String, World> worlds;
    private final Predicate<Player> isVanished;
    private final Executor mainThread;
    private final Consumer<Player> showHere;

    public LocationAdminCommand(LocationRetentionApi api, PermissionGate permissions, java.util.function.BiPredicate<CommandSender, String> holdsCached,
                                Supplier<TeleportService> teleports, Function<String, World> worlds, Predicate<Player> isVanished,
                                Executor mainThread, Consumer<Player> showHere) {
        this.api = api;
        this.permissions = permissions;
        this.holdsCached = holdsCached;
        this.teleports = teleports;
        this.worlds = worlds;
        this.isVanished = isVanished;
        this.mainThread = mainThread;
        this.showHere = showHere;
    }

    public static CommandMetadata metadata() {
        return new CommandMetadata("location",
            "Your current location, teleport to a Location by id, list orphaned Locations",
            "/knk location here | tp <id> | orphans [page]",
            null, // each action checks its own knk.admin.location* node
            List.of("/knk location here", "/knk location tp 42", "/knk location orphans", "/knk location orphans 2"));
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "here" -> permissions.whenAllowed(sender, HERE_NODE, () -> {
                if (sender instanceof Player player) {
                    showHere.accept(player);
                } else {
                    sender.sendMessage(ChatColor.RED + "Only players can use this command.");
                }
            });
            case "tp" -> teleport(sender, args);
            case "orphans" -> orphans(sender, args);
            default -> sender.sendMessage(ChatColor.YELLOW + "Usage: " + metadata().usage());
        }
        return true;
    }

    /** Only the actions the sender holds the node for (cached check; running an action checks for real). */
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return actionsFor(sender).stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }

    /** Whether /knk help and tab completion list /knk location at all: any of its nodes. */
    public boolean visibleTo(CommandSender sender) {
        return !actionsFor(sender).isEmpty();
    }

    private List<String> actionsFor(CommandSender sender) {
        List<String> actions = new java.util.ArrayList<>();
        if (holdsCached.test(sender, HERE_NODE)) actions.add("here");
        if (holdsCached.test(sender, TELEPORT_NODE)) actions.add("tp");
        if (holdsCached.test(sender, ORPHANS_NODE)) actions.add("orphans");
        return actions;
    }

    // ===== tp =====

    private void teleport(CommandSender sender, String[] args) {
        Integer id = args.length >= 2 ? parseId(args[1]) : null;
        if (id == null) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk location tp <id>");
            return;
        }
        permissions.whenAllowed(sender, TELEPORT_NODE, () -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only players can teleport.");
                return;
            }
            api.teleportTarget(id).whenComplete((target, ex) -> mainThread.execute(() -> {
                if (ex != null) {
                    LOGGER.warning("Could not load Location " + id + " for " + player.getName() + ": " + ex.getMessage());
                    sender.sendMessage(ChatColor.RED + "Couldn't load Location #" + id + " - the web API didn't answer. Try again in a moment.");
                } else if (target.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "Location #" + id + " doesn't exist (it may have been deleted).");
                } else {
                    teleportTo(player, target.get());
                }
            }));
        });
    }

    /** Main thread. */
    private void teleportTo(Player player, LocationTeleportTarget target) {
        String worldName = target.world() == null || target.world().isBlank() ? "world" : target.world();
        World world = worlds.apply(worldName);
        if (world == null) {
            player.sendMessage(ChatColor.RED + "Location #" + target.id() + " is in world '" + worldName + "', which isn't loaded on this server.");
            return;
        }
        TeleportService service = teleports.get();
        if (service == null) {
            player.sendMessage(ChatColor.RED + "Teleports aren't available - the teleport engine didn't start.");
            return;
        }
        Location destination = new Location(world, target.x(), target.y(), target.z(), target.yaw(), target.pitch());
        String label = String.format(Locale.ROOT, "Location #%d (%.1f, %.1f, %.1f in %s)",
            target.id(), target.x(), target.y(), target.z(), worldName);
        // Vanish-safe like /tp: a vanished staff member's teleport is always silent.
        TeleportPlan plan = TeleportPlan.staffToLocation(player, player, destination, label, isVanished.test(player));
        service.start(plan).thenAccept(outcome -> {
            if (outcome.isTeleported()) {
                player.sendMessage(ChatColor.GREEN + "Teleported to " + label + ".");
            } else {
                player.sendMessage(ChatColor.RED + (outcome.message() != null ? outcome.message() : "The teleport didn't happen."));
            }
        });
    }

    // ===== orphans =====

    private void orphans(CommandSender sender, String[] args) {
        Integer page = args.length >= 2 ? parseId(args[1]) : Integer.valueOf(1);
        if (page == null) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk location orphans [page]");
            return;
        }
        permissions.whenAllowed(sender, ORPHANS_NODE, () -> api.listOrphans("open", page, PAGE_SIZE)
            .whenComplete((result, ex) -> mainThread.execute(() -> {
                if (ex != null) {
                    LOGGER.warning("Could not list orphaned Locations: " + ex.getMessage());
                    sender.sendMessage(ChatColor.RED + "Couldn't load the orphaned Locations - the web API didn't answer.");
                } else {
                    show(sender, result);
                }
            })));
    }

    /** Main thread. */
    void show(CommandSender sender, LocationOrphanPage result) {
        if (result.items().isEmpty()) {
            sender.sendMessage(ChatColor.GREEN + (result.pageNumber() > 1 ? "No more orphaned Locations." : "No orphaned Locations waiting for review."));
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "Orphaned Locations: " + ChatColor.YELLOW + result.openCount() + " open"
            + ChatColor.GRAY + " (page " + result.pageNumber() + "/" + result.totalPages() + ")");
        boolean teleportLinks = holdsCached.test(sender, TELEPORT_NODE);
        for (LocationOrphanEntry entry : result.items()) {
            Component line = Component.text(String.format(Locale.ROOT, "#%d ", entry.locationId()), NamedTextColor.YELLOW)
                .append(Component.text(String.format(Locale.ROOT, "%s %.1f %.1f %.1f", entry.world() == null ? "world" : entry.world(),
                    entry.x(), entry.y(), entry.z()), NamedTextColor.WHITE))
                .append(Component.text(" flagged " + shortDate(entry.flaggedAt()), NamedTextColor.GRAY));
            if (entry.previouslyKeptBy() != null) {
                line = line.append(Component.text(" (kept before by " + entry.previouslyKeptBy()
                    + (entry.previousNote() != null && !entry.previousNote().isBlank() ? ": " + entry.previousNote() : "") + ")", NamedTextColor.AQUA));
            }
            if (teleportLinks) {
                line = line.append(Component.text(" [tp]", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/knk location tp " + entry.locationId()))
                    .hoverEvent(HoverEvent.showText(Component.text("Teleport to Location #" + entry.locationId()))));
            }
            sender.sendMessage(line);
        }
        if (result.pageNumber() < result.totalPages()) {
            int next = result.pageNumber() + 1;
            sender.sendMessage(Component.text("[next page]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/knk location orphans " + next)));
        }
        sender.sendMessage(ChatColor.GRAY + "Keep or delete them in the web app: Player moderation → Orphaned Locations.");
    }

    private static String shortDate(String iso) {
        return iso == null ? "?" : iso.length() >= 10 ? iso.substring(0, 10) : iso;
    }

    private static Integer parseId(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
