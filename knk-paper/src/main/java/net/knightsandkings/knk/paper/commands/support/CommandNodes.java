package net.knightsandkings.knk.paper.commands.support;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import net.knightsandkings.knk.core.messaging.PrivateMessageNodes;
import net.knightsandkings.knk.paper.commands.EnderchestCommand;
import net.knightsandkings.knk.paper.commands.FlyCommand;
import net.knightsandkings.knk.paper.commands.GamemodeCommand;
import net.knightsandkings.knk.paper.commands.InventoryCommand;
import net.knightsandkings.knk.paper.commands.RestoreCommand;
import net.knightsandkings.knk.paper.currency.PlayerCurrencyService;
import net.knightsandkings.knk.paper.kit.KitGrantFlow;
import net.knightsandkings.knk.paper.modes.ModeService;
import net.knightsandkings.knk.paper.navigation.NavigateCommand;
import net.knightsandkings.knk.paper.siege.SiegeService;
import net.knightsandkings.knk.paper.teleport.BackService;
import net.knightsandkings.knk.paper.teleport.TeleportExemptions;
import net.knightsandkings.knk.paper.teleport.TeleportNodes;

/**
 * KNG-107: the standalone commands every form of which needs a node, with those nodes (holding any
 * one lets the sender use some form). One list for three things: a sender holding none is told they
 * lack permission instead of the usage ({@link PermissionGatedCommand}), the command is left out of
 * their "/" suggestions ({@code GatedCommandVisibilityListener}) and out of {@code /knk help}.
 * <p>
 * Not listed = open to everyone (some form needs no node): {@code /msg}, {@code /reply},
 * {@code /ignore}, {@code /unignore}, {@code /tpa} (accept/deny), {@code /tpaccept}, {@code /tpdeny},
 * {@code /tpcancel}, {@code /user}, {@code /stats}, {@code /leaderboard}, {@code /afk}, {@code /siege}
 * and {@code /lootbox} (help), {@code /gate} and {@code /gatedoor} (info, list, passthrough),
 * {@code /knk}. Commands gated by plugin.yml {@code permission:} ({@code /account}, {@code /menu},
 * {@code /discoveries}, {@code /ce}) are left to Bukkit.
 */
public final class CommandNodes {

    private static final Map<String, List<String>> ANY_OF = Map.ofEntries(
            Map.entry("tp", List.of(TeleportNodes.STAFF, TeleportNodes.LEGACY_ADMIN_TP, TeleportNodes.STAFF_OTHERS,
                    TeleportExemptions.Type.TP.allNode())),
            Map.entry("tphere", List.of(TeleportNodes.STAFF_OTHERS, TeleportExemptions.Type.TPHERE.allNode())),
            Map.entry("tpahere", List.of(TeleportNodes.REQUEST_HERE)),
            Map.entry("spawn", List.of(TeleportNodes.SPAWN, TeleportNodes.STAFF_OTHERS, TeleportExemptions.Type.SPAWN.allNode())),
            Map.entry("warp", List.of(TeleportNodes.WARP, TeleportNodes.STAFF_OTHERS)),
            Map.entry("warps", List.of(TeleportNodes.WARP)),
            Map.entry("back", Stream.concat(BackService.PLAYER_NODES.stream(), Stream.of(TeleportNodes.STAFF_BACK_OTHERS)).toList()),
            Map.entry("pay", List.of(PlayerCurrencyService.PAY_NODE)),
            Map.entry("balance", List.of(PlayerCurrencyService.BALANCE_NODE, PlayerCurrencyService.BALANCE_OTHERS_NODE)),
            Map.entry("baltop", List.of(PlayerCurrencyService.BALTOP_NODE)),
            Map.entry("transactions", List.of(PlayerCurrencyService.TRANSACTIONS_NODE, PlayerCurrencyService.TRANSACTIONS_OTHERS_NODE)),
            Map.entry("fly", List.of(FlyCommand.NODE, FlyCommand.NODE_OTHERS)),
            Map.entry("gamemode", GamemodeCommand.NODES),
            Map.entry("heal", restoreNodes(RestoreCommand.Kind.HEAL)),
            Map.entry("feed", restoreNodes(RestoreCommand.Kind.FEED)),
            Map.entry("enderchest", List.of(EnderchestCommand.NODE, EnderchestCommand.NODE_OPEN)),
            Map.entry("inventory", List.of(InventoryCommand.NODE_OPEN, InventoryCommand.NODE_CLEAR)),
            Map.entry("socialspy", List.of(PrivateMessageNodes.SOCIAL_SPY)),
            Map.entry("ownermode", List.of(ModeService.OWNER_NODE)),
            Map.entry("staffmode", List.of(ModeService.STAFF_NODE)),
            Map.entry("navigate", List.of(NavigateCommand.NODE)),
            Map.entry("kit", List.of("knk.kit.list", KitGrantFlow.GET_NODE, KitGrantFlow.GIVE_NODE, KitGrantFlow.PURCHASE_NODE,
                    "knk.kit.manage")),
            Map.entry("siegemenu", List.of(SiegeService.PERMISSION_PLAY)),
            Map.entry("freeze", List.of("knk.freeze")),
            Map.entry("unfreeze", List.of("knk.unfreeze")),
            Map.entry("staffchat", List.of("knk.staffchat")));

    private CommandNodes() {
    }

    /** The nodes any one of which lets the sender use some form of {@code command}; empty = open. */
    public static List<String> anyOf(String command) {
        return ANY_OF.getOrDefault(command.toLowerCase(Locale.ROOT), List.of());
    }

    /** Every gated command name. */
    public static java.util.Set<String> commands() {
        return ANY_OF.keySet();
    }

    private static List<String> restoreNodes(RestoreCommand.Kind kind) {
        return List.of(kind.node(), kind.othersNode(), kind.allNode());
    }
}
