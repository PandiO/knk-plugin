package net.knightsandkings.knk.paper.commands;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.landmarks.KnkLandmark;
import net.knightsandkings.knk.core.domain.landmarks.LandmarkWrite;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.landmarks.LandmarkCommandArgs;
import net.knightsandkings.knk.core.navigation.model.DomainPlace;
import net.knightsandkings.knk.core.navigation.model.Landmark;
import net.knightsandkings.knk.core.navigation.model.NavigationModel;
import net.knightsandkings.knk.core.ports.api.LandmarksApi;

/**
 * {@code /knk landmark} (off-road navigation phase 5.1, PandiO/knk-workspace#708, DESIGN § 4.5): staff make and
 * change landmarks in game, the same records the web app edits ({@code api/landmarks}, with the plugin's service key).
 * Gated on {@value #PERMISSION} (the {@code /knk} metadata node, checked in-house, so {@code knk.admin.world.*},
 * {@code knk.admin.*} and {@code *} match it). With {@code navigation.landmarks} off every action says so.
 *
 * <pre>
 * create &lt;name&gt; [region &lt;id&gt;]                at the sender's position (no radius form since #985, LD08)
 * list                                       the sender's world (all worlds from the console)
 * show &lt;landmark&gt;
 * move &lt;landmark&gt;                            the pin to the sender's position
 * delete &lt;landmark&gt;
 * link &lt;landmark&gt; &lt;domain&gt;                    the parent: a domain by name or #id, never the landmark itself
 * unlink &lt;landmark&gt;
 * </pre>
 *
 * A landmark is its name (spaces allowed) or {@code #id}. After a change the navigation model is refreshed, so
 * {@code /navigate} sees it within seconds instead of the next minute.
 */
public class LandmarkAdminCommand {

    public static final String PERMISSION = "knk.admin.world.landmark";
    static final List<String> ACTIONS = List.of("create", "list", "show", "move", "delete", "link", "unlink");
    static final String OFF = "Landmarks are off on this server (navigation.landmarks: false in config.yml).";
    private static final int LISTED = 20;

    private final boolean enabled;
    private final LandmarksApi api;
    private final Supplier<NavigationModel> model;
    private final Runnable refreshModel;
    private final Executor mainThread;

    /**
     * @param enabled      {@code navigation.landmarks}
     * @param api          {@code api/landmarks}
     * @param model        the navigation model (domain names for {@code link}, landmark names for completion); may answer null
     * @param refreshModel asks for a model refresh after a change
     * @param mainThread   where replies are sent (the server's main thread)
     */
    public LandmarkAdminCommand(boolean enabled, LandmarksApi api, Supplier<NavigationModel> model, Runnable refreshModel,
                                Executor mainThread) {
        this.enabled = enabled;
        this.api = api;
        this.model = Objects.requireNonNull(model, "model");
        this.refreshModel = Objects.requireNonNull(refreshModel, "refreshModel");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
    }

    public static CommandMetadata metadata() {
        return new CommandMetadata(
            "landmark",
            "Create, move, link and delete landmarks: named places players can /navigate to (off-road navigation)",
            "/knk landmark <create <name> [region <id>]|list|show|move|delete|link|unlink> ...",
            PERMISSION,
            List.of("/knk landmark create Throne room region keep_throne", "/knk landmark create Old well",
                "/knk landmark list", "/knk landmark link Throne room Keep", "/knk landmark move #12",
                "/knk landmark delete Old well"));
    }

    // ==================== completion ====================

