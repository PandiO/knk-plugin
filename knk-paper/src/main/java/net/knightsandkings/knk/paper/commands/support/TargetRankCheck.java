package net.knightsandkings.knk.paper.commands.support;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Runs an action on another player only if the sender outranks them ({@link RankHierarchy}, the
 * same check {@code /knk tp}, {@code /freeze} and {@code /knk user} use). Implemented in
 * {@code KnKPlugin} over {@code UserAdminService.resolveTarget}/{@code withRankCheck}, so the
 * console and holders of {@code knk.admin.user.manage.all} pass; kept as an interface so commands
 * don't depend on the whole service.
 */
@FunctionalInterface
public interface TargetRankCheck {

    /** Calls {@code onAllowed} on the main thread when allowed; otherwise the sender has been told why not. */
    void whenOutranks(CommandSender sender, Player target, Runnable onAllowed);
}
