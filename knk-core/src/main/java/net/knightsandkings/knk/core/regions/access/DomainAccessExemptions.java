package net.knightsandkings.knk.core.regions.access;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-player exceptions to the domain AllowEntry/AllowExit rules (arena duels DESIGN §8.2, D16), asked by
 * {@link RegionAccessRules} for every crossing the domain-access handler judges:
 * <ul>
 *   <li><b>entry exemption</b>: the player may enter a region whose entry is denied (a duelist entering their
 *       allocated battleground);</li>
 *   <li><b>confinement</b>: the player may not leave a region even though its exit is allowed (a duelist in the
 *       countdown or the fight).</li>
 * </ul>
 * Exemptions live in memory only, so a crash leaves nothing behind. A runtime registers its provider with
 * {@link Registry}; with none registered (the default) nothing changes.
 */
public interface DomainAccessExemptions {

    DomainAccessExemptions NONE = new DomainAccessExemptions() {
        @Override
        public boolean exemptFromEntry(UUID playerId, String regionId) {
            return false;
        }

        @Override
        public Optional<String> confinement(UUID playerId, String regionId) {
            return Optional.empty();
        }
    };

    /** May {@code playerId} enter {@code regionId} although its entry is denied? */
    boolean exemptFromEntry(UUID playerId, String regionId);

    /** The message when {@code playerId} may not leave {@code regionId} now, or empty when they may. */
    Optional<String> confinement(UUID playerId, String regionId);

    /** Every registered provider: an entry exemption from any of them counts; the first confinement wins. */
    final class Registry implements DomainAccessExemptions {
        private final List<DomainAccessExemptions> providers = new CopyOnWriteArrayList<>();

        public void register(DomainAccessExemptions provider) {
            providers.add(java.util.Objects.requireNonNull(provider, "provider"));
        }

        public void unregister(DomainAccessExemptions provider) {
            providers.remove(provider);
        }

        public boolean isEmpty() {
            return providers.isEmpty();
        }

        @Override
        public boolean exemptFromEntry(UUID playerId, String regionId) {
            if (playerId == null || regionId == null) return false;
            for (DomainAccessExemptions provider : providers) {
                if (provider.exemptFromEntry(playerId, regionId)) return true;
            }
            return false;
        }

        @Override
        public Optional<String> confinement(UUID playerId, String regionId) {
            if (playerId == null || regionId == null) return Optional.empty();
            for (DomainAccessExemptions provider : providers) {
                Optional<String> message = provider.confinement(playerId, regionId);
                if (message.isPresent()) return message;
            }
            return Optional.empty();
        }
    }
}
