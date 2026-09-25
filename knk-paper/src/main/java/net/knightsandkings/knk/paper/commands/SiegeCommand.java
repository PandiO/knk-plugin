package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.siege.ObjectiveState;
import net.knightsandkings.knk.core.siege.SiegeDisplayText;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.MemberView;
import net.knightsandkings.knk.core.siege.SiegePhase;
import net.knightsandkings.knk.core.siege.SiegeSpawnOptions;
import net.knightsandkings.knk.core.siege.VoteTally;
import net.knightsandkings.knk.paper.siege.SiegeBukkit;
import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import net.knightsandkings.knk.paper.siege.SiegeMatch;
import net.knightsandkings.knk.paper.siege.SiegeMessages;
import net.knightsandkings.knk.paper.siege.SiegeService;
import net.knightsandkings.knk.paper.siege.SiegeService.Reply;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * {@code /siege} (DESIGN §11.1): every player and admin subcommand, each one call into
 * {@link SiegeService} - the Phase 8b menu actions will call the same methods. Until the menus ship,
 * {@code /siege}, {@code /siege info}, {@code /siege vote} and {@code /siege spawn} are chat
 * fallbacks, so a match is fully playable with commands only.
 * <p>
 * Plain {@link CommandExecutor} with manual dispatch (KitCommand style). Permissions go through
 * {@code KnkPermissible} per subcommand, not {@code plugin.yml} {@code permission:}, so per-node
 * gating stays reachable; the console may run every admin subcommand.
 */
public class SiegeCommand implements CommandExecutor, TabCompleter {

    private static final String WEB_APP_HINT = "Siege lobbies, scenarios, teams, objectives and settings are created and "
            + "edited in the web app: /forms/siegelobby, /forms/siegescenario, /forms/clan, /forms/bannerdesign and the "
            + "\"Siege Settings\" page. In-game siege editing isn't available.";

    private final SiegeService service;

