package net.knightsandkings.knk.core.landmarks;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntPredicate;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.domain.landmarks.KnkLandmark;
import net.knightsandkings.knk.core.ports.api.LandmarksApi;

/**
 * Which landmarks show enter and leave messages (landmark-domain DESIGN § 5 rule 6, LD05, LD15,
 * PandiO/knk-workspace#985): the landmarks whose {@code showEnterLeaveMessages} is on, by domain id. Off unless
 * switched on per landmark, and off for a landmark this set has not heard of (before the first read, or one created
 * since), so a landmark is silent by default. The region decisions the transition service resolves don't carry the
 * switch, so it is read from {@code api/landmarks}: at start-up and again on a realtime {@code Landmark} change.
 * Thread-safe; the test is a set lookup.
 */
public final class LandmarkMessageSettings implements IntPredicate {

    private static final Logger LOGGER = Logger.getLogger(LandmarkMessageSettings.class.getName());

    private volatile Set<Integer> on = Set.of();

    /** Whether landmark {@code domainId} shows its enter and leave messages. */
    @Override
    public boolean test(int domainId) {
        return on.contains(domainId);
    }

    /** The landmarks with messages on become {@code landmarks}' with the switch on. */
    public void replace(Collection<KnkLandmark> landmarks) {
        Set<Integer> next = new HashSet<>();
        if (landmarks != null) {
            for (KnkLandmark landmark : landmarks) {
                if (landmark != null && landmark.showEnterLeaveMessages()) {
                    next.add(landmark.id());
                }
            }
        }
        on = Set.copyOf(next);
    }

    /** Reads every landmark from {@code api}; a failed read keeps the last set (logged). */
    public CompletableFuture<Void> refresh(LandmarksApi api) {
        Objects.requireNonNull(api, "api");
        CompletableFuture<java.util.List<KnkLandmark>> read;
        try {
            read = api.list(null);
        } catch (RuntimeException e) {
            read = CompletableFuture.failedFuture(e);
        }
        return read.handle((landmarks, error) -> {
            if (error != null || landmarks == null) {
                LOGGER.warning("[KnK Regions] Landmark message settings not refreshed, keeping the last ones: "
                    + (error == null ? "no answer" : error.getMessage()));
                return null;
            }
            replace(landmarks);
            return null;
        });
    }
}
