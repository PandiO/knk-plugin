package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.siege.CaptureActivityTracker;
import net.knightsandkings.knk.core.siege.CaptureActivityTracker.Activity;
import net.knightsandkings.knk.core.siege.CaptureActivityTracker.Ongoing;
import net.knightsandkings.knk.core.siege.CaptureActivityTracker.Started;
import net.knightsandkings.knk.core.siege.ObjectiveState.CaptureEvent;
import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;
import net.knightsandkings.knk.core.siege.SiegeDisplayText;
import net.knightsandkings.knk.core.siege.SiegeObjectiveBoard.BoardStep;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Capture feedback (smoke test 2026-09-26): sounds, particles and chat when a side begins attacking
 * or defending an objective, a continuous cue for the players doing it, and a burst on capture (the
 * capture's own sounds and messages are in {@code SiegeService.announceCapture}). Built on
 * {@link CaptureActivityTracker}; every effect is sent per player, to match members only.
 * <p>
 * All cues are the constants below, so tuning them is a one-line change:
 * <ul>
 *   <li>Attack begins: the attackers' alliance hears a war horn and sees crits, with a chat line naming
 *       who began capturing what; the holder's alliance hears the alarm bell and sees angry-villager
 *       clouds, with a chat line naming the objective, the attacking team and the progress.</li>
 *   <li>Defence begins (the holder pushing the points back): the holder's alliance hears a second horn
 *       and sees happy-villager sparks, with a chat line naming the defenders; the other side hears a low
 *       bass note and sees smoke.</li>
 *   <li>Every second of it: the attackers in the ring hear a pling rising in pitch with the capture
 *       progress (enchanted-hit sparks around them); defenders in the ring hear a chime rising as the
 *       points recover (happy-villager sparks). Members nearby see the actors' particles too.</li>
 *   <li>Capture: a totem burst at the capture point for members nearby.</li>
 * </ul>
 */
public final class SiegeCaptureFeedback implements SiegeMatchObserver {

    // ---- Attack begins ----
    static final Sound ATTACK_START_ALLIES_SOUND = Sound.ITEM_GOAT_HORN_SOUND_0;   // "Ponder" war horn
    static final Particle ATTACK_START_ALLIES_PARTICLE = Particle.CRIT;
    static final Sound ATTACK_START_DEFENDERS_SOUND = Sound.BLOCK_BELL_USE;        // alarm bell
    static final Particle ATTACK_START_DEFENDERS_PARTICLE = Particle.ANGRY_VILLAGER;

    // ---- Defence begins ----
    static final Sound DEFEND_START_ALLIES_SOUND = Sound.ITEM_GOAT_HORN_SOUND_1;   // "Sing" horn
    static final Particle DEFEND_START_ALLIES_PARTICLE = Particle.HAPPY_VILLAGER;
    static final Sound DEFEND_START_ENEMIES_SOUND = Sound.BLOCK_NOTE_BLOCK_BASS;
    static final Particle DEFEND_START_ENEMIES_PARTICLE = Particle.SMOKE;

    // ---- Every second, for the players doing it ----
    static final Sound CAPTURING_SOUND = Sound.BLOCK_NOTE_BLOCK_PLING;
    static final Particle CAPTURING_PARTICLE = Particle.ENCHANTED_HIT;
    static final Sound DEFENDING_SOUND = Sound.BLOCK_NOTE_BLOCK_CHIME;
    static final Particle DEFENDING_PARTICLE = Particle.HAPPY_VILLAGER;

    // ---- Capture ----
    static final Particle CAPTURED_PARTICLE = Particle.TOTEM_OF_UNDYING;

    /** Members further away than this don't see other players' capture particles. */
    private static final double PARTICLE_RANGE = 48;

    private final Map<String, CaptureActivityTracker> trackers = new HashMap<>();

    @Override
    public void matchStarted(SiegeLobbyRuntime lobby, SiegeMatch match) {
        trackers.put(match.matchToken(), new CaptureActivityTracker());
    }

    @Override
    public void secondTicked(SiegeLobbyRuntime lobby, SiegeMatch match, BoardStep step,
                             Map<Integer, List<Presence>> presence) {
        CaptureActivityTracker tracker = trackers.computeIfAbsent(match.matchToken(), t -> new CaptureActivityTracker());
        CaptureActivityTracker.Update update = tracker.step(step.steps(), presence, match.board()::objective, match.alliances());
        for (Started started : update.started()) {
            if (started.activity() == Activity.ATTACKING) attackStarted(match, started);
            else defenceStarted(match, started);
        }
        for (Ongoing ongoing : update.ongoing()) {
            continuous(match, ongoing);
        }
    }

    @Override
    public void objectiveCaptured(SiegeLobbyRuntime lobby, SiegeMatch match, CaptureEvent capture) {
        match.scenario().objective(capture.objectiveId())
                .flatMap(o -> SiegeBukkit.toLocation(o.captureLocation()))
                .map(SiegeBukkit::floorOf)
                .ifPresent(center -> {
                    Location at = center.clone().add(0, 1, 0);
                    for (Player p : members(match)) {
                        if (near(p, at)) p.spawnParticle(CAPTURED_PARTICLE, at, 60, 0.8, 1.2, 0.8, 0.35);
                    }
                });
    }

    @Override
    public void matchEnded(SiegeLobbyRuntime lobby, SiegeMatch match) {
        trackers.remove(match.matchToken());
    }

    @Override
    public void shutdown() {
        trackers.clear();
    }

    // ==================== Begin cues ====================

    private void attackStarted(SiegeMatch match, Started s) {
        KnkSiegeScenario scenario = match.scenario();
        String objective = objectiveName(scenario, s.objectiveId());
        String who = names(s.actors());
        int holderAlliance = match.alliances().allianceOf(s.holderTeamId());
        Component attackers = s.actorTeamIds().isEmpty() ? Component.text("the enemy", SiegeMessages.BAD)
                : SiegeBukkit.teamComponent(scenario.team(s.actorTeamIds().get(0)).orElse(null));
        List<Integer> attackerAlliances = s.actorTeamIds().stream().map(t -> match.alliances().allianceOf(t)).toList();

        for (Player p : members(match)) {
            int alliance = allianceOf(match, p);
            if (attackerAlliances.contains(alliance)) {
                p.sendMessage(SiegeMessages.good(who + " began " + (s.retake() ? "retaking " : "capturing ") + objective + "!"));
                once(p, ATTACK_START_ALLIES_SOUND, 1f, 1f, ATTACK_START_ALLIES_PARTICLE, 20);
            } else if (alliance == holderAlliance) {
                p.sendMessage(SiegeMessages.prefixed(Component.text(objective + " is being captured by ", SiegeMessages.BAD)
                        .append(attackers)
                        .append(Component.text("! (" + s.capturePercent() + "%)", SiegeMessages.BAD))));
                once(p, ATTACK_START_DEFENDERS_SOUND, 1f, 0.8f, ATTACK_START_DEFENDERS_PARTICLE, 8);
            }
        }
    }

    private void defenceStarted(SiegeMatch match, Started s) {
        String objective = objectiveName(match.scenario(), s.objectiveId());
        String who = names(s.actors());
        int holderAlliance = match.alliances().allianceOf(s.holderTeamId());
        for (Player p : members(match)) {
            if (allianceOf(match, p) == holderAlliance) {
                p.sendMessage(SiegeMessages.good(who + " began defending " + objective + " (" + s.capturePercent() + "% captured)."));
                once(p, DEFEND_START_ALLIES_SOUND, 1f, 1f, DEFEND_START_ALLIES_PARTICLE, 12);
            } else {
                p.sendMessage(SiegeMessages.bad("The enemy is pushing you back at " + objective + "!"));
                once(p, DEFEND_START_ENEMIES_SOUND, 1f, 0.6f, DEFEND_START_ENEMIES_PARTICLE, 12);
            }
        }
    }

    // ==================== Continuous cue ====================

    private void continuous(SiegeMatch match, Ongoing o) {
        boolean attacking = o.activity() == Activity.ATTACKING;
        // Rises 0.5 -> 2.0 with the capture progress (attackers) or the recovery (defenders).
        float progress = (attacking ? o.capturePercent() : 100 - o.capturePercent()) / 100f;
        float pitch = 0.5f + 1.5f * Math.max(0f, Math.min(1f, progress));
        Sound sound = attacking ? CAPTURING_SOUND : DEFENDING_SOUND;
        Particle particle = attacking ? CAPTURING_PARTICLE : DEFENDING_PARTICLE;
        List<Player> viewers = members(match);
        for (UUID id : o.actors()) {
            Player actor = Bukkit.getPlayer(id);
            if (actor == null) continue;
            actor.playSound(actor.getLocation(), sound, 0.6f, pitch);
            Location at = actor.getLocation().add(0, 1, 0);
            for (Player viewer : viewers) {
                if (near(viewer, at)) viewer.spawnParticle(particle, at, 8, 0.4, 0.6, 0.4, 0.05);
            }
        }
    }

    // ==================== Helpers ====================

    private static void once(Player p, Sound sound, float volume, float pitch, Particle particle, int count) {
        p.playSound(p.getLocation(), sound, volume, pitch);
        p.spawnParticle(particle, p.getLocation().add(0, 1.2, 0), count, 0.5, 0.6, 0.5, 0.05);
    }

    private static List<Player> members(SiegeMatch match) {
        List<Player> players = new ArrayList<>();
        for (UUID id : match.roster().playerIds()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) players.add(p);
        }
        return players;
    }

    private static int allianceOf(SiegeMatch match, Player p) {
        int teamId = match.roster().teamOf(p.getUniqueId()).orElse(Integer.MIN_VALUE);
        return match.alliances().knows(teamId) ? match.alliances().allianceOf(teamId) : Integer.MIN_VALUE;
    }

    private static boolean near(Player viewer, Location at) {
        return Objects.equals(viewer.getWorld(), at.getWorld())
                && viewer.getLocation().distanceSquared(at) <= PARTICLE_RANGE * PARTICLE_RANGE;
    }

    private static String objectiveName(KnkSiegeScenario scenario, int objectiveId) {
        return scenario.objective(objectiveId).map(o -> SiegeDisplayText.clean(o.name(), "#" + o.id())).orElse("#" + objectiveId);
    }

    /** "Alice", "Alice and Bob", "Alice, Bob and 2 others". */
    static String names(List<UUID> ids) {
        List<String> names = new ArrayList<>();
        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            names.add(p != null ? p.getName() : "Someone");
        }
        if (names.isEmpty()) return "Your team";
        if (names.size() == 1) return names.get(0);
        if (names.size() == 2) return names.get(0) + " and " + names.get(1);
        return names.get(0) + ", " + names.get(1) + " and " + (names.size() - 2) + " other" + (names.size() == 3 ? "" : "s");
    }
}