    public List<String> complete(CommandSender sender, String[] args) {
        if (!enabled || args.length == 0) {
            return List.of();
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            return ACTIONS.stream().filter(a -> a.startsWith(last)).toList();
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("create")) {
            return args.length >= 3 ? List.of("region").stream().filter(k -> k.startsWith(last)).toList() : List.of();
        }
        if (args.length == 2 && List.of("show", "move", "delete", "link", "unlink").contains(action)) {
            return landmarkNames(worldOf(sender)).stream()
                .map(name -> name.contains(" ") ? name.substring(0, name.indexOf(' ')) : name)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(last)).distinct().sorted().toList();
        }
        if (action.equals("link") && args.length >= 3) {
            NavigationModel current = model.get();
            if (current == null) {
                return List.of();
            }
            // the parent may be any domain, another landmark too, but never the landmark itself (#985)
            Set<Integer> self = landmarkIdsNamed(current, worldOf(sender),
                String.join(" ", Arrays.asList(args).subList(1, args.length - 1)));
            return current.domains().stream().filter(d -> !self.contains(d.id())).map(DomainPlace::name)
                .map(name -> name.contains(" ") ? name.substring(0, name.indexOf(' ')) : name)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(last)).distinct().sorted().limit(50).toList();
        }
        return List.of();
    }

    /** The ids of the landmarks of {@code world} (null: any) whose name, or {@code #id}, is {@code text}. */
    private static Set<Integer> landmarkIdsNamed(NavigationModel current, String world, String text) {
        String wanted = text == null ? "" : text.trim();
        Optional<Integer> byId = LandmarkCommandArgs.idRef(wanted);
        Set<Integer> ids = new HashSet<>();
        for (Landmark l : current.landmarks()) {
            if (byId.isPresent() ? byId.get() == l.id()
                : (world == null || world.equalsIgnoreCase(l.world())) && l.name().equalsIgnoreCase(wanted)) {
                ids.add(l.id());
            }
        }
        return ids;
    }

    private List<String> landmarkNames(String world) {
        NavigationModel current = model.get();
        if (current == null) {
            return List.of();
        }
        return current.landmarks().stream()
            .filter(l -> world == null || world.equalsIgnoreCase(l.world()))
            .map(Landmark::name).toList();
    }

    // ==================== execute ====================

    public boolean execute(CommandSender sender, String[] args) {
        if (!enabled || api == null) {
            sender.sendMessage(ChatColor.RED + OFF);
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        List<String> rest = Arrays.asList(args).subList(1, args.length);
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "create" -> create(sender, rest);
                case "list" -> list(sender);
                case "show" -> withLandmark(sender, rest, 0, (landmark, words) -> show(sender, landmark));
                case "move" -> move(sender, rest);
                case "delete" -> withLandmark(sender, rest, 0, (landmark, words) -> delete(sender, landmark));
                case "link" -> withLandmark(sender, rest, 1, (landmark, words) -> link(sender, landmark, words));
                case "unlink" -> withLandmark(sender, rest, 0, (landmark, words) -> update(sender, landmark,
                    LandmarkWrite.from(landmark).withParent(null), "Unlinked " + landmark.name() + " from its parent."));
                default -> usage(sender);
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage(ChatColor.RED + e.getMessage());
        }
        return true;
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Usage: /knk landmark create <name> [region <id>] | list | "
            + "show <landmark> | move <landmark> | delete <landmark> | link <landmark> <domain> | unlink <landmark>");
        sender.sendMessage(ChatColor.GRAY + "A landmark is its name or #id.");
    }

    private void create(CommandSender sender, List<String> words) {
        Player player = playerOrRefuse(sender, "create");
        if (player == null) {
            return;
        }
        LandmarkCommandArgs.Create parsed;
        try {
            parsed = LandmarkCommandArgs.parseCreate(words);
        } catch (LandmarkCommandArgs.RadiusGone gone) {
            sender.sendMessage(ChatColor.RED + gone.getMessage());
            usage(sender);
            return;
        }
        LandmarkWrite write = new LandmarkWrite(parsed.name(), pinAt(player.getLocation(), null), null, parsed.areaKind(),
            parsed.regionId(), null);
        reply(sender, api.create(write), created -> {
            refreshModel.run();
            sender.sendMessage(ChatColor.GREEN + "Created landmark " + ChatColor.WHITE + created.name() + ChatColor.GREEN
                + " (#" + created.id() + ") " + describeArea(created) + ".");
        });
    }

    private void list(CommandSender sender) {
        String world = worldOf(sender);
        reply(sender, api.list(world), landmarks -> {
            if (landmarks.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "No landmarks" + (world == null ? "" : " in " + world) + ".");
                return;
            }
            sender.sendMessage(ChatColor.GRAY + "Landmarks" + (world == null ? "" : " in " + world) + " ("
                + landmarks.size() + "):");
            landmarks.stream().sorted(Comparator.comparing(KnkLandmark::name, String.CASE_INSENSITIVE_ORDER)).limit(LISTED)
                .forEach(l -> sender.sendMessage(ChatColor.GRAY + "#" + l.id() + " " + ChatColor.WHITE + l.name()
                    + ChatColor.GRAY + " " + describeArea(l)
                    + (l.parentDomainName() == null ? "" : ", in " + l.parentDomainName())
                    + (l.navigationEnabled() ? "" : ", not in /navigate")));
            if (landmarks.size() > LISTED) {
                sender.sendMessage(ChatColor.GRAY + "... and " + (landmarks.size() - LISTED) + " more (the web app lists all).");
            }
        });
    }

    private void show(CommandSender sender, KnkLandmark l) {
        sender.sendMessage(ChatColor.GRAY + "Landmark #" + l.id() + ": " + ChatColor.WHITE + l.name());
        sender.sendMessage(ChatColor.GRAY + "At " + Math.round(l.x()) + " " + Math.round(l.y()) + " " + Math.round(l.z())
            + " in " + l.world() + ", " + describeArea(l));
        sender.sendMessage(ChatColor.GRAY + "Parent: " + ChatColor.WHITE
            + (l.parentDomainName() == null ? "none" : l.parentDomainName()
                + (l.parentDomainType() == null ? "" : " (" + l.parentDomainType() + ")") + " #" + l.parentDomainId()));
        sender.sendMessage(ChatColor.GRAY + "Enter and leave messages: " + ChatColor.WHITE
            + (l.showEnterLeaveMessages() ? "on" : "off"));
        sender.sendMessage(ChatColor.GRAY + "In /navigate: " + ChatColor.WHITE + (l.navigationEnabled() ? "yes" : "no")
            + ChatColor.GRAY + ", route preference " + ChatColor.WHITE + l.preference());
    }

    private void move(CommandSender sender, List<String> words) {
        Player player = playerOrRefuse(sender, "move");
        if (player == null) {
            return;
        }
        withLandmark(sender, words, 0, (landmark, rest) -> {
            if (!player.getWorld().getName().equalsIgnoreCase(landmark.world())) {
                sender.sendMessage(ChatColor.RED + landmark.name() + " is in " + landmark.world()
                    + ": stand in that world to move it.");
                return;
            }
            update(sender, landmark, LandmarkWrite.from(landmark).withPin(pinAt(player.getLocation(), landmark.locationId())),
                "Moved " + landmark.name() + " to where you stand.");
        });
    }

    private void delete(CommandSender sender, KnkLandmark landmark) {
        reply(sender, api.delete(landmark.id()), done -> {
            refreshModel.run();
            sender.sendMessage(ChatColor.GREEN + "Deleted landmark " + ChatColor.WHITE + landmark.name()
                + ChatColor.GREEN + " (#" + landmark.id() + ").");
        });
    }

    private void link(CommandSender sender, KnkLandmark landmark, List<String> words) {
        String domainText = String.join(" ", words).trim();
        NavigationModel current = model.get();
        if (current == null) {
            sender.sendMessage(ChatColor.RED + "The domain list isn't loaded yet; try again in a moment.");
            return;
        }
        Optional<Integer> byId = LandmarkCommandArgs.idRef(domainText);
        List<DomainPlace> matches = byId.isPresent()
            ? current.domain(byId.get()).map(List::of).orElse(List.of())
            : current.domains().stream().filter(d -> d.name().equalsIgnoreCase(domainText)).toList();
        if (matches.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No domain " + domainText + ".");
            return;
        }
        if (matches.size() > 1) {
            sender.sendMessage(ChatColor.RED + "Several domains are called " + domainText + "; use its #id: "
                + String.join(", ", matches.stream().map(d -> d.domainType() + " #" + d.id()).toList()));
            return;
        }
        DomainPlace domain = matches.get(0);
        if (domain.id() == landmark.id()) {
            sender.sendMessage(ChatColor.RED + "A landmark can't be its own parent.");
            return;
        }
        update(sender, landmark, LandmarkWrite.from(landmark).withParent(domain.id()),
            "Linked " + landmark.name() + " to " + domain.name() + " (" + domain.domainType() + ").");
    }

    private void update(CommandSender sender, KnkLandmark landmark, LandmarkWrite write, String done) {
        reply(sender, api.update(landmark.id(), write), saved -> {
            refreshModel.run();
            sender.sendMessage(ChatColor.GREEN + done);
        });
    }

    // ==================== landmark lookup ====================

    private interface WithLandmark {
        void run(KnkLandmark landmark, List<String> rest);
    }

    /** Resolves the leading words to one landmark of the sender's world (any world for {@code #id} and the console). */
    private void withLandmark(CommandSender sender, List<String> words, int minRest, WithLandmark action) {
        if (words.isEmpty()) {
            usage(sender);
            return;
        }
        Optional<Integer> id = LandmarkCommandArgs.idRef(words.get(0));
        if (id.isPresent()) {
            if (words.size() - 1 < minRest) {
                usage(sender);
                return;
            }
            reply(sender, api.get(id.get()), found -> {
                if (found.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "No landmark #" + id.get() + ".");
                    return;
                }
                action.run(found.get(), words.subList(1, words.size()));
            });
            return;
        }
        String world = worldOf(sender);
        reply(sender, api.list(world), landmarks -> {
            List<String> names = landmarks.stream().map(KnkLandmark::name).toList();
            Optional<LandmarkCommandArgs.Split> split = LandmarkCommandArgs.split(words, names, minRest);
            if (split.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "No landmark " + String.join(" ", minRest > 0 && words.size() > 1
                    ? words.subList(0, words.size() - minRest) : words) + (world == null ? "" : " in " + world)
                    + ". Use /knk landmark list, or its #id.");
                return;
            }
            List<KnkLandmark> named = landmarks.stream()
                .filter(l -> l.name().equalsIgnoreCase(split.get().reference())).toList();
            if (named.size() > 1) {
                sender.sendMessage(ChatColor.RED + "Several landmarks are called " + split.get().reference() + "; use its #id: "
                    + String.join(", ", named.stream().map(l -> "#" + l.id() + " in " + l.world()).toList()));
                return;
            }
            action.run(named.get(0), split.get().rest());
        });
    }

    // ==================== helpers ====================

    private <T> void reply(CommandSender sender, CompletableFuture<T> call, java.util.function.Consumer<T> onSuccess) {
        call.whenComplete((value, error) -> mainThread.execute(() -> {
            if (error != null) {
                sender.sendMessage(ChatColor.RED + refusal(error));
                return;
            }
            try {
                onSuccess.accept(value);
            } catch (IllegalArgumentException e) {
                sender.sendMessage(ChatColor.RED + e.getMessage());
            }
        }));
    }

    /** The API's reason for a 400, "not found" for a 404, else a short failure line. */
    static String refusal(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
            && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof ApiException api) {
            if (api.getStatusCode() == 404) {
                return "That landmark no longer exists.";
            }
            if (api.getStatusCode() == 400 && api.getResponseBody() != null && !api.getResponseBody().isBlank()) {
                String body = api.getResponseBody().trim();
                if (body.startsWith("\"") && body.endsWith("\"") && body.length() > 1) {
                    body = body.substring(1, body.length() - 1);
                }
                return body;
            }
            if (api.getStatusCode() == 401 || api.getStatusCode() == 403) {
                return "The web API refused the change (check the plugin's api.auth key).";
            }
        }
        return "The web API could not be reached; nothing was changed. (" + cause.getClass().getSimpleName() + ")";
    }

    /** The area in a few words (landmark-domain § 5 rule 3): a point, its own region, or the parent's area. */
    static String describeArea(KnkLandmark l) {
        return switch (l.areaKind()) {
            case OWN_REGION -> "region " + (l.wgRegionId() == null ? "?" : l.wgRegionId())
                + (l.subRegionIds().isEmpty() ? "" : " and " + l.subRegionIds().size()
                    + (l.subRegionIds().size() == 1 ? " sub-region" : " sub-regions"));
            case LINKED_DOMAIN -> "the area of " + (l.parentDomainName() == null ? "its parent" : l.parentDomainName());
            case NONE -> "point only";
        };
    }

    private static Player playerOrRefuse(CommandSender sender, String action) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(ChatColor.RED + "Only a player can " + action + " a landmark: it takes the position you stand on.");
        return null;
    }

    private static String worldOf(CommandSender sender) {
        return sender instanceof Player player ? player.getWorld().getName() : null;
    }

    private static LandmarkWrite.Pin pinAt(Location at, Integer locationId) {
        return new LandmarkWrite.Pin(locationId, at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(),
            at.getPitch());
    }
}
