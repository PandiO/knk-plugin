package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.domain.roads.RoadMaterialRole;
import net.knightsandkings.knk.core.roads.build.ProfileSet.Profile;
import net.knightsandkings.knk.core.roads.survey.ProposedProfile;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * Matches an edge to a profile (DESIGN §5.6 step 5): the histogram of floor materials within the
 * distance-transform radius of the chain, compared by cosine similarity with each applicable
 * profile's floor-material shares ({@code centreShare} of its Surface/Edge/Accent materials —
 * Overlay materials are never floors, Phase 2b). The best match wins; a profile needs at least one
 * of the chain's materials to be a candidate; ties go to the lower profile id.
 */
public final class ProfileMatcher {

    private final ProfileSet profiles;

    public ProfileMatcher(ProfileSet profiles) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    /**
     * Floor-material counts of every mask span within {@code dt − 1} hops of some chain span (the
     * whole cross-section around the chain), each span counted once. Sorted by material name.
     */
    public Map<String, Integer> histogram(RoadMask mask, int[] dt, int[] chain) {
        boolean[] seen = new boolean[mask.size()];
        Map<String, Integer> counts = new TreeMap<>();
        for (int span : chain) {
            int radius = Math.max(0, dt[span] - 1);
            if (!seen[span]) {
                seen[span] = true;
                counts.merge(mask.floor(span), 1, Integer::sum);
            }
            // Local BFS per chain span, bounded by its own radius; spans already counted are not
            // re-expanded from a shallower depth, which is fine: their neighbours get visited from
            // whichever chain span reaches them first, and radii along a chain change slowly.
            ArrayDeque<Integer> local = new ArrayDeque<>();
            local.add(span);
            Map<Integer, Integer> localDepth = new HashMap<>();
            localDepth.put(span, 0);
            while (!local.isEmpty()) {
                int i = local.poll();
                int d = localDepth.get(i);
                if (d >= radius) {
                    continue;
                }
                for (int dir = 0; dir < SpanGrid.DIRECTIONS; dir++) {
                    int nb = mask.neighbour(i, dir);
                    if (nb == RoadMask.NONE || localDepth.containsKey(nb)) {
                        continue;
                    }
                    localDepth.put(nb, d + 1);
                    local.add(nb);
                    if (!seen[nb]) {
                        seen[nb] = true;
                        counts.merge(mask.floor(nb), 1, Integer::sum);
                    }
                }
            }
        }
        return counts;
    }

    /** The best-matching enabled profile for a chain, if any profile shares a material with it. */
    public OptionalInt match(RoadMask mask, int[] dt, int[] chain) {
        if (chain.length == 0) {
            return OptionalInt.empty();
        }
        Map<String, Integer> histogram = histogram(mask, dt, chain);
        int mid = chain[chain.length / 2];
        return match(histogram, profiles.profilesAt(mask.x(mid), mask.z(mid)));
    }

    /** The best-matching profile among candidates for a material histogram. */
    public OptionalInt match(Map<String, Integer> histogram, List<Profile> candidates) {
        double total = 0;
        for (int count : histogram.values()) {
            total += count;
        }
        if (total == 0) {
            return OptionalInt.empty();
        }
        double best = -1;
        int bestId = 0;
        for (Profile profile : candidates) {
            double similarity = cosine(histogram, total, profile);
            if (similarity > best + 1e-12 || (Math.abs(similarity - best) <= 1e-12 && best >= 0 && profile.id() < bestId)) {
                best = similarity;
                bestId = profile.id();
            }
        }
        return best > 0 ? OptionalInt.of(bestId) : OptionalInt.empty();
    }

    /**
     * Cosine similarity between the histogram (shares) and the profile's floor-material weights
     * (centreShare; uniform when the profile has no shares yet). 0 when nothing is shared.
     */
    static double cosine(Map<String, Integer> histogram, double total, Profile profile) {
        Map<String, Double> weights = floorWeights(profile);
        if (weights.isEmpty()) {
            return 0;
        }
        double dot = 0;
        double histNorm = 0;
        double profileNorm = 0;
        for (Map.Entry<String, Integer> e : histogram.entrySet()) {
            double share = e.getValue() / total;
            histNorm += share * share;
            Double w = weights.get(e.getKey());
            if (w != null) {
                dot += share * w;
            }
        }
        for (double w : weights.values()) {
            profileNorm += w * w;
        }
        if (dot == 0 || histNorm == 0 || profileNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(histNorm) * Math.sqrt(profileNorm));
    }

    private static Map<String, Double> floorWeights(Profile profile) {
        Map<String, Double> weights = new LinkedHashMap<>();
        double sum = 0;
        for (ProposedProfile.Material m : profile.materials()) {
            if (m.role() == RoadMaterialRole.OVERLAY) {
                continue;
            }
            double w = Math.max(0, m.centreShare());
            weights.put(m.material(), w);
            sum += w;
        }
        if (sum == 0) {
            weights.replaceAll((k, v) -> 1.0);
        }
        return weights;
    }
}
