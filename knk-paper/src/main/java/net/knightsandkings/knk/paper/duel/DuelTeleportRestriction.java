package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.minigame.MinigameTeleportAttempt;
import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.core.teleport.TeleportKind;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The duel teleport guard (arena duels DESIGN §8.3), asked through the minigame registry by P0's
 * {@code MinigameTeleportRestriction}: from LOCKING until their gear is back, every teleport-engine move of a duelist
 * ({@code /spawn}, {@code /warp}, {@code /home}, {@code /tpa}, {@code /back}, {@code /hub}, menu teleports, staff
 * teleports) and every request whose other side is a duelist is refused - except the runtime's own
 * ({@link TeleportKind#ARENA}, {@link TeleportKind#ARENA_SYSTEM}). A staff teleport passes with
 * {@link DuelService#ADMIN_NODE}.
 */
public final class DuelTeleportRestriction {

    public static final String CODE = "DUEL";

    private final Predicate<UUID> inDuel;

    public DuelTeleportRestriction(Predicate<UUID> inDuel) {
        this.inDuel = Objects.requireNonNull(inDuel, "inDuel");
    }

    public Optional<TeleportDenial> deny(MinigameTeleportAttempt attempt) {
        if (attempt.kind() == TeleportKind.ARENA || attempt.kind() == TeleportKind.ARENA_SYSTEM) return Optional.empty();
        if (attempt.kind().isStaff() && attempt.hasBypass(DuelService.ADMIN_NODE)) return Optional.empty();
        if (inDuel.test(attempt.subject())) {
            return Optional.of(TeleportDenial.of(CODE, attempt.movesSelf()
                    ? "You can't teleport during a duel."
                    : name(attempt.subjectName()) + " is in a duel."));
        }
        if (attempt.visited() != null && inDuel.test(attempt.visited())) {
            return Optional.of(TeleportDenial.of(CODE, name(attempt.visitedName()) + " is in a duel."));
        }
        return Optional.empty();
    }

    public Set<String> bypassNodes() {
        return Set.of(DuelService.ADMIN_NODE);
    }

    private static String name(String name) {
        return name != null ? name : "That player";
    }
}
