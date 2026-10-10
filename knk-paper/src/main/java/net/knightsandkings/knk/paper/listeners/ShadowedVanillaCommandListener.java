package net.knightsandkings.knk.paper.listeners;

import java.util.Set;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;

/**
 * Keeps {@code /minecraft:tell} and {@code /minecraft:w} from breaking the player's secure chat (KNG-25).
 * <p>
 * The plugin's /msg takes {@code tell} and {@code w} as aliases. In the vanilla command tree those
 * two are redirects to {@code msg}, and on the server the namespaced {@code minecraft:tell} /
 * {@code minecraft:w} now resolve to the plugin's command, which has no signed arguments. The client
 * is still told they lead to vanilla /msg with a signed {@code message} argument, so it signs the
 * message. The server rejects the command ("Signed command mismatch ... got [message] from client,
 * but expected []"), and the player's chat stays disabled until they reconnect ("Chat disabled due to
 * broken chain"). That check runs while the packet is decoded, before any Bukkit command event, so
 * rewriting the command later can't help.
 * <p>
 * So the two labels are left out of the command tree sent to clients. The client then sends them as
 * plain unsigned commands, which is what the server expects, and they reach the plugin's /msg (or
 * {@link VanillaMessagingBlockListener}'s rewrite to /msg). /minecraft:msg is untouched: it still
 * leads to vanilla /msg on both sides, so its signatures match.
 * <p>
 * Ops are routed to {@code /minecraft:msg} instead, so vanilla target selectors such as {@code @a}
 * keep working for them ({@link VanillaMessagingBlockListener} leaves ops alone for the same reason).
 */
public class ShadowedVanillaCommandListener implements Listener {

    /** Vanilla redirects to msg whose plain label the plugin's /msg took as an alias. */
    static final Set<String> SHADOWED = Set.of("minecraft:tell", "minecraft:w");
    static final String VANILLA_MSG = "minecraft:msg";

    @EventHandler(priority = EventPriority.HIGH)
    public void onCommandSend(PlayerCommandSendEvent event) {
        event.getCommands().removeIf(label -> SHADOWED.contains(label.toLowerCase(java.util.Locale.ROOT)));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!event.getPlayer().isOp()) {
            return;
        }
        String message = event.getMessage();
        if (SHADOWED.contains(VanillaMessagingBlockListener.label(message))) {
            int space = message.indexOf(' ');
            event.setMessage("/" + VANILLA_MSG + (space < 0 ? "" : message.substring(space)));
        }
    }
}
