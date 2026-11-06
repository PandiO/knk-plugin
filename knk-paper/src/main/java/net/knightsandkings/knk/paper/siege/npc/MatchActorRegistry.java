package net.knightsandkings.knk.paper.siege.npc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;

/**
 * The match actors of the running Survival matches (Siege Survival link S3, PandiO/knk-workspace#41): mobs the
 * Survival runtime (link S5) registers with their lobby, NPC team and capture weight. The registry
 * <ul>
 *   <li>tags the entity (persistent data {@code <plugin>:match_actor} = {@code "<lobbyId>:<teamId>"}) so a
 *       restart's leftover sweep can recognise and remove match mobs ({@link #tagOf});</li>
 *   <li>answers "is this entity (or this projectile's shooter) a match actor, of which team" for the siege gate
 *       damage rule ({@code SiegeGateController.mayDamage(Entity, int)}), the statistics exclusion and the
 *       Survival targeting;</li>
 *   <li>supplies the living actors inside an objective's capture radius as weighted {@link Presence}s for the
 *       capture step ({@code SiegeService}); an actor whose entity is gone or dead is forgotten on the way.</li>
 * </ul>
 * Without registered actors nothing here changes a match. Main thread.
 */
public final class MatchActorRegistry {

    /** The persistent-data key name under the plugin's namespace. */
    public static final String TAG_KEY = "match_actor";

    private final NamespacedKey tagKey;
    private final Function<UUID, Entity> entities;
    private final Map<UUID, MatchActor> actors = new ConcurrentHashMap<>();

    public MatchActorRegistry(Plugin plugin) {
        this(new NamespacedKey(plugin, TAG_KEY), Bukkit::getEntity);
    }

    /** @param entities resolves a registered actor's entity ({@code Bukkit::getEntity}); null when it is gone */
    MatchActorRegistry(NamespacedKey tagKey, Function<UUID, Entity> entities) {
        this.tagKey = Objects.requireNonNull(tagKey, "tagKey");
        this.entities = Objects.requireNonNull(entities, "entities");
    }

    /**
     * Registers (or re-registers) a mob as an actor of the lobby's NPC team and tags it.
     *
     * @param captureWeight the difficulty's capture contribution per NPC (1 = one player)
     */
    public MatchActor register(Entity entity, int lobbyId, int teamId, double captureWeight) {
        Objects.requireNonNull(entity, "entity");
        MatchActor actor = new MatchActor(entity.getUniqueId(), lobbyId, teamId, captureWeight);
        actors.put(actor.entityId(), actor);
        entity.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, lobbyId + ":" + teamId);
        return actor;
    }

    /** Forgets an actor (its death, removal or the match's end); the tag stays on a still-living entity. */
    public Optional<MatchActor> unregister(UUID entityId) {
        return entityId == null ? Optional.empty() : Optional.ofNullable(actors.remove(entityId));
    }

    /** Forgets every actor of the lobby (the match ended or was restored). Returns the forgotten actors. */
    public List<MatchActor> clear(int lobbyId) {
        List<MatchActor> removed = new ArrayList<>();
        actors.values().removeIf(actor -> {
            if (actor.lobbyId() != lobbyId) return false;
            removed.add(actor);
            return true;
        });
        return removed;
    }

    /** The actor behind an entity: the entity itself, or a projectile's shooter when that is a registered mob. */
    public Optional<MatchActor> actorOf(Entity entity) {
        if (entity == null) return Optional.empty();
        MatchActor direct = actors.get(entity.getUniqueId());
        if (direct != null) return Optional.of(direct);
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            return Optional.ofNullable(actors.get(shooter.getUniqueId()));
        }
        return Optional.empty();
    }

    public Optional<MatchActor> actorOf(UUID entityId) {
        return entityId == null ? Optional.empty() : Optional.ofNullable(actors.get(entityId));
    }

    /** Whether the entity (or its projectile's shooter) is a registered match actor. */
    public boolean isActor(Entity entity) {
        return actorOf(entity).isPresent();
    }

    /** The registered actors of a lobby (a snapshot). */
    public List<MatchActor> actors(int lobbyId) {
        return actors.values().stream().filter(a -> a.lobbyId() == lobbyId).toList();
    }

    public Collection<MatchActor> actors() {
        return List.copyOf(actors.values());
    }

    public int size() {
        return actors.size();
    }

    /**
     * The tag an entity carries, {@code [lobbyId, teamId]}, whether or not it is registered in this run - for the
     * leftover sweep after a restart. Empty for untagged entities and unreadable tags.
     */
    public Optional<int[]> tagOf(Entity entity) {
        if (entity == null) return Optional.empty();
        String tag = entity.getPersistentDataContainer().get(tagKey, PersistentDataType.STRING);
        if (tag == null) return Optional.empty();
        int colon = tag.indexOf(':');
        if (colon <= 0) return Optional.empty();
        try {
            return Optional.of(new int[] {Integer.parseInt(tag.substring(0, colon)), Integer.parseInt(tag.substring(colon + 1))});
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * The living actors of the lobby inside the radius around {@code center}, as weighted NPC presences for the
     * capture step (DESIGN §4: "NPC presence must participate in the objective capture calculation"). Actors whose
     * entity is gone or dead are forgotten. An entity in another world is outside.
     */
    public List<Presence> presences(int lobbyId, Location center, double radius) {
        List<Presence> present = new ArrayList<>();
        if (center == null || center.getWorld() == null) return present;
        for (MatchActor actor : actors.values()) {
            if (actor.lobbyId() != lobbyId) continue;
            Entity entity = entities.apply(actor.entityId());
            if (!(entity instanceof LivingEntity living) || living.isDead() || !living.isValid()) {
                actors.remove(actor.entityId());
                continue;
            }
            Location at = living.getLocation();
            if (at == null || !Objects.equals(at.getWorld(), center.getWorld())) continue;
            double distance = at.distance(center);
            if (distance <= radius) {
                present.add(Presence.npc(actor.entityId(), actor.teamId(), distance, actor.captureWeight()));
            }
        }
        return present;
    }
}
