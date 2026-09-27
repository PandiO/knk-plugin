package net.knightsandkings.knk.paper.listeners;

import java.util.Locale;
import java.util.Set;

import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * Closes the vanilla side doors around /msg (docs/specs/private-messages/DESIGN.md §3.3.9). The
 * plugin's /msg, /tell and /w shadow the vanilla commands, but the namespaced vanilla ones and
 * /teammsg, /tm and /me stay available to every player and skip social spy, the rate limit and the
 * PM log. /teammsg is worse than a PM: every non-premium player shares the "default" scoreboard
 * team, so it's an unmonitored broadcast.
 * <ul>
 *   <li>{@code /minecraft:msg|tell|w <…>} is rewritten to {@code /msg <…>};</li>
 *   <li>{@code /teammsg}, {@code /tm}, {@code /me} (and their {@code minecraft:} forms) are
 *   cancelled.</li>
 * </ul>
 * Ops are left alone (e.g. for vanilla target selectors). Runs at LOWEST so every later listener
 * (freeze, siege command filter) sees the rewritten command.
 */
public class VanillaMessagingBlockListener implements Listener {

    static final Set<String> REWRITTEN = Set.of("minecraft:msg", "minecraft:tell", "minecraft:w");
    static final Set<String> BLOCKED = Set.of("teammsg", "tm", "minecraft:teammsg", "minecraft:tm", "me", "minecraft:me");
    static final String DISABLED_MESSAGE = "That command is disabled.";

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (event.getPlayer().isOp()) {
            return;
        }
        String message = event.getMessage();
        String label = label(message);
        if (REWRITTEN.contains(label)) {
            String rest = message.substring(labelEnd(message));
            event.setMessage("/msg" + rest);
        } else if (BLOCKED.contains(label)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + DISABLED_MESSAGE);
        }
    }

    /** The lower-cased command label, namespace kept: {@code "/Minecraft:Tell Bob hi"} → {@code "minecraft:tell"}. */
    public static String label(String message) {
        String trimmed = message.startsWith("/") ? message.substring(1) : message;
        int space = trimmed.indexOf(' ');
        return (space < 0 ? trimmed : trimmed.substring(0, space)).toLowerCase(Locale.ROOT);
    }

    private static int labelEnd(String message) {
        int space = message.indexOf(' ');
        return space < 0 ? message.length() : space;
    }
}
