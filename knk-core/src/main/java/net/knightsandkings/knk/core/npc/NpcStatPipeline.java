package net.knightsandkings.knk.core.npc;

import net.knightsandkings.knk.core.npc.cooldown.CooldownJitter;
import net.knightsandkings.knk.core.npc.cooldown.CooldownTracker;
import net.knightsandkings.knk.core.npc.modifier.ActorModifiers;
import net.knightsandkings.knk.core.npc.modifier.ModifierSet;
import net.knightsandkings.knk.core.npc.profile.ProfileRegistry;
import net.knightsandkings.knk.core.npc.resolve.ResolvedCombat;
import net.knightsandkings.knk.core.npc.resolve.ResolvedSpeed;
import net.knightsandkings.knk.core.npc.resolve.StatResolver;
import net.knightsandkings.knk.core.npc.resolve.VanillaBases;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * The one object a Paper adapter (link S5) holds: profiles, per-actor modifiers and cooldowns, with
 * the resolver wired in. Consumers (Survival's round scaling, status effects) talk to
 * {@link #modifiers()}; adapters call {@link #resolveCombat} on every attack or shot and
 * {@link #resolveSpeed} on every speed refresh, then {@link #tryMelee} / {@link #tryShot} to pace them.
 *
 * <p>Profiles can be swapped on a config reload with {@link #reloadProfiles}; modifier and cooldown
 * state survives the swap. {@link ModifierSet#revision()} tells an adapter whether anything changed
 * since it last applied a value, so clamp reports are logged once per change rather than per tick.
 */
public final class NpcStatPipeline {
    private volatile ProfileRegistry profiles;
    private final ActorModifiers modifiers = new ActorModifiers();
    private final CooldownTracker cooldowns;

    public NpcStatPipeline(ProfileRegistry profiles) {
        this(profiles, CooldownJitter.OFF, RandomGenerator.getDefault());
    }

    public NpcStatPipeline(ProfileRegistry profiles, CooldownJitter jitter, RandomGenerator random) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.cooldowns = new CooldownTracker(jitter, random);
    }

    /** Code defaults, no jitter. */
    public static NpcStatPipeline vanilla() {
        return new NpcStatPipeline(ProfileRegistry.vanilla());
    }

    public ProfileRegistry profiles() {
        return profiles;
    }

    public void reloadProfiles(ProfileRegistry registry) {
        this.profiles = Objects.requireNonNull(registry, "registry");
    }

    public ActorModifiers modifiers() {
        return modifiers;
    }

    public CooldownTracker cooldowns() {
        return cooldowns;
    }

    /** The actor's combat stats for {@code profileId} against {@code targetType}; vanilla damages from {@code bases}. */
    public ResolvedCombat resolveCombat(UUID actor, String profileId, String targetType, VanillaBases bases) {
        return StatResolver.resolveCombat(profiles.combat(profileId), targetType, modifierSet(actor), bases);
    }

    /** The actor's movement speed for {@code profileId} on the given terrain / movement-profile keys. */
    public ResolvedSpeed resolveSpeed(UUID actor, String profileId, Collection<String> factorKeys) {
        return StatResolver.resolveSpeed(profiles.speed(profileId), factorKeys, modifierSet(actor));
    }

    public ResolvedSpeed resolveSpeed(UUID actor, String profileId) {
        return resolveSpeed(actor, profileId, List.of());
    }

    /** A consumer-defined stat on the generic channel, resolved against the actor's modifiers. */
    public ResolvedStat resolveNamed(UUID actor, NpcStat stat, double base, StatBounds bounds) {
        return StatResolver.resolve(stat, base, modifierSet(actor), bounds);
    }

    /** Fires the melee channel if its resolved cooldown has elapsed (arms it when it fires). */
    public boolean tryMelee(UUID actor, long nowTick, ResolvedCombat combat) {
        return cooldowns.tryFire(actor, CooldownTracker.MELEE, nowTick, combat.meleeCooldownTicks());
    }

    /** Fires the ranged channel if its resolved cooldown has elapsed (arms it when it fires). */
    public boolean tryShot(UUID actor, long nowTick, ResolvedCombat combat) {
        return cooldowns.tryFire(actor, CooldownTracker.RANGED, nowTick, combat.rangedCooldownTicks());
    }

    /** The modifier revision for an actor (0 for an actor without modifiers), for change detection. */
    public long revision(UUID actor) {
        return modifiers.find(actor).map(ModifierSet::revision).orElse(0L);
    }

    /** Drops every per-actor state (despawn, death, match end). */
    public void forget(UUID actor) {
        modifiers.forget(actor);
        cooldowns.forget(actor);
    }

    private ModifierSet modifierSet(UUID actor) {
        return modifiers.find(Objects.requireNonNull(actor, "actor")).orElseGet(ModifierSet::new);
    }
}
