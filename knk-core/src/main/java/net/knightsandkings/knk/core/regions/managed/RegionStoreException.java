package net.knightsandkings.knk.core.regions.managed;

/** A {@link ManagedRegionStore} could not read, apply or persist. An {@code apply} that throws changed nothing. */
public class RegionStoreException extends Exception {

    public RegionStoreException(String message) {
        super(message);
    }

    public RegionStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
