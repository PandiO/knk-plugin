package net.knightsandkings.knk.paper.roads;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeUpdate;
import net.knightsandkings.knk.core.domain.roads.RoadMaterialRole;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.domain.roads.RoadNodeAnchor;
import net.knightsandkings.knk.core.domain.roads.RoadNodeUpdate;
import net.knightsandkings.knk.core.domain.roads.RoadProfile;
import net.knightsandkings.knk.core.domain.roads.RoadProfileUpsert;
import net.knightsandkings.knk.core.domain.roads.RoadSeed;
import net.knightsandkings.knk.core.domain.roads.RoadSeedCreate;
import net.knightsandkings.knk.core.domain.roads.RoadTile;
import net.knightsandkings.knk.core.domain.streets.StreetSummary;
import net.knightsandkings.knk.core.ports.api.RoadNetworkCommandApi;
import net.knightsandkings.knk.core.ports.api.RoadNetworkQueryApi;
import net.knightsandkings.knk.core.ports.api.StreetsQueryApi;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.SnapPoint;
import net.knightsandkings.knk.core.roads.route.Snapper;
import net.knightsandkings.knk.core.roads.survey.ProposedProfile;
import net.knightsandkings.knk.paper.commands.CommandMetadata;
import net.knightsandkings.knk.paper.commands.SubcommandExecutor;
import net.kyori.adventure.text.Component;

/**
 * {@code /knk road …} - the road network's in-game admin tools (DESIGN §7, plan Phase 3
 * "RoadAdminCommand"). Gated by {@code knk.admin.road} like {@code DiscoveryAdminCommand}: the plugin.yml
 * node (the {@code knk.admin} umbrella grants it) or the same node in the web app's permission system;
 * the console passes. Every service is a supplier because {@code registerCommands()} runs before
 * {@code initializeRoads()}; a null service means "navigation.enabled: false".
 *
 * <p>Subcommands: {@code survey start [profile]|stop|cancel|save|merge <profile>|discard},
 * {@code profile list|show|role|ambiguous|enable|disable}, {@code build here|tile <x> <z>|radius <r>|dirty|all|
 * status|cancel}, {@code seed add [note]|remove <id>|list}, {@code show [radius] [all]|hide},
 * {@code street <street> [edgeId] [--continue]}, {@code node name <name>|unname|merge <keep> <merge>|anchor [name]|
 * lock|unlock}, {@code record start|stop [street]|cancel}, {@code edge set <id|here> cost|oneway|nogps|close|open|
 * profile|unlabel}, {@code edge delete <id>}, {@code tiles [page]}, {@code why}, {@code reload}, {@code status},
 * {@code goto <x> <y> <z>} (the target of every clickable teleport).
 */
public class RoadAdminCommand implements SubcommandExecutor {

    public static final String NODE = "knk.admin.road";
    public static final String USAGE = "/knk road <survey|profile|build|seed|show|hide|street|node|record|edge|tiles|reload|status|why>";
    static final int PAGE_SIZE = 10;
    /** How far from the admin a node/edge may be for "here". */
    static final double HERE_DISTANCE = 6.0;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final List<String> ROOTS = List.of("survey", "profile", "build", "seed", "show", "hide", "street",
        "node", "record", "edge", "tiles", "reload", "status", "why", "goto");

    private final RoadNetworkQueryApi queryApi;
    private final RoadNetworkCommandApi commandApi;
    private final StreetsQueryApi streetsQueryApi;
    private final Executor mainThread;
    private final BiPredicate<Player, String> knkPermission;
    private final Supplier<RoadNetworkCache> cache;
    private final Supplier<RoadDirtyTracker> dirtyTracker;
    private final Supplier<RoadOverlayRenderer> overlay;
    private final Supplier<RoadSurveyService> surveys;
    private final Supplier<RoadBuildQueue> builds;
    /** Phase 4: {@code why} needs /navigate's service and catalogue; both null until navigation started. */
    private volatile Supplier<net.knightsandkings.knk.paper.navigation.NavigationService> navigation = () -> null;
    private volatile Supplier<net.knightsandkings.knk.paper.navigation.NavigationDestinations> destinations = () -> null;

    public RoadAdminCommand(RoadNetworkQueryApi queryApi, RoadNetworkCommandApi commandApi, StreetsQueryApi streetsQueryApi,
                            Executor mainThread, BiPredicate<Player, String> knkPermission,
                            Supplier<RoadNetworkCache> cache, Supplier<RoadDirtyTracker> dirtyTracker,
                            Supplier<RoadOverlayRenderer> overlay, Supplier<RoadSurveyService> surveys,
                            Supplier<RoadBuildQueue> builds) {
        this.queryApi = Objects.requireNonNull(queryApi, "queryApi");
        this.commandApi = Objects.requireNonNull(commandApi, "commandApi");
        this.streetsQueryApi = streetsQueryApi;
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.knkPermission = knkPermission;
        this.cache = cache;
        this.dirtyTracker = dirtyTracker;
        this.overlay = overlay;
        this.surveys = surveys;
        this.builds = builds;
    }

    public static CommandMetadata metadata() {
        // No metadata permission: execute checks the node through both permission systems itself.
        return new CommandMetadata("road", "Survey, build, review and tune the road network", USAGE, null,
                List.of("/knk road survey start \"Kardenna main street\"", "/knk road build radius 1500", "/knk road show",
                        "/knk road street \"Market Street\" --continue", "/knk road tiles"));
    }

