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
 * (schema version 1, IMPLEMENTATION_PLAN.md §5.2a). Entries are kept sorted by key, the order the
 * {@link #contentHash(Collection) content hash} is taken in.
 */
public record CatalogSnapshot(
        int schemaVersion,
        String minecraftVersion,
        String serverVersion,
        String contentHash,
        List<CatalogEntry> entries
) {
    public static final int SCHEMA_VERSION = 1;

    public CatalogSnapshot {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** A schema-version-1 snapshot of {@code entries} (any order) with its content hash. */
    public static CatalogSnapshot of(String minecraftVersion, String serverVersion, Collection<CatalogEntry> entries) {
        List<CatalogEntry> sorted = sortedByKey(entries);
        return new CatalogSnapshot(SCHEMA_VERSION, minecraftVersion, serverVersion, hashOfSorted(sorted), sorted);
    }

    /** Entries ordered by key, ordinal (char by char), as both sides of the contract sort them. */
    public static List<CatalogEntry> sortedByKey(Collection<CatalogEntry> entries) {
        List<CatalogEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(CatalogEntry::key));
        return sorted;
    }

    /**
     * The text the hash is taken over: one {@code key|isItem|isBlock|maxStackSize|translationKey|displayName|defaultBlockState}
     * line per entry, sorted by key, joined with {@code \n} and no trailing newline; a null field is empty.
     */
    public static String canonicalText(Collection<CatalogEntry> entries) {
        return canonicalTextOfSorted(sortedByKey(entries));
    }

    /** Lower-case hex SHA-256 of the UTF-8 {@link #canonicalText(Collection) canonical text}. */
    public static String contentHash(Collection<CatalogEntry> entries) {
        return hashOfSorted(sortedByKey(entries));
    }

    private static String hashOfSorted(List<CatalogEntry> sorted) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonicalTextOfSorted(sorted).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String canonicalTextOfSorted(List<CatalogEntry> sorted) {
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
