package net.knightsandkings.knk.core.domain.landmarks;

import java.util.Locale;

/**
 * Where a landmark's area is (landmark-domain DESIGN § 5 rule 3, LD06–LD08, LD16; PandiO/knk-workspace#985): the API's
 * {@code AreaKind}.
 */
public enum LandmarkAreaKind {
    /** A point: the pin only, no region (arrival within {@code arrive-distance}). */
    NONE,
    /** Its own main region ({@code Domain.WgRegionId}) and its own sub-regions. */
    OWN_REGION,
    /** The parent domain's main region and sub-regions; the landmark owns no region (LD07). */
    LINKED_DOMAIN;

    /** The API's word ({@code None}, {@code OwnRegion}, {@code LinkedDomain}), any case; null and unknown words are NONE. */
    public static LandmarkAreaKind parse(String value) {
        if (value == null) {
            return NONE;
        }
        return switch (value.trim().replace("_", "").replace("-", "").replace(" ", "").toLowerCase(Locale.ROOT)) {
            case "ownregion" -> OWN_REGION;
            case "linkeddomain" -> LINKED_DOMAIN;
            default -> NONE;
        };
    }

    /** The API's word. */
    public String apiWord() {
        return switch (this) {
            case NONE -> "None";
            case OWN_REGION -> "OwnRegion";
            case LINKED_DOMAIN -> "LinkedDomain";
        };
    }
}