    // ===== dispatch =====

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!hasNode(sender)) {
            sender.sendMessage(RoadMessages.bad("You don't have permission to use this command."));
            return true;
        }
        if (cache.get() == null) {
            sender.sendMessage(RoadMessages.bad("Road navigation is disabled (navigation.enabled in config.yml)."));
            return true;
        }
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        String[] rest = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
        try {
            switch (action) {
                case "survey" -> survey(sender, rest);
                case "profile" -> profile(sender, rest);
                case "build" -> build(sender, rest);
                case "seed" -> seed(sender, rest);
                case "show" -> show(sender, rest);
                case "hide" -> hide(sender);
                case "street" -> street(sender, rest);
                case "node" -> node(sender, rest);
                case "record" -> record(sender, rest);
                case "edge" -> edge(sender, rest);
                case "tiles" -> tiles(sender, rest);
                case "reload" -> reload(sender);
                case "status" -> status(sender);
                case "why" -> why(sender, rest);
                case "goto" -> gotoBlock(sender, rest);
                default -> sender.sendMessage(RoadMessages.usage(USAGE));
            }
        } catch (RuntimeException e) {
            sender.sendMessage(RoadMessages.bad("Command failed: " + e.getMessage()));
        }
        return true;
    }

    boolean hasNode(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        return player.hasPermission(NODE) || (knkPermission != null && knkPermission.test(player, NODE));
    }

    /** Tab completion for {@code KnkAdminCommand} (R13): the arguments after {@code road}. */
    public List<String> complete(CommandSender sender, String[] args) {
        if (!hasNode(sender) || args.length == 0) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return prefix(ROOTS, args[0]);
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        String last = args[args.length - 1];
        if ("why".equals(root)) {
            net.knightsandkings.knk.paper.navigation.NavigationDestinations catalogue = destinations.get();
            if (catalogue == null || !(sender instanceof Player player)) {
                return Collections.emptyList();
            }
            List<String> out = new ArrayList<>(catalogue.complete(Arrays.asList(args).subList(1, args.length),
                player.getWorld().getName()));
            if (args.length >= 3 && "--as".startsWith(last.toLowerCase(Locale.ROOT)) && !out.contains("--as")) {
                out.add("--as");
            }
            return out;
        }
        if (args.length == 2) {
            return switch (root) {
                case "survey" -> prefix(List.of("start", "stop", "cancel", "save", "merge", "discard"), last);
                case "profile" -> prefix(List.of("list", "show", "role", "ambiguous", "enable", "disable"), last);
                case "build" -> prefix(List.of("here", "tile", "radius", "dirty", "all", "status", "cancel"), last);
                case "seed" -> prefix(List.of("add", "remove", "list"), last);
                case "show" -> prefix(List.of("16", "32", "48", "96", "all"), last);
                case "node" -> prefix(List.of("name", "unname", "merge", "anchor", "lock", "unlock", "prune", "unprune"), last);
                case "record" -> prefix(List.of("start", "stop", "cancel"), last);
                case "edge" -> prefix(List.of("set", "delete", "prune"), last);
                case "street" -> prefix(streetNames(sender), last);
                default -> Collections.emptyList();
            };
        }
        if (args.length == 3) {
            switch (root) {
                case "profile" -> {
                    if (!"list".equals(args[1])) {
                        return prefix(profileNames(), last);
                    }
                }
                case "survey" -> {
                    if ("start".equals(args[1]) || "merge".equals(args[1])) {
                        return prefix(profileNames(), last);
                    }
                }
                case "show" -> {
                    return prefix(List.of("all"), last);
                }
                case "edge" -> {
                    return prefix(List.of("here"), last);
                }
                default -> {
                }
            }
        }
        if (args.length == 4 && "edge".equals(root) && "set".equals(args[1])) {
            return prefix(List.of("cost", "oneway", "nogps", "close", "open", "profile", "unlabel"), last);
        }
        if (args.length == 5 && "profile".equals(root) && "role".equals(args[1])) {
            return prefix(Arrays.stream(RoadMaterialRole.values()).map(RoadMaterialRole::apiName).toList(), last);
        }
        if (args.length == 5 && "profile".equals(root) && "ambiguous".equals(args[1])) {
            return prefix(List.of("true", "false"), last);
        }
        return Collections.emptyList();
    }

    /** Options starting with what was typed, ignoring case and the opening quote of a quoted name. */
    private static List<String> prefix(List<String> options, String typed) {
        String lower = openQuoteStripped(typed).toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> openQuoteStripped(o).toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }

    private static String openQuoteStripped(String word) {
        return word.startsWith("\"") ? word.substring(1) : word;
    }

    private List<String> profileNames() {
        RoadNetworkCache c = cache.get();
        return c == null ? List.of() : c.profiles().stream().map(RoadProfile::name).map(RoadAdminCommand::quoteIfSpaced).toList();
    }

    private List<String> streetNames(CommandSender sender) {
        RoadNetworkCache c = cache.get();
        if (c == null || !(sender instanceof Player player)) {
            return List.of();
        }
        return c.snapshot(player.getWorld().getName()).streets().values().stream()
            .map(RoadNetworkSnapshot.Street::name).map(RoadAdminCommand::quoteIfSpaced).toList();
    }

    static String quoteIfSpaced(String name) {
        return name.contains(" ") ? "\"" + name + "\"" : name;
    }

    // ===== survey (RoadSurveyService, Phase 3b) =====

    private void survey(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        RoadSurveyService service = surveys.get();
        if (player == null || service == null) {
            if (service == null) {
                sender.sendMessage(RoadMessages.bad("Surveys are not available (navigation disabled)."));
            }
            return;
        }
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "start" -> service.start(player, args.length > 1 ? joinQuoted(args, 1) : null);
            case "stop" -> service.stop(player);
            case "cancel" -> service.cancel(player);
            case "save" -> service.save(player, args.length > 1 ? joinQuoted(args, 1) : null);
            case "merge" -> {
                if (args.length < 2) {
                    sender.sendMessage(RoadMessages.usage("/knk road survey merge <profile>"));
                    return;
                }
                service.mergeInto(player, joinQuoted(args, 1));
            }
            case "discard" -> service.discard(player);
            default -> sender.sendMessage(RoadMessages.usage("/knk road survey start [profile] | stop | cancel | save [name] | merge <profile> | discard"));
        }
    }

    // ===== record (RoadSurveyService, Phase 3b) =====

    private void record(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        RoadSurveyService service = surveys.get();
        if (player == null || service == null) {
            return;
        }
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "start" -> service.startRecord(player);
            case "stop" -> {
                String streetName = args.length > 1 ? joinQuoted(args, 1) : null;
                if (streetName == null) {
                    service.stopRecord(player, OptionalInt.empty());
                } else {
                    resolveStreet(player, streetName, streetId -> service.stopRecord(player, OptionalInt.of(streetId)));
                }
            }
            case "cancel" -> service.cancelRecord(player);
            default -> sender.sendMessage(RoadMessages.usage("/knk road record start | stop [street] | cancel"));
        }
    }

    // ===== profile =====

    private void profile(CommandSender sender, String[] args) {
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "list" -> queryApi.profiles().whenComplete((profiles, ex) -> mainThread.execute(() -> {
                if (failed(sender, "load the profiles", ex)) {
                    return;
                }
                sender.sendMessage(RoadMessages.prefixed(Component.text("Road profiles (" + profiles.size() + ")", RoadMessages.HIGHLIGHT)));
                for (RoadProfile p : profiles) {
                    sender.sendMessage(Component.text(" #" + p.id() + " ", RoadMessages.INFO)
                        .append(Component.text(p.name(), RoadMessages.VALUE))
                        .append(Component.text(" " + p.roadClass().apiName() + " ×" + p.costMultiplier() + ", "
                            + p.materials().size() + " materials, width " + p.widthMin() + ".." + p.widthMax()
                            + ", " + p.sampleCount() + " samples" + (p.enabled() ? "" : ", DISABLED")
                            + (p.scopeTownIds().isEmpty() ? "" : ", scope towns " + p.scopeTownIds()), RoadMessages.INFO))
                        .append(Component.text("  "))
                        .append(RoadMessages.command("[show]", "/knk road profile show " + p.id())));
                }
            }));
            case "show" -> withProfile(sender, args, 1, profile -> {
                sender.sendMessage(RoadMessages.prefixed(Component.text("Profile #" + profile.id() + " " + profile.name(), RoadMessages.HIGHLIGHT)));
                sender.sendMessage(RoadMessages.field("class", profile.roadClass().apiName() + " ×" + profile.costMultiplier()
                    + (profile.enabled() ? "" : " (disabled)")));
                sender.sendMessage(RoadMessages.field("width", profile.widthMin() + ".." + profile.widthMax() + " blocks, "
                    + profile.sampleCount() + " samples" + (profile.hasStats() ? "" : ", no stored stats")));
                for (RoadMaterialRole role : RoadMaterialRole.values()) {
                    List<ProposedProfile.Material> of = profile.materials().stream().filter(m -> m.role() == role).toList();
                    if (of.isEmpty()) {
                        continue;
                    }
                    StringBuilder line = new StringBuilder();
                    for (ProposedProfile.Material m : of) {
                        if (line.length() > 0) {
                            line.append(", ");
                        }
                        line.append(m.material()).append(" ").append(RoadMessages.percent(m.centreShare()));
                        if (m.ambiguous()) {
                            line.append("?");
                        }
                    }
                    sender.sendMessage(RoadMessages.field(role.apiName(), line.toString()));
                }
                sender.sendMessage(Component.text("? = ambiguous (only counts near a sure road cell). ", RoadMessages.INFO)
                    .append(RoadMessages.suggest("[role]", "/knk road profile role " + profile.id() + " <material> <role>"))
                    .append(Component.text(" "))
                    .append(RoadMessages.suggest("[ambiguous]", "/knk road profile ambiguous " + profile.id() + " <material> true"))
                    .append(Component.text(" "))
                    .append(RoadMessages.command(profile.enabled() ? "[disable]" : "[enable]",
                        "/knk road profile " + (profile.enabled() ? "disable " : "enable ") + profile.id())));
            });
            case "role" -> {
                if (args.length < 4) {
                    sender.sendMessage(RoadMessages.usage("/knk road profile role <profile> <material> <Surface|Edge|Accent|Overlay>"));
                    return;
                }
                RoadMaterialRole role;
                try {
                    role = RoadMaterialRole.fromApiName(args[3]);
                } catch (IllegalArgumentException e) {
                    sender.sendMessage(RoadMessages.bad("Unknown role '" + args[3] + "' (Surface, Edge, Accent, Overlay)."));
                    return;
                }
                String material = args[2].toUpperCase(Locale.ROOT);
                withProfile(sender, args, 1, profile -> saveProfile(sender, profile, editMaterial(profile, material,
                    m -> new ProposedProfile.Material(m.material(), role, m.ambiguous(), m.centreShare(), m.edgeShare(), m.samples()),
                    () -> new ProposedProfile.Material(material, role, false, 0, 0, 0)),
                    "Set " + material + " to " + role.apiName() + " in " + profile.name()));
            }
            case "ambiguous" -> {
                if (args.length < 4) {
                    sender.sendMessage(RoadMessages.usage("/knk road profile ambiguous <profile> <material> <true|false>"));
                    return;
                }
                boolean ambiguous = Boolean.parseBoolean(args[3]);
                String material = args[2].toUpperCase(Locale.ROOT);
                withProfile(sender, args, 1, profile -> {
                    if (profile.materials().stream().noneMatch(m -> m.material().equals(material))) {
                        sender.sendMessage(RoadMessages.bad(material + " is not in profile " + profile.name() + "."));
                        return;
                    }
                    saveProfile(sender, profile, editMaterial(profile, material,
                        m -> new ProposedProfile.Material(m.material(), m.role(), ambiguous, m.centreShare(), m.edgeShare(), m.samples()),
                        null), material + " in " + profile.name() + " is now " + (ambiguous ? "ambiguous" : "unambiguous"));
                });
            }
            case "enable", "disable" -> {
                boolean enable = "enable".equals(action);
                withProfile(sender, args, 1, profile -> saveProfile(sender,
                    new RoadProfile(profile.id(), profile.name(), profile.roadClass(), profile.costMultiplier(), profile.materials(),
                        profile.widthMin(), profile.widthMax(), profile.sampleCount(), enable, profile.scopeTownIds(),
                        profile.statsJson(), profile.createdAt(), profile.updatedAt()),
                    profile.materials(), (enable ? "Enabled " : "Disabled ") + profile.name()));
            }
            default -> sender.sendMessage(RoadMessages.usage(
                "/knk road profile list | show <profile> | role <profile> <material> <role> | ambiguous <profile> <material> <true|false> | enable|disable <profile>"));
        }
    }

    private static List<ProposedProfile.Material> editMaterial(RoadProfile profile, String material,
                                                               java.util.function.UnaryOperator<ProposedProfile.Material> edit,
                                                               Supplier<ProposedProfile.Material> ifMissing) {
        List<ProposedProfile.Material> out = new ArrayList<>();
        boolean found = false;
        for (ProposedProfile.Material m : profile.materials()) {
            if (m.material().equals(material)) {
                out.add(edit.apply(m));
                found = true;
            } else {
                out.add(m);
            }
        }
        if (!found && ifMissing != null) {
            out.add(ifMissing.get());
        }
        return out;
    }

    /** PUTs the profile with the given materials; {@code statsJson = null} keeps the stored stats (2e decision 3). */
    private void saveProfile(CommandSender sender, RoadProfile profile, List<ProposedProfile.Material> materials, String done) {
        RoadProfileUpsert upsert = new RoadProfileUpsert(profile.name(), profile.roadClass(), profile.costMultiplier(), materials,
            profile.widthMin(), profile.widthMax(), profile.sampleCount(), profile.enabled(), profile.scopeTownIds(), null);
        commandApi.updateProfile(profile.id(), upsert).whenComplete((saved, ex) -> mainThread.execute(() -> {
            if (failed(sender, "save the profile", ex)) {
                return;
            }
            sender.sendMessage(RoadMessages.good(done + "."));
            refreshMeta(sender);
        }));
    }

    /** Resolves {@code args[index]} (id or name, quoted names allowed) to a profile, on the main thread. */
    private void withProfile(CommandSender sender, String[] args, int index, java.util.function.Consumer<RoadProfile> then) {
        if (args.length <= index) {
            sender.sendMessage(RoadMessages.usage("/knk road profile " + args[0] + " <profile>"));
            return;
        }
        String wanted = args.length == index + 1 || "role".equals(args[0]) || "ambiguous".equals(args[0]) ? unquote(args[index]) : joinQuoted(args, index);
        queryApi.profiles().whenComplete((profiles, ex) -> mainThread.execute(() -> {
            if (failed(sender, "load the profiles", ex)) {
                return;
            }
            Optional<RoadProfile> match = findProfile(profiles, wanted);
            if (match.isEmpty()) {
                sender.sendMessage(RoadMessages.bad("No profile '" + wanted + "' (see /knk road profile list)."));
                return;
            }
            then.accept(match.get());
        }));
    }

    static Optional<RoadProfile> findProfile(List<RoadProfile> profiles, String idOrName) {
        try {
            int id = Integer.parseInt(idOrName);
            Optional<RoadProfile> byId = profiles.stream().filter(p -> p.id() == id).findFirst();
            if (byId.isPresent()) {
                return byId;
            }
        } catch (NumberFormatException ignored) {
            // a name
        }
        return profiles.stream().filter(p -> p.name().equalsIgnoreCase(idOrName)).findFirst();
    }

    // ===== build (RoadBuildQueue, Phase 3b) =====

    private void build(CommandSender sender, String[] args) {
        RoadBuildQueue queue = builds.get();
        if (queue == null) {
            sender.sendMessage(RoadMessages.bad("Builds are not available (navigation disabled)."));
            return;
        }
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "here" -> {
                Player player = requirePlayer(sender);
                if (player != null) {
                    Location at = player.getLocation();
                    queue.enqueue(sender, List.of(TileKey.of(at.getWorld().getName(), at.getBlockX(), at.getBlockZ())), "here");
                }
            }
            case "tile" -> {
                if (args.length < 3) {
                    sender.sendMessage(RoadMessages.usage("/knk road build tile <tileX> <tileZ> [world]"));
                    return;
                }
                Integer x = parseInt(args[1]);
                Integer z = parseInt(args[2]);
                String world = args.length > 3 ? args[3] : worldOf(sender);
                if (x == null || z == null || world == null) {
                    sender.sendMessage(RoadMessages.bad("Tile coordinates must be numbers; the console must name the world."));
                    return;
                }
                queue.enqueue(sender, List.of(new TileKey(world, x, z)), "tile " + x + "," + z);
            }
            case "radius" -> {
                Player player = requirePlayer(sender);
                Integer radius = args.length > 1 ? parseInt(args[1]) : null;
                if (player == null) {
                    return;
                }
                if (radius == null || radius < 1) {
                    sender.sendMessage(RoadMessages.usage("/knk road build radius <blocks>"));
                    return;
                }
                Location at = player.getLocation();
                queue.enqueue(sender, RoadBuildQueue.tilesWithin(at.getWorld().getName(), at.getBlockX(), at.getBlockZ(), radius),
                    "radius " + radius);
            }
            case "dirty" -> {
                String world = args.length > 1 ? args[1] : worldOf(sender);
                if (world == null) {
                    sender.sendMessage(RoadMessages.bad("The console must name the world: /knk road build dirty <world>"));
                    return;
                }
                queue.enqueueDirty(sender, world);
            }
            case "all" -> {
                String world = args.length > 1 ? args[1] : worldOf(sender);
                if (world == null) {
                    sender.sendMessage(RoadMessages.bad("The console must name the world: /knk road build all <world>"));
                    return;
                }
                queue.enqueueAll(sender, world);
            }
            case "status" -> queue.status(sender);
            case "cancel" -> queue.cancel(sender);
            default -> sender.sendMessage(RoadMessages.usage("/knk road build here | tile <x> <z> | radius <r> | dirty | all | status | cancel"));
        }
    }

    // ===== seed =====

    private void seed(CommandSender sender, String[] args) {
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "add" -> {
                Player player = requirePlayer(sender);
                if (player == null) {
                    return;
                }
                Location floor = floorOf(player);
                String note = args.length > 1 ? joinQuoted(args, 1) : null;
                commandApi.createSeed(RoadSeedCreate.admin(floor.getWorld().getName(), floor.getBlockX(), floor.getBlockY(), floor.getBlockZ(), note))
                    .whenComplete((seed, ex) -> mainThread.execute(() -> {
                        if (failed(sender, "add the seed", ex)) {
                            return;
                        }
                        sender.sendMessage(RoadMessages.good("Seed #" + seed.id() + " added at " + seed.x() + " " + seed.y() + " " + seed.z()
                            + (note == null ? "" : " (" + note + ")") + ". The next build of tile " + TileKey.of(seed.world(), seed.x(), seed.z()) + " starts from it too."));
                    }));
            }
            case "remove" -> {
                Integer id = args.length > 1 ? parseInt(args[1]) : null;
                if (id == null) {
                    sender.sendMessage(RoadMessages.usage("/knk road seed remove <id>"));
                    return;
                }
                commandApi.deleteSeed(id).whenComplete((ok, ex) -> mainThread.execute(() -> {
                    if (failed(sender, "remove seed #" + id, ex)) {
                        return;
                    }
                    sender.sendMessage(Boolean.TRUE.equals(ok) ? RoadMessages.good("Seed #" + id + " removed.")
                        : RoadMessages.warn("Seed #" + id + " did not exist."));
                }));
            }
            case "list" -> {
                String world = args.length > 1 ? args[1] : worldOf(sender);
                if (world == null) {
                    sender.sendMessage(RoadMessages.bad("The console must name the world: /knk road seed list <world>"));
                    return;
                }
                queryApi.seeds(world).whenComplete((seeds, ex) -> mainThread.execute(() -> {
                    if (failed(sender, "load the seeds", ex)) {
                        return;
                    }
                    sender.sendMessage(RoadMessages.prefixed(Component.text("Seeds in " + world + " (" + seeds.size() + ")", RoadMessages.HIGHLIGHT)));
                    int shown = 0;
                    for (RoadSeed s : seeds) {
                        if (shown++ >= 30) {
                            sender.sendMessage(Component.text(" … " + (seeds.size() - 30) + " more (the web app lists all)", RoadMessages.INFO));
                            break;
                        }
                        sender.sendMessage(Component.text(" #" + s.id() + " " + s.source().apiName() + " ", RoadMessages.INFO)
                            .append(RoadMessages.teleport(s.x(), s.y(), s.z()))
                            .append(Component.text(s.note() == null ? "" : " " + s.note(), RoadMessages.VALUE))
                            .append(Component.text(" "))
                            .append(RoadMessages.command("[remove]", "/knk road seed remove " + s.id())));
                    }
                }));
            }
            default -> sender.sendMessage(RoadMessages.usage("/knk road seed add [note] | remove <id> | list"));
        }
    }

    // ===== show / hide =====

    private void show(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        RoadOverlayRenderer renderer = overlay.get();
        if (player == null || renderer == null) {
            return;
        }
        int radius = RoadOverlayRenderer.DEFAULT_RADIUS;
        boolean all = false;
        for (String arg : args) {
            if ("all".equalsIgnoreCase(arg)) {
                all = true;
            } else {
                Integer r = parseInt(arg);
                if (r == null) {
                    sender.sendMessage(RoadMessages.usage("/knk road show [radius] [all]"));
                    return;
                }
                radius = r;
            }
        }
        renderer.show(player, radius, all);
        RoadNetworkSnapshot snapshot = cache.get().snapshot(player.getWorld().getName());
        sender.sendMessage(RoadMessages.good("Overlay on: " + Math.min(radius, RoadOverlayRenderer.MAX_RADIUS) + " blocks"
            + (all ? ", every level" : ", ±" + RoadOverlayRenderer.LEVEL_RANGE + " blocks of your height")
            + " (" + snapshot.nodeCount() + " nodes, " + snapshot.edgeCount() + " edges in this world). "
            + "Streets in colour, unlabelled grey, stale orange, closed red, gates yellow. /knk road hide to stop."));
    }

    private void hide(CommandSender sender) {
        Player player = requirePlayer(sender);
        RoadOverlayRenderer renderer = overlay.get();
        if (player == null || renderer == null) {
            return;
        }
        sender.sendMessage(renderer.hide(player) ? RoadMessages.info("Overlay off.") : RoadMessages.info("The overlay was not on."));
    }

    // ===== street =====

    private void street(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        List<String> words = new ArrayList<>(Arrays.asList(args));
        boolean propagate = words.removeIf(w -> w.equalsIgnoreCase("--continue") || w.equalsIgnoreCase("-c"));
        if (words.isEmpty()) {
            sender.sendMessage(RoadMessages.usage("/knk road street <street> [edgeId] [--continue]"));
            return;
        }
        Integer edgeId = words.size() > 1 ? parseInt(words.get(words.size() - 1)) : null;
        if (edgeId != null) {
            words.remove(words.size() - 1);
        }
        String streetName = joinQuoted(words.toArray(String[]::new), 0);
        RoadNetworkSnapshot snapshot = cache.get().snapshot(player.getWorld().getName());
        Optional<RoadEdge> edge = edgeId != null ? snapshot.edge(edgeId) : edgeHere(snapshot, player);
        if (edge.isEmpty()) {
            sender.sendMessage(RoadMessages.bad(edgeId != null ? "No edge #" + edgeId + " in this world's network."
                : "Stand on a road (within " + (int) HERE_DISTANCE + " blocks of an edge) or give an edge id."));
            return;
        }
        int id = edge.get().id();
        resolveStreet(player, streetName, streetId -> commandApi.updateEdge(id, RoadEdgeUpdate.street(streetId, propagate))
            .whenComplete((result, ex) -> mainThread.execute(() -> {
                if (failed(sender, "label the edge", ex)) {
                    return;
                }
                int changed = result.changedEdgeIds().size();
                sender.sendMessage(RoadMessages.good("Edge #" + id + (propagate && changed > 1 ? " and " + (changed - 1) + " more along the road" : "")
                    + " labelled " + streetName + " (Manual - later builds keep it)."));
                refreshTiles(player.getWorld().getName());
            })));
    }

    /** A street by id, by name in the current meta, else through the streets search; on the main thread. */
    private void resolveStreet(Player player, String idOrName, java.util.function.IntConsumer then) {
        Integer id = parseInt(idOrName);
        if (id != null) {
            then.accept(id);
            return;
        }
        RoadNetworkSnapshot snapshot = cache.get().snapshot(player.getWorld().getName());
        Optional<RoadNetworkSnapshot.Street> known = snapshot.streets().values().stream()
            .filter(s -> s.name().equalsIgnoreCase(idOrName)).findFirst();
        if (known.isPresent()) {
            then.accept(known.get().id());
            return;
        }
        if (streetsQueryApi == null) {
            player.sendMessage(RoadMessages.bad("No street '" + idOrName + "' in the road network's street list."));
            return;
        }
        streetsQueryApi.search(new PagedQuery(1, 10, idOrName, null, false, Map.of())).whenComplete((page, ex) -> mainThread.execute(() -> {
            if (failed(player, "search streets", ex)) {
                return;
            }
            List<StreetSummary> items = page == null || page.items() == null ? List.of() : page.items();
            Optional<StreetSummary> exact = items.stream().filter(s -> idOrName.equalsIgnoreCase(s.name())).findFirst();
            if (exact.isEmpty() && items.size() == 1) {
                exact = Optional.of(items.get(0));
            }
            if (exact.isEmpty() || exact.get().id() == null) {
                player.sendMessage(RoadMessages.bad(items.isEmpty() ? "No street named '" + idOrName + "'. Create it in the web app first."
                    : "Several streets match '" + idOrName + "': " + items.stream().map(s -> "#" + s.id() + " " + s.name()).toList()));
                return;
            }
            then.accept(exact.get().id());
        }));
    }

    // ===== node =====

    private void node(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        RoadNetworkSnapshot snapshot = cache.get().snapshot(player.getWorld().getName());
        switch (action) {
            case "name" -> {
                if (args.length < 2) {
                    sender.sendMessage(RoadMessages.usage("/knk road node name <name>"));
                    return;
                }
                String name = joinQuoted(args, 1);
                withNodeHere(sender, snapshot, player, node -> commandApi.updateNode(node.id(), RoadNodeUpdate.rename(name))
                    .whenComplete((n, ex) -> done(sender, "rename the node", ex, "Node #" + node.id() + " is now \"" + name + "\" (a /navigate destination).", player)));
            }
            case "unname" -> withNodeHere(sender, snapshot, player, node -> commandApi.updateNode(node.id(), RoadNodeUpdate.unnamed())
                .whenComplete((n, ex) -> done(sender, "unname the node", ex, "Node #" + node.id() + " has no name any more.", player)));
            case "merge" -> {
                Integer keep = args.length > 1 ? parseInt(args[1]) : null;
                Integer merge = args.length > 2 ? parseInt(args[2]) : null;
                if (keep == null || merge == null) {
                    sender.sendMessage(RoadMessages.usage("/knk road node merge <keepId> <mergeId>"));
                    return;
                }
                commandApi.mergeNodes(keep, merge).whenComplete((n, ex) ->
                    done(sender, "merge the nodes", ex, "Node #" + merge + " merged into #" + keep + "; its edges now end at #" + keep + ".", player));
            }
            case "anchor" -> {
                Location floor = floorOf(player);
                String name = args.length > 1 ? joinQuoted(args, 1) : null;
                commandApi.createAnchor(new RoadNodeAnchor(floor.getWorld().getName(), floor.getBlockX(), floor.getBlockY(), floor.getBlockZ(), name))
                    .whenComplete((n, ex) -> done(sender, "create the anchor", ex,
                        "Anchor node #" + (n == null ? "?" : n.id()) + " at " + floor.getBlockX() + " " + floor.getBlockY() + " " + floor.getBlockZ()
                            + ". The next build of this tile puts a junction here.", player));
            }
            case "lock", "unlock" -> {
                boolean lock = "lock".equals(action);
                Integer id = args.length > 1 ? parseInt(args[1]) : null;
                if (id != null) {
                    commandApi.updateNode(id, RoadNodeUpdate.locked(lock)).whenComplete((n, ex) ->
                        done(sender, (lock ? "lock" : "unlock") + " the node", ex, "Node #" + id + (lock ? " locked (rebuilds keep it)." : " unlocked."), player));
                } else {
                    withNodeHere(sender, snapshot, player, node -> commandApi.updateNode(node.id(), RoadNodeUpdate.locked(lock))
                        .whenComplete((n, ex) -> done(sender, (lock ? "lock" : "unlock") + " the node", ex,
                            "Node #" + node.id() + (lock ? " locked (rebuilds keep it)." : " unlocked."), player)));
                }
            }
            case "prune" -> {
                Integer id = args.length > 1 ? parseInt(args[1]) : null;
                boolean confirmed = args.length > 2 && "confirm".equalsIgnoreCase(args[2]);
                if (id == null) {
                    // A dead end nearby wins (as before); otherwise the junction nearby (confirmed first).
                    Optional<RoadNode> near = nodeHere(snapshot, player, RoadNodeKind.ENDPOINT)
                        .or(() -> nodeHere(snapshot, player, RoadNodeKind.JUNCTION));
                    if (near.isEmpty()) {
                        sender.sendMessage(RoadMessages.bad("No endpoint or junction within " + (int) HERE_DISTANCE + " blocks - stand at it or give its id."));
                        return;
                    }
                    id = near.get().id();
                }
                int target = id;
                Optional<RoadNode> known = snapshot.node(target);
                if (known.isPresent() && known.get().kind() == RoadNodeKind.JUNCTION) {
                    pruneJunction(player, snapshot, known.get(), confirmed);
                    return;
                }
                commandApi.pruneNode(target).whenComplete((n, ex) -> done(sender, "prune the node", ex,
                    "Node #" + target + " pruned: its dead end is gone and stays out of later builds (/knk road node unprune " + target + " to undo).", player));
            }
            case "unprune" -> {
                Integer id = args.length > 1 ? parseInt(args[1]) : null;
                if (id == null) {
                    Optional<RoadNode> tombstone = nodeHereMatching(snapshot, player, RoadNodeKind::isTombstone);
                    if (tombstone.isEmpty()) {
                        sender.sendMessage(RoadMessages.bad("No pruned node within " + (int) HERE_DISTANCE + " blocks - give its id."));
                        return;
                    }
                    id = tombstone.get().id();
                }
                int target = id;
                commandApi.unpruneNode(target).whenComplete((removed, ex) -> mainThread.execute(() -> {
                    if (failed(sender, "unprune the node", ex)) {
                        return;
                    }
                    if (!Boolean.TRUE.equals(removed)) {
                        sender.sendMessage(RoadMessages.bad("No pruned node #" + target + "."));
                        return;
                    }
                    sender.sendMessage(RoadMessages.good("Node #" + target + " unpruned: the next build of its tile brings the dead end or edge back."));
                    refreshTiles(player.getWorld().getName());
                }));
            }
            default -> sender.sendMessage(RoadMessages.usage("/knk road node name <name> | unname | merge <keep> <merge> | anchor [name] | lock|unlock [id] | prune|unprune [id]"));
        }
    }

    /**
     * Smoke test 2026-10-03: a junction the builder keeps re-detecting (a plaza's corners, a loop round a
     * fountain) goes for good by pruning its detected edges - each leaves a PrunedEdge tombstone the
     * builder respects. This cuts any road through the junction, so the edges are listed first and only
     * {@code /knk road node prune <id> confirm} prunes them. Recorded and stitch edges stay (they are not
     * the builder's); a named junction is refused (its name would stay on a node without edges).
     */
    private void pruneJunction(Player player, RoadNetworkSnapshot snapshot, RoadNode junction, boolean confirmed) {
        if (junction.nameOptional().isPresent()) {
            player.sendMessage(RoadMessages.bad("Junction #" + junction.id() + " is named \"" + junction.nameOptional().get()
                + "\" - /knk road node unname it first."));
            return;
        }
        List<RoadEdge> edges = snapshot.edgesAt(junction.id());
        List<Integer> detected = edges.stream()
            .filter(e -> e.source() == net.knightsandkings.knk.core.domain.roads.RoadEdgeSource.DETECTED)
            .map(RoadEdge::id).toList();
        List<Integer> kept = edges.stream().map(RoadEdge::id).filter(id -> !detected.contains(id)).toList();
        if (detected.isEmpty()) {
            player.sendMessage(RoadMessages.bad("Junction #" + junction.id() + " has no detected edges to prune"
                + (kept.isEmpty() ? "." : " (recorded/stitch edges " + ids(kept) + " are yours - delete those).")));
            return;
        }
        if (!confirmed) {
            StringBuilder list = new StringBuilder();
            for (RoadEdge e : edges) {
                if (detected.contains(e.id())) {
                    int other = e.fromNodeId() == junction.id() ? e.toNodeId() : e.fromNodeId();
                    list.append(list.isEmpty() ? "" : ", ").append("#").append(e.id()).append(" (")
                        .append(RoadMessages.distance(e.length())).append(" to node #").append(other).append(")");
                }
            }
            player.sendMessage(RoadMessages.warn("Prune junction #" + junction.id() + "? Its detected edges " + list
                + " go for good and stay out of later builds - a road through it is cut."
                + (kept.isEmpty() ? "" : " Recorded/stitch edges " + ids(kept) + " stay.")));
            player.sendMessage(RoadMessages.prefixed(RoadMessages.command("[Confirm]", "/knk road node prune " + junction.id() + " confirm")
                .append(Component.text(" or prune single edges: /knk road edge prune <id|here>", RoadMessages.INFO))));
            return;
        }
        commandApi.pruneEdges(detected).whenComplete((result, ex) -> mainThread.execute(() -> {
            if (failed(player, "prune junction #" + junction.id(), ex)) {
                return;
            }
            player.sendMessage(RoadMessages.good("Junction #" + junction.id() + ": edges " + ids(detected) + " pruned"
                + (result.deletedNodeIds().contains(junction.id()) ? " and the junction removed" : "")
                + "; they stay out of later builds. Undo one with /knk road node unprune <id> on tombstone "
                + ids(result.tombstones().stream().map(RoadNode::id).toList()) + "."));
            refreshTiles(player.getWorld().getName());
        }));
    }

    static String ids(List<Integer> ids) {
        return ids.stream().map(id -> "#" + id).collect(java.util.stream.Collectors.joining(", "));
    }

    private void withNodeHere(CommandSender sender, RoadNetworkSnapshot snapshot, Player player, java.util.function.Consumer<RoadNode> then) {
        Optional<RoadNode> node = nodeHere(snapshot, player);
        if (node.isEmpty()) {
            sender.sendMessage(RoadMessages.bad("No node within " + (int) HERE_DISTANCE + " blocks (use /knk road show to see them)."));
            return;
        }
        then.accept(node.get());
    }

    /** The nearest node within {@link #HERE_DISTANCE} of the player's feet (floor = feet − 1); never a pruned one. */
    static Optional<RoadNode> nodeHere(RoadNetworkSnapshot snapshot, Player player) {
        return nodeHere(snapshot, player, null);
    }

    /** Like {@link #nodeHere(RoadNetworkSnapshot, Player)}, only nodes of {@code kind} (null: any but a tombstone). */
    static Optional<RoadNode> nodeHere(RoadNetworkSnapshot snapshot, Player player, RoadNodeKind kind) {
        return nodeHereMatching(snapshot, player, kind == null ? k -> !k.isTombstone() : k -> k == kind);
    }

    /** The nearest node within {@link #HERE_DISTANCE} of the player's feet whose kind passes {@code accept}. */
    static Optional<RoadNode> nodeHereMatching(RoadNetworkSnapshot snapshot, Player player, java.util.function.Predicate<RoadNodeKind> accept) {
        Location at = player.getLocation();
        RoadNode best = null;
        double bestDistance = HERE_DISTANCE;
        for (RoadNode node : snapshot.nodes()) {
            if (!accept.test(node.kind())) {
                continue;
            }
            double d = node.distanceTo(at.getX() - 0.5, at.getY() - 1, at.getZ() - 0.5);
            if (d < bestDistance) {
                bestDistance = d;
                best = node;
            }
        }
        return Optional.ofNullable(best);
    }

    /** The edge under the player's feet (snapped within {@link #HERE_DISTANCE}, height weighted ×2). */
    static Optional<RoadEdge> edgeHere(RoadNetworkSnapshot snapshot, Player player) {
        Location at = player.getLocation();
        Optional<SnapPoint> snap = Snapper.snap(snapshot, at.getX() - 0.5, at.getY() - 1, at.getZ() - 0.5, HERE_DISTANCE, 2.0);
        return snap.flatMap(s -> snapshot.edge(s.edgeId()));
    }

    // ===== edge =====

    /** {@code /knk road edge prune <id|here>}: a detected edge goes for good (a PrunedEdge tombstone). */
    private void pruneEdge(CommandSender sender, String[] args) {
        Player player = sender instanceof Player p ? p : null;
        Integer id;
        if (args.length < 2 || "here".equalsIgnoreCase(args[1])) {
            if (player == null) {
                sender.sendMessage(RoadMessages.usage("/knk road edge prune <id>"));
                return;
            }
            Optional<RoadEdge> here = edgeHere(cache.get().snapshot(player.getWorld().getName()), player);
            if (here.isEmpty()) {
                sender.sendMessage(RoadMessages.bad("No edge here (within " + (int) HERE_DISTANCE + " blocks) - give an edge id."));
                return;
            }
            id = here.get().id();
        } else {
            id = parseInt(args[1]);
            if (id == null) {
                sender.sendMessage(RoadMessages.usage("/knk road edge prune <id|here>"));
                return;
            }
        }
        int target = id;
        commandApi.pruneEdges(List.of(target)).whenComplete((result, ex) -> mainThread.execute(() -> {
            if (failed(sender, "prune edge #" + target, ex)) {
                return;
            }
            String tombstone = result.tombstones().isEmpty() ? "<id>" : String.valueOf(result.tombstones().get(0).id());
            sender.sendMessage(RoadMessages.good("Edge #" + target + " pruned: it stays out of later builds"
                + (result.deletedNodeIds().isEmpty() ? "" : "; nodes " + ids(result.deletedNodeIds()) + " were left without edges and are gone")
                + ". Undo: /knk road node unprune " + tombstone + "."));
            String world = worldOf(sender);
            if (world != null) {
                refreshTiles(world);
            }
        }));
    }

    private void edge(CommandSender sender, String[] args) {
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if ("delete".equals(action)) {
            Integer id = args.length > 1 ? parseInt(args[1]) : null;
            if (id == null) {
                sender.sendMessage(RoadMessages.usage("/knk road edge delete <id>"));
                return;
            }
            commandApi.deleteEdge(id).whenComplete((ok, ex) -> mainThread.execute(() -> {
                if (failed(sender, "delete edge #" + id, ex)) {
                    return;
                }
                sender.sendMessage(Boolean.TRUE.equals(ok) ? RoadMessages.good("Edge #" + id + " deleted (a detected edge comes back on the next build - /knk road edge prune keeps it out).")
                    : RoadMessages.warn("Edge #" + id + " did not exist."));
                String world = worldOf(sender);
                if (world != null) {
                    refreshTiles(world);
                }
            }));
            return;
        }
        if ("prune".equals(action)) {
            pruneEdge(sender, args);
            return;
        }
        if (!"set".equals(action) || args.length < 3) {
            sender.sendMessage(RoadMessages.usage("/knk road edge set <id|here> cost <x> | oneway [on|off] | nogps [on|off] | close | open | profile <id> | unlabel"
                + " | prune <id|here> | delete <id>"));
            return;
        }
        Player player = sender instanceof Player p ? p : null;
        Optional<RoadEdge> edge;
        if ("here".equalsIgnoreCase(args[1])) {
            if (player == null) {
                sender.sendMessage(RoadMessages.bad("'here' needs a player; give an edge id."));
                return;
            }
            edge = edgeHere(cache.get().snapshot(player.getWorld().getName()), player);
        } else {
            Integer id = parseInt(args[1]);
            edge = id == null ? Optional.empty() : findEdge(id, player);
            if (id != null && edge.isEmpty()) {
                // Not in a loaded snapshot: still allow flag/cost edits by id with an empty flag baseline.
                edge = Optional.of(new RoadEdge(id, 0, 1, List.of(new int[] {0, 0, 0}, new int[] {1, 0, 0}), 1, 1,
                    OptionalInt.empty(), OptionalInt.empty(), 1, Set.of(), List.of(), List.of(), List.of(),
                    net.knightsandkings.knk.core.domain.roads.RoadEdgeSource.DETECTED, false));
            }
        }
        if (edge.isEmpty()) {
            sender.sendMessage(RoadMessages.bad("No edge here (within " + (int) HERE_DISTANCE + " blocks) - give an edge id."));
            return;
        }
        RoadEdge target = edge.get();
        String field = args[2].toLowerCase(Locale.ROOT);
        String value = args.length > 3 ? args[3] : null;
        RoadEdgeUpdate update;
        String done;
        switch (field) {
            case "cost" -> {
                Double cost = value == null ? null : parseDouble(value);
                if (cost == null || cost <= 0) {
                    sender.sendMessage(RoadMessages.usage("/knk road edge set <id|here> cost <positive number>"));
                    return;
                }
                update = RoadEdgeUpdate.costMultiplier(cost);
                done = "cost ×" + cost;
            }
            case "oneway" -> {
                boolean on = value == null ? !target.hasFlag(RoadEdgeFlag.ONEWAY) : isOn(value);
                update = RoadEdgeUpdate.flags(withFlag(target.flags(), RoadEdgeFlag.ONEWAY, on));
                done = on ? "one-way (from node #" + target.fromNodeId() + " to #" + target.toNodeId() + ")" : "two-way";
            }
            case "nogps" -> {
                boolean on = value == null ? !target.hasFlag(RoadEdgeFlag.NO_GPS) : isOn(value);
                update = RoadEdgeUpdate.flags(withFlag(target.flags(), RoadEdgeFlag.NO_GPS, on));
                done = on ? "excluded from routing (no-gps)" : "routable again";
            }
            case "close" -> {
                update = RoadEdgeUpdate.flags(withFlag(target.flags(), RoadEdgeFlag.CLOSED, true));
                done = "closed";
            }
            case "open" -> {
                update = RoadEdgeUpdate.flags(withFlag(target.flags(), RoadEdgeFlag.CLOSED, false));
                done = "open";
            }
            case "profile" -> {
                Integer profileId = value == null ? null : parseInt(value);
                if (profileId == null) {
                    sender.sendMessage(RoadMessages.usage("/knk road edge set <id|here> profile <profileId>"));
                    return;
                }
                update = RoadEdgeUpdate.profile(profileId);
                done = "profile #" + profileId;
            }
            case "unlabel" -> {
                update = RoadEdgeUpdate.unlabelled();
                done = "unlabelled";
            }
            default -> {
                sender.sendMessage(RoadMessages.usage("/knk road edge set <id|here> cost <x> | oneway | nogps | close | open | profile <id> | unlabel"));
                return;
            }
        }
        commandApi.updateEdge(target.id(), update).whenComplete((result, ex) -> mainThread.execute(() -> {
            if (failed(sender, "update edge #" + target.id(), ex)) {
                return;
            }
            sender.sendMessage(RoadMessages.good("Edge #" + target.id() + ": " + done + "."));
            String world = worldOf(sender);
            if (world != null) {
                refreshTiles(world);
            }
        }));
    }

    private Optional<RoadEdge> findEdge(int id, Player player) {
        RoadNetworkCache c = cache.get();
        if (player != null) {
            Optional<RoadEdge> inWorld = c.snapshot(player.getWorld().getName()).edge(id);
            if (inWorld.isPresent()) {
                return inWorld;
            }
        }
        for (World world : Bukkit.getWorlds()) {
            Optional<RoadEdge> e = c.snapshot(world.getName()).edge(id);
            if (e.isPresent()) {
                return e;
            }
        }
        return Optional.empty();
    }

    static Set<RoadEdgeFlag> withFlag(Set<RoadEdgeFlag> current, RoadEdgeFlag flag, boolean on) {
        EnumSet<RoadEdgeFlag> flags = current.isEmpty() ? EnumSet.noneOf(RoadEdgeFlag.class) : EnumSet.copyOf(current);
        if (on) {
            flags.add(flag);
        } else {
            flags.remove(flag);
        }
        return flags;
    }

    static boolean isOn(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        return v.equals("on") || v.equals("true") || v.equals("yes") || v.equals("1");
    }

    // ===== tiles =====

    private void tiles(CommandSender sender, String[] args) {
        String world = worldOf(sender);
        int page = 1;
        for (String arg : args) {
            Integer p = parseInt(arg);
            if (p != null) {
                page = Math.max(1, p);
            } else {
                world = arg;
            }
        }
        if (world == null) {
            sender.sendMessage(RoadMessages.bad("The console must name the world: /knk road tiles <world> [page]"));
            return;
        }
        RoadNetworkCache c = cache.get();
        List<RoadTile> tiles = new ArrayList<>(c.tiles(world));
        tiles.sort((a, b) -> {
            if (a.dirty() != b.dirty()) {
                return a.dirty() ? -1 : 1;
            }
            return Integer.compare(a.tileX() * 4096 + a.tileZ(), b.tileX() * 4096 + b.tileZ());
        });
        long dirty = tiles.stream().filter(RoadTile::dirty).count();
        long built = tiles.stream().filter(RoadTile::isBuilt).count();
        RoadNetworkSnapshot snapshot = c.snapshot(world);
        sender.sendMessage(RoadMessages.prefixed(Component.text("Tiles of " + world + ": " + tiles.size() + " known, " + built + " built, "
            + dirty + " dirty; network " + snapshot.nodeCount() + " nodes / " + snapshot.edgeCount() + " edges", RoadMessages.HIGHLIGHT)));
        RoadDirtyTracker tracker = dirtyTracker.get();
        if (tracker != null && tracker.dirtyTiles().pendingCount() > 0) {
            sender.sendMessage(Component.text(" " + tracker.dirtyTiles().pendingCount() + " tile(s) changed in the last 30 s, not yet reported", RoadMessages.INFO));
        }
        int pages = Math.max(1, (int) Math.ceil(tiles.size() / (double) PAGE_SIZE));
        for (RoadTile tile : tiles.subList(Math.min(tiles.size(), (page - 1) * PAGE_SIZE), Math.min(tiles.size(), page * PAGE_SIZE))) {
            TileKey key = TileKey.of(tile);
            Component line = Component.text(" " + tile.tileX() + "," + tile.tileZ() + " ", RoadMessages.VALUE)
                .append(RoadMessages.teleport(key.minX() + RoadTile.SIZE / 2, snapshotHeight(snapshot, key), key.minZ() + RoadTile.SIZE / 2))
                .append(Component.text(" v" + tile.version() + (tile.isBuilt() ? " built " + DATE.format(tile.builtAt()) : " never built")
                    + " " + tile.nodeCount() + "n/" + tile.edgeCount() + "e" + (tile.levelCount() > 1 ? " " + tile.levelCount() + " levels" : "")
                    + (tile.dirty() ? " DIRTY" : "") + (tile.warnings().isEmpty() ? "" : " " + tile.warnings().size() + " warning(s)"), tile.dirty() ? RoadMessages.WARN : RoadMessages.INFO))
                .append(Component.text(" "))
                .append(RoadMessages.command("[build]", "/knk road build tile " + tile.tileX() + " " + tile.tileZ() + " " + world));
            sender.sendMessage(line);
        }
        sender.sendMessage(Component.text(" Page " + page + "/" + pages, RoadMessages.INFO)
            .append(page < pages ? Component.text(" ").append(RoadMessages.command("[next]", "/knk road tiles " + world + " " + (page + 1))) : Component.empty()));
    }

    /** A sensible y for a tile teleport: the average node height of the tile, else the world's sea level. */
    private static int snapshotHeight(RoadNetworkSnapshot snapshot, TileKey key) {
        long sum = 0;
        int count = 0;
        for (RoadNode node : snapshot.nodes()) {
            if (key.contains(node.x(), node.z())) {
                sum += node.y();
                count++;
            }
        }
        return count == 0 ? 64 : (int) (sum / count) + 1;
    }

    // ===== reload / status / goto =====

    private void reload(CommandSender sender) {
        RoadNetworkCache c = cache.get();
        String world = worldOf(sender);
        CompletableFuture<Void> done = world == null ? c.refreshAll() : c.reload(world);
        sender.sendMessage(RoadMessages.info("Reloading road tiles and profiles" + (world == null ? " of every world" : " of " + world) + "…"));
        done.whenComplete((v, ex) -> mainThread.execute(() -> {
            if (world != null) {
                RoadNetworkSnapshot snapshot = c.snapshot(world);
                sender.sendMessage(RoadMessages.good("Reloaded: " + snapshot.nodeCount() + " nodes, " + snapshot.edgeCount() + " edges, "
                    + snapshot.streets().size() + " streets, " + c.profiles().size() + " profiles."));
            } else {
                sender.sendMessage(RoadMessages.good("Reloaded every world."));
            }
        }));
    }

    /** Wires {@code /knk road why} to /navigate (Phase 4); read lazily, so it may be called any time. */
    public void setNavigation(Supplier<net.knightsandkings.knk.paper.navigation.NavigationService> navigation,
                              Supplier<net.knightsandkings.knk.paper.navigation.NavigationDestinations> destinations) {
        this.navigation = navigation == null ? () -> null : navigation;
        this.destinations = destinations == null ? () -> null : destinations;
    }

    /**
     * {@code /knk road why <destination> [--as <player>]} (DESIGN §7): the route the admin - or
     * {@code --as} player - would get, listing every gate and domain verdict along it.
     */
    private void why(CommandSender sender, String[] args) {
        net.knightsandkings.knk.paper.navigation.NavigationService service = navigation.get();
        net.knightsandkings.knk.paper.navigation.NavigationDestinations catalogue = destinations.get();
        if (service == null || catalogue == null) {
            sender.sendMessage(RoadMessages.bad("/navigate is not running (navigation.enabled: false or the road cache failed)."));
            return;
        }
        List<String> words = new ArrayList<>(Arrays.asList(args));
        Player as = sender instanceof Player p ? p : null;
        int asIndex = words.indexOf("--as");
        if (asIndex >= 0) {
            if (asIndex + 1 >= words.size()) {
                sender.sendMessage(RoadMessages.usage("/knk road why <destination> [--as <player>]"));
                return;
            }
            as = Bukkit.getPlayerExact(words.get(asIndex + 1));
            if (as == null) {
                sender.sendMessage(RoadMessages.bad("Player " + words.get(asIndex + 1) + " is not online."));
                return;
            }
            words.subList(asIndex, asIndex + 2).clear();
        }
        if (words.isEmpty()) {
            sender.sendMessage(RoadMessages.usage("/knk road why <destination> [--as <player>]"));
            return;
        }
        if (as == null) {
            sender.sendMessage(RoadMessages.bad("From the console, name the player: /knk road why <destination> --as <player>"));
            return;
        }
        Player player = as;
        String input = unquote(String.join(" ", words));
        var resolution = catalogue.resolve(input, player.getWorld().getName());
        if (resolution.ambiguous()) {
            sender.sendMessage(RoadMessages.warn("Several places are called \"" + input + "\": "
                + String.join(", ", resolution.choiceNames())));
            return;
        }
        if (!resolution.found()) {
            sender.sendMessage(RoadMessages.bad("No place called \"" + input + "\" was found."));
            return;
        }
        catalogue.locate(resolution.target(), net.knightsandkings.knk.paper.navigation.NavigationDestinations.Mode.DEFAULT,
                player.getWorld().getName())
            .whenComplete((located, ex) -> mainThread.execute(() -> {
                if (ex != null || located == null || !located.ok()) {
                    sender.sendMessage(RoadMessages.bad("Could not locate " + resolution.target().name()
                        + (located != null && located.failure() != null ? " (" + located.failure() + ")" : "") + "."));
                    return;
                }
                service.explain(player, located.destination(), sender::sendMessage);
            }));
    }

    private void status(CommandSender sender) {
        RoadNetworkCache c = cache.get();
        sender.sendMessage(RoadMessages.prefixed(Component.text("Road navigation: enabled", RoadMessages.HIGHLIGHT)));
        for (World world : Bukkit.getWorlds()) {
            RoadNetworkSnapshot snapshot = c.snapshot(world.getName());
            if (snapshot.isEmpty() && c.tiles(world.getName()).isEmpty()) {
                continue;
            }
            sender.sendMessage(RoadMessages.field(world.getName(), snapshot.nodeCount() + " nodes, " + snapshot.edgeCount() + " edges, "
                + c.tiles(world.getName()).size() + " tiles, " + snapshot.streets().size() + " streets"));
        }
        sender.sendMessage(RoadMessages.field("profiles", c.profiles().size() + " (" + c.profiles().stream().filter(RoadProfile::enabled).count() + " enabled)"));
        RoadDirtyTracker tracker = dirtyTracker.get();
        if (tracker != null) {
            sender.sendMessage(RoadMessages.field("dirty", tracker.dirtyTiles().pendingCount() + " tile(s) waiting for the next flush; "
                + tracker.dirtyTiles().roadCells().size() + " road cells watched"));
        }
        RoadOverlayRenderer renderer = overlay.get();
        if (renderer != null) {
            sender.sendMessage(RoadMessages.field("overlay", renderer.viewerCount() + " viewer(s)"));
        }
        RoadBuildQueue queue = builds.get();
        if (queue != null) {
            sender.sendMessage(RoadMessages.field("build queue", queue.describe()));
        }
        RoadSurveyService service = surveys.get();
        if (service != null) {
            sender.sendMessage(RoadMessages.field("surveys", service.activeCount() + " walking"));
        }
        net.knightsandkings.knk.paper.navigation.NavigationService nav = navigation.get();
        if (nav != null) {
            sender.sendMessage(RoadMessages.field("walk paths", nav.walkStatus()));
        }
    }

    private void gotoBlock(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        Integer x = args.length > 0 ? parseInt(args[0]) : null;
        Integer y = args.length > 1 ? parseInt(args[1]) : null;
        Integer z = args.length > 2 ? parseInt(args[2]) : null;
        if (x == null || y == null || z == null) {
            sender.sendMessage(RoadMessages.usage("/knk road goto <x> <y> <z>"));
            return;
        }
        World world = args.length > 3 ? Bukkit.getWorld(args[3]) : player.getWorld();
        if (world == null) {
            sender.sendMessage(RoadMessages.bad("Unknown world '" + args[3] + "'."));
            return;
        }
        Location target = new Location(world, x + 0.5, y + 1, z + 0.5, player.getLocation().getYaw(), player.getLocation().getPitch());
        player.teleportAsync(target).thenAccept(ok -> mainThread.execute(() ->
            player.sendMessage(Boolean.TRUE.equals(ok) ? RoadMessages.info("Teleported to " + x + " " + y + " " + z + ".")
                : RoadMessages.bad("Teleport failed."))));
    }

    // ===== helpers =====

    private void done(CommandSender sender, String verb, Throwable ex, String message, Player player) {
        mainThread.execute(() -> {
            if (failed(sender, verb, ex)) {
                return;
            }
            sender.sendMessage(RoadMessages.good(message));
            if (player != null) {
                refreshTiles(player.getWorld().getName());
            }
        });
    }

    /** Main thread. Reports a failed API call; true when there was one. */
    static boolean failed(CommandSender sender, String verb, Throwable ex) {
        if (ex == null) {
            return false;
        }
        sender.sendMessage(RoadMessages.bad("Could not " + verb + ": " + RoadMessages.describeError(ex)));
        return true;
    }

    private void refreshTiles(String world) {
        RoadNetworkCache c = cache.get();
        if (c != null) {
            c.refreshTiles(world);
        }
    }

    private void refreshMeta(CommandSender sender) {
        RoadNetworkCache c = cache.get();
        if (c == null) {
            return;
        }
        String world = worldOf(sender);
        if (world != null) {
            c.refreshMeta(world);
        } else {
            Bukkit.getWorlds().forEach(w -> c.refreshMeta(w.getName()));
        }
    }

    private static Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(RoadMessages.bad("This subcommand needs a player in the world."));
        return null;
    }

    private static String worldOf(CommandSender sender) {
        return sender instanceof Player player ? player.getWorld().getName() : null;
    }

    /** The floor block under the player's feet (the network's coordinate convention), as a location. */
    static Location floorOf(Player player) {
        Location at = player.getLocation();
        // floor(y - ε): standing on a full block at y = 64.0 gives 63, on a slab at 63.5 gives the slab (63)
        return new Location(at.getWorld(), at.getBlockX(), (int) Math.floor(at.getY() - 0.001), at.getBlockZ());
    }

    static Integer parseInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double parseDouble(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code args[from..]} joined with spaces, surrounding quotes removed ({@code "Kardenna main street"}). */
    static String joinQuoted(String[] args, int from) {
        return unquote(String.join(" ", Arrays.copyOfRange(args, from, args.length)));
    }

    static String unquote(String text) {
        String t = text.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }
}
