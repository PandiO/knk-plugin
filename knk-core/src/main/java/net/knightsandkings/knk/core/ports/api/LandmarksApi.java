package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.landmarks.KnkLandmark;
import net.knightsandkings.knk.core.domain.landmarks.LandmarkWrite;

/**
 * The web API's landmarks, {@code api/landmarks} (off-road navigation phase 5.1, PandiO/knk-workspace#708). Reads are
 * anonymous on the API; writes need the plugin's service key (or a staff node, for the web app). A refused write
 * completes exceptionally with the API's {@code ApiException} (400 with the reason, 404 when gone).
 */
public interface LandmarksApi {

    /** Every landmark, or those whose pin is in {@code world} (null: all), by name. */
    CompletableFuture<List<KnkLandmark>> list(String world);

    /** One landmark; empty when there is none with that id. */
    CompletableFuture<Optional<KnkLandmark>> get(int id);

    /** Creates one and answers it as saved. */
    CompletableFuture<KnkLandmark> create(LandmarkWrite landmark);

    /** Updates one and answers it as saved. */
    CompletableFuture<KnkLandmark> update(int id, LandmarkWrite landmark);

    CompletableFuture<Void> delete(int id);
}
