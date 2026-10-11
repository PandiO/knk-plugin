package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.domain.roads.RoadMaterialRole;
import net.knightsandkings.knk.core.roads.survey.ProposedProfile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

/**
 * The enabled road profiles of a build, indexed by material (DESIGN §5.1): which floor materials are
 * road, which of them are ambiguous, and which profiles list them. Bukkit-free; materials are names.
 *
 * <p>Rules (DESIGN §5.1):
 * <ul>
 *   <li>a floor material is a <b>road material</b> when some applicable profile lists it as
 *       {@code Surface}, {@code Edge} or {@code Accent} — {@code Overlay} materials are never floors
 *       (the survey side looks through them);</li>
 *   <li>it is <b>ambiguous</b> only when <em>every</em> applicable profile listing it marks it ambiguous
 *       ("a road made entirely of an ambiguous material is marked unambiguous in its own profile");</li>
 *   <li>a profile with a town scope (plan D8) applies only in columns whose town is in the scope,
 *       answered by the {@link ScopeLookup} port; an unscoped profile applies everywhere.</li>
 * </ul>
 *
 * <p>Materials reuse {@link ProposedProfile.Material} (the {@code RoadMaterialDto} shape from Phase
 * 2b) so the learner's output and the API's profiles share one record; Phase 2e maps the DTOs here.
 */
public final class ProfileSet {

    /**
     * One road profile as the builder needs it (the {@code RoadProfileDto} fields it reads).
     *
     * @param id             profile id (the API's)
     * @param name           display name (warnings, tests)
     * @param enabled        disabled profiles are ignored entirely
     * @param widthMin       learned 5th-percentile width (unused by the builder, carried for completeness)
     * @param widthMax       learned 95th-percentile width; plaza collapse uses {@code widthMax / 2}
     *                       (DESIGN §5.6 step 4); 15 means "at least the whole cross-section"
     * @param scopeTownIds   Town domain ids the profile is limited to; empty = everywhere
     * @param materials      the profile's materials with role and ambiguity
     */
    public record Profile(int id, String name, boolean enabled, int widthMin, int widthMax,
                          Set<Integer> scopeTownIds, List<ProposedProfile.Material> materials) {
        public Profile {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(scopeTownIds, "scopeTownIds");
            Objects.requireNonNull(materials, "materials");
            if (widthMin < 1 || widthMax < widthMin) {
                throw new IllegalArgumentException("profile " + id + ": widths must satisfy 1 <= widthMin <= widthMax");
            }
            scopeTownIds = Collections.unmodifiableSet(new TreeSet<>(scopeTownIds));
            materials = List.copyOf(materials);
        }

        /** The profile's entry for a material, if any. */
        public Optional<ProposedProfile.Material> material(String name) {
            return materials.stream().filter(m -> m.material().equals(name)).findFirst();
        }

        /** Whether the profile applies in a column with this town (empty = wilderness). */
        public boolean appliesTo(OptionalInt townId) {
            if (scopeTownIds.isEmpty()) {
                return true;
            }
            return townId.isPresent() && scopeTownIds.contains(townId.getAsInt());
        }

        /** Whether the material is a floor material of this profile (Surface, Edge or Accent). */
        public boolean isFloorMaterial(String name) {
            return material(name).map(m -> m.role() != RoadMaterialRole.OVERLAY).orElse(false);
        }
    }

    /** What the set knows about one material in one column: its role(s), ambiguity and profiles. */
    public record MaterialInfo(RoadMaterialRole role, boolean ambiguous, List<Integer> profileIds) {
    }

    private final List<Profile> profiles;
    private final Map<Integer, Profile> byId;
    private final Map<String, List<Profile>> byFloorMaterial;
    private final boolean anyScoped;
    private final ScopeLookup scope;

