package net.knightsandkings.knk.core.navigation.destinations;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A policy of the destination engine (DESIGN §3, §4): when it {@link #applies} it decides the whole list - scopes
 * are exclusive by priority (E2), the highest applicable one wins and the lower ones are not consulted. It yields
 * either providers (with a filter and an ordering of its own) or nothing with a reason the player is told.
 * The initial table is {@link InitialScopes}; another minigame adds one scope and one provider (§9).
 */
public final class Scope {

    /** What a scope yields. */
    public sealed interface Yield permits Listed, Empty {
    }

    /**
     * Candidates: the union of {@code providers}, filtered, ordered by {@code ordering} (null: none), then by each
     * candidate's priority and label. {@code reasonWhenEmpty} (null: none) is the list's reason when the
     * providers leave nothing navigable - the hub extension point of E10: a lobby member gets the reason unless a
     * hub provider has destinations.
     */
    public record Listed(List<Provider> providers, Predicate<Candidate> filter, Comparator<Candidate> ordering,
                         String reasonWhenEmpty) implements Yield {
        public Listed {
            providers = providers == null ? List.of() : List.copyOf(providers);
            filter = filter == null ? c -> true : filter;
        }

        public static Listed of(List<Provider> providers) {
            return new Listed(providers, null, null, null);
        }
    }

    /** Nothing may be navigated to, and this is why (today's player-facing text). */
    public record Empty(String reason) implements Yield {
        public Empty {
            Objects.requireNonNull(reason, "reason");
        }
    }

    private final String id;
    private final int priority;
    private final String displayName;
    private final Predicate<NavigationContext> applies;
    private final Function<NavigationContext, Yield> yield;

    /**
     * @param id          stable id, recorded on the list and the handle
     * @param priority    higher decides first; on a tie the scope registered first does
     * @param displayName how the list is called to the player ("this match", "the world")
     * @param applies     whether this scope decides for the context
     * @param yield       what it yields for the context
     */
    public Scope(String id, int priority, String displayName, Predicate<NavigationContext> applies,
                 Function<NavigationContext, Yield> yield) {
        this.id = Objects.requireNonNull(id, "id");
        this.priority = priority;
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.applies = Objects.requireNonNull(applies, "applies");
        this.yield = Objects.requireNonNull(yield, "yield");
    }

    /** A scope that lists what {@code providers} offer. */
    public static Scope listing(String id, int priority, String displayName, Predicate<NavigationContext> applies,
                                List<Provider> providers) {
        Listed listed = Listed.of(providers);
        return new Scope(id, priority, displayName, applies, ctx -> listed);
    }

    /** A scope that lists what {@code providers} offer, or gives {@code reasonWhenEmpty} when that is nothing. */
    public static Scope listing(String id, int priority, String displayName, Predicate<NavigationContext> applies,
                                List<Provider> providers, String reasonWhenEmpty) {
        Listed listed = new Listed(providers, null, null, reasonWhenEmpty);
        return new Scope(id, priority, displayName, applies, ctx -> listed);
    }

    /** A scope that yields nothing, with {@code reason}. */
    public static Scope empty(String id, int priority, String displayName, Predicate<NavigationContext> applies,
                              String reason) {
        Empty empty = new Empty(reason);
        return new Scope(id, priority, displayName, applies, ctx -> empty);
    }

    public String id() {
        return id;
    }

    public int priority() {
        return priority;
    }

    public String displayName() {
        return displayName;
    }

    public boolean applies(NavigationContext ctx) {
        return applies.test(ctx);
    }

    public Yield yield(NavigationContext ctx) {
        return yield.apply(ctx);
    }

    /** The same scope with {@code filter} on its listed candidates (an {@link Empty} yield is unchanged). */
    public Scope filtered(Predicate<Candidate> filter) {
        Objects.requireNonNull(filter, "filter");
        return new Scope(id, priority, displayName, applies, ctx -> {
            Yield y = yield.apply(ctx);
            return y instanceof Listed l
                ? new Listed(l.providers(), l.filter().and(filter), l.ordering(), l.reasonWhenEmpty()) : y;
        });
    }

    /** The same scope with {@code ordering} first on its listed candidates (an {@link Empty} yield is unchanged). */
    public Scope ordered(Comparator<Candidate> ordering) {
        Objects.requireNonNull(ordering, "ordering");
        return new Scope(id, priority, displayName, applies, ctx -> {
            Yield y = yield.apply(ctx);
            return y instanceof Listed l ? new Listed(l.providers(), l.filter(), ordering, l.reasonWhenEmpty()) : y;
        });
    }

    @Override
    public String toString() {
        return "Scope " + id + " (" + priority + ")";
    }
}
