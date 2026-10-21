package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.siege.SiegeCommandFilter;
import net.knightsandkings.knk.paper.duel.DuelService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The duel command filter (arena duels DESIGN §8.3): in the countdown and the fight a duelist may only use
 * {@code duels.command-allow-list} (default {@code /duel, /msg, /r, /staffchat}), under any alias or namespaced form.
 * Reuses the siege filter's matching (its always-allowed {@code /siege} labels stay allowed; the siege refuses a
 * duelist anyway). Staff with {@link DuelService#ADMIN_NODE} are exempt. {@code LOWEST}, so a blocked command never
 * reaches another handler.
 */
public class DuelCommandFilterListener implements Listener {

    private final DuelService service;
    private final Supplier<List<String>> allowed;
    private final UnaryOperator<String> aliases;
    private final Predicate<Player> exempt;

    public DuelCommandFilterListener(DuelService service, Supplier<List<String>> allowed, UnaryOperator<String> aliases,
                                     Predicate<Player> exempt) {
        this.service = service;
        this.allowed = allowed;
        this.aliases = aliases == null ? UnaryOperator.identity() : aliases;
        this.exempt = exempt == null ? p -> false : exempt;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!service.isOnBattleground(player.getUniqueId()) || exempt.test(player)) return;
        List<String> list = allowed.get();
        if (SiegeCommandFilter.isAllowed(event.getMessage(), list, aliases)) return;
        event.setCancelled(true);
        player.sendMessage(ChatColor.RED + "You can't use that command during a duel. Allowed: " + String.join(", ", list) + ".");
    }
}
