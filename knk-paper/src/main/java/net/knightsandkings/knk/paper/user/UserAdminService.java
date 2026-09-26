package net.knightsandkings.knk.paper.user;

import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.domain.permissions.PermissionGroupSummary;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.TitleBracket;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.PermissionGroupsQueryApi;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.paper.chat.RewardMessageFormat;
import net.knightsandkings.knk.paper.commands.support.PromotionEffects;
import net.knightsandkings.knk.paper.commands.support.RankHierarchy;
import net.knightsandkings.knk.paper.modes.ModeService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The one code path for staff edits of another player's account (InventoryMenu content port CP8,
 * catalogue P8): {@code /knk user}, {@code /freeze}/{@code /unfreeze} and the in-game Player
 * manager ({@code users.manager*}) all call these methods - neither calls the other. Extracted
 * from {@code UserManagementCommand}/{@code FreezeCommand} with their behaviour and messages
 * unchanged:
 * <ul>
 *   <li>per-property permission {@code knk.admin.user.<property>} (plain Bukkit nodes, like every
 *       other {@code /knk} admin subcommand);</li>
 *   <li>{@link RankHierarchy#actorOutranks} for group/perm/freeze (and the new mode/salary
 *       actions) - the actor's highest group weight must exceed the target's; the console and
 *       holders of {@link #MANAGE_ALL_NODE} always pass;</li>
 *   <li>balances as signed deltas on {@code PUT /api/users/{id}/balances} (a "set" is a computed
 *       delta), promotion effects shown for an online target;</li>
 *   <li>{@code ModeService.refreshVisibilityFor} after a group/perm change;</li>
 *   <li>every mutation goes through {@code UsersCommandApi.withActor(actor)} (content port CP7) so
 *       it can be audit-logged under the staff member.</li>
 * </ul>
 * Every mutation returns a future that completes on the main thread <em>after</em> its feedback
 * was sent - {@code true} when the change went through - so the menu can repaint afterwards.
 */
public final class UserAdminService {

    public static final String NODE_PREFIX = "knk.admin.user.";
    /**
     * Menu follow-up 2026-09-26 (owner request): edit every player - equal or higher rank, offline,
     * or yourself - without {@link RankHierarchy#actorOutranks}. Per-property nodes still apply.
     */
    public static final String MANAGE_ALL_NODE = NODE_PREFIX + "manage.all";

    private final Executor mainThread;
    private final UsersDataAccess usersDataAccess;
    private final UsersCommandApi usersCommandApi;
    private final PermissionGroupsQueryApi permissionGroupsQueryApi;
    private final RankHierarchy rankHierarchy;
    private final ModeService modeService;
    private final AdminFreezeManager freezeManager;
    /** Re-renders an online player's tab-list team and footer from a fresh summary (KNG-7). */
    private final BiConsumer<Player, UserSummary> displayRefresher;

    public UserAdminService(Executor mainThread, UsersDataAccess usersDataAccess, UsersCommandApi usersCommandApi,
                            PermissionGroupsQueryApi permissionGroupsQueryApi, RankHierarchy rankHierarchy,
                            ModeService modeService, AdminFreezeManager freezeManager) {
        this(mainThread, usersDataAccess, usersCommandApi, permissionGroupsQueryApi, rankHierarchy, modeService,
                freezeManager, (player, summary) -> { });
    }

    public UserAdminService(Executor mainThread, UsersDataAccess usersDataAccess, UsersCommandApi usersCommandApi,
                            PermissionGroupsQueryApi permissionGroupsQueryApi, RankHierarchy rankHierarchy,
                            ModeService modeService, AdminFreezeManager freezeManager,
                            BiConsumer<Player, UserSummary> displayRefresher) {
        this.displayRefresher = displayRefresher;
        this.mainThread = mainThread;
        this.usersDataAccess = usersDataAccess;
        this.usersCommandApi = usersCommandApi;
        this.permissionGroupsQueryApi = permissionGroupsQueryApi;
        this.rankHierarchy = rankHierarchy;
        this.modeService = modeService;
        this.freezeManager = freezeManager;
    }

    // ===== permission, target, actor, rank =====

    /** {@code knk.admin.user.<property>} on the sender; tells them when missing. */
    public boolean requireProperty(CommandSender sender, String property) {
        if (sender.hasPermission(NODE_PREFIX + property)) {
            return true;
        }
        sender.sendMessage(ChatColor.RED + "You don't have permission to manage this player's " + property + ".");
        return false;
    }

    /**
     * Resolves {@code targetName} to a live UserSummary via the API directly (not the cache) so an
     * offline player's current balances are still accurate; reports "not found" on the sender.
     * {@code onFound} always runs on the main thread.
     */
    public void resolveTarget(CommandSender sender, String targetName, Consumer<UserSummary> onFound) {
        usersDataAccess.getByUsernameAsync(targetName).thenAccept(result -> mainThread.execute(() -> {
            if (!result.isSuccess() || result.value().isEmpty()) {
                sender.sendMessage(ChatColor.RED + "No player found named '" + targetName + "'.");
                return;
            }
            onFound.accept(result.value().get());
        })).exceptionally(ex -> {
            mainThread.execute(() -> sender.sendMessage(ChatColor.RED + "Failed to look up '" + targetName + "': " + describeError(ex)));
            return null;
        });
    }

    /**
     * Runs {@code onAllowed} (main thread) with the command API attributed to the sender if they
     * outrank {@code target}; the console always passes. {@code onDenied} runs after the denial
     * message (or a failure) was sent.
     */
    public void withRankCheck(CommandSender sender, UserSummary target, Consumer<UsersCommandApi> onAllowed, Runnable onDenied) {
        if (!(sender instanceof Player senderPlayer)) {
            onAllowed.accept(usersCommandApi); // Console always allowed - matches /knk gate admin's console-safe precedent.
            return;
        }
        resolveActor(senderPlayer).thenAccept(actor -> {
            if (actor == null) {
                mainThread.execute(() -> {
                    sender.sendMessage(ChatColor.RED + "No player found named '" + senderPlayer.getName() + "'.");
                    onDenied.run();
                });
                return;
            }
            if (bypassesRankCheck(sender)) {
                mainThread.execute(() -> onAllowed.accept(usersCommandApi.withActor(actor.id())));
                return;
            }
            rankHierarchy.actorOutranks(actor.id(), target.id()).thenAccept(outranks -> mainThread.execute(() -> {
                if (!outranks) {
                    sender.sendMessage(ChatColor.RED + "You cannot act on a player of equal or higher rank.");
                    onDenied.run();
                    return;
                }
                onAllowed.accept(usersCommandApi.withActor(actor.id()));
            })).exceptionally(ex -> {
                mainThread.execute(() -> {
                    sender.sendMessage(ChatColor.RED + "Failed to check rank: " + describeError(ex));
                    onDenied.run();
                });
                return null;
            });
        }).exceptionally(ex -> {
            mainThread.execute(() -> {
                sender.sendMessage(ChatColor.RED + "Failed to look up '" + senderPlayer.getName() + "': " + describeError(ex));
                onDenied.run();
            });
            return null;
        });
    }

    /** Holders of {@link #MANAGE_ALL_NODE} may edit anyone, themselves included; the console always may. */
    public static boolean bypassesRankCheck(CommandSender sender) {
        return !(sender instanceof Player) || sender.hasPermission(MANAGE_ALL_NODE);
    }

    /** Whether {@code actorUserId} outranks {@code targetUserId} (the menu's click condition reads a cached answer). */
    public CompletableFuture<Boolean> outranks(int actorUserId, int targetUserId) {
        return rankHierarchy.actorOutranks(actorUserId, targetUserId);
    }

    /**
     * The command API attributed to the acting player (their knk user id via the cache-first
     * lookup), or the plain API for the console / an unresolvable account - a missing actor never
     * blocks the action, it only leaves the audit actor empty (content port CP7).
     */
    public CompletableFuture<UsersCommandApi> actorApi(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return CompletableFuture.completedFuture(usersCommandApi);
        }
        return usersDataAccess.getByUuidAsync(player.getUniqueId())
                .thenApply(result -> result != null && result.isSuccess() && result.value().isPresent()
                        ? usersCommandApi.withActor(result.value().get().id())
                        : usersCommandApi)
                .exceptionally(ex -> usersCommandApi);
    }

    private CompletableFuture<UserSummary> resolveActor(Player player) {
        return usersDataAccess.getByUsernameAsync(player.getName())
                .thenApply(result -> result.isSuccess() ? result.value().orElse(null) : null);
    }

    // ===== balances (coins / gems / xp) =====

    /**
     * {@code /knk user <p> coins|gems|xp set|add|remove <amount>}: turns the action into a signed
     * delta against the target's current value, then {@link #adjustBalance}.
     */
    public CompletableFuture<Boolean> changeBalance(CommandSender sender, UserSummary target, String property, String action,
                                                    int amount, String reason) {
        int current = currentValue(target, property);
        int delta = switch (action) {
            case "set" -> amount - current;
            case "remove" -> -amount;
            default -> amount;
        };
        if (delta == 0) {
            sender.sendMessage(ChatColor.YELLOW + target.username() + "'s " + property + " is already " + amount + ".");
            return CompletableFuture.completedFuture(false);
        }
        return adjustBalance(sender, target, property, delta, reason);
    }

    /** Adds {@code delta} (may be negative) to one balance; the server rejects underflow. */
    public CompletableFuture<Boolean> adjustBalance(CommandSender sender, UserSummary target, String property, int delta,
                                                    String reason) {
        int current = currentValue(target, property);
        int coinsDelta = property.equals("coins") ? delta : 0;
        int gemsDelta = property.equals("gems") ? delta : 0;
        int experienceDelta = property.equals("xp") ? delta : 0;

        // Online target: show any title change right here from the response, and tell the API
        // not to also queue it for PlayerNotificationPoller (which would show it twice).
        // Offline target: let the API queue it so it shows when they next join.
        boolean targetOnline = Bukkit.getPlayerExact(target.username()) != null;

        CompletableFuture<Boolean> done = new CompletableFuture<>();
        actorApi(sender)
                .thenCompose(api -> api.adjustBalancesById(target.id(), coinsDelta, gemsDelta, experienceDelta, reason, !targetOnline))
                .thenAccept(result -> mainThread.execute(() -> {
                    String verb = delta > 0 ? "Increased" : "Decreased";
                    sender.sendMessage(ChatColor.GREEN + verb + " " + target.username() + "'s " + property
                            + " by " + Math.abs(delta) + " (now " + (current + delta) + ").");
                    Player targetPlayer = Bukkit.getPlayerExact(target.username());
                    if (targetOnline && targetPlayer != null && result != null && result.titleChange() != null) {
                        PromotionEffects.show(targetPlayer, result.titleChange());
                    }
                    done.complete(true);
                    // New balances/title into the cache, and the title into chat and the tab list.
                    refreshTargetSummary(target).thenAccept(fresh -> mainThread.execute(() -> refreshTargetDisplay(fresh)));
                }))
                .exceptionally(ex -> {
                    mainThread.execute(() -> {
                        sender.sendMessage(ChatColor.RED + "Failed: " + describeError(ex));
                        done.complete(false);
                    });
                    return null;
                });
        return done;
    }

    /**
     * Player manager "set title": the XP delta that puts the target exactly on the bracket's
     * minimum ({@code minExperience - xp}, negative for a demotion) - the server then resolves and
     * audit-logs the title change like any XP adjustment.
     */
    public CompletableFuture<Boolean> setTitle(CommandSender sender, UserSummary target, TitleBracket bracket) {
        int delta = bracket.minExperience() - target.experiencePoints();
        String name = bracket.nameFor(target.gender());
        if (delta == 0) {
            sender.sendMessage(ChatColor.YELLOW + target.username() + " already has exactly the XP for " + name + ".");
            return CompletableFuture.completedFuture(false);
        }
        return adjustBalance(sender, target, "xp", delta, "Title set to " + name + " by " + sender.getName());
    }

    private static int currentValue(UserSummary target, String property) {
        return switch (property) {
            case "coins" -> target.coins();
            case "gems" -> target.gems();
            default -> target.experiencePoints();
        };
    }

    // ===== group membership / permission grants =====

    /** {@code /knk user <p> group add|remove <groupName>}: looks the group up by name, then {@link #changeGroup}. */
    public CompletableFuture<Boolean> changeGroupByName(CommandSender sender, UserSummary target, String groupName, boolean adding,
                                                       OffsetDateTime expiresAt) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        permissionGroupsQueryApi.list().thenAccept(groups -> {
            PermissionGroupSummary group = groups.stream()
                    .filter(g -> g.name().equalsIgnoreCase(groupName))
                    .findFirst()
                    .orElse(null);
            mainThread.execute(() -> {
                if (group == null) {
                    sender.sendMessage(ChatColor.RED + "No PermissionGroup named '" + groupName + "'.");
                    done.complete(false);
                    return;
                }
                changeGroup(sender, target, group, adding, expiresAt).thenAccept(done::complete);
            });
        }).exceptionally(ex -> {
            mainThread.execute(() -> {
                sender.sendMessage(ChatColor.RED + "Failed to list groups: " + describeError(ex));
                done.complete(false);
            });
            return null;
        });
        return done;
    }

    /**
     * Adds or removes one group membership (rank-checked, attributed, visibility refreshed), then
     * re-reads the target so their premium tier shows in chat and the tab list straight away and
     * on their next login.
     */
    public CompletableFuture<Boolean> changeGroup(CommandSender sender, UserSummary target, PermissionGroupSummary group,
                                                  boolean adding, OffsetDateTime expiresAt) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        withRankCheck(sender, target, api -> {
            CompletableFuture<Void> call = adding
                    ? api.addGroupMembership(target.id(), group.id(), expiresAt)
                    : api.removeGroupMembership(target.id(), group.id());
            call.thenCompose(v -> refreshTargetSummary(target)).thenAccept(fresh -> mainThread.execute(() -> {
                String verb = adding ? "Added" : "Removed";
                String durationSuffix = adding ? (expiresAt != null ? " (expires " + expiresAt + ")" : " (permanent)") : "";
                sender.sendMessage(ChatColor.GREEN + verb + " " + target.username() + "'s membership in "
                        + group.name() + durationSuffix + ".");
                if (group.isPremiumTier()) {
                    reportPremiumTier(sender, target, group, adding, fresh);
                }
                notifyGroupChange(target, group, adding);
                refreshTargetVisibility(target);
                refreshTargetDisplay(fresh);
                done.complete(true);
            })).exceptionally(ex -> fail(sender, done, ex));
        }, () -> done.complete(false));
        return done;
    }

    /**
     * Player manager rank switch: makes {@code rank} (Default or a premium tier, see
     * {@link PlayerRanks}) the target's only rank, permanently - added first, then each of
     * {@code replacedRanks} removed, so a failure part-way never leaves them without a rank.
     * Rank-checked and attributed like {@link #changeGroup}; the target is re-read afterwards so
     * chat and the tab list show the new rank. {@code /knk user ... group add} still adds
     * alongside, e.g. a temporary higher tier on top of a permanent one.
     */
    public CompletableFuture<Boolean> setRank(CommandSender sender, UserSummary target, PermissionGroupSummary rank,
                                              java.util.List<PermissionGroupSummary> replacedRanks) {
        java.util.List<PermissionGroupSummary> replaced = replacedRanks.stream().filter(g -> g.id() != rank.id()).toList();
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        withRankCheck(sender, target, api -> {
            CompletableFuture<Void> chain = api.addGroupMembership(target.id(), rank.id(), null);
            for (PermissionGroupSummary old : replaced) {
                chain = chain.thenCompose(v -> api.removeGroupMembership(target.id(), old.id()));
            }
            chain.thenCompose(v -> refreshTargetSummary(target)).thenAccept(fresh -> mainThread.execute(() -> {
                String was = replaced.stream().map(PermissionGroupSummary::name).collect(java.util.stream.Collectors.joining(", "));
                sender.sendMessage(ChatColor.GREEN + "Set " + target.username() + "'s rank to " + rank.name()
                        + (was.isEmpty() ? "" : " (was " + was + ")") + ".");
                notifyRankChange(target, rank);
                refreshTargetVisibility(target);
                refreshTargetDisplay(fresh);
                done.complete(true);
            })).exceptionally(ex -> {
                // Part of the switch may have gone through - re-read so what's shown matches.
                refreshTargetSummary(target).thenAccept(fresh -> mainThread.execute(() -> refreshTargetDisplay(fresh)));
                return fail(sender, done, ex);
            });
        }, () -> done.complete(false));
        return done;
    }

    /** Grants or revokes one permission node (rank-checked, attributed, visibility refreshed). */
    public CompletableFuture<Boolean> changePermission(CommandSender sender, UserSummary target, String node, boolean granting,
                                                       OffsetDateTime expiresAt) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        withRankCheck(sender, target, api -> {
            CompletableFuture<Void> call = granting
                    ? api.grantPermission(target.id(), node, expiresAt)
                    : api.revokePermission(target.id(), node);
            call.thenAccept(v -> mainThread.execute(() -> {
                String verb = granting ? "Granted" : "Revoked";
                String prep = granting ? " to " : " from ";
                sender.sendMessage(ChatColor.GREEN + verb + " '" + node + "'" + prep + target.username() + ".");
                refreshTargetVisibility(target);
                done.complete(true);
            })).exceptionally(ex -> fail(sender, done, ex));
        }, () -> done.complete(false));
        return done;
    }

    // ===== freeze / mode / salary =====

    /** {@code /freeze <p> <reason>}, {@code /unfreeze <p>} and the Player manager's freeze toggle. */
    public CompletableFuture<Boolean> setFrozen(CommandSender sender, UserSummary target, boolean freezing, String reason) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        withRankCheck(sender, target, api -> {
            CompletableFuture<Void> call = freezing ? api.freezeById(target.id(), reason) : api.unfreezeById(target.id());
            call.thenAccept(v -> mainThread.execute(() -> {
                Player targetPlayer = Bukkit.getPlayerExact(target.username());
                if (freezing) {
                    if (targetPlayer != null) {
                        freezeManager.freeze(targetPlayer.getUniqueId(), reason);
                        targetPlayer.sendMessage(ChatColor.RED + "You have been frozen: " + reason);
                    }
                    sender.sendMessage(ChatColor.GREEN + "Froze " + target.username() + (targetPlayer == null ? " (offline - takes effect on next join)." : "."));
                } else {
                    if (targetPlayer != null) {
                        freezeManager.unfreeze(targetPlayer.getUniqueId());
                        targetPlayer.sendMessage(ChatColor.GREEN + "You have been unfrozen.");
                    }
                    sender.sendMessage(ChatColor.GREEN + "Unfroze " + target.username() + ".");
                }
                done.complete(true);
            })).exceptionally(ex -> fail(sender, done, ex));
        }, () -> done.complete(false));
        return done;
    }

    /**
     * Player manager "mode": switches an <em>online</em> target's owner/staff mode (rank-checked,
     * {@code knk.admin.user.mode}). A non-NONE mode needs the target to hold that mode's node -
     * otherwise they'd be vanished with no way to turn it off themselves.
     */
    public CompletableFuture<Boolean> setMode(CommandSender sender, UserSummary target, ActiveMode mode) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        Player targetPlayer = Bukkit.getPlayerExact(target.username());
        if (targetPlayer == null || modeService == null) {
            sender.sendMessage(ChatColor.RED + target.username() + " must be online to change their mode.");
            return CompletableFuture.completedFuture(false);
        }
        withRankCheck(sender, target, api -> {
            Consumer<Boolean> apply = allowed -> {
                if (!allowed) {
                    sender.sendMessage(ChatColor.RED + target.username() + " doesn't have " + ModeService.displayName(mode) + " mode.");
                    done.complete(false);
                    return;
                }
                modeService.applyMode(targetPlayer, mode, true);
                modeService.persist(targetPlayer, mode, api).whenComplete((v, ex) -> mainThread.execute(() -> {
                    if (ex != null) {
                        sender.sendMessage(ChatColor.RED + "Failed: " + describeError(ex));
                        done.complete(false);
                        return;
                    }
                    sender.sendMessage(ChatColor.GREEN + "Set " + target.username() + "'s mode to " + ModeService.displayName(mode) + ".");
                    done.complete(true);
                }));
            };
            if (mode == ActiveMode.NONE) {
                apply.accept(true);
            } else {
                modeService.whenHasModePermission(targetPlayer, mode, apply);
            }
        }, () -> done.complete(false));
        return done;
    }

    /** Player manager "salary payout": pays the target's outstanding salary now (rank-checked, {@code knk.admin.user.salary}). */
    public CompletableFuture<Boolean> payOutSalary(CommandSender sender, UserSummary target) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        withRankCheck(sender, target, api -> api.payOutSalaryById(target.id()).thenAccept(result -> mainThread.execute(() -> {
            if (result != null && result.paid()) {
                sender.sendMessage(ChatColor.GREEN + "Paid " + target.username() + " " + result.amountPaid() + " coins of salary.");
                Player targetPlayer = Bukkit.getPlayerExact(target.username());
                if (targetPlayer != null) {
                    RewardMessageFormat.salary(result, target.titleName()).forEach(targetPlayer::sendMessage);
                }
                done.complete(true);
            } else {
                sender.sendMessage(ChatColor.YELLOW + target.username() + " has no salary to pay out yet.");
                done.complete(false);
            }
        })).exceptionally(ex -> fail(sender, done, ex)), () -> done.complete(false));
        return done;
    }

    // ===== kick / ban (Paper's own commands, run as the staff member) =====

    public static final String KICK_REASON = "You were kicked by a member of staff.";
    public static final String BAN_REASON = "You were banned by a member of staff.";

    /**
     * Runs Paper's {@code /kick <name> <reason>} as {@code staff} (owner decision: vanilla command
     * permissions decide, not a knk node). Returns whether the command was found and run.
     */
    public boolean kick(Player staff, UserSummary target) {
        return staff.performCommand("kick " + target.username() + " " + KICK_REASON);
    }

    /** Paper's {@code /ban <name> <reason>} as {@code staff}; see {@link #kick}. */
    public boolean ban(Player staff, UserSummary target) {
        return staff.performCommand("ban " + target.username() + " " + BAN_REASON);
    }

    // ===== helpers =====

    private Void fail(CommandSender sender, CompletableFuture<Boolean> done, Throwable ex) {
        mainThread.execute(() -> {
            sender.sendMessage(ChatColor.RED + "Failed: " + describeError(ex));
            done.complete(false);
        });
        return null;
    }

    /**
     * Re-reads {@code target} from the API into the user cache after a change that affects how
     * they are shown (premium tier, title, balances). Chat reads that cache, and the next login
     * would otherwise start from the pre-change summary. Never fails - the change itself already
     * went through, so a failed refresh only means the old display lingers - and resolves to the
     * fresh summary, or null (no UUID, e.g. a web-only account, or the read failed).
     */
    private CompletableFuture<UserSummary> refreshTargetSummary(UserSummary target) {
        if (target.uuid() == null) {
            return CompletableFuture.completedFuture(null);
        }
        return usersDataAccess.refreshAsync(target.uuid())
                .handle((result, ex) -> ex == null && result != null ? result.value().orElse(null) : null);
    }

    /** Main thread: re-renders an online player's tab-list team and footer from {@code fresh}. */
    private void refreshTargetDisplay(UserSummary fresh) {
        if (fresh == null || fresh.uuid() == null) {
            return;
        }
        Player targetPlayer = Bukkit.getPlayer(fresh.uuid());
        if (targetPlayer != null) {
            displayRefresher.accept(targetPlayer, fresh);
        }
    }

    /**
     * The displayed premium tier is the highest-weight active premium membership, so adding a
     * lower tier next to a higher one changes nothing visible - say so, rather than leave the
     * staff member wondering why the prefix didn't change.
     */
    static void reportPremiumTier(CommandSender sender, UserSummary target, PermissionGroupSummary group,
                                  boolean adding, UserSummary fresh) {
        if (fresh == null) {
            return;
        }
        String shown = fresh.premiumTierName() != null ? fresh.premiumTierName() : "none";
        if (adding && !java.util.Objects.equals(fresh.premiumTierGroupId(), group.id())) {
            sender.sendMessage(ChatColor.YELLOW + target.username() + "'s displayed premium tier is still " + shown
                    + " (the highest-weight tier wins) - remove " + shown + " to show " + group.name() + ".");
        } else {
            sender.sendMessage(ChatColor.GRAY + target.username() + "'s displayed premium tier is now " + shown + ".");
        }
    }

    /**
     * A group/perm change can affect what an online target may now see (e.g. granting
     * knk.mode.staff should let them immediately see already-vanished players, not just after
     * their next relog) - docs/specs/user-features/IMPLEMENTATION_PLAN.md §3's carried-forward gap
     * item 3. No-op if the target is offline or modeService wasn't wired.
     */
    private void refreshTargetVisibility(UserSummary target) {
        if (modeService == null) {
            return;
        }
        Player targetPlayer = Bukkit.getPlayerExact(target.username());
        if (targetPlayer != null) {
            modeService.refreshVisibilityFor(targetPlayer);
        }
    }

    private void notifyRankChange(UserSummary target, PermissionGroupSummary rank) {
        Player targetPlayer = Bukkit.getPlayerExact(target.username());
        if (targetPlayer == null) {
            return; // Offline - nothing to notify.
        }
        targetPlayer.playSound(targetPlayer.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        targetPlayer.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "✦ " + ChatColor.YELLOW + "Your rank is now " + rank.name() + "!");
    }

    private void notifyGroupChange(UserSummary target, PermissionGroupSummary group, boolean added) {
        Player targetPlayer = Bukkit.getPlayerExact(target.username());
        if (targetPlayer == null) {
            return; // Offline - nothing to notify.
        }
        targetPlayer.playSound(targetPlayer.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        if (added) {
            targetPlayer.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "✦ " + ChatColor.YELLOW + "You have been added to " + group.name() + "!");
        } else {
            targetPlayer.sendMessage(ChatColor.RED + "Your " + group.name() + " membership was removed.");
        }
    }

    public static String describeError(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof ApiException apiEx) {
                if (apiEx.getResponseBody() != null && !apiEx.getResponseBody().isEmpty()) {
                    return apiEx.getResponseBody();
                }
                return apiEx.getMessage();
            }
            cause = cause.getCause();
        }
        return ex.getMessage();
    }
}
