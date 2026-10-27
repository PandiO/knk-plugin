package net.knightsandkings.knk.paper.teleport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;

/**
 * Who another player's teleport may move (PandiO/knk-workspace#171, developer decision 2026-10-27):
 * <ul>
 *   <li><b>Exempt players</b>: holders of {@code knk.teleport.exempt.<type>} ({@link Type#exemptNode()}; the API's
 *       wildcard matching makes {@code knk.teleport.exempt.*} cover every type) and players in staff mode or owner
 *       mode are not moved by another player's {@code /hub}, {@code /spawn}, {@code /tp} or {@code /tphere} of that
 *       type, nor by its {@code all} form.</li>
 *   <li><b>Override</b> (single-player forms only): the console and holders of {@code knk.admin.user.manage.all} still
 *       move an exempt player they name. The {@code all} forms never move an exempt player, whoever runs them - a mass
 *       teleport shouldn't sweep up the players the exemption is for (coordinator default, flagged for review).</li>
 *   <li><b>{@code all}</b> ({@link #forAll}): every online player of the same rank as the sender or lower (the
 *       {@link RankRule}; the console and {@code knk.admin.user.manage.all} pass everyone) that isn't exempt,
 *       never the sender. Each {@code all} form has its own node ({@link Type#allNode()}).</li>
 * </ul>
 * Built in {@code KnKPlugin} over {@code UserAdminService}; the commands take it as an optional collaborator, so
 * without it nobody is exempt and the {@code all} forms are unavailable.
 */
public final class TeleportExemptions {

    /** The four teleport kinds that move another player and have an {@code all} form. */
    public enum Type {
        HUB, SPAWN, TP, TPHERE;

        /** {@code knk.teleport.exempt.<type>}: not moved by other players' teleports of this type. */
        public String exemptNode() {
            return EXEMPT_PREFIX + name().toLowerCase(Locale.ROOT);
        }

        /** {@code knk.teleport.all.<type>}: the {@code all} form of this teleport. */
        public String allNode() {
            return ALL_PREFIX + name().toLowerCase(Locale.ROOT);
        }
    }

    public static final String EXEMPT_PREFIX = "knk.teleport.exempt.";
    /** Every type at once (resolved by the API's trailing-wildcard matching). */
    public static final String EXEMPT_ALL = EXEMPT_PREFIX + "*";
    public static final String ALL_PREFIX = "knk.teleport.all.";
    /** The argument that selects every eligible player. */
    public static final String ALL_ARGUMENT = "all";

    /** Whether the sender may move the target by rank: same rank or lower; the console and manage.all always may. */
    @FunctionalInterface
    public interface RankRule {
        CompletableFuture<Boolean> mayMove(CommandSender sender, Player target);
    }

    /** Whether the sender overrides exemptions on a single-player teleport (the console, knk.admin.user.manage.all). */
    @FunctionalInterface
    public interface Override {
        CompletableFuture<Boolean> overrides(CommandSender sender);
    }

    private final PlayerCommandSupport support;
    private final Predicate<Player> inStaffOrOwnerMode;
    private final RankRule rankRule;
    private final Override override;

    public TeleportExemptions(PlayerCommandSupport support, Predicate<Player> inStaffOrOwnerMode, RankRule rankRule,
                              Override override) {
        this.support = Objects.requireNonNull(support, "support must not be null");
        this.inStaffOrOwnerMode = Objects.requireNonNull(inStaffOrOwnerMode, "inStaffOrOwnerMode must not be null");
        this.rankRule = Objects.requireNonNull(rankRule, "rankRule must not be null");
        this.override = Objects.requireNonNull(override, "override must not be null");
    }

    /** {@link Type} names, for the plugin.yml/CommandNodes declarations and tests. */
    public static List<String> allNodes() {
        List<String> nodes = new ArrayList<>();
        for (Type type : Type.values()) {
            nodes.add(type.allNode());
        }
        return nodes;
    }

    /**
     * Runs {@code onAllowed} on the main thread unless {@code target} is exempt from {@code type} and the sender
     * doesn't override that; otherwise tells the sender. The rank rule is checked separately (TargetRankCheck).
     */
    public void whenNotExempt(CommandSender sender, Player target, Type type, Runnable onAllowed) {
        if (PlayerCommandSupport.isSelf(sender, target)) {
            onAllowed.run();
            return;
        }
        override.overrides(sender).exceptionally(ex -> false).thenCompose(overrides -> Boolean.TRUE.equals(overrides)
                ? CompletableFuture.completedFuture(Exemption.NONE)
                : exemption(target, type))
            .thenAccept(exemption -> support.mainThread().execute(() -> {
                switch (exemption) {
                    case NONE -> onAllowed.run();
                    case MODE -> sender.sendMessage(ChatColor.RED + target.getName()
                        + " is in staff or owner mode and can't be teleported by others.");
                    case NODE -> sender.sendMessage(ChatColor.RED + target.getName()
                        + " is exempt from being teleported by others.");
                }
            }));
    }

