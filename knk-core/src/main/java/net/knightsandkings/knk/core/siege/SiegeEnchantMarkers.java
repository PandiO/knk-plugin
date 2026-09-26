package net.knightsandkings.knk.core.siege;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Per-item record of siege-applied enchantments (DESIGN §9.4, D7): {@code knk:siege_enchants =
 * [{matchToken, key, previousLevel}]}. Every siege book application records itself on the target
 * item; the restore path and the join-time sweep revert each recorded enchantment of a match that is
 * no longer running to its previous level (removing it when there was none), so the item is exactly
 * as it was before the siege even if the vault restore somehow didn't replace it.
 * <p>
 * Stored as one string in the item's {@code PersistentDataContainer}:
 * {@code token;key;previousLevel|token;key;-} ({@code -} = the item didn't have it).
 */
public final class SiegeEnchantMarkers {
    private SiegeEnchantMarkers() {}

    /** One application: before this match the item had {@code enchantmentKey} at {@code previousLevel} (null = none). */
    public record Entry(String matchToken, String enchantmentKey, Integer previousLevel) {
        public Entry {
            Objects.requireNonNull(matchToken, "matchToken");
            Objects.requireNonNull(enchantmentKey, "enchantmentKey");
        }
    }

    /**
     * The result of {@link #revert}.
     *
     * @param enchantments the item's enchantments after reverting (key → level; a removed one is absent)
     * @param remaining    markers that stay (their match is still running)
     * @param changed      something was reverted or dropped
     */
    public record Reversion(Map<String, Integer> enchantments, List<Entry> remaining, boolean changed) {}

    public static String encode(List<Entry> entries) {
        StringBuilder out = new StringBuilder();
        for (Entry e : entries) {
            if (!out.isEmpty()) out.append('|');
            out.append(e.matchToken().replace("|", "").replace(";", "")).append(';')
                    .append(e.enchantmentKey().replace("|", "").replace(";", "")).append(';')
                    .append(e.previousLevel() == null ? "-" : e.previousLevel().toString());
        }
        return out.toString();
    }

    /** Lenient: malformed parts are skipped, never thrown. */
    public static List<Entry> decode(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<Entry> entries = new ArrayList<>();
        for (String part : raw.split("\\|")) {
            String[] f = part.split(";", -1);
            if (f.length != 3 || f[0].isBlank() || f[1].isBlank()) continue;
            Integer previous = null;
            if (!f[2].equals("-")) {
                try {
                    previous = Integer.parseInt(f[2].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
            }
            entries.add(new Entry(f[0], f[1], previous));
        }
        return Collections.unmodifiableList(entries);
    }

    /**
     * Records an application of {@code key} in match {@code matchToken} on an item that currently has
     * it at {@code existingLevel} (0 = not at all). A second application in the same match keeps the
     * first record, whose previous level is the pre-siege one.
     */
    public static List<Entry> recordApplication(List<Entry> markers, String matchToken, String key, int existingLevel) {
        boolean already = markers.stream().anyMatch(e -> e.matchToken().equals(matchToken) && e.enchantmentKey().equals(key));
        if (already) return List.copyOf(markers);
        List<Entry> updated = new ArrayList<>(markers);
        updated.add(new Entry(matchToken, key, existingLevel > 0 ? existingLevel : null));
        return Collections.unmodifiableList(updated);
    }

    /**
     * DESIGN §9.3/§9.4: a siege book may be picked up only by a member of the running match it dropped
     * in. {@code runningToken} is the picker's running match (null for non-members).
     */
    public static boolean mayPickUp(String bookToken, String runningToken) {
        return bookToken != null && bookToken.equals(runningToken);
    }

    /** The sweep keeps a siege book only while its match is the player's running match; otherwise it's stray. */
    public static boolean keepBook(String bookToken, String runningToken) {
        return mayPickUp(bookToken, runningToken);
    }

    /** The level a siege book gives: {@code max(existing, book)} (vanilla anvil cap, DESIGN §9.4). */
    public static int appliedLevel(int existingLevel, int bookLevel) {
        return Math.max(existingLevel, bookLevel);
    }

    /**
     * Reverts every marker whose match {@code keep} rejects, newest first, so with several matches the
     * oldest record's previous level wins.
     *
     * @param current the item's enchantments now (key → level)
     * @param keep    tokens of matches still running for this player (their markers stay)
     */
    public static Reversion revert(Map<String, Integer> current, List<Entry> markers, Predicate<String> keep) {
        Map<String, Integer> result = new LinkedHashMap<>(current);
        List<Entry> remaining = new ArrayList<>();
        boolean changed = false;
        for (int i = markers.size() - 1; i >= 0; i--) {
            Entry e = markers.get(i);
            if (keep.test(e.matchToken())) {
                remaining.add(0, e);
                continue;
            }
            if (e.previousLevel() == null || e.previousLevel() <= 0) result.remove(e.enchantmentKey());
            else result.put(e.enchantmentKey(), e.previousLevel());
            changed = true;
        }
        return new Reversion(Collections.unmodifiableMap(result), Collections.unmodifiableList(remaining), changed);
    }
}
