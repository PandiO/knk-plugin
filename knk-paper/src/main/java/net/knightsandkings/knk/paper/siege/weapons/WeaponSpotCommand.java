package net.knightsandkings.knk.paper.siege.weapons;

import net.knightsandkings.knk.core.ports.api.SiegeWeaponSpotsApi;
import net.knightsandkings.knk.core.siege.weapons.SpotTeams;
import net.knightsandkings.knk.paper.commands.CommandMetadata;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * {@code /knk weaponspot <scenario id> <team> <name>} (SIEGE_WEAPONS § 4, PandiO/knk-workspace#783): starts the
 * {@link WeaponSpotTask} for one team of a scenario. The team is its id, name or {@code attackers}/{@code defenders}.
 * Gated on {@code knk.siege.admin.manage} (the node that edits spots in the web app) through the subcommand metadata.
 * Only registered while {@code siege.weapons.enabled} is on.
 */
public class WeaponSpotCommand {

    public static final String NODE = "knk.siege.admin.manage";
    static final String USAGE = "/knk weaponspot <scenario id> <team id|name|attackers|defenders> <spot name>";

    private final SiegeWeaponSpotsApi api;
    private final WeaponSpotTask task;
    /** Why the player can't start a task now (another world task or capture is open), or empty. */
    private final Function<Player, Optional<String>> busy;
    private final Executor mainThread;

    public WeaponSpotCommand(SiegeWeaponSpotsApi api, WeaponSpotTask task, Function<Player, Optional<String>> busy,
                             Executor mainThread) {
        this.api = api;
        this.task = task;
        this.busy = busy;
        this.mainThread = mainThread;
    }

    public static CommandMetadata metadata() {
        return new CommandMetadata("weaponspot",
                "Create a siege weapon spot from a WorldEdit selection or WorldGuard region (siege weapons)",
                USAGE, NODE,
                List.of("/knk weaponspot 3 attackers North wall", "/knk weaponspot 3 12 Gate tower"));
    }

    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only a player in game can select a weapon spot.");
            return true;
        }
        if (args.length < 3 || !args[0].matches("\\d+")) {
            player.sendMessage(ChatColor.YELLOW + "Usage: " + USAGE);
            return true;
        }
        if (task.isHandling(player)) {
            player.sendMessage(WeaponSpotTask.PREFIX + ChatColor.RED
                    + "You already have a weapon spot task open: finish it ('confirm') or type 'cancel'.");
            return true;
        }
        Optional<String> refusal = busy.apply(player);
        if (refusal.isPresent()) {
            player.sendMessage(WeaponSpotTask.PREFIX + ChatColor.RED + refusal.get());
            return true;
        }
        int scenarioId = Integer.parseInt(args[0]);
        String teamText = args[1];
        String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
        if (name.length() > 100) {
            player.sendMessage(WeaponSpotTask.PREFIX + ChatColor.RED + "The spot name can be at most 100 characters.");
            return true;
        }
        UUID playerId = player.getUniqueId();
        api.listTeams(scenarioId).whenComplete((teams, failure) -> mainThread.execute(() -> {
            if (!player.isOnline() || !playerId.equals(player.getUniqueId())) return;
            if (failure != null) {
                player.sendMessage(WeaponSpotTask.PREFIX + ChatColor.RED
                        + "The scenario's teams can't be read right now; try again later.");
                return;
            }
            if (teams == null) {
                player.sendMessage(WeaponSpotTask.PREFIX + ChatColor.RED + "No siege scenario " + scenarioId + ".");
                return;
            }
            SpotTeams.Resolution team = SpotTeams.resolve(teams, teamText);
            if (!team.found()) {
                player.sendMessage(WeaponSpotTask.PREFIX + ChatColor.RED + team.error());
                return;
            }
            if (task.isHandling(player)) return; // started twice quickly
            task.start(player, scenarioId, team.team(), name);
        }));
        return true;
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(java.util.Locale.ROOT);
            return List.of("attackers", "defenders").stream().filter(v -> v.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