    /**
     * The players an {@code all} teleport of {@code type} moves, handed to {@code onMainThread}: online players
     * (vanished ones too) other than the sender and {@code excluded}, not in staff or owner mode, without the
     * exempt node, and of the sender's rank or lower. Never fails: a player whose checks fail is left out.
     */
    public void forAll(CommandSender sender, Type type, Set<UUID> excluded, Consumer<List<Player>> onMainThread) {
        List<Player> candidates = new ArrayList<>();
        for (Player player : support.onlinePlayers()) {
            if (PlayerCommandSupport.isSelf(sender, player) || excluded.contains(player.getUniqueId())
                    || inStaffOrOwnerMode.test(player)) {
                continue;
            }
            candidates.add(player);
        }
        List<CompletableFuture<Boolean>> checks = new ArrayList<>();
        for (Player candidate : candidates) {
            CompletableFuture<Boolean> exempt = support.hasAsync(candidate, type.exemptNode());
            CompletableFuture<Boolean> rank = safe(() -> rankRule.mayMove(sender, candidate));
            checks.add(exempt.thenCombine(rank, (isExempt, mayMove) -> !Boolean.TRUE.equals(isExempt) && Boolean.TRUE.equals(mayMove))
                .exceptionally(ex -> false));
        }
        CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).whenComplete((ignored, ex) ->
            support.mainThread().execute(() -> {
                List<Player> eligible = new ArrayList<>();
                for (int i = 0; i < candidates.size(); i++) {
                    if (Boolean.TRUE.equals(checks.get(i).getNow(false)) && candidates.get(i).isOnline()) {
                        eligible.add(candidates.get(i));
                    }
                }
                onMainThread.accept(eligible);
            }));
    }

    /**
     * Runs one teleport per player and reports the total to the sender once all have ended: "Teleported 3 of 4
     * players to the hub." plus who wasn't moved. {@code teleportOne} answers the outcome of that player's teleport.
     */
    public static void runAll(CommandSender sender, Collection<Player> players, String where,
                              Function<Player, CompletableFuture<TeleportOutcome>> teleportOne) {
        if (players.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "Nobody to teleport: only online players of your rank or lower who "
                + "aren't exempt (or in staff or owner mode) are moved.");
            return;
        }
        List<Player> list = List.copyOf(players);
        List<CompletableFuture<TeleportOutcome>> outcomes = new ArrayList<>();
        for (Player player : list) {
            CompletableFuture<TeleportOutcome> outcome;
            try {
                outcome = teleportOne.apply(player);
            } catch (RuntimeException ex) {
                outcome = CompletableFuture.failedFuture(ex);
            }
            outcomes.add(outcome.exceptionally(ex -> TeleportOutcome.failed("error")));
        }
        CompletableFuture.allOf(outcomes.toArray(CompletableFuture[]::new)).whenComplete((ignored, ex) -> {
            int moved = 0;
            List<String> notMoved = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                TeleportOutcome outcome = outcomes.get(i).getNow(null);
                if (outcome != null && outcome.isTeleported()) {
                    moved++;
                } else {
                    notMoved.add(list.get(i).getName());
                }
            }
            sender.sendMessage((moved == list.size() ? ChatColor.GREEN : ChatColor.YELLOW) + "Teleported " + moved
                + " of " + list.size() + " player" + (list.size() == 1 ? "" : "s") + " " + where + ".");
            if (!notMoved.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "Not moved: " + String.join(", ", notMoved));
            }
        });
    }

    /** {@code all} offered in tab completion to holders of the type's all node (cached check). */
    public boolean offersAll(CommandSender sender, Type type, String prefix) {
        return ALL_ARGUMENT.startsWith(prefix.toLowerCase(Locale.ROOT)) && support.holds(sender, type.allNode());
    }

    private enum Exemption { NONE, MODE, NODE }

    private CompletableFuture<Exemption> exemption(Player target, Type type) {
        if (inStaffOrOwnerMode.test(target)) {
            return CompletableFuture.completedFuture(Exemption.MODE);
        }
        return support.hasAsync(target, type.exemptNode()).thenApply(exempt -> Boolean.TRUE.equals(exempt) ? Exemption.NODE : Exemption.NONE);
    }

    private static CompletableFuture<Boolean> safe(java.util.function.Supplier<CompletableFuture<Boolean>> check) {
        try {
            CompletableFuture<Boolean> future = check.get();
            return future != null ? future.exceptionally(ex -> false) : CompletableFuture.completedFuture(false);
        } catch (RuntimeException ex) {
            return CompletableFuture.completedFuture(false);
        }
    }
}
