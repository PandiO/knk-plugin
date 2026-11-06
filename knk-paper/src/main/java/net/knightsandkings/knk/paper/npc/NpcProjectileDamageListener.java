package net.knightsandkings.knk.paper.npc;

import net.knightsandkings.knk.core.npc.profile.TargetTypes;
import net.knightsandkings.knk.core.npc.resolve.ResolvedCombat;
import net.knightsandkings.knk.core.npc.resolve.VanillaBases;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;

/**
 * Sets an adopted mob's arrow damage to the resolved projectile damage in the shoot event (NPC platform §5b; checks
 * report §4: arrow damage is on the projectile, not an attribute). The vanilla arrow damage is the base the pipeline
 * scales. Projectiles of mobs the platform doesn't know are untouched.
 */
public final class NpcProjectileDamageListener implements Listener {

    private final NpcPlatform platform;

    public NpcProjectileDamageListener(NpcPlatform platform) {
        this.platform = platform;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !platform.isAdopted(mob.getUniqueId())) return;
        if (!(event.getProjectile() instanceof AbstractArrow arrow)) return;
        String targetType = mob.getTarget() instanceof org.bukkit.entity.Player ? TargetTypes.PLAYER : TargetTypes.NONE;
        ResolvedCombat combat = platform.resolveCombat(mob, targetType, VanillaBases.projectile(arrow.getDamage()));
        arrow.setDamage(combat.projectileDamageValue());
    }
}
