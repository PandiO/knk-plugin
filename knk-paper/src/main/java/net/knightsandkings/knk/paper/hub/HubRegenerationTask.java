package net.knightsandkings.knk.paper.hub;

import java.util.Objects;

import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.scheduler.BukkitRunnable;

import net.knightsandkings.knk.core.domain.settings.KnkHubRules;
import net.knightsandkings.knk.core.hub.HubRegeneration;

/**
 * The hub's healing and feeding (hub DESIGN §16.5), once per tick: only inside the selected hub's region, only while
 * the hub world's {@code naturalRegeneration} is on, and not when that world is already Peaceful (vanilla does it
 * there; doing both would double it). The heal goes through {@link EntityRegainHealthEvent} with
 * {@link EntityRegainHealthEvent.RegainReason#REGEN}, like vanilla's.
 */
public final class HubRegenerationTask extends BukkitRunnable {

    private final HubService hub;
    private long tick;

    public HubRegenerationTask(HubService hub) {
        this.hub = Objects.requireNonNull(hub, "hub");
    }

    @Override
    public void run() {
        tick++;
        if (!hub.configured()) {
            return;
        }
        KnkHubRules rules = hub.rules();
        if (!HubRegeneration.anythingDue(rules, tick)) {
            return;
        }
        World world = hub.world().orElse(null);
        if (world == null || world.getDifficulty() == Difficulty.PEACEFUL
            || Boolean.FALSE.equals(world.getGameRuleValue(GameRule.NATURAL_REGENERATION))) {
            return;
        }
        for (Player player : world.getPlayers()) {
            if (player.isDead() || !hub.inHub(player.getLocation())) {
                continue;
            }
            apply(player, rules);
        }
    }

    private void apply(Player player, KnkHubRules rules) {
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = max != null ? max.getValue() : 20.0;
        HubRegeneration.Vitals before = new HubRegeneration.Vitals(player.getHealth(), maxHealth, player.getFoodLevel(),
            player.getSaturation());
        HubRegeneration.Vitals after = HubRegeneration.tick(rules, tick, before);
        if (after.health() > before.health()) {
            EntityRegainHealthEvent event = new EntityRegainHealthEvent(player, after.health() - before.health(),
                EntityRegainHealthEvent.RegainReason.REGEN);
            player.getServer().getPluginManager().callEvent(event);
            if (!event.isCancelled()) {
                player.setHealth(Math.min(maxHealth, player.getHealth() + event.getAmount()));
            }
        }
        if (after.food() != before.food()) {
            player.setFoodLevel(after.food());
        }
        if (after.saturation() != before.saturation()) {
            player.setSaturation(after.saturation());
        }
    }
}
