package net.knightsandkings.knk.core.random;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Randomness with a memory (PandiO/knk-workspace#476): a bag of marbles that are drawn one by one without putting
 * them back, and refilled when it is empty. Mechanically a deck of cards: shuffle, deal off the top, shuffle again
 * when it runs out.
 * <p>
 * An independent roll per draw allows any streak: at 25% there is a 31.6% chance of four misses in a row and a 10%
 * chance of eight. A bag of two red and six blue marbles gives the same 25% but exactly two reds in every eight
 * draws, so the longest run without one is twelve draws (the reds first in one bag and last in the next). That is how
 * players expect chance to behave.
 * <p>
 * Bags nest: a marble can stand for another bag, which keeps rare outcomes both rare and spread apart. Make the
 * marbles suppliers for that, for example a consonant bag in which one marble leads to the rare letters:
 * <pre>{@code
 * MarbleBag<Character> rare = MarbleBag.of(List.of('J', 'K', 'Q', 'X', 'Z'), random);
 * MarbleBag<Supplier<Character>> consonants = MarbleBag.of(List.of(() -> 'T', () -> 'N', () -> 'S', rare::next), random);
 * char letter = consonants.next().get();
 * }</pre>
 * Safe to use from several threads when its {@link RandomGenerator} is ({@link java.util.Random} is,
 * {@link java.util.SplittableRandom} is not).
 *
 * @param <T> what a marble stands for
 */
public final class MarbleBag<T> {
    private final Supplier<? extends Collection<? extends T>> refill;
    private final RandomGenerator random;
    private final List<T> current = new ArrayList<>();

    private MarbleBag(Supplier<? extends Collection<? extends T>> refill, RandomGenerator random) {
        this.refill = Objects.requireNonNull(refill, "refill must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    /**
     * A bag that is refilled with the same marbles every time. Put a marble in as often as it should come out per
     * bag: two {@code true} and six {@code false} for 25%.
     *
     * @throws IllegalArgumentException when {@code contents} is empty
     */
    public static <T> MarbleBag<T> of(Collection<? extends T> contents, RandomGenerator random) {
        Objects.requireNonNull(contents, "contents must not be null");
        List<T> fixed = new ArrayList<>(contents);
        if (fixed.isEmpty()) {
            throw new IllegalArgumentException("A marble bag needs at least one marble");
        }
        return new MarbleBag<>(() -> fixed, random);
    }

    /**
     * A bag whose marbles are asked for at every refill, for contents that differ per bag (see {@link ChanceBag}).
     * {@code refill} must return at least one marble.
     */
    public static <T> MarbleBag<T> refilledBy(Supplier<? extends Collection<? extends T>> refill, RandomGenerator random) {
        return new MarbleBag<>(refill, random);
    }

    /**
     * Draws one marble, refilling the bag first when it is empty.
     *
     * @throws IllegalStateException when a refill returns no marbles
     */
    public synchronized T next() {
        if (current.isEmpty()) {
            Collection<? extends T> marbles = refill.get();
            if (marbles == null || marbles.isEmpty()) {
                throw new IllegalStateException("The marble bag was refilled with no marbles");
            }
            current.addAll(marbles);
        }
        // A random pick from what is left equals dealing from a shuffled bag, without the shuffle.
        int last = current.size() - 1;
        int index = random.nextInt(current.size());
        T marble = current.get(index);
        current.set(index, current.get(last));
        current.remove(last);
        return marble;
    }

    /** Throws away the marbles that are left; the next draw starts a full bag. */
    public synchronized void reset() {
        current.clear();
    }

    /** How many marbles are left before the next refill; 0 for a new or just emptied bag. */
    public synchronized int remaining() {
        return current.size();
    }
}
