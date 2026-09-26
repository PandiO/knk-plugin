package net.knightsandkings.knk.core.domain.settings;

import java.util.Locale;

import net.knightsandkings.knk.core.domain.location.KnkLocation;

/**
 * A spawn point authored on the web-app Game Settings page (knk-web-api {@code LocationReferenceDto},
 * {@code GameSettings.JoinSpawnReference}): a Location, or a Town/District/Structure whose own Location
 * is the spot.
 *
 * @param sourceType   what {@code sourceId} points at; null when the API sent a type this plugin doesn't know
 * @param sourceId     id of the Location or domain
 * @param displayLabel the label the web app stored ("Town: Kardenna (world 10, 64, -3)"), may be blank
 * @param snapshot     the coordinates as they were when the reference was saved, may be null
 */
public record KnkSpawnReference(SourceType sourceType, int sourceId, String displayLabel, KnkLocation snapshot) {

    public enum SourceType {
        LOCATION, TOWN, DISTRICT, STRUCTURE;

        /** The API's value ({@code "Location"}, {@code "Town"}, ...), case-insensitive; null when unknown. */
        public static SourceType parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }

    /** The label to show players: the stored one, else e.g. "Town #4". */
    public String label() {
        if (displayLabel != null && !displayLabel.isBlank()) {
            return displayLabel;
        }
        String type = sourceType != null
            ? sourceType.name().charAt(0) + sourceType.name().substring(1).toLowerCase(Locale.ROOT)
            : "Location";
        return type + " #" + sourceId;
    }
}