    public SiegeCommand(SiegeService service) {
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "" -> overview(sender);
            case "join" -> playerOnly(sender, p -> service.join(p, arg(rest, 0)));
            case "leave" -> playerOnly(sender, service::leave);
            case "info" -> info(sender, arg(rest, 0));
            case "vote" -> playerOnly(sender, p -> service.vote(p, joined(rest)));
            case "spawn" -> playerOnly(sender, p -> service.spawn(p, joined(rest)));
            case "skip" -> {
                if (!allowed(sender, SiegeService.PERMISSION_SKIP)) return true;
                send(sender, service.skip(sender, arg(rest, 0), false));
            }
            case "admin" -> admin(sender, rest);
            case "help" -> help(sender);
            default -> {
                sender.sendMessage(SiegeMessages.bad("Unknown subcommand '" + args[0] + "'."));
                help(sender);
            }
        }
        return true;
    }

    // ==================== Player ====================

    private void overview(CommandSender sender) {
        if (sender instanceof Player && !allowed(sender, SiegeService.PERMISSION_PLAY)) return;
        List<SiegeLobbyRuntime> lobbies = service.lobbies();
        Component msg = SiegeMessages.info("Siege lobbies:");
        if (lobbies.isEmpty()) {
            msg = msg.append(Component.newline()).append(Component.text("  No siege lobbies are running.", SiegeMessages.INFO));
        }
        for (SiegeLobbyRuntime rt : lobbies) {
            msg = msg.append(Component.newline()).append(lobbyLine(rt));
        }
        if (sender instanceof Player p) {
            Optional<SiegeLobbyRuntime> mine = service.lobbyOf(p.getUniqueId());
            msg = msg.append(Component.newline());
            if (mine.isPresent()) {
                msg = msg.append(Component.text("You are in " + mine.get().displayName() + ". ", SiegeMessages.GOOD))
                        .append(SiegeMessages.command("/siege info")).append(Component.text(" ", SiegeMessages.INFO))
                        .append(SiegeMessages.command("/siege leave"));
            } else {
                msg = msg.append(Component.text("Join with /siege join <lobby>. More: ", SiegeMessages.INFO))
                        .append(SiegeMessages.command("/siege help"));
            }
        }
        sender.sendMessage(msg);
    }

    private Component lobbyLine(SiegeLobbyRuntime rt) {
        SiegePhase phase = rt.phase();
        String timer = switch (phase) {
            case MATCHMAKING, HUB -> "starts in " + SiegeMessages.duration(rt.machine().secondsRemaining());
            case IN_PROGRESS -> SiegeMessages.clock(rt.machine().secondsRemaining()) + " left";
            case COOLDOWN -> "next round in " + SiegeMessages.duration(rt.machine().secondsRemaining());
            case ENDING, DISABLED -> "";
        };
        String scenario = rt.drawnScenario().map(s -> " - " + scenarioName(s)).orElse("");
        Component line = Component.text("  " + rt.key() + " ", SiegeMessages.HIGHLIGHT)
                .append(Component.text(rt.displayName() + scenario + ": ", NamedTextColor.WHITE))
                .append(Component.text(SiegeMessages.phaseLabel(phase), phaseColor(phase)))
                .append(Component.text((timer.isEmpty() ? "" : ", " + timer) + ", " + rt.memberCount()
                        + (rt.machine().isJoinable() ? "/" + rt.machine().joinCapacity() : "") + " players", SiegeMessages.INFO));
        if (rt.machine().isJoinable()) {
            line = line.append(Component.text(" ", SiegeMessages.INFO)).append(SiegeMessages.command("/siege join " + rt.key()));
        }
        return line;
    }

    private void info(CommandSender sender, String key) {
        if (sender instanceof Player && !allowed(sender, SiegeService.PERMISSION_PLAY)) return;
        Optional<SiegeLobbyRuntime> found;
        if (key != null) {
            found = service.lobbyByKey(key);
        } else if (sender instanceof Player p && service.lobbyOf(p.getUniqueId()).isPresent()) {
            found = service.lobbyOf(p.getUniqueId());
        } else {
            found = service.lobbies().size() == 1 ? Optional.of(service.lobbies().get(0)) : Optional.empty();
        }
        if (found.isEmpty()) {
            sender.sendMessage(SiegeMessages.bad(key == null ? "Say which lobby: /siege info <lobby>." : "There is no siege lobby '" + key + "'."));
            return;
        }
        sender.sendMessage(describe(found.get(), sender instanceof Player p ? p.getUniqueId() : null));
    }

    /** Chat fallback of {@code siege.information} (MENU_TEMPLATES C.3), per phase. */
    private Component describe(SiegeLobbyRuntime rt, UUID viewer) {
        Component msg = SiegeMessages.prefixed(Component.text(rt.displayName(), SiegeMessages.HIGHLIGHT)
                .append(Component.text(" (" + rt.key() + ") - " + SiegeMessages.phaseLabel(rt.phase()), phaseColor(rt.phase()))));
        int seconds = rt.machine().secondsRemaining();
        switch (rt.phase()) {
            case MATCHMAKING, HUB -> {
                msg = line(msg, "Starts in " + SiegeMessages.duration(seconds) + "; " + rt.memberCount() + "/"
                        + rt.machine().joinCapacity() + " players.");
                int minXp = rt.machine().joinMinTitleExperience();
                if (minXp > 0) msg = line(msg, "Entry requires the title " + service.titleRanks().requirementLabel(minXp) + ".");
                Optional<KnkSiegeScenario> drawn = rt.drawnScenario();
                if (drawn.isPresent()) {
                    msg = line(msg, "Scenario: " + scenarioName(drawn.get()) + " (" + drawn.get().teams().size() + " teams, "
                            + drawn.get().objectives().size() + " objectives" + (drawn.get().allowRecapture() ? ", recapture on" : "") + ").");
                } else {
                    Optional<VoteTally> tally = rt.machine().voteTally();
                    List<String> votes = new ArrayList<>();
                    for (KnkSiegeScenario s : rt.machine().candidates()) {
                        votes.add(scenarioName(s) + " (" + tally.map(t -> t.votesFor(s.id())).orElse(0) + ")");
                    }
                    if (tally.map(VoteTally::allowRandomVote).orElse(false)) {
                        votes.add("Random (" + tally.get().randomVotes() + ")");
                    }
                    msg = line(msg, "Vote" + (rt.machine().isVotingOpen() ? "" : " (closed)") + ": " + String.join(", ", votes));
                }
                if (rt.split().isPresent() && viewer != null) {
                    for (var entry : rt.split().get().entrySet()) {
                        if (entry.getValue().contains(viewer)) {
                            KnkSiegeTeam team = rt.drawnScenario().flatMap(s -> s.team(entry.getKey())).orElse(null);
                            msg = msg.append(Component.newline()).append(Component.text("  Your team: ", SiegeMessages.INFO))
                                    .append(SiegeBukkit.teamComponent(team));
                        }
                    }
                }
                msg = line(msg, "Players: " + names(rt.members().stream().toList()));
            }
            case IN_PROGRESS -> {
                SiegeMatch match = rt.match().orElse(null);
                if (match == null) break;
                msg = line(msg, scenarioName(match.scenario()) + ", " + SiegeMessages.clock(seconds) + " left.");
                for (KnkSiegeTeam team : match.scenario().teams()) {
                    List<UUID> ids = match.roster().membersOf(team.id());
                    msg = msg.append(Component.newline()).append(Component.text("  ", SiegeMessages.INFO))
                            .append(SiegeBukkit.teamComponent(team))
                            .append(Component.text(" (" + (team.isDefender() ? "defender" : "attacker") + ", alliance "
                                    + team.allianceGroup() + "): " + names(ids), SiegeMessages.INFO));
                }
                for (ObjectiveState state : match.board().objectives()) {
                    KnkSiegeTeam holder = match.scenario().team(state.holderTeamId()).orElse(null);
                    Component objective = Component.text("  " + SiegeDisplayText.clean(state.objective().name(), "#" + state.objectiveId())
                                    + (state.objective().instantVictory() ? " [main]" : "") + ": held by ", SiegeMessages.INFO)
                            .append(SiegeBukkit.teamComponent(holder))
                            .append(Component.text(", captured " + state.capturePercent() + "%", SiegeMessages.INFO));
                    if (state.isContested()) objective = objective.append(Component.text(" - under attack!", SiegeMessages.BAD));
                    if (state.isCapturedFinal()) objective = objective.append(Component.text(" - taken", SiegeMessages.INFO));
                    msg = msg.append(Component.newline()).append(objective);
                }
                if (viewer != null && match.roster().contains(viewer)) {
                    MemberView me = match.roster().member(viewer).orElseThrow();
                    KnkSiegeTeam team = match.scenario().team(me.teamId()).orElse(null);
                    String spawn = team == null ? "?" : SiegeSpawnOptions.forTeam(team, match.board(), me.spawnChoice()).stream()
                            .filter(SiegeSpawnOptions.SpawnOption::current)
                            .map(o -> SiegeDisplayText.clean(o.name(), "#" + o.id())).findFirst().orElse("default spawn");
                    msg = msg.append(Component.newline()).append(Component.text("  You: ", SiegeMessages.INFO))
                            .append(SiegeBukkit.teamComponent(team))
                            .append(Component.text(" - " + me.kills() + " kills, " + me.deaths() + " deaths, respawn at "
                                    + spawn + ". ", SiegeMessages.INFO))
                            .append(SiegeMessages.command("/siege spawn"));
                }
            }
            case COOLDOWN -> msg = line(msg, "Next matchmaking in " + SiegeMessages.duration(seconds) + ".");
            case ENDING -> msg = line(msg, "The match is ending.");
            case DISABLED -> msg = line(msg, rt.lobby().hasReadyScenario()
                    ? "Stopped by an admin." : "No ready scenario in the rotation.");
        }
        return msg;
    }

    // ==================== Admin ====================

    private void admin(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "list" -> {
                if (!allowed(sender, SiegeService.PERMISSION_ADMIN_LIST)) return;
                adminList(sender);
            }
            case "start" -> {
                if (!allowed(sender, SiegeService.PERMISSION_ADMIN_CONTROL)) return;
                if (rest.length < 1) {
                    sender.sendMessage(SiegeMessages.bad("Usage: /siege admin start <lobby>"));
                    return;
                }
                send(sender, service.adminStart(sender, rest[0]));
            }
            case "stop" -> {
                if (!allowed(sender, SiegeService.PERMISSION_ADMIN_CONTROL)) return;
                if (rest.length < 1) {
                    sender.sendMessage(SiegeMessages.bad("Usage: /siege admin stop <lobby> [reason]"));
                    return;
                }
                send(sender, service.adminStop(sender, rest[0], joined(Arrays.copyOfRange(rest, 1, rest.length))));
            }
            case "skip" -> {
                if (!allowed(sender, SiegeService.PERMISSION_ADMIN_CONTROL)) return;
                send(sender, service.skip(sender, arg(rest, 0), true));
            }
            case "kick" -> {
                if (!allowed(sender, SiegeService.PERMISSION_ADMIN_CONTROL)) return;
                Player target = rest.length > 0 ? Bukkit.getPlayerExact(rest[0]) : null;
                if (target == null) {
                    sender.sendMessage(SiegeMessages.bad(rest.length == 0 ? "Usage: /siege admin kick <player>"
                            : rest[0] + " is not online."));
                    return;
                }
                send(sender, service.adminKick(sender, target));
            }
            case "reload" -> {
                if (!allowed(sender, SiegeService.PERMISSION_ADMIN_RELOAD)) return;
                send(sender, service.reload(sender));
            }
            case "manage" -> {
                if (!allowed(sender, SiegeService.PERMISSION_ADMIN_MANAGE)) return;
                sender.sendMessage(SiegeMessages.info(WEB_APP_HINT));
            }
            default -> sender.sendMessage(SiegeMessages.info(
                    "Admin: /siege admin list | start <lobby> | stop <lobby> [reason] | skip <lobby> | kick <player> | reload | manage"));
        }
    }

    private void adminList(CommandSender sender) {
        Component msg = SiegeMessages.info("Siege lobbies (" + service.lobbies().size() + "):");
        for (SiegeLobbyRuntime rt : service.lobbies()) {
            msg = msg.append(Component.newline()).append(lobbyLine(rt));
            String locked = service.locks().lockedScenarioOf(rt.id()).isPresent()
                    ? "scenario " + service.locks().lockedScenarioOf(rt.id()).getAsInt() + " locked" : "no lock";
            msg = msg.append(Component.newline()).append(Component.text("    id " + rt.id() + ", " + locked + ", rotation "
                    + rt.lobby().rotation().size() + " ready / " + rt.lobby().skippedScenarios().size() + " not ready"
                    + (rt.machine().hasPendingConfiguration() ? ", new config pending" : "")
                    + ", members: " + names(rt.members().stream().toList()), NamedTextColor.DARK_GRAY));
        }
        sender.sendMessage(msg);
    }

    private void help(CommandSender sender) {
        sender.sendMessage(SiegeMessages.info("/siege - lobbies | join [lobby] | leave | info [lobby] | vote [n|name|random] | "
                + "spawn [option] | skip [lobby] (cooldown, or matchmaking to 1 minute)" + (!(sender instanceof Player p) || service.hasPermission(p, SiegeService.PERMISSION_ADMIN_LIST)
                ? " | admin ..." : "")));
    }

    // ==================== Tab completion ====================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(Stream.of("join", "leave", "info", "vote", "spawn", "skip", "help", "admin"), args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "join", "info", "skip" -> filter(service.lobbies().stream().map(SiegeLobbyRuntime::key), args[1]);
                case "vote" -> filter(Stream.concat(Stream.of("random"), voteNames(sender)), args[1]);
                case "admin" -> filter(Stream.of("list", "start", "stop", "skip", "kick", "reload", "manage"), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && sub.equals("admin")) {
            String adminSub = args[1].toLowerCase(Locale.ROOT);
            if (List.of("start", "stop", "skip").contains(adminSub)) {
                return filter(service.lobbies().stream().map(SiegeLobbyRuntime::key), args[2]);
            }
            if (adminSub.equals("kick")) {
                return filter(service.lobbies().stream().flatMap(rt -> rt.members().stream())
                        .map(Bukkit::getPlayer).filter(p -> p != null).map(Player::getName), args[2]);
            }
        }
        return List.of();
    }

    private Stream<String> voteNames(CommandSender sender) {
        if (!(sender instanceof Player p)) return Stream.empty();
        return service.lobbyOf(p.getUniqueId()).stream()
                .flatMap(rt -> rt.machine().candidates().stream())
                .map(SiegeCommand::scenarioName)
                .map(n -> n.split(" ")[0]);
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.filter(o -> o != null && o.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }

    // ==================== Helpers ====================

    private void playerOnly(CommandSender sender, java.util.function.Function<Player, Reply> action) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(SiegeMessages.bad("Only players can use this."));
            return;
        }
        send(sender, action.apply(player));
    }

    private boolean allowed(CommandSender sender, String node) {
        if (!(sender instanceof Player player)) return true;
        if (service.hasPermission(player, node)) return true;
        sender.sendMessage(SiegeMessages.bad("You don't have permission to do that (" + node + ")."));
        return false;
    }

    private static void send(CommandSender sender, Reply reply) {
        if (reply != null && reply.message() != null) sender.sendMessage(reply.message());
    }

    private static String arg(String[] args, int index) {
        return args.length > index ? args[index] : null;
    }

    private static String joined(String[] args) {
        return args.length == 0 ? null : String.join(" ", args);
    }

    private static Component line(Component msg, String text) {
        return msg.append(Component.newline()).append(Component.text("  " + text, SiegeMessages.INFO));
    }

    private static String names(List<UUID> ids) {
        if (ids.isEmpty()) return "none";
        List<String> names = new ArrayList<>();
        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            names.add(p != null ? p.getName() : id.toString().substring(0, 8));
        }
        return String.join(", ", names);
    }

    private static String scenarioName(KnkSiegeScenario s) {
        return SiegeDisplayText.clean(s.name(), "Scenario " + s.id());
    }

    private static NamedTextColor phaseColor(SiegePhase phase) {
        return switch (phase) {
            case MATCHMAKING -> NamedTextColor.GREEN;
            case HUB, IN_PROGRESS -> NamedTextColor.GOLD;
            case ENDING, COOLDOWN -> NamedTextColor.YELLOW;
            case DISABLED -> NamedTextColor.RED;
        };
    }
}