    /** Enabled profiles of the build; disabled ones are dropped here. */
    public ProfileSet(Collection<Profile> profiles, ScopeLookup scope) {
        Objects.requireNonNull(profiles, "profiles");
        this.scope = Objects.requireNonNull(scope, "scope");
        List<Profile> enabled = new ArrayList<>();
        Map<Integer, Profile> ids = new LinkedHashMap<>();
        Map<String, List<Profile>> floors = new HashMap<>();
        boolean scoped = false;
        for (Profile profile : profiles) {
            if (!profile.enabled()) {
                continue;
            }
            if (ids.putIfAbsent(profile.id(), profile) != null) {
                throw new IllegalArgumentException("duplicate profile id " + profile.id());
            }
            enabled.add(profile);
            scoped |= !profile.scopeTownIds().isEmpty();
            for (ProposedProfile.Material material : profile.materials()) {
                if (material.role() != RoadMaterialRole.OVERLAY) {
                    floors.computeIfAbsent(material.material(), k -> new ArrayList<>()).add(profile);
                }
            }
        }
        this.profiles = Collections.unmodifiableList(enabled);
        this.byId = Collections.unmodifiableMap(ids);
        this.byFloorMaterial = floors;
        this.anyScoped = scoped;
    }

    /** Enabled profiles without any town scope lookup (scoped profiles never apply). */
    public ProfileSet(Collection<Profile> profiles) {
        this(profiles, ScopeLookup.NONE);
    }

    /** The enabled profiles, in input order. */
    public List<Profile> profiles() {
        return profiles;
    }

    /** An enabled profile by id. */
    public Optional<Profile> profile(int id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The enabled profiles that apply in this column. */
    public List<Profile> profilesAt(int x, int z) {
        if (!anyScoped) {
            return profiles;
        }
        OptionalInt town = scope.townAt(x, z);
        return profiles.stream().filter(p -> p.appliesTo(town)).toList();
    }

    /** Whether the floor material is a road material of some profile applying in this column. */
    public boolean isRoadMaterial(String material, int x, int z) {
        return !applicable(material, x, z).isEmpty();
    }

    /**
     * Whether the road material is ambiguous in this column: every applicable profile listing it
     * says so. False for non-road materials.
     */
    public boolean isAmbiguous(String material, int x, int z) {
        List<Profile> listing = applicable(material, x, z);
        if (listing.isEmpty()) {
            return false;
        }
        for (Profile profile : listing) {
            if (!profile.material(material).orElseThrow().ambiguous()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Role, ambiguity and profile ids of a road material in this column; empty for non-road
     * materials. When profiles disagree on the role, the most central one wins
     * (Surface over Edge over Accent).
     */
    public Optional<MaterialInfo> info(String material, int x, int z) {
        List<Profile> listing = applicable(material, x, z);
        if (listing.isEmpty()) {
            return Optional.empty();
        }
        RoadMaterialRole role = RoadMaterialRole.ACCENT;
        boolean ambiguous = true;
        List<Integer> ids = new ArrayList<>(listing.size());
        for (Profile profile : listing) {
            ProposedProfile.Material m = profile.material(material).orElseThrow();
            if (m.role().ordinal() < role.ordinal()) {
                role = m.role();
            }
            ambiguous &= m.ambiguous();
            ids.add(profile.id());
        }
        return Optional.of(new MaterialInfo(role, ambiguous, Collections.unmodifiableList(ids)));
    }

    /**
     * The largest {@code widthMax} among the applicable profiles listing this road material — the
     * plaza threshold (DESIGN §5.6 step 4) for a span before its edge is matched; empty for
     * non-road materials.
     */
    public OptionalInt maxWidthMax(String material, int x, int z) {
        List<Profile> listing = applicable(material, x, z);
        if (listing.isEmpty()) {
            return OptionalInt.empty();
        }
        int max = 0;
        for (Profile profile : listing) {
            max = Math.max(max, profile.widthMax());
        }
        return OptionalInt.of(max);
    }

    private List<Profile> applicable(String material, int x, int z) {
        List<Profile> listing = byFloorMaterial.get(Objects.requireNonNull(material, "material"));
        if (listing == null) {
            return List.of();
        }
        if (!anyScoped) {
            return listing;
        }
        OptionalInt town = scope.townAt(x, z);
        return listing.stream().filter(p -> p.appliesTo(town)).toList();
    }
}
