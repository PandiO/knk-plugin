package net.knightsandkings.knk.paper.npc.citizens;

import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.npc.nav.NpcMoverFactory;

/**
 * Starts the Citizens adapter when Citizens is enabled. Its signature names no Citizens type, so callers load and run
 * without Citizens on the server.
 */
public final class CitizensBootstrap {

    private CitizensBootstrap() {
    }

    /** Why Citizens can't be used, or empty when it can. */
    public static Optional<String> unavailableReason() {
        Plugin citizens = Bukkit.getPluginManager().getPlugin("Citizens");
        if (citizens == null) {
            return Optional.of("Citizens is not installed");
        }
        if (!citizens.isEnabled()) {
            return Optional.of("Citizens is installed but not enabled");
        }
        return Optional.empty();
    }

    /** The Citizens version, or "absent". */
    public static String citizensVersion() {
        Plugin citizens = Bukkit.getPluginManager().getPlugin("Citizens");
        return citizens == null ? "absent" : citizens.getDescription().getVersion();
    }

    /** The mover factory on Citizens; empty with the reason logged by the caller when Citizens isn't usable. */
    public static Optional<NpcMoverFactory> start(Plugin plugin) {
        if (unavailableReason().isPresent()) {
            return Optional.empty();
        }
        return Optional.of(new CitizensNpcRuntime(plugin));
    }

    /**
     * A mover factory on Citizens with its own NPC registry, beside the navigation module's (the NPC scenarios of
     * implementation plan 4b.2 item 18); stop it with {@link #stop}.
     */
    public static Optional<NpcMoverFactory> start(Plugin plugin, String registryName) {
        if (unavailableReason().isPresent()) {
            return Optional.empty();
        }
        return Optional.of(new CitizensNpcRuntime(plugin, registryName));
    }

    /**
     * How often the K&K block examiner was asked about a cell and how often it blocked one, since the server
     * started: {@code {asked, blocked}}. Zeros without Citizens.
     */
    public static long[] examinerStats() {
        if (unavailableReason().isPresent()) {
            return new long[] {0, 0};
        }
        return ExaminerStats.read();
    }

    /** Kept apart so {@link KnkBlockExaminer} (which names Citizens types) is only loaded with Citizens present. */
    private static final class ExaminerStats {
        static long[] read() {
            return new long[] {KnkBlockExaminer.CALLS.sum(), KnkBlockExaminer.BLOCKED.sum()};
        }
    }

    /**
     * Puts a Citizens NPC's body somewhere else at once (scenarios: a player pushing an NPC into a corner); false for
     * a mover that is not a Citizens one.
     */
    public static boolean teleport(net.knightsandkings.knk.core.npc.nav.NpcMover mover,
                                   net.knightsandkings.knk.core.npc.nav.NavPoint feet) {
        if (mover instanceof CitizensNpcMover citizens) {
            citizens.teleport(feet);
            return true;
        }
        return false;
    }

    /** The NPC's entity while it is spawned (scenarios and the Paper wiring only). */
    public static java.util.Optional<org.bukkit.entity.Entity> entity(net.knightsandkings.knk.core.npc.nav.NpcMover mover) {
        if (mover instanceof CitizensNpcMover citizens && citizens.npc().isSpawned()) {
            return java.util.Optional.ofNullable(citizens.npc().getEntity());
        }
        return java.util.Optional.empty();
    }

    /**
     * Citizens' protection of an NPC (scenarios only): a protected NPC takes no damage, so an attack knocks it back
     * only when it is not protected. K&K's NPCs keep Citizens' default (protected).
     */
    public static boolean setProtected(net.knightsandkings.knk.core.npc.nav.NpcMover mover, boolean on) {
        if (mover instanceof CitizensNpcMover citizens) {
            citizens.npc().setProtected(on);
            return true;
        }
        return false;
    }

    /** Removes the runtime's NPCs and registry; safe with any factory or null. */
    public static void stop(NpcMoverFactory factory) {
        if (factory instanceof CitizensNpcRuntime runtime) {
            runtime.shutdown();
        }
    }
}
