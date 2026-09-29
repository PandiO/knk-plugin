package net.knightsandkings.knk.paper.teleport;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.regions.RegionTransitionDecision;
import net.knightsandkings.knk.core.teleport.TeleportDenial;

/**
 * Refuses a teleport into a domain with {@code AllowEntry = false} or out of one with
 * {@code AllowExit = false} up front, with the same verdict {@code WorldGuardRegionListener} would
 * reach when the teleport event fires (docs/specs/teleport/DESIGN.md §3.1/§3.4) - so a player is
 * refused before a warmup rather than after it. Holders of {@code knk.region.bypass} pass (for a
 * staff teleport: the staff member); the listener lets the same teleport through for them.
 * <p>
 * Reads cached domain data only ({@code WorldGuardRegionTracker.previewAccess}); a region that
 * isn't cached yet counts as allowed here and is still enforced by the listener.
 */
public final class RegionTeleportRestriction implements TeleportRestriction {

    private final BiFunction<Player, Location, RegionTransitionDecision> preview;

    public RegionTeleportRestriction(BiFunction<Player, Location, RegionTransitionDecision> preview) {
        this.preview = Objects.requireNonNull(preview, "preview must not be null");
    }

    @Override
    public Optional<TeleportDenial> deny(TeleportCheck check) {
        if (check.to() == null || check.hasBypass(TeleportNodes.REGION_BYPASS)) {
            return Optional.empty();
        }
        RegionTransitionDecision decision = preview.apply(check.subject(), check.to());
        if (decision == null || decision.isMovementAllowed()) {
            return Optional.empty();
        }
        return Optional.of(TeleportDenial.of(TeleportDenial.REGION,
            decision.getMessage().orElse("You can't teleport there.")));
    }

    @Override
    public Set<String> bypassNodes() {
        return Set.of(TeleportNodes.REGION_BYPASS);
    }
}
