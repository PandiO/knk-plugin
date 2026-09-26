package net.knightsandkings.knk.paper.commands.support;

import java.util.function.Consumer;

import org.bukkit.command.CommandSender;

import net.knightsandkings.knk.core.domain.users.UserSummary;

/**
 * Runs an action on another player only if the sender outranks them ({@link RankHierarchy}, the
 * same check {@code /knk tp}, {@code /freeze} and {@code /knk user} use). Works by name, so it covers
 * offline players too, and hands the action the target's knk user (its UUID locates offline storage).
 * Implemented in {@code KnKPlugin} over {@code UserAdminService.resolveTarget}/{@code withRankCheck},
 * so the console and holders of {@code knk.admin.user.manage.all} pass; kept as an interface so
 * commands don't depend on the whole service.
 */
@FunctionalInterface
public interface TargetRankCheck {

    /**
     * Calls {@code onAllowed} on the main thread when allowed; otherwise the sender has been told why
     * not (unknown player, equal or higher rank, lookup failure).
     */
    void whenOutranks(CommandSender sender, String targetName, Consumer<UserSummary> onAllowed);
}
