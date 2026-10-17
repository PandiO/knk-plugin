package net.knightsandkings.knk.paper.hub;

import java.util.Objects;

import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import com.sk89q.worldguard.bukkit.event.block.UseBlockEvent;
import com.sk89q.worldguard.bukkit.event.entity.UseEntityEvent;

/**
 * Lets the hub's own rules decide the uses they allow (hub DESIGN §13: doors and NPCs allowed by default). WorldGuard
 * refuses doors, storage and NPCs to anyone who isn't a member of the region unless its {@code use}, {@code
 * chest-access} or {@code interact} flag allows them, so in a hub inside a Town region the hub's "doors allowed" did
 * nothing (smoke test 2026-10-17). For a use the hub's rules allow, a player's WorldGuard use event is marked allowed
 * before WorldGuard's region check (which runs at NORMAL and skips an allowed event). Refusals by other plugins are left
 * alone, and the uses the hub refuses stay with {@link HubRulesListener}.
 */
public final class HubWorldGuardListener implements Listener {

    private final HubRulesListener rules;

    public HubWorldGuardListener(HubRulesListener rules) {
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onUseBlock(UseBlockEvent event) {
        if (event.getCause().getFirstPlayer() != null && !event.getBlocks().isEmpty()
                && event.getBlocks().stream().allMatch(rules::allowsUse)) {
            event.setResult(Event.Result.ALLOW);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onUseEntity(UseEntityEvent event) {
        if (event.getCause().getFirstPlayer() != null && rules.allowsUse(event.getEntity())) {
            event.setResult(Event.Result.ALLOW);
        }
    }
}
