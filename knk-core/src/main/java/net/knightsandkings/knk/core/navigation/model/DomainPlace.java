package net.knightsandkings.knk.core.navigation.model;

import java.util.Objects;
import java.util.Optional;

/**
 * One domain in the navigation model (destination engine DESIGN § 7.2): what {@code /navigate <domain>} needs without
 * an API call at click time, and the road-access answer the route layer reads (E19).
 *
 * @param id                the domain's id
 * @param name              its name
 * @param domainType        the API's subtype word ({@code Town}, {@code District}, {@code Structure},
 *                          {@code GateStructure}, ...)
 * @param navigationDefault where {@code /navigate <domain>} leads without {@code spawn}/{@code region}: "Spawn" or
 *                          "Region" (KNG-73); null when the API did not say
 * @param roadAccess        "Applies" or "Ignored" (rev. 7 Part C, KNG-92); null when the API did not say (counts as
 *                          "Applies")
 * @param wgRegionId        the domain's WorldGuard region; null when it has none
 * @param world             the domain's world (KNG-111); null when the API did not say
 * @param spawn             the domain's own Location; null when it has none or it is not known yet
 * @param placeKnown        whether {@code spawn} is the answer (the API's search sent the Location field, or the paced
 *                          fallback looked it up); false while an older API leaves it unknown (§ 7.2)
 */
public record DomainPlace(int id, String name, String domainType, String navigationDefault, String roadAccess,
                          String wgRegionId, String world, ModelPoint spawn, boolean placeKnown) {

    public DomainPlace {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(domainType, "domainType");
        if (!placeKnown && spawn != null) {
            throw new IllegalArgumentException("a spawn is only set when the place is known");
        }
    }

    /** Only an explicit "Ignored" lifts the domain's entry/exit rule off the roads; unknown keeps it (as today). */
    public boolean roadAccessIgnored() {
        return "Ignored".equalsIgnoreCase(roadAccess);
    }

    public Optional<ModelPoint> spawnPoint() {
        return Optional.ofNullable(spawn);
    }

    /** The same domain with its place as looked up ({@code spawn} null: it has none). */
    public DomainPlace withPlace(ModelPoint spawn) {
        return new DomainPlace(id, name, domainType, navigationDefault, roadAccess, wgRegionId, world, spawn, true);
    }

    /** The same domain with the place of {@code earlier}, when this one is unknown and {@code earlier} knew it. */
    DomainPlace withPlaceOf(DomainPlace earlier) {
        if (placeKnown || earlier == null || !earlier.placeKnown()) {
            return this;
        }
        return withPlace(earlier.spawn());
    }
}
