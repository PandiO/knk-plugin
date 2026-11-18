package net.knightsandkings.knk.paper.navigation.engine;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.navigation.compass.CompassSettings;
import net.knightsandkings.knk.core.navigation.destinations.AllowedDestination;
import net.knightsandkings.knk.core.navigation.destinations.Candidate;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine.Resolution;
import net.knightsandkings.knk.core.navigation.destinations.DestinationList;
import net.knightsandkings.knk.core.navigation.destinations.InitialScopes;
import net.knightsandkings.knk.core.navigation.destinations.NavigationContext;
import net.knightsandkings.knk.core.navigation.destinations.Provider;
import net.knightsandkings.knk.core.navigation.engine.SiegeMatchProvider;
import net.knightsandkings.knk.core.navigation.model.ModelFreshness;
import net.knightsandkings.knk.paper.siege.SiegeMatch;
import net.knightsandkings.knk.paper.siege.SiegeService;

/**
 * The destination engine as the plugin's clients use it (DESIGN § 3.1, § 12.1, phase 3, PandiO/knk-workspace#69;
 * only with {@code navigation.destination-engine: true}): builds the player's {@link NavigationContext} on the main
 * thread from offline-aware sources (§ 7.5: the mode, freeze and permission answers of the security services; the
 * local loading state; the siege in memory; the model's version) and answers {@code /navigate}, tab completion and
 * the compass through one {@link DestinationEngine} with the initial scope table. Lists carry the model's freshness
 * note (§ 7.3, § 7.4). Main thread.
 */
public final class PlayerNavigationEngine {

    /**
     * Where the context's facts come from.
     *
     * @param loading      the account is still loading
     * @param frozen       admin-frozen
     * @param mode         the active owner/staff mode
     * @param hasNode      whether the player holds a node (Bukkit's permissible or the in-house cache)
     * @param siege        the siege service (may be absent)
     * @param compass      the compass settings in force (the item rule and {@code siegeEnabled} build
     *                     {@code Siege.compassEnabled}, L1 note)
     * @param modelVersion the navigation model's version
     * @param freshness    the navigation model's freshness now
     */
    public record Sources(Predicate<UUID> loading, Predicate<UUID> frozen, Function<Player, ActiveMode> mode,
                         BiPredicate<Player, String> hasNode, Supplier<SiegeService> siege,
                         Supplier<CompassSettings> compass, java.util.function.LongSupplier modelVersion,
                         Supplier<ModelFreshness> freshness) {
        public Sources {
            Objects.requireNonNull(loading, "loading");
            Objects.requireNonNull(frozen, "frozen");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(hasNode, "hasNode");
            Objects.requireNonNull(siege, "siege");
            Objects.requireNonNull(compass, "compass");
            Objects.requireNonNull(modelVersion, "modelVersion");
            Objects.requireNonNull(freshness, "freshness");
        }
    }

    private final DestinationEngine engine;
    private final Sources sources;

    public PlayerNavigationEngine(DestinationEngine engine, Sources sources) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    /** The initial scope table over {@code world} providers and the siege match provider (no hub provider, E10). */
    public static PlayerNavigationEngine create(List<Provider> world, Provider siegeMatch, Sources sources) {
        return new PlayerNavigationEngine(new DestinationEngine(InitialScopes.create(world, siegeMatch)), sources);
    }

    /** The match provider's view of the player's running match: the match and their team, if they have one. */
    public static Function<UUID, Optional<SiegeMatchProvider.Match>> matchesOf(Supplier<SiegeService> siege) {
        Objects.requireNonNull(siege, "siege");
        return uuid -> {
            SiegeService service = siege.get();
            Optional<SiegeMatch> match = service == null ? Optional.empty() : service.runningMatchOf(uuid);
            if (match.isEmpty()) {
                return Optional.empty();
            }
            OptionalInt team = match.get().roster().teamOf(uuid);
            return team.isEmpty() ? Optional.empty() : Optional.of(new SiegeMatchProvider.Match(match.get().scenario(),
                match.get().board(), match.get().alliances(), team.getAsInt()));
        };
    }

    public DestinationEngine engine() {
        return engine;
    }

    // ==================== the context ====================

