package net.knightsandkings.knk.core.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * The catalogue snapshot the plugin sends to {@code PUT api/MinecraftCatalog/scans/{scanId}/snapshot}
 * (schema version 2: the materials of IMPLEMENTATION_PLAN.md §5.2a plus the enchantments of §12.2).
 * Entries and enchantments are kept sorted by key, the order the
 * {@link #contentHash(Collection, Collection) content hash} is taken in.
 */
public record CatalogSnapshot(
        int schemaVersion,
        String minecraftVersion,
        String serverVersion,
        String contentHash,
        List<CatalogEntry> entries,
        List<CatalogEnchantment> enchantments
) {
    public static final int SCHEMA_VERSION = 2;

    public CatalogSnapshot {
        entries = entries == null ? List.of() : List.copyOf(entries);
        enchantments = enchantments == null ? List.of() : List.copyOf(enchantments);
    }

    /** A schema-version-2 snapshot of {@code entries} and {@code enchantments} (any order) with its content hash. */
    public static CatalogSnapshot of(String minecraftVersion, String serverVersion, Collection<CatalogEntry> entries,
                                     Collection<CatalogEnchantment> enchantments) {
        List<CatalogEntry> sortedEntries = sortedByKey(entries);
        List<CatalogEnchantment> sortedEnchantments = enchantmentsSortedByKey(enchantments);
        return new CatalogSnapshot(SCHEMA_VERSION, minecraftVersion, serverVersion,
            sha256Hex(canonicalTextOfSorted(sortedEntries, sortedEnchantments)), sortedEntries, sortedEnchantments);
    }

    /** Entries ordered by key, ordinal (char by char), as both sides of the contract sort them. */
    public static List<CatalogEntry> sortedByKey(Collection<CatalogEntry> entries) {
        List<CatalogEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(CatalogEntry::key));
        return sorted;
    }

    /** Enchantments ordered by key, ordinal (char by char). */
    public static List<CatalogEnchantment> enchantmentsSortedByKey(Collection<CatalogEnchantment> enchantments) {
        List<CatalogEnchantment> sorted = new ArrayList<>(enchantments);
        sorted.sort(Comparator.comparing(CatalogEnchantment::key));
        return sorted;
    }

    /**
     * The material part of the hashed text (§5.2a steps 1-3, the whole text of schema version 1): one
     * {@code key|isItem|isBlock|maxStackSize|translationKey|displayName|defaultBlockState} line per entry,
     * sorted by key, joined with {@code \n} and no trailing newline; a null field is empty.
     */
    public static String materialText(Collection<CatalogEntry> entries) {
        return materialTextOfSorted(sortedByKey(entries));
    }

    /**
     * The text the version 2 hash is taken over (§12.2): the {@link #materialText(Collection) material text},
     * then {@code \n#enchantments}, then for each enchantment sorted by key {@code \n} plus
     * {@code key|isCustom|maxLevel|translationKey|displayName|category}; a null field is empty.
     */
    public static String canonicalText(Collection<CatalogEntry> entries, Collection<CatalogEnchantment> enchantments) {
        return canonicalTextOfSorted(sortedByKey(entries), enchantmentsSortedByKey(enchantments));
    }

    /** Lower-case hex SHA-256 of the UTF-8 {@link #canonicalText(Collection, Collection) canonical text}. */
    public static String contentHash(Collection<CatalogEntry> entries, Collection<CatalogEnchantment> enchantments) {
        return sha256Hex(canonicalText(entries, enchantments));
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String canonicalTextOfSorted(List<CatalogEntry> sortedEntries, List<CatalogEnchantment> sortedEnchantments) {
        StringBuilder text = new StringBuilder(materialTextOfSorted(sortedEntries));
        text.append("\n#enchantments");
        for (CatalogEnchantment enchantment : sortedEnchantments) {
            text.append('\n')
                .append(enchantment.key()).append('|')
                .append(enchantment.isCustom()).append('|')
                .append(enchantment.maxLevel() == null ? "" : enchantment.maxLevel().toString()).append('|')
                .append(orEmpty(enchantment.translationKey())).append('|')
                .append(orEmpty(enchantment.displayName())).append('|')
                .append(orEmpty(enchantment.category()));
        }
        return text.toString();
    }

    private static String materialTextOfSorted(List<CatalogEntry> sorted) {
        StringBuilder text = new StringBuilder();
        for (CatalogEntry entry : sorted) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(entry.key()).append('|')
                .append(entry.isItem()).append('|')
                .append(entry.isBlock()).append('|')
                .append(entry.maxStackSize()).append('|')
                .append(orEmpty(entry.translationKey())).append('|')
                .append(orEmpty(entry.displayName())).append('|')
                .append(orEmpty(entry.defaultBlockState()));
        }
        return text.toString();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
