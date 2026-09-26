package net.knightsandkings.knk.paper.siege;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.paper.teleport.BackDeathExclusion;
import net.knightsandkings.knk.paper.teleport.TeleportCheck;
import net.knightsandkings.knk.paper.teleport.TeleportRestriction;

/**
 * The siege guards of the teleport engine (docs/specs/teleport/DESIGN.md §3.4 step 1, §4 D8/D9):
 * while a player is away in a siege ({@link SiegeService#activeLobbyOf}: HUB or IN_PROGRESS, the
 * vault holds their gear and return spot) no {@code /tp}, {@code /tpa}, {@code /spawn}, {@code /warp},
 * menu warp or {@code /back} moves them.
 * <ul>
 *   <li>A member's own teleport, or a {@code /tpahere} pulling a member, is refused unless the member
 *       holds {@code knk.siege.bypass.commands} (the node that also lets them past the siege command
 *       filter).</li>
 *   <li>Staff moving a member is always refused - a teleport would desync the vault snapshot; the
 *       member has to be removed with {@code /siege admin kick} first.</li>
 *   <li>A request whose other side is a member ({@code /tpa member}) is refused. Staff may still
 *       {@code /tp} themselves to a member to watch the match.</li>
 * </ul>
 * The siege's own teleports (hub, start, spawn picks, vault restore) don't go through the engine at
 * all: they call {@code Player.teleport} with {@code TeleportCause.PLUGIN} ({@link SiegeBukkit#teleport}),
 * so this restriction never sees them. The scenario area is open to non-members (the area lockdown was
 * removed, siege DESIGN §8.5), so there is no destination check.
 */
public final class SiegeTeleportRestriction implements TeleportRestriction {

    /** A member holding this may still teleport themselves (siege DESIGN §6.9 staff bypass). */
    public static final String BYPASS_NODE = SiegeService.PERMISSION_BYPASS_COMMANDS;

    private final Predicate<UUID> inActiveSiege;

    /** @param inActiveSiege true while the player is away in a siege (HUB or IN_PROGRESS) */
    public SiegeTeleportRestriction(Predicate<UUID> inActiveSiege) {
        this.inActiveSiege = Objects.requireNonNull(inActiveSiege, "inActiveSiege must not be null");
    }

    /** Membership read live from the siege runtime on every check. */
    public static SiegeTeleportRestriction of(SiegeService service) {
        Objects.requireNonNull(service, "service must not be null");
        return new SiegeTeleportRestriction(id -> service.activeLobbyOf(id).isPresent());
    }

    @Override
    public Optional<TeleportDenial> deny(TeleportCheck check) {
        Player subject = check.subject();
        if (inActiveSiege.test(subject.getUniqueId())) {
            boolean movesSelf = check.actor() instanceof Player actor && actor.getUniqueId().equals(subject.getUniqueId());
            if (check.kind().isStaff() && !movesSelf) {
                return Optional.of(TeleportDenial.of(TeleportDenial.SIEGE,
                    subject.getName() + " is in a siege match; use /siege admin kick first."));
            }
            if (!check.hasBypass(BYPASS_NODE)) {
                return Optional.of(TeleportDenial.of(TeleportDenial.SIEGE, movesSelf
                    ? "You can't teleport during a siege. Use /siege leave to leave it."
                    : subject.getName() + " is in a siege match."));
            }
        }
        Player visited = check.visited();
        if (!check.kind().isStaff() && visited != null && inActiveSiege.test(visited.getUniqueId())) {
            return Optional.of(TeleportDenial.of(TeleportDenial.SIEGE, visited.getName() + " is in a siege match."));
        }
        return Optional.empty();
    }

    @Override
    public Set<String> bypassNodes() {
        return Set.of(BYPASS_NODE);
    }

    /**
     * No {@code /back} after a death in a siege (teleport developer decision Q5). Asked at
     * {@code PlayerDeathEvent} LOWEST, before the siege's own death handling.
     */
    public BackDeathExclusion backDeathExclusion() {
        return player -> inActiveSiege.test(player.getUniqueId());
    }
}
