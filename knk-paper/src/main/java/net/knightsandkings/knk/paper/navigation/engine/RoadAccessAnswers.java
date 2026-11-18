package net.knightsandkings.knk.paper.navigation.engine;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.navigation.model.NavigationModel;
import net.knightsandkings.knk.core.navigation.model.NavigationModelHolder;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations;

/**
 * The road-access answers of rev. 7 Part C (KNG-92) for the route layer - {@code NavigationAccess}'s road rule and
 * the live road tags - from one place (destination engine E19, phase 3, PandiO/knk-workspace#69). With
 * {@code navigation.destination-engine} off they are today's {@link NavigationDestinations} answers; with it on they
 * come from the navigation model (the same data, now also after a restart with the API down). Without a model or a
 * catalogue load yet every answer is "keep the rule", as before the first catalogue load.
 */
public final class RoadAccessAnswers {

    private static final Logger LOGGER = Logger.getLogger(RoadAccessAnswers.class.getName());

    private final boolean fromModel;
    private final Supplier<NavigationDestinations> catalogue;
    private final Supplier<NavigationModelHolder> model;

    /**
     * @param fromModel {@code navigation.destination-engine}
     * @param catalogue today's catalogue (may answer null before navigation starts)
     * @param model     the navigation model's holder (may answer null when the model did not start)
     */
    public RoadAccessAnswers(boolean fromModel, Supplier<NavigationDestinations> catalogue,
                             Supplier<NavigationModelHolder> model) {
        this.fromModel = fromModel;
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.model = Objects.requireNonNull(model, "model");
    }

    public boolean fromModel() {
        return fromModel;
    }

    /** Whether the domain's entry/exit rule is lifted off the roads ("Ignored"). */
    public boolean roadAccessIgnored(int domainId) {
        if (fromModel) {
            NavigationModel current = currentModel();
            return current != null && current.roadAccessIgnored(domainId);
        }
        NavigationDestinations destinations = catalogue.get();
        return destinations != null && destinations.roadAccessIgnored(domainId);
    }

    /** Whether a WorldGuard region belongs to a domain whose rule is "Ignored" for roads. */
    public boolean roadsIgnoreRegion(String regionId) {
        if (fromModel) {
            NavigationModel current = currentModel();
            return current != null && current.roadsIgnoreRegion(regionId);
        }
        NavigationDestinations destinations = catalogue.get();
        return destinations != null && destinations.roadsIgnoreRegion(regionId);
    }

    /**
     * Calls {@code listener} after a load that changed which regions are "Ignored" for roads: today's catalogue
     * listener, or with the flag on a model change whose ignored regions differ from the last model's.
     */
    public void onRoadAccessChanged(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        if (!fromModel) {
            NavigationDestinations destinations = catalogue.get();
            if (destinations != null) {
                destinations.onRoadAccessChanged(listener);
            }
            return;
        }
        NavigationModelHolder holder = model.get();
        if (holder == null) {
            return;
        }
        NavigationModel start = holder.current();
        AtomicReference<Set<String>> last = new AtomicReference<>(start == null ? Set.of() : start.roadAccessIgnoredRegions());
        holder.onChange(next -> {
            Set<String> regions = next.roadAccessIgnoredRegions();
            if (!regions.equals(last.getAndSet(regions))) {
                try {
                    listener.run();
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "[Navigation] Road access listener failed", e);
                }
            }
        });
    }

    private NavigationModel currentModel() {
        NavigationModelHolder holder = model.get();
        return holder == null ? null : holder.current();
    }
}
