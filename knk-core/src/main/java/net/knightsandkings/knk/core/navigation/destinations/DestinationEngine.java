package net.knightsandkings.knk.core.navigation.destinations;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.navigation.destinations.Scope.Empty;
import net.knightsandkings.knk.core.navigation.destinations.Scope.Listed;
import net.knightsandkings.knk.core.navigation.destinations.Scope.Yield;
import net.knightsandkings.knk.core.util.NamedTargets;

/**
 * Builds, per player, the ordered list of destinations they may navigate to (destination engine DESIGN §3.1, §4):
 * the scopes are walked by descending priority and the first that applies decides (E2); its providers' candidates
 * are filtered, ordered (the scope's ordering, then candidate priority, then label) and split by world (E11).
 * Names resolve through {@link NamedTargets} over {@link Candidate}, so {@code type:name}, {@code type:#id},
 * ambiguity choices, suggestions and completion behave the same in every scope - in a match as in the world.
 * Pure and deterministic for a context and model version; main thread.
 */
public final class DestinationEngine {

    private static final Logger LOGGER = Logger.getLogger(DestinationEngine.class.getName());

    /** The name lookup over candidates: label, type word, {@code #id} and the aliases. */
    public static final NamedTargets<Candidate> NAMES =
        new NamedTargets<>(Candidate::label, Candidate::typeWord, Candidate::id, Candidate::aliases);

    /** Candidate priority (lower first), then label ignoring case, then key (so the order is total). */
    static final Comparator<Candidate> BY_PRIORITY_THEN_LABEL = Comparator.comparingInt(Candidate::priority)
        .thenComparing(c -> c.label().toLowerCase(Locale.ROOT))
        .thenComparing(Candidate::key);

    /** {@link #resolve}'s answer. */
    public record Resolution(Outcome outcome, AllowedDestination destination, Candidate candidate, List<Candidate> choices,
                             List<Candidate> suggestions, String reason, DestinationList list) {

        public enum Outcome {
            /** One candidate; {@link #destination()} is the handle. */
            FOUND,
            /** Several candidates share the name; {@link #choices()} are them. */
            AMBIGUOUS,
            /** No candidate of that name; {@link #suggestions()} are the name prefixes, maybe none. */
            NOT_FOUND,
            /** The name is a place in another world (E11); {@link #candidate()} is it. */
            OTHER_WORLD,
            /** The domain has neither a Location nor a region for what was asked; {@link #candidate()} is it. */
            NO_LOCATION,
            /** Nothing may be navigated to; {@link #reason()} is the scope's. */
            NOT_ALLOWED
        }

        public Resolution {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(list, "list");
            choices = choices == null ? List.of() : List.copyOf(choices);
            suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        }

        public boolean found() {
            return outcome == Outcome.FOUND;
        }

        public boolean ambiguous() {
            return outcome == Outcome.AMBIGUOUS;
        }

        /** The {@code type:name} forms of the choices, for the "pick one" message. */
        public List<String> choiceNames() {
            return choices.stream().map(Candidate::qualifiedName).toList();
        }

        /** The labels of the suggestions, each once, for "did you mean". */
        public List<String> suggestionNames() {
            return suggestions.stream().map(Candidate::label).distinct().toList();
        }
    }

    /**
     * {@link #revalidate}'s answer: still allowed (with the candidate as listed now, whose kind or detail may have
     * changed), or ended with the reason the session is told.
     */
    public record Revalidation(boolean allowed, Candidate candidate, String reason) {

        static Revalidation stillAllowed(Candidate candidate) {
            return new Revalidation(true, candidate, null);
        }

        static Revalidation ended(String reason) {
            return new Revalidation(false, null, Objects.requireNonNull(reason, "reason"));
        }
    }

    private final List<Scope> scopes;

    /** @param scopes in any order; walked by descending priority, ties in the given order */
    public DestinationEngine(List<Scope> scopes) {
        List<Scope> ordered = new ArrayList<>(Objects.requireNonNull(scopes, "scopes"));
        ordered.sort(Comparator.comparingInt(Scope::priority).reversed()); // List.sort is stable
        this.scopes = List.copyOf(ordered);
    }

    /** The scopes in the order they are walked. */
    public List<Scope> scopes() {
        return scopes;
    }

    /** The scope that decides for {@code ctx}. */
    public Scope scopeFor(NavigationContext ctx) {
        for (Scope scope : scopes) {
            if (scope.applies(ctx)) {
                return scope;
            }
        }
        throw new IllegalStateException("no scope applies - the table needs a default (" + scopes + ")");
    }

    // ==================== listing ====================

