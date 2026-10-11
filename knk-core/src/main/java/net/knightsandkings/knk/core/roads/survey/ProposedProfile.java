package net.knightsandkings.knk.core.roads.survey;

import net.knightsandkings.knk.core.domain.roads.RoadMaterialRole;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@link ProfileLearner} proposes from a survey's statistics: the material roles and the width
 * range of a {@code RoadProfile} (DESIGN §3.1). Field names follow the web-api's
 * {@code RoadProfileDto}/{@code RoadMaterialDto} so Phase 2e can map them one to one. Name, class,
 * cost and scope are the admin's — they are not learned and not here.
 *
 * @param materials   proposed materials, sorted by role (Surface, Edge, Accent, Overlay), then samples
 *                    descending, then name; unmodifiable
 * @param widthMin    5th-percentile run width (≥ 1)
 * @param widthMax    95th-percentile run width (≥ widthMin; {@value SurveySample#WIDTH} means "at least")
 * @param sampleCount total survey samples the proposal is based on
 */
public record ProposedProfile(List<Material> materials, int widthMin, int widthMax, int sampleCount) {

    /**
     * One proposed material (mirrors {@code RoadMaterialDto}).
     *
     * @param material    material name
     * @param role        proposed role
     * @param ambiguous   true when the material was also seen off the road often enough to need the
     *                    ambiguity reach (DESIGN §5.1)
     * @param centreShare share of the road's centre cells that were this material (0..1; 0 for overlays)
     * @param edgeShare   share of run ends that were this material (0..1; 0 for overlays)
     * @param samples     samples the material was part of the run in (for overlays: samples it lay on)
     */
    public record Material(String material, RoadMaterialRole role, boolean ambiguous, double centreShare,
                           double edgeShare, int samples) {
        public Material {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(role, "role");
        }
    }

    public ProposedProfile {
        Objects.requireNonNull(materials, "materials");
        if (widthMin < 1 || widthMax < widthMin) {
            throw new IllegalArgumentException("widths must satisfy 1 <= widthMin <= widthMax, got "
                + widthMin + ".." + widthMax);
        }
        if (sampleCount < 0) {
            throw new IllegalArgumentException("sampleCount must be >= 0");
        }
        materials = Collections.unmodifiableList(new ArrayList<>(materials));
    }

    /** The proposal for one material, if present. */
    public Optional<Material> material(String name) {
        return materials.stream().filter(m -> m.material().equals(name)).findFirst();
    }

    /** The proposed role of a material, if present. */
    public Optional<RoadMaterialRole> roleOf(String name) {
        return material(name).map(Material::role);
    }

    /** Materials with the given role, in proposal order. */
    public List<Material> withRole(RoadMaterialRole role) {
        return materials.stream().filter(m -> m.role() == role).toList();
    }
}
