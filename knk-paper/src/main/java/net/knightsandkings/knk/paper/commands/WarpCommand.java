package net.knightsandkings.knk.paper.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.dataaccess.TeleportDestinationsDataAccess;
import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.core.teleport.WarpTargets;
import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;
import net.knightsandkings.knk.paper.commands.support.TargetRankCheck;
import net.knightsandkings.knk.paper.teleport.TeleportAuditor;
import net.knightsandkings.knk.paper.teleport.TeleportCharges;
import net.knightsandkings.knk.paper.teleport.TeleportNodes;
import net.knightsandkings.knk.paper.teleport.TeleportPlan;
import net.knightsandkings.knk.paper.teleport.TeleportService;
import net.knightsandkings.knk.paper.teleport.VisibleTargetResolver;
import net.knightsandkings.knk.paper.utils.ColorOptions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Domain teleports (docs/specs/teleport/DESIGN.md §3.2/§3.7, Phase 5):
 * <ul>
 *   <li>{@code /warp <destination>} ({@code /point}) - to a Town, District or Structure staff enabled
 *       as a destination ({@value TeleportNodes#WARP}). A player teleport: warmup (5 s, 3 s with
 *       {@value TeleportNodes#WARMUP_SHORT}), cooldown, combat tag, safe spot, every guard. After the
 *       warmup knk-web-api checks the title / premium tier / discovery requirements again and takes
 *       the gem price (nothing is charged when the warmup is cancelled; refunded if the teleport
 *       then fails). {@value TeleportNodes#BYPASS_REQUIREMENTS} / {@value TeleportNodes#BYPASS_COST}
 *       skip the requirements / the price. Names are case-insensitive; a name several places share
 *       takes the {@code type:name} form ({@code town:Kardenna}).</li>
 *   <li>{@code /warp} - opens the teleport menu ({@code teleport.destinations}, Phase 6); the chat
 *       list when the menu isn't available.</li>
 *   <li>{@code /warps}, {@code /warp list} - the destinations with price and lock state in chat.</li>
 *   <li>{@code /warp <destination> <player> [-s]} - send someone ({@value TeleportNodes#STAFF_OTHERS},
 *       you must outrank them): a staff teleport - instant, free, audited. Console allowed.</li>
 * </ul>
 * The list comes from {@link TeleportDestinationsDataAccess} (cached per player); it is only used to
 * resolve names and show locks - the server decides at the charge. The teleport menu's destination
 * tiles run the player form through {@link #warpTo(Player, int)}.
 */
public class WarpCommand implements TabExecutor {

    private static final Logger LOGGER = Logger.getLogger(WarpCommand.class.getName());
    static final String UNAVAILABLE = "Warps aren't available right now.";

    public enum Form { WARP, LIST }

    /** Everything a warp command needs, shared by its forms. */
    public record Deps(
        PlayerCommandSupport support,
        TargetRankCheck rankCheck,
        VisibleTargetResolver targets,
        TeleportService teleportService,
        TeleportDestinationsDataAccess destinations,
        TeleportCharges charges,
        TeleportAuditor.UserIdLookup userIds,
        TeleportService.PermissionLookup permissions,
        Function<String, World> worlds,
        Predicate<Player> isVanished
    ) {
        public Deps {
            Objects.requireNonNull(support, "support must not be null");
            Objects.requireNonNull(rankCheck, "rankCheck must not be null");
            Objects.requireNonNull(targets, "targets must not be null");
            Objects.requireNonNull(teleportService, "teleportService must not be null");
            Objects.requireNonNull(destinations, "destinations must not be null");
            Objects.requireNonNull(charges, "charges must not be null");
            Objects.requireNonNull(userIds, "userIds must not be null");
            Objects.requireNonNull(permissions, "permissions must not be null");
            Objects.requireNonNull(worlds, "worlds must not be null");
            Objects.requireNonNull(isVanished, "isVanished must not be null");
        }
    }

    private final Form form;
    private final Deps deps;
    /** Players' knk user ids, remembered for tab completion (which can't wait for a lookup). */
    private final Map<UUID, Integer> knownUserIds;
    /** Opens the teleport menu for a player; false when it isn't available (shared by every form). */
    private final AtomicReference<Predicate<Player>> menuOpener;

    public WarpCommand(Form form, Deps deps) {
        this(form, deps, new ConcurrentHashMap<>(), new AtomicReference<>(player -> false));
    }

    private WarpCommand(Form form, Deps deps, Map<UUID, Integer> knownUserIds, AtomicReference<Predicate<Player>> menuOpener) {
        this.form = Objects.requireNonNull(form, "form must not be null");
        this.deps = Objects.requireNonNull(deps, "deps must not be null");
        this.knownUserIds = knownUserIds;
        this.menuOpener = menuOpener;
    }

    /** The same command in another form, sharing everything else. */
    public WarpCommand withForm(Form other) {
        return new WarpCommand(other, deps, knownUserIds, menuOpener);
    }

    /**
     * How a bare {@code /warp} opens the teleport menu (Phase 6): returns false when the menu isn't
     * available, and the chat list is shown instead. Null turns the menu off.
     */
    public void setMenuOpener(Predicate<Player> opener) {
        menuOpener.set(opener != null ? opener : player -> false);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (form == Form.WARP && args.length == 0 && sender instanceof Player player) {
            deps.support().whenAllowed(player, TeleportNodes.WARP, () -> {
                if (!menuOpener.get().test(player)) {
                    listTo(player);
                }
            });
            return true;
        }
        if (form == Form.LIST || args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("list"))) {
            list(sender);
            return true;
        }
        List<String> words = new ArrayList<>(Arrays.asList(args));
        boolean silent = false;
        if (words.size() > 1 && words.get(words.size() - 1).equalsIgnoreCase("-s")) {
            silent = true;
            words.remove(words.size() - 1);
        }
        String all = String.join(" ", words);
        if (!silent && !(sender instanceof Player) && words.size() < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage from the console: /warp <destination> <player> [-s]");
            return true;
        }
        if (words.size() >= 2) {
            // "/warp New Haven" (a two-word place) or "/warp Kardenna Bob" (send Bob)?
            String place = String.join(" ", words.subList(0, words.size() - 1));
            String playerName = words.get(words.size() - 1);
            Player target = deps.targets().find(sender, playerName);
            if (target != null && (silent || !(sender instanceof Player) || !knowsPlace(sender, all))) {
                other(sender, place, target, silent);
                return true;
            }
            if (silent || !(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "No online player named '" + playerName + "'.");
                return true;
            }
        }
        self(sender, all);
        return true;
    }

    // ----- /warp <destination> -----

    private void self(CommandSender sender, String name) {
        Player player = deps.support().requirePlayer(sender);
        if (player == null) {
            return;
        }
        warpSelf(player, list -> resolve(player, list, name));
    }

    /**
     * The teleport menu's destination tiles (Phase 6): {@code player} warps to the destination with
     * this domain id exactly as {@code /warp <name>} would - node, locks, warmup, every engine guard,
     * then the server-side charge. The id is looked up in the player's own list again, so a stale or
     * forged click can't reach a place the list doesn't offer. Main thread.
     */
    public void warpTo(Player player, int domainId) {
        warpSelf(player, list -> {
            for (KnkTeleportDestination destination : list) {
                if (destination.domainId() == domainId) {
                    return destination;
                }
            }
            player.sendMessage(ChatColor.RED + "That teleport destination isn't available any more. /warps lists them.");
            return null;
        });
    }

    /** {@code player}'s own warp to whatever {@code pick} chooses from their list (null: it already said why not). */
    private void warpSelf(Player player, Function<List<KnkTeleportDestination>, KnkTeleportDestination> pick) {
        deps.support().whenAllowed(player, TeleportNodes.WARP, () -> withDestinations(player, player, list -> {
            KnkTeleportDestination destination = pick.apply(list);
            if (destination == null) {
                return;
            }
            withBypass(player, (bypassRequirements, bypassCost) -> {
                String lock = destination.lockReason(bypassRequirements, bypassCost);
                if (lock != null) {
                    player.sendMessage(ChatColor.RED + lock);
                    return;
                }
                Location location = locationOf(destination);
                if (location == null) {
                    player.sendMessage(ChatColor.RED + destination.name() + " isn't available right now.");
                    return;
                }
                TeleportPlan plan = TeleportPlan.warp(player, location, destination.name(),
                    deps.charges().warp(player, destination, bypassRequirements, bypassCost));
                deps.teleportService().start(plan).thenAccept(outcome -> reportOwn(player, destination, outcome));
            });
        }));
    }

    // ----- /warp <destination> <player> -----

    private void other(CommandSender sender, String name, Player target, boolean silentRequested) {
        if (PlayerCommandSupport.isSelf(sender, target) && !silentRequested) {
            self(sender, name);
            return;
        }
        deps.support().whenAllowed(sender, TeleportNodes.STAFF_OTHERS, () -> withSilent(sender, silentRequested, silent ->
            deps.rankCheck().whenOutranks(sender, target.getName(), summary -> withDestinations(sender, target, list -> {
                KnkTeleportDestination destination = resolve(sender, list, name);
                if (destination == null) {
                    return;
                }
                Location location = locationOf(destination);
                if (location == null) {
                    sender.sendMessage(ChatColor.RED + destination.name() + " isn't available right now.");
                    return;
                }
                // Staff teleport: instant, free, audited (DESIGN §3.10); requirements don't apply.
                TeleportPlan plan = TeleportPlan.staffToLocation(sender, target, location, destination.name(), silent);
                deps.teleportService().start(plan).thenAccept(outcome -> StaffTeleportCommand.report(sender, plan, outcome, () -> {
                    sender.sendMessage(ChatColor.GREEN + "Sent " + target.getName() + " to " + destination.name() + ".");
                    if (!silent && target.isOnline()) {
                        target.sendMessage(ChatColor.GRAY + sender.getName() + " sent you to " + destination.name() + ".");
                    }
                }));
            }))));
    }

    // ----- /warps -----

    private void list(CommandSender sender) {
        Player player = deps.support().requirePlayer(sender);
        if (player == null) {
            sender.sendMessage(ChatColor.YELLOW + "Usage from the console: /warp <destination> <player> [-s]");
            return;
        }
        deps.support().whenAllowed(player, TeleportNodes.WARP, () -> listTo(player));
    }

    /** The chat list, after the node check. */
    private void listTo(Player player) {
        withDestinations(player, player, list ->
            withBypass(player, (bypassRequirements, bypassCost) -> {
                if (list.isEmpty()) {
                    player.sendMessage(ChatColor.YELLOW + "There are no teleport destinations yet.");
                    return;
                }
                player.sendMessage(Component.text("Teleport destinations (/warp <name>):", ColorOptions.statsformat));
                for (KnkTeleportDestination destination : list) {
                    player.sendMessage(listLine(destination, bypassRequirements, bypassCost));
                }
            }));
    }

    /** "Kardenna (Town) - 10 gems - Available" (click to warp) or "... - Locked: Reach title X to unlock". */
    static Component listLine(KnkTeleportDestination destination, boolean bypassRequirements, boolean bypassCost) {
        String lock = destination.lockReason(bypassRequirements, bypassCost);
        String price = destination.priceGems() > 0 && !bypassCost ? destination.priceGems() + " gems" : "free";
        Component name = Component.text(destination.name(), lock == null ? NamedTextColor.GREEN : NamedTextColor.GRAY);
        if (lock == null) {
            String command = "/warp " + destination.qualifiedName();
            name = name.clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Click to teleport to " + destination.name(), NamedTextColor.GREEN)));
        }
        return Component.text()
            .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
            .append(name)
            .append(Component.text(" (" + destination.domainType() + ", " + price + ") ", ColorOptions.message))
            .append(lock == null
                ? Component.text("Available!", NamedTextColor.GREEN)
                : Component.text("Locked! " + lock, NamedTextColor.RED))
            .build();
    }

    // ----- plumbing -----

    private boolean knowsPlace(CommandSender sender, String name) {
        if (!(sender instanceof Player player)) {
            return false;
        }
        Integer userId = knownUserIds.get(player.getUniqueId());
        return userId != null && WarpTargets.resolve(deps.destinations().cachedOrEmpty(userId), name).found();
    }

    /** Tells {@code viewer} when {@code name} matches nothing or several places; else the match. */
    private static KnkTeleportDestination resolve(CommandSender viewer, List<KnkTeleportDestination> list, String name) {
        WarpTargets.Match match = WarpTargets.resolve(list, name);
        if (match.found()) {
            return match.destination();
        }
        if (match.ambiguous()) {
            viewer.sendMessage(ChatColor.YELLOW + "Several places are called '" + name + "': "
                + String.join(", ", match.choices().stream().map(KnkTeleportDestination::qualifiedName).toList())
                + ". Use one of those.");
        } else {
            viewer.sendMessage(ChatColor.RED + "No teleport destination named '" + name + "'. /warps lists them.");
        }
        return null;
    }

    /**
     * Load {@code whose} destination list (async, cached) and hand it to {@code next} on the main
     * thread; tells {@code viewer} when it can't be loaded.
     */
    private void withDestinations(CommandSender viewer, Player whose, Consumer<List<KnkTeleportDestination>> next) {
        UUID id = whose.getUniqueId();
        CompletableFuture<List<KnkTeleportDestination>> loaded;
        try {
            loaded = deps.userIds().idOf(id).thenCompose(userId -> {
                if (userId == null) {
                    return CompletableFuture.completedFuture(null);
                }
                knownUserIds.put(id, userId);
                return deps.destinations().listAsync(userId);
            });
        } catch (RuntimeException ex) {
            loaded = CompletableFuture.failedFuture(ex);
        }
        loaded.whenComplete((list, ex) -> deps.support().mainThread().execute(() -> {
            if (ex != null || list == null) {
                if (ex != null) {
                    LOGGER.log(Level.WARNING, "[KnK Teleport] Could not load the warp destinations of " + whose.getName(), ex);
                }
                viewer.sendMessage(ChatColor.RED + UNAVAILABLE);
                return;
            }
            try {
                next.accept(list);
            } catch (RuntimeException failure) {
                LOGGER.log(Level.SEVERE, "[KnK Teleport] /warp failed for " + viewer.getName(), failure);
                viewer.sendMessage(ChatColor.RED + "Warp failed - see the server log.");
            }
        }));
    }

    @FunctionalInterface
    private interface BypassConsumer {
        void accept(boolean bypassRequirements, boolean bypassCost);
    }

    /** Resolve {@code player}'s two warp bypass nodes and continue on the main thread. */
    private void withBypass(Player player, BypassConsumer next) {
        CompletableFuture<Boolean> requirements = deps.permissions().has(player, TeleportNodes.BYPASS_REQUIREMENTS).exceptionally(ex -> false);
        CompletableFuture<Boolean> cost = deps.permissions().has(player, TeleportNodes.BYPASS_COST).exceptionally(ex -> false);
        CompletableFuture.allOf(requirements, cost).whenComplete((ignored, ex) -> deps.support().mainThread().execute(() ->
            next.accept(Boolean.TRUE.equals(requirements.getNow(false)), Boolean.TRUE.equals(cost.getNow(false)))));
    }

    private Location locationOf(KnkTeleportDestination destination) {
        return TeleportCharges.toLocation(destination, deps.worlds());
    }

    /** Silent while the actor is vanished (DESIGN §3.4.4); otherwise only when asked, which needs the node. */
    private void withSilent(CommandSender sender, boolean requested, Consumer<Boolean> next) {
        if (sender instanceof Player player && deps.isVanished().test(player)) {
            next.accept(true);
        } else if (requested) {
            deps.support().whenAllowed(sender, TeleportNodes.STAFF_SILENT, () -> next.accept(true));
        } else {
            next.accept(false);
        }
    }

    /** Tell a player how their own warp ended; the engine already explained a cancelled warmup. */
    private static void reportOwn(Player player, KnkTeleportDestination destination, TeleportOutcome outcome) {
        if (!player.isOnline()) {
            return;
        }
        switch (outcome.status()) {
            case TELEPORTED -> player.sendMessage(ChatColor.GREEN + "You teleported to " + destination.name() + ".");
            case CANCELLED -> { }
            case DENIED, FAILED -> player.sendMessage(ChatColor.RED
                + (outcome.message() != null ? outcome.message() : "The teleport didn't happen."));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (form == Form.LIST || args.length == 0) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            if ("list".startsWith(args[0].toLowerCase(Locale.ROOT))) {
                out.add("list");
            }
            if (sender instanceof Player player) {
                Integer userId = knownUserIds.get(player.getUniqueId());
                if (userId != null) {
                    out.addAll(WarpTargets.complete(deps.destinations().cachedOrEmpty(userId), args[0]));
                    deps.destinations().listAsync(userId); // refresh in the background when stale
                } else {
                    prefetch(player);
                }
            }
            return out;
        }
        if (args.length == 2) {
            return deps.targets().complete(sender, args[1]);
        }
        if (args.length == 3 && "-s".startsWith(args[2].toLowerCase(Locale.ROOT))) {
            return List.of("-s");
        }
        return Collections.emptyList();
    }

    /** Look the player up and load their list, so the next tab press can complete. */
    private void prefetch(Player player) {
        UUID id = player.getUniqueId();
        try {
            deps.userIds().idOf(id).thenAccept(userId -> {
                if (userId != null) {
                    knownUserIds.put(id, userId);
                    deps.destinations().listAsync(userId);
                }
            }).exceptionally(ex -> null);
        } catch (RuntimeException ignored) {
            // Tab completion must never throw.
        }
    }

    /** A player left: their id is looked up again next time. */
    public void forget(UUID player) {
        knownUserIds.remove(player);
    }
}
