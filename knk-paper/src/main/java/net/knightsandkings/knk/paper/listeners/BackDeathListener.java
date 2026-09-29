package net.knightsandkings.knk.paper.listeners;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import net.knightsandkings.knk.paper.teleport.BackDeathExclusion;
import net.knightsandkings.knk.paper.teleport.BackService;

/**
 * Records where players die, for {@code /back} (docs/specs/teleport Phase 7).
 * <p>
 * Two steps, because "was this a siege death?" must be answered before the siege handles the death
 * (its listener runs at HIGHEST and may end the match), while the death must only be recorded if it
 * really happened (Paper lets a plugin cancel a {@code PlayerDeathEvent}):
 * <ol>
 *   <li>LOWEST: ask the {@link BackDeathExclusion}s, remember the verdict.</li>
 *   <li>MONITOR: if the death went through, record it (or, for an excluded death, forget any older
 *       one); the verdict is dropped either way.</li>
 * </ol>
 */
public class BackDeathListener implements Listener {

    private final BackService backService;
    /** Verdicts between the two steps of one death event (main thread; cleared at MONITOR). */
    private final Map<UUID, Boolean> excludedDeaths = new ConcurrentHashMap<>();

    public BackDeathListener(BackService backService) {
        this.backService = Objects.requireNonNull(backService, "backService must not be null");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeathEarly(PlayerDeathEvent event) {
        Player player = event.getEntity();
        excludedDeaths.put(player.getUniqueId(), backService.isExcluded(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Boolean excluded = excludedDeaths.remove(player.getUniqueId());
        if (event.isCancelled()) {
            return;
        }
        // No early verdict (another plugin fired the event itself): ask now.
        boolean skip = excluded != null ? excluded : backService.isExcluded(player);
        backService.recordDeath(player, player.getLocation(), skip);
    }
}