    /** The player's context now. */
    public NavigationContext context(Player player) {
        UUID uuid = player.getUniqueId();
        Set<String> nodes = new LinkedHashSet<>();
        for (String node : List.of(InitialScopes.NAVIGATE_NODE, InitialScopes.BYPASS_NODE)) {
            if (sources.hasNode().test(player, node)) {
                nodes.add(node);
            }
        }
        return new NavigationContext(uuid, player.getWorld().getName(), sources.mode().apply(player),
            sources.loading().test(uuid), sources.frozen().test(uuid), nodes, siegeOf(uuid), sources.modelVersion().getAsLong());
    }

    private NavigationContext.Siege siegeOf(UUID uuid) {
        SiegeService service = sources.siege().get();
        if (service == null) {
            return NavigationContext.Siege.NONE;
        }
        CompassSettings settings = sources.compass().get();
        boolean compassEnabled = settings == null || (settings.item().enabled() && settings.siegeEnabled());
        Optional<SiegeMatch> match = service.runningMatchOf(uuid);
        if (match.isPresent()) {
            OptionalInt team = match.get().roster().teamOf(uuid);
            return new NavigationContext.Siege(true, true, team.isPresent() ? team.getAsInt() : null,
                match.get().scenario().id(), compassEnabled);
        }
        if (service.lobbyOf(uuid).isPresent()) {
            return NavigationContext.Siege.lobby().withCompassEnabled(compassEnabled);
        }
        return NavigationContext.Siege.NONE;
    }

    // ==================== answers ====================

    /** The player's list, with the model's freshness note (§ 7.3, § 7.4). */
    public DestinationList list(Player player) {
        return withFreshness(engine.listFor(context(player)));
    }

    /**
     * What the player typed, against their list. As today, a domain asked for with {@code spawn} that has only a
     * region is led to the region, and one asked for with {@code region} that has only a spawn to the spawn
     * ({@code NavigationDestinations.locateDomain}): the engine answers {@code NO_LOCATION} for the mode, so it is
     * asked again with the other one.
     */
    public Resolution resolve(Player player, String typed, Candidate.Mode mode) {
        NavigationContext ctx = context(player);
        Resolution resolution = engine.resolve(ctx, typed, mode);
        Candidate.Mode other = otherMode(mode);
        if (resolution.outcome() == Resolution.Outcome.NO_LOCATION && other != null) {
            Resolution again = engine.resolveKey(ctx, resolution.candidate().key(), other);
            if (again.found()) {
                return again;
            }
        }
        return resolution;
    }

    /** A handle for the listed candidate with {@code key} (the compass, a resume, a match prefix). */
    public Resolution resolveKey(Player player, String key, Candidate.Mode mode) {
        NavigationContext ctx = context(player);
        Resolution resolution = engine.resolveKey(ctx, key, mode);
        Candidate.Mode other = otherMode(mode);
        if (resolution.outcome() == Resolution.Outcome.NO_LOCATION && other != null) {
            Resolution again = engine.resolveKey(ctx, key, other);
            if (again.found()) {
                return again;
            }
        }
        return resolution;
    }

    private static Candidate.Mode otherMode(Candidate.Mode mode) {
        if (mode == Candidate.Mode.SPAWN) {
            return Candidate.Mode.REGION;
        }
        return mode == Candidate.Mode.REGION ? Candidate.Mode.SPAWN : null;
    }

    /**
     * Whether a running session's destination is still in the player's list (phase 4, PandiO/knk-workspace#62,
     * DESIGN § 8): the list is built again for the player's context now and the handle's key looked up in it; an
     * empty scope (loading, frozen, no permission, the siege lobby) or a key that dropped out ends the session with
     * the reason. What {@code NavigationService} re-checks running sessions with.
     */
    public DestinationEngine.Revalidation revalidate(Player player, AllowedDestination handle) {
        return engine.revalidate(context(player), handle);
    }

    /** Tab completions for the destination words, inside the list. */
    public List<String> complete(Player player, List<String> words) {
        return engine.complete(context(player), words);
    }

    /** Whether the words name exactly one listed candidate (so {@code spawn} / {@code region} may follow). */
    public boolean isComplete(Player player, List<String> words) {
        return engine.isComplete(context(player), words);
    }

    /** The model's "not available" / "may be out of date" note, when the lists are degraded now. */
    public Optional<String> degradedNote() {
        ModelFreshness freshness = sources.freshness().get();
        return freshness == null || !freshness.degraded() ? Optional.empty() : Optional.ofNullable(freshness.text());
    }

    /** {@code list} with the model's freshness note. */
    public DestinationList withFreshness(DestinationList list) {
        ModelFreshness freshness = sources.freshness().get();
        return freshness == null ? list : freshness.applyTo(list);
    }
}
