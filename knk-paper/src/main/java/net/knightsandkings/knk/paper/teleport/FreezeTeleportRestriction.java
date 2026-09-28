package net.knightsandkings.knk.paper.teleport;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import net.knightsandkings.knk.core.teleport.TeleportDenial;

/**
 * A frozen player can't start or take part in a player teleport (docs/specs/teleport/DESIGN.md
 * §4 D10) - before this, freezing only blocked walking and commands, so anything else could still
 * teleport them. Staff teleports still move frozen players (e.g. to a jail spot); the staff
 * commands already require {@code knk.teleport.staff.others} to move anyone but yourself.
 */
public final class FreezeTeleportRestriction implements TeleportRestriction {

    private final Predicate<UUID> isFrozen;

    public FreezeTeleportRestriction(Predicate<UUID> isFrozen) {
        this.isFrozen = Objects.requireNonNull(isFrozen, "isFrozen must not be null");
    }

    @Override
    public Optional<TeleportDenial> deny(TeleportCheck check) {
        if (check.kind().isStaff()) {
            return Optional.empty();
        }
        if (isFrozen.test(check.subject().getUniqueId())) {
            return Optional.of(TeleportDenial.of(TeleportDenial.FROZEN, "You can't teleport while frozen."));
        }
        if (check.visited() != null && isFrozen.test(check.visited().getUniqueId())) {
            return Optional.of(TeleportDenial.of(TeleportDenial.FROZEN, check.visited().getName() + " is frozen."));
        }
        return Optional.empty();
    }
}