    /** The ordered list the player may navigate to now, or an empty one with the scope's reason. */
    public DestinationList listFor(NavigationContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        Scope scope = scopeFor(ctx);
        Yield yield = scope.yield(ctx);
        if (yield instanceof Empty empty) {
            return DestinationList.empty(scope.id(), scope.displayName(), empty.reason(), ctx.modelVersion());
        }
        Listed listed = (Listed) yield;
        Map<String, Candidate> byKey = new LinkedHashMap<>();
        for (Provider provider : listed.providers()) {
            for (Candidate candidate : candidatesOf(provider, ctx)) {
                if (candidate != null && listed.filter().test(candidate)) {
                    byKey.putIfAbsent(candidate.key(), candidate); // the first provider to offer a key keeps it
                }
            }
        }
        List<Candidate> here = new ArrayList<>();
        List<Candidate> elsewhere = new ArrayList<>();
        for (Candidate candidate : byKey.values()) {
            (candidate.inWorld(ctx.world()) ? here : elsewhere).add(candidate);
        }
        Comparator<Candidate> order = listed.ordering() == null ? BY_PRIORITY_THEN_LABEL
            : listed.ordering().thenComparing(BY_PRIORITY_THEN_LABEL);
        here.sort(order);
        elsewhere.sort(order);
        String reason = here.isEmpty() ? listed.reasonWhenEmpty() : null;
        return new DestinationList(scope.id(), scope.displayName(), here, elsewhere, reason, false, null, ctx.modelVersion());
    }

    /** A provider that throws is logged and skipped; the rest of the list is still served (§7.4). */
    private static List<Candidate> candidatesOf(Provider provider, NavigationContext ctx) {
        try {
            List<Candidate> candidates = provider.candidates(ctx);
            return candidates == null ? List.of() : candidates;
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "[Navigation] Destination provider " + provider.id() + " failed for "
                + ctx.player() + ": " + e.getMessage(), e);
            return List.of();
        }
    }

    // ==================== resolving ====================

    /**
     * What the player typed ({@code Kardenna}, {@code town:Kardenna}, {@code town:#12}, {@code Keep Gate}) against
     * their list only: found (the handle, minted here and nowhere else), ambiguous, not found with suggestions,
     * in another world, or not allowed with the scope's reason.
     */
    public Resolution resolve(NavigationContext ctx, String typed, Candidate.Mode mode) {
        DestinationList list = listFor(ctx);
        Candidate.Mode wanted = mode == null ? Candidate.Mode.DEFAULT : mode;
        if (list.refused()) {
            return new Resolution(Resolution.Outcome.NOT_ALLOWED, null, null, List.of(), List.of(), list.reason(), list);
        }
        NamedTargets.Match<Candidate> match = NAMES.resolve(list.candidates(), typed);
        if (match.found()) {
            Candidate candidate = match.target();
            if (candidate.place() instanceof Candidate.Place.Domain domain && !domain.offers(wanted)) {
                return new Resolution(Resolution.Outcome.NO_LOCATION, null, candidate, List.of(), List.of(), null, list);
            }
            AllowedDestination handle = new AllowedDestination(ctx.player(), list.scopeId(), candidate, wanted, list.modelVersion());
            return new Resolution(Resolution.Outcome.FOUND, handle, candidate, List.of(), List.of(), null, list);
        }
        if (match.ambiguous()) {
            return new Resolution(Resolution.Outcome.AMBIGUOUS, null, null, match.choices(), List.of(), null, list);
        }
        NamedTargets.Match<Candidate> away = NAMES.resolve(list.otherWorld(), typed);
        if (away.found() || away.ambiguous()) {
            Candidate candidate = away.found() ? away.target() : away.choices().get(0);
            return new Resolution(Resolution.Outcome.OTHER_WORLD, null, candidate, List.of(), List.of(), null, list);
        }
        return new Resolution(Resolution.Outcome.NOT_FOUND, null, null, List.of(), NAMES.suggestions(list.candidates(), typed),
            null, list);
    }

    /** Tab completions for the destination words typed so far, inside the list only; nothing when refused. */
    public List<String> complete(NavigationContext ctx, List<String> words) {
        DestinationList list = listFor(ctx);
        return list.refused() ? List.of() : NAMES.complete(list.candidates(), words);
    }

    /** Whether {@code words} name exactly one listed candidate (so the next word may be {@code spawn} / {@code region}). */
    public boolean isComplete(NavigationContext ctx, List<String> words) {
        DestinationList list = listFor(ctx);
        return !list.refused() && NAMES.resolve(list.candidates(), String.join(" ", words)).found();
    }

    // ==================== re-validation ====================

    /**
     * Whether a running session's destination is still in the player's list (DESIGN §8): the list is built again
     * and the candidate looked up by key, so a re-ordering or a candidate that changed kind (an objective that
     * flipped from capture to defend) keeps the session; an empty scope ends it with the scope's reason, a key
     * that dropped out with the list's own wording.
     */
    public Revalidation revalidate(NavigationContext ctx, AllowedDestination handle) {
        Objects.requireNonNull(handle, "handle");
        if (!handle.player().equals(ctx.player())) {
            throw new IllegalArgumentException("handle of " + handle.player() + " re-validated for " + ctx.player());
        }
        DestinationList list = listFor(ctx);
        if (list.refused()) {
            return Revalidation.ended(list.reason());
        }
        return list.find(handle.candidateKey())
            .map(Revalidation::stillAllowed)
            .orElseGet(() -> Revalidation.ended(noLongerListed(handle.candidate(), list)));
    }

    /** "Keep Gate is no longer part of this match." */
    static String noLongerListed(Candidate candidate, DestinationList list) {
        return candidate.label() + " is no longer part of " + list.displayName() + ".";
    }
}
