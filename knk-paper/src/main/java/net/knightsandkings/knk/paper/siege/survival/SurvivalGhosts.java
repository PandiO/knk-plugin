package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.survival.SurvivalSubstate;
import net.knightsandkings.knk.core.visibility.GhostTeam;
import net.knightsandkings.knk.paper.visibility.PlayerVisibilityArbiter;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The ghosts of one survival match (DESIGN §3b, S20, S41, S60): who is a ghost, until when ({@link GhostRules}), and
 * the Paper side of the state: hidden from everyone but teammates through the visibility arbiter (link S3), marked
 * invulnerable, no collision, and released at the intermission (or the next round) at a valid spawn. The event
 * cancellations (damage, hunger, interaction, pickups, targeting, projectiles, confinement) are in
 * {@link SurvivalGhostListener}, which asks {@link #isGhost}. Main thread.
 */
public final class SurvivalGhosts {

    /** One ghost: when they died and when they come back. */
    public record Ghost(UUID playerId, SurvivalSubstate diedIn, GhostRules.Release release, boolean effectsApplied) {
        Ghost applied() {
            return new Ghost(playerId, diedIn, release, true);
        }
    }

    private final PlayerVisibilityArbiter arbiter;
    private final GhostTeam team;
    private final Function<Player, Optional<Location>> respawnPoint;
    private final Map<UUID, Ghost> ghosts = new LinkedHashMap<>();

    /**
     * @param respawnPoint where a released ghost goes (the Siege respawn resolution: the player's valid spawn choice,
     *                     falling back to a team spawn when the chosen objective was lost)
     */
    public SurvivalGhosts(PlayerVisibilityArbiter arbiter, GhostTeam team, Function<Player, Optional<Location>> respawnPoint) {
        this.arbiter = Objects.requireNonNull(arbiter, "arbiter");
        this.team = Objects.requireNonNull(team, "team");
        this.respawnPoint = Objects.requireNonNull(respawnPoint, "respawnPoint");
    }

    public boolean isGhost(UUID playerId) {
        return ghosts.containsKey(playerId);
    }

    public Set<UUID> ghostIds() {
        return Set.copyOf(ghosts.keySet());
    }

    public Optional<Ghost> ghost(UUID playerId) {
        return Optional.ofNullable(ghosts.get(playerId));
    }

    public int size() {
        return ghosts.size();
    }

    /**
     * A member died in {@code substate}: becomes a ghost when the rules say so (not before wave 1, not while ending).
     * The Paper effects follow at the respawn ({@link #applyOnRespawn}), because the dead body has no living entity.
     *
     * @return true when the player is now a ghost
     */
    public boolean onDeath(Player player, SurvivalSubstate substate) {
        if (!GhostRules.becomesGhost(substate)) return false;
        ghosts.put(player.getUniqueId(), new Ghost(player.getUniqueId(), substate, GhostRules.releaseFor(substate), false));
        return true;
    }

    /**
     * The player respawned (clicked the button). A ghost whose release moment already passed respawns alive (DESIGN
     * §3: the intermission respawn is at its start); otherwise the ghost look and rules are applied now.
     *
     * @return true when the player is a ghost after this respawn
     */
    public boolean applyOnRespawn(Player player, SurvivalSubstate now) {
        Ghost ghost = ghosts.get(player.getUniqueId());
        if (ghost == null) return false;
        if (GhostRules.respawnsAlive(ghost.release(), now)) {
            ghosts.remove(player.getUniqueId());
            return false;
        }
        applyEffects(player);
        ghosts.put(player.getUniqueId(), ghost.applied());
        return true;
    }

    /** Hides the ghost from non-teammates, makes it invulnerable and non-colliding. Idempotent; also the re-apply after a join hold. */
    public void applyEffects(Player player) {
        arbiter.markGhost(player, team);
        player.setInvulnerable(true);
        player.setCollidable(false);
        player.setFireTicks(0);
        player.setFoodLevel(Math.max(player.getFoodLevel(), 20));
        player.setSaturation(Math.max(player.getSaturation(), 5f));
    }

    /** The substate changed: releases the ghosts whose moment this is, teleporting them to a valid spawn. */
    public List<UUID> onTransition(SurvivalSubstate entered, Function<UUID, Player> online) {
        List<UUID> released = new ArrayList<>();
        for (Ghost ghost : new ArrayList<>(ghosts.values())) {
            if (!GhostRules.releasedBy(ghost.release(), entered)) continue;
            release(ghost.playerId(), online.apply(ghost.playerId()), true);
            released.add(ghost.playerId());
        }
        return released;
    }

    /** Ends one player's ghost state (left the match, match end); {@code teleport} = send them to a spawn. */
    public void release(UUID playerId, Player player, boolean teleport) {
        Ghost ghost = ghosts.remove(playerId);
        if (player == null) return;
        if (ghost != null && ghost.effectsApplied()) clearEffects(player);
        else if (arbiter.isGhost(playerId)) clearEffects(player);
        if (teleport && player.isOnline() && !player.isDead()) {
            respawnPoint.apply(player).ifPresent(at -> player.teleport(at));
        }
    }

    private void clearEffects(Player player) {
        arbiter.clearGhost(player);
        player.setInvulnerable(false);
        player.setCollidable(true);
    }

    /** Match end / restore: every ghost state ends (DESIGN §3b: "the ghost state also ends when the game ends"). */
    public void releaseAll(Function<UUID, Player> online) {
        for (UUID id : new ArrayList<>(ghosts.keySet())) {
            release(id, online.apply(id), false);
        }
    }

    /** The team whose members see the ghosts. */
    public GhostTeam team() {
        return team;
    }
}
