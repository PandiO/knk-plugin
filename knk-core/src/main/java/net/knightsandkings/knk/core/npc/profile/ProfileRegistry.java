package net.knightsandkings.knk.core.npc.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The effective combat and speed profiles by id: code defaults ({@link VanillaProfiles}) with
 * {@link ProfileOverrides} applied. Immutable; a config reload builds a new registry and hands it to
 * the pipeline. Lookups never fail: an unknown id resolves to the {@link VanillaProfiles#DEFAULT}
 * profile, so a new mob type works before anyone configures it.
 */
public final class ProfileRegistry {
    private final Map<String, CombatProfile> combat;
    private final Map<String, SpeedProfile> speed;

    public ProfileRegistry(Map<String, CombatProfile> combat, Map<String, SpeedProfile> speed) {
        Map<String, CombatProfile> c = new LinkedHashMap<>();
        Objects.requireNonNull(combat, "combat").forEach((id, p) -> c.put(normalize(id), p));
        Map<String, SpeedProfile> s = new LinkedHashMap<>();
        Objects.requireNonNull(speed, "speed").forEach((id, p) -> s.put(normalize(id), p));
        if (!c.containsKey(VanillaProfiles.DEFAULT)) c.put(VanillaProfiles.DEFAULT, CombatProfile.vanilla(VanillaProfiles.DEFAULT));
        if (!s.containsKey(VanillaProfiles.DEFAULT)) s.put(VanillaProfiles.DEFAULT, SpeedProfile.of(VanillaProfiles.DEFAULT, VanillaProfiles.ZOMBIE_SPEED));
        this.combat = Map.copyOf(c);
        this.speed = Map.copyOf(s);
    }

    /** The code defaults only. */
    public static ProfileRegistry vanilla() {
        return new ProfileRegistry(VanillaProfiles.combat(), VanillaProfiles.speed());
    }

    /** The combat profile for an id (lower-cased), or the default profile when unknown. */
    public CombatProfile combat(String id) {
        CombatProfile p = combat.get(normalize(id));
        return p != null ? p : combat.get(VanillaProfiles.DEFAULT);
    }

    public Optional<CombatProfile> findCombat(String id) {
        return Optional.ofNullable(combat.get(normalize(id)));
    }

    /** The speed profile for an id (lower-cased), or the default profile when unknown. */
    public SpeedProfile speed(String id) {
        SpeedProfile p = speed.get(normalize(id));
        return p != null ? p : speed.get(VanillaProfiles.DEFAULT);
    }

    public Optional<SpeedProfile> findSpeed(String id) {
        return Optional.ofNullable(speed.get(normalize(id)));
    }

    public Set<String> combatIds() {
        return combat.keySet();
    }

    public Set<String> speedIds() {
        return speed.keySet();
    }

    /**
     * A registry with the overrides applied on top of this one. Profiles the overrides name but this
     * registry lacks are created from their {@code based-on} profile (default {@code "default"}; an
     * unknown {@code based-on} is rejected and the default used). Rejections are returned, not thrown.
     */
    public OverrideResult withOverrides(ProfileOverrides overrides) {
        Objects.requireNonNull(overrides, "overrides");
        List<String> rejected = new ArrayList<>();
        Map<String, CombatProfile> c = new LinkedHashMap<>(combat);
        Map<String, SpeedProfile> s = new LinkedHashMap<>(speed);
        Set<String> ids = new LinkedHashSet<>(overrides.byProfile().keySet());
        for (String id : ids) {
            overrides.rejectUnknownKeys(id, rejected);
            String basedOn = overrides.basedOn(id, VanillaProfiles.DEFAULT);
            CombatProfile combatBase = c.get(id);
            SpeedProfile speedBase = s.get(id);
            if (combatBase == null || speedBase == null) {
                if (!combat.containsKey(basedOn) || !speed.containsKey(basedOn)) {
                    rejected.add(id + "/" + ProfileOverrides.BASED_ON + ": unknown profile '" + basedOn + "', using '"
                        + VanillaProfiles.DEFAULT + "'");
                    basedOn = VanillaProfiles.DEFAULT;
                }
                if (combatBase == null) combatBase = combat.get(basedOn);
                if (speedBase == null) speedBase = speed.get(basedOn);
            }
            c.put(id, overrides.applyCombat(id, combatBase, rejected));
            s.put(id, overrides.applySpeed(id, speedBase, rejected));
        }
        return new OverrideResult(new ProfileRegistry(c, s), List.copyOf(rejected));
    }

    /** The registry after an override pass plus every rejected key with its reason. */
    public record OverrideResult(ProfileRegistry registry, List<String> rejected) {
        public boolean clean() {
            return rejected.isEmpty();
        }
    }

    private static String normalize(String id) {
        return id == null ? VanillaProfiles.DEFAULT : id.trim().toLowerCase(Locale.ROOT);
    }
}
