package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.siege.SiegeCommandFilter;
import net.knightsandkings.knk.paper.siege.SiegeMessages;
import net.knightsandkings.knk.paper.siege.SiegeService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.List;

/**
 * The in-match command filter (DESIGN §6.9, fixes N10): while a member is in HUB/IN_PROGRESS only
 * {@code SiegeConfiguration.AllowedCommands} run ({@code /siege} always does). Staff with
 * {@code knk.siege.bypass.commands} are exempt. Runs at {@code LOWEST} so a blocked command never
 * reaches another plugin's handler.
 */
public class SiegeCommandFilterListener implements Listener {

    private final SiegeService service;

    public SiegeCommandFilterListener(SiegeService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (service.isCommandAllowed(player, event.getMessage())) return;
        event.setCancelled(true);
        List<String> allowed = service.allowedCommandsFor(player).stream()
                .filter(c -> !SiegeCommandFilter.ALWAYS_ALLOWED_LABELS.contains(SiegeCommandFilter.label(c)))
                .toList();
        player.sendMessage(SiegeMessages.bad("You can't use that command during a siege. Allowed: /siege, /sgm"
                + (allowed.isEmpty() ? "" : ", " + String.join(", ", allowed)) + "."));
    }
}
