package net.knightsandkings.knk.paper.npc.nav;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.npc.nav.NpcCollisionPolicy;
import net.knightsandkings.knk.core.npc.nav.NpcMover;

/**
 * K&K's collision policy on Paper (implementation plan 4c.4 item 7, link M7): per-entity collidable exemptions. An NPC
 * with collision on stays collidable (it pushes and blocks other NPCs); a player whose UUID is in its
 * {@link LivingEntity#getCollidableExemptions()} does not collide with it, so cannot push it. With
 * {@code players-push-npcs} off every online player is exempt (kept up to date on joins and quits); with it on only
 * the players the navigation service exempts after repeated pushes.
 * <p>
 * Chosen by the {@code npc-collision-policy} scenario on Paper 26.3 b205 with Citizens b4269 (link M7): a fake player
 * walked into a standing NPC carries it 2.85 blocks with collision on; with the player exempt the NPC does not move and
 * NPCs still push each other apart. A scoreboard team was not chosen although {@code FOR_OTHER_TEAMS} works too:
 * Citizens sends its own per-NPC teams to clients for name tags, an entity is on one team only, and other plugins (tab
 * lists, name tags) own teams; exemptions touch no team. (The rule names read backwards, research report § 6.4:
 * {@code FOR_OWN_TEAM}, vanilla {@code pushOwnTeam}, still let the player push.) A real client still predicts its own
 * player being nudged by the NPC's body; the NPC is not moved.
 * <p>
 * The exemption set lives on the entity, so a respawned NPC is enrolled again (the service does that). Main thread.
 */
public final class BukkitCollisionPolicy implements NpcCollisionPolicy, Listener {

    private record Enrolled(NpcMover mover, boolean playersPush) {
    }

    private final Function<NpcMover, Optional<Entity>> entities;
    private final Map<Integer, Enrolled> enrolled = new HashMap<>();
    private Plugin plugin;

    /** @param entities the NPC's entity while it is spawned (on Citizens {@code CitizensBootstrap::entity}) */
    public BukkitCollisionPolicy(Function<NpcMover, Optional<Entity>> entities) {
        this.entities = entities;
    }

    /** Listens for joins and quits, so new players are exempt from NPCs that players never push. */
    public BukkitCollisionPolicy start(Plugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        return this;
    }

    /** Stops listening; the exemptions stay on the entities until they are removed. */
    public void stop() {
        HandlerList.unregisterAll(this);
        enrolled.clear();
        plugin = null;
    }

    @Override
    public void enroll(NpcMover mover, boolean playersPush) {
        enrolled.put(mover.id(), new Enrolled(mover, playersPush));
        living(mover).ifPresent(e -> {
            e.setCollidable(true);
            if (!playersPush) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    e.getCollidableExemptions().add(p.getUniqueId());
                }
            }
        });
    }

    @Override
    public void release(int moverId) {
        enrolled.remove(moverId);
    }

    @Override
    public void exempt(int moverId, String playerId, boolean exempt) {
        Enrolled e = enrolled.get(moverId);
        UUID player = uuid(playerId);
        if (e == null || player == null) {
            return;
        }
        living(e.mover()).ifPresent(entity -> {
            if (exempt || !e.playersPush()) {
                entity.getCollidableExemptions().add(player); // players who never push stay exempt
            } else {
                entity.getCollidableExemptions().remove(player);
            }
        });
    }

    /** Whether a player is exempt from an enrolled NPC's collision now (diagnostics, tests). */
    public boolean exempt(int moverId, UUID player) {
        Enrolled e = enrolled.get(moverId);
        return e != null && living(e.mover()).map(entity -> entity.getCollidableExemptions().contains(player))
            .orElse(false);
    }

    public int enrolledCount() {
        return enrolled.size();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        for (Enrolled e : enrolled.values()) {
            if (!e.playersPush()) {
                living(e.mover()).ifPresent(entity -> entity.getCollidableExemptions().add(id));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        for (Enrolled e : enrolled.values()) {
            living(e.mover()).ifPresent(entity -> entity.getCollidableExemptions().remove(id));
        }
    }

    private Optional<LivingEntity> living(NpcMover mover) {
        return entities.apply(mover).filter(LivingEntity.class::isInstance).map(LivingEntity.class::cast);
    }

    private static UUID uuid(String id) {
        try {
            return id == null ? null : UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
