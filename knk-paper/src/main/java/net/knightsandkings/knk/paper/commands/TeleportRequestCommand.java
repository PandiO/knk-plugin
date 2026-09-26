package net.knightsandkings.knk.paper.commands;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.teleport.TeleportRequestBook.Direction;
import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;
import net.knightsandkings.knk.paper.teleport.TeleportNodes;
import net.knightsandkings.knk.paper.teleport.TeleportRequestService;
import net.knightsandkings.knk.paper.teleport.VisibleTargetResolver;

/**
 * Player teleport requests (docs/specs/teleport/DESIGN.md §3.2/§3.5, Phase 3):
 * <ul>
 *   <li>{@code /tpa <player>} - ask to go to a player ({@value TeleportNodes#REQUEST}).</li>
 *   <li>{@code /tpahere <player>} - ask a player to come to you ({@value TeleportNodes#REQUEST_HERE},
 *       Dragon Blood).</li>
 *   <li>{@code /tpaccept [player]} ({@code /tpyes}), {@code /tpdeny [player]} ({@code /tpno}), and v1's
 *       {@code /tpa accept|deny [player]} - answer the newest (or the named player's) request. No
 *       node: answering is always allowed.</li>
 *   <li>{@code /tpcancel} - withdraw your request, or stop your own running teleport warmup.</li>
 * </ul>
 * Players only. Names resolve vanish-aware ({@link VisibleTargetResolver}); the rules live in
 * {@link TeleportRequestService}. Permissions resolve through {@code KnkPermissible}, hence no
 * {@code permission:} on the plugin.yml entries.
 */
public class TeleportRequestCommand implements TabExecutor {

    public enum Form { TPA, TPAHERE, ACCEPT, DENY, CANCEL }

    private final Form form;
    private final PlayerCommandSupport support;
    private final VisibleTargetResolver targets;
    private final TeleportRequestService requests;

    public TeleportRequestCommand(Form form, PlayerCommandSupport support, VisibleTargetResolver targets,
                                  TeleportRequestService requests) {
        this.form = Objects.requireNonNull(form, "form must not be null");
        this.support = Objects.requireNonNull(support, "support must not be null");
        this.targets = Objects.requireNonNull(targets, "targets must not be null");
        this.requests = Objects.requireNonNull(requests, "requests must not be null");
    }

    /** The same command in another form, sharing everything else. */
    public TeleportRequestCommand withForm(Form other) {
        return new TeleportRequestCommand(other, support, targets, requests);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = support.requirePlayer(sender);
        if (player == null) {
            return true;
        }
        switch (form) {
            case TPA -> {
                if (args.length >= 1 && args.length <= 2 && isAnswer(args[0])) {
                    answer(player, args[0].equalsIgnoreCase("accept"), args.length == 2 ? args[1] : null);
                } else if (args.length == 1) {
                    request(player, args[0], TeleportNodes.REQUEST, Direction.TO_TARGET);
                } else {
                    player.sendMessage(ChatColor.YELLOW + "Usage: /tpa <player> | /tpa accept|deny [player]");
                }
            }
            case TPAHERE -> {
                if (args.length == 1) {
                    request(player, args[0], TeleportNodes.REQUEST_HERE, Direction.TO_REQUESTER);
                } else {
                    player.sendMessage(ChatColor.YELLOW + "Usage: /tpahere <player>");
                }
            }
            case ACCEPT, DENY -> {
                if (args.length <= 1) {
                    answer(player, form == Form.ACCEPT, args.length == 1 ? args[0] : null);
                } else {
                    player.sendMessage(ChatColor.YELLOW + "Usage: /" + (form == Form.ACCEPT ? "tpaccept" : "tpdeny") + " [player]");
                }
            }
            case CANCEL -> requests.cancel(player);
        }
        return true;
    }

    private void request(Player requester, String targetName, String node, Direction direction) {
        support.whenAllowed(requester, node, () -> {
            if (!requester.isOnline()) {
                return;
            }
            Player target = targets.find(requester, targetName);
            if (target == null) {
                requester.sendMessage(ChatColor.RED + "No online player named '" + targetName + "'.");
                return;
            }
            requests.send(requester, target, direction);
        });
    }

    private void answer(Player player, boolean accept, String requesterName) {
        if (accept) {
            requests.accept(player, requesterName);
        } else {
            requests.deny(player, requesterName);
        }
    }

    private static boolean isAnswer(String arg) {
        return arg.equalsIgnoreCase("accept") || arg.equalsIgnoreCase("deny");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || args.length == 0) {
            return Collections.emptyList();
        }
        String last = args[args.length - 1];
        return switch (form) {
            case TPA -> {
                if (args.length == 1) {
                    yield targets.complete(sender, last, "accept", "deny");
                }
                yield args.length == 2 && isAnswer(args[0]) ? prefixed(requests.pendingRequesterNames(player), last)
                    : Collections.emptyList();
            }
            case TPAHERE -> args.length == 1 ? targets.complete(sender, last) : Collections.emptyList();
            case ACCEPT, DENY -> args.length == 1 ? prefixed(requests.pendingRequesterNames(player), last)
                : Collections.emptyList();
            case CANCEL -> Collections.emptyList();
        };
    }

    private static List<String> prefixed(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
            .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }
}
