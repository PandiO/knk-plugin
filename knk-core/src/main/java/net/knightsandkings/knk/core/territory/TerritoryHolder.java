package net.knightsandkings.knk.core.territory;

import java.util.Locale;
import java.util.Objects;

/**
 * Who holds territory (territory DESIGN § 2, PandiO/knk-workspace#689): a town, or a kingdom (its crown land). Ordered by
 * kind, then id, so a contested pair has one normal order, as the API's {@code TerritoryHolder} has.
 */
public record TerritoryHolder(Kind kind, int id) implements Comparable<TerritoryHolder> {

    /** Holder kinds of V1. The order matches the API's enum (Town, Kingdom). */
    public enum Kind {
        TOWN("Town"),
        KINGDOM("Kingdom");

        private final String apiName;

        Kind(String apiName) {
            this.apiName = apiName;
        }

        /** The name the API uses ({@code Town}, {@code Kingdom}). */
        public String apiName() {
            return apiName;
        }

        /** Crown land (held by a kingdom) outranks towns (territory DESIGN § 2): 2 against 1. */
        public int rank() {
            return this == KINGDOM ? 2 : 1;
        }

        /** Parses the API name, any case; null when unknown. */
        public static Kind parse(String name) {
            if (name == null) {
                return null;
            }
            return switch (name.trim().toLowerCase(Locale.ROOT)) {
                case "town" -> TOWN;
                case "kingdom" -> KINGDOM;
                default -> null;
            };
        }
    }

    public TerritoryHolder {
        Objects.requireNonNull(kind, "kind");
    }

    public static TerritoryHolder town(int id) {
        return new TerritoryHolder(Kind.TOWN, id);
    }

    public static TerritoryHolder kingdom(int id) {
        return new TerritoryHolder(Kind.KINGDOM, id);
    }

    public int rank() {
        return kind.rank();
    }

    @Override
    public int compareTo(TerritoryHolder other) {
        int byKind = kind.compareTo(other.kind);
        return byKind != 0 ? byKind : Integer.compare(id, other.id);
    }

    @Override
    public String toString() {
        return kind.apiName() + " " + id;
    }
}
