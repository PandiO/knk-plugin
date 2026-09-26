package net.knightsandkings.knk.paper.listeners;

import java.util.Objects;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.paper.user.IgnoreService;
import net.knightsandkings.knk.paper.user.MessagingService;
import net.knightsandkings.knk.paper.user.SpyService;

/**
 * Per-session private messaging state (docs/specs/private-messages/DESIGN.md §3.3.9): on join,
 * resolve the player's social spy nodes and load their ignore list; on quit, drop their reply
 * link, rate-limit history, spy flags and ignore list. Links pointing at the quitting player are
 * kept, so /r works again once they're back.
 */
public class PrivateMessageSessionListener implements Listener {

    /**
     * A second spy check (and ignore-list load, if the first found no user id) after join: the knk
     * user summary may not be cached yet at join.
     */
    private static final long SECOND_REFRESH_TICKS = 100L;

    private final Plugin plugin;
    private final MessagingService messagingService;
    private final SpyService spyService;
    private final IgnoreService ignoreService;

    public PrivateMessageSessionListener(Plugin plugin, MessagingService messagingService, SpyService spyService,
                                         IgnoreService ignoreService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.messagingService = Objects.requireNonNull(messagingService, "messagingService must not be null");
        this.spyService = Objects.requireNonNull(spyService, "spyService must not be null");
        this.ignoreService = Objects.requireNonNull(ignoreService, "ignoreService must not be null");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        spyService.refresh(player);
        ignoreService.load(player.getUniqueId());
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                spyService.refresh(player);
                ignoreService.load(player.getUniqueId());
            }
        }, SECOND_REFRESH_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        messagingService.forget(player.getUniqueId());
        spyService.forget(player.getUniqueId());
        ignoreService.forget(player.getUniqueId());
    }
}
