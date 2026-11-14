package net.knightsandkings.knk.core.random;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarbleBagTest {

    @Test
    void everyBagDealsExactlyItsContents() {
        List<String> contents = List.of("red", "red", "blue", "blue", "blue", "blue", "blue", "blue");
        MarbleBag<String> bag = MarbleBag.of(contents, new Random(1));

        for (int round = 0; round < 200; round++) {
            List<String> drawn = new ArrayList<>();
            for (int i = 0; i < contents.size(); i++) {
                drawn.add(bag.next());
            }
            assertEquals(2, Collections.frequency(drawn, "red"), "round " + round);
            assertEquals(6, Collections.frequency(drawn, "blue"), "round " + round);
            assertEquals(0, bag.remaining());
        }
    }

    @Test
    void theOrderDiffersBetweenBags() {
        MarbleBag<Integer> bag = MarbleBag.of(List.of(1, 2, 3, 4, 5, 6, 7, 8), new Random(7));
        List<List<Integer>> orders = new ArrayList<>();
        for (int round = 0; round < 20; round++) {
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                order.add(bag.next());
            }
            orders.add(order);
        }
        assertTrue(orders.stream().distinct().count() > 1);
    }

    @Test
    void resetStartsAFullBag() {
        MarbleBag<String> bag = MarbleBag.of(List.of("red", "blue", "blue", "blue"), new Random(3));
        bag.next();
        assertEquals(3, bag.remaining());

        bag.reset();
        assertEquals(0, bag.remaining());
        List<String> drawn = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            drawn.add(bag.next());
        }
        assertEquals(1, Collections.frequency(drawn, "red"));
    }

    @Test
    void aMarbleCanLeadToAnotherBag() {
        Random random = new Random(11);
        MarbleBag<Character> rare = MarbleBag.of(List.of('J', 'K', 'Q', 'X', 'Z'), random);
        List<Supplier<Character>> marbles = List.of(() -> 'T', () -> 'N', () -> 'S', rare::next);
        MarbleBag<Supplier<Character>> consonants = MarbleBag.of(marbles, random);

        List<Character> rareDrawn = new ArrayList<>();
        for (int i = 0; i < 4 * 5; i++) {
            char letter = consonants.next().get();
            if ("JKQXZ".indexOf(letter) >= 0) {
                rareDrawn.add(letter);
            }
        }
        // One rare letter per bag of four, and each rare letter once in five bags.
        assertEquals(5, rareDrawn.size());
        assertEquals(5, rareDrawn.stream().distinct().count());
    }

    @Test
    void contentsAreCopied() {
        List<String> contents = new ArrayList<>(List.of("red", "blue"));
        MarbleBag<String> bag = MarbleBag.of(contents, new Random(5));
        contents.clear();

        List<String> drawn = List.of(bag.next(), bag.next());
        assertTrue(drawn.contains("red") && drawn.contains("blue"));
    }

    @Test
    void anEmptyBagIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> MarbleBag.of(List.of(), new Random()));
        MarbleBag<String> bag = MarbleBag.refilledBy(List::of, new Random());
        assertThrows(IllegalStateException.class, bag::next);
    }
}
