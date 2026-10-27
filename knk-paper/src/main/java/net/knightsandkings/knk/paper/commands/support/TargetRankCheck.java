package net.knightsandkings.knk.paper.commands.support;

import java.util.function.Consumer;

import org.bukkit.command.CommandSender;

import net.knightsandkings.knk.core.domain.users.UserSummary;

/**
 * Runs an action on another player only if the sender's rank allows it ({@link RankHierarchy}). Two rules are
 * wired in {@code KnKPlugin}: the teleport commands ({@code /tp}, {@code /tphere}, {@code /spawn}, {@code /hub},
 * {@code /warp}, {@code /back} on another player) accept a target of the <b>same rank or lower</b>
 * (PandiO/knk-workspace#171); {@code /enderchest} and {@code /inventory} keep the strictly-lower rule of
 * {@code /freeze} and {@code /knk user}. Works by name, so it covers
 * offline players too, and hands the action the target's knk user (its UUID locates offline storage).
 * Implemented in {@code KnKPlugin} over {@code UserAdminService.resolveTarget}/{@code withRankCheck},
 * so the console and holders of {@code knk.admin.user.manage.all} pass; kept as an interface so
 * commands don't depend on the whole service.
 */
@FunctionalInterface
public interface TargetRankCheck {

    /**
     * Calls {@code onAllowed} on the main thread when allowed; otherwise the sender has been told why
     * not (unknown player, higher rank - or equal, under the strict rule - lookup failure).
     */
    void whenOutranks(CommandSender sender, String targetName, Consumer<UserSummary> onAllowed);
}
