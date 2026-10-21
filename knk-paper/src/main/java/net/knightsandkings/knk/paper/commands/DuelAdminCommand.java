package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.domain.duels.DuelRefusedException;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Result;
import net.knightsandkings.knk.core.duel.DuelEscrowState;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.ports.api.DuelMatchesApi;
import net.knightsandkings.knk.paper.duel.DuelEscrowSpool;
import net.knightsandkings.knk.paper.duel.DuelMatch;
import net.knightsandkings.knk.paper.duel.DuelService;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Staff {@code /knk duel} (arena duels DESIGN §6.1, §7.4), node {@code knk.admin.duel}:
 * <pre>
 * /knk duel list                          running matches, and escrow files that are held or unclaimed
 * /knk duel cancel &lt;match&gt;               cancel a running match at any phase (refund)
 * /knk duel refund &lt;match&gt;               the same for a running match (the API's cancel endpoint)
 * /knk duel review &lt;match&gt; release|void [reason]   a held result (also needs knk.admin.duel.review)
 * </pre>
 * {@code <match>} is the API match id or the first characters of the match token (as {@code list} shows them).
 * A finished, paid result is reverted or confiscated in the web app ("Duels"), not here.
 */
public final class DuelAdminCommand {

    public static final String NODE = "knk.admin.duel";
    public static final String REVIEW_NODE = "knk.admin.duel.review";
    private static final List<String> ACTIONS = List.of("list", "cancel", "refund", "review");

    private final Supplier<DuelService> service;
    private final Supplier<DuelMatchesApi> api;
    private final Function<UUID, Integer> userIds;
    private final BiPredicate<CommandSender, String> hasPermission;
    private final Executor mainThread;

    public DuelAdminCommand(Supplier<DuelService> service, Supplier<DuelMatchesApi> api, Function<UUID, Integer> userIds,
                            BiPredicate<CommandSender, String> hasPermission, Executor mainThread) {
        this.service = service;
        this.api = api;
        this.userIds = userIds;
        this.hasPermission = hasPermission;
        this.mainThread = mainThread;
    }

    public static CommandMetadata metadata() {
        return new CommandMetadata("duel",
                "Arena duels: list running matches and held results, cancel or refund a match, release or void a held result",
                "/knk duel <list|cancel <match>|refund <match>|review <match> release|void [reason]>",
                NODE,
                List.of("/knk duel list", "/knk duel cancel 3f2a", "/knk duel review 41 release", "/knk duel review 41 void thrown fight"));
    }

    public boolean execute(CommandSender sender, String[] args) {
        DuelService duels = service.get();
        if (duels == null) {
            sender.sendMessage(ChatColor.RED + "Duels are turned off.");
            return true;
        }
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> list(sender, duels);
            case "cancel", "refund" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /knk duel " + action + " <match>");
                    return true;
                }
                cancel(sender, duels, args[1], action);
            }
            case "review" -> {
                if (!hasPermission.test(sender, REVIEW_NODE)) {
                    sender.sendMessage(ChatColor.RED + "You need " + REVIEW_NODE + " to review duel results.");
                    return true;
                }
                if (args.length < 3 || !List.of("release", "void").contains(args[2].toLowerCase(Locale.ROOT))) {
                    sender.sendMessage(ChatColor.RED + "Usage: /knk duel review <match> release|void [reason]");
                    return true;
                }
                String reason = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : null;
                review(sender, duels, args[1], args[2].toLowerCase(Locale.ROOT), reason);
            }
            default -> sender.sendMessage(ChatColor.RED + "Usage: " + metadata().usage());
        }
        return true;
    }

    private void list(CommandSender sender, DuelService duels) {
        List<DuelMatch> running = duels.matches();
        sender.sendMessage(ChatColor.GOLD + "Running duels: " + running.size());
        for (DuelMatch m : running) {
            sender.sendMessage(ChatColor.YELLOW + " " + shortToken(m.token()) + (m.matchId() == null ? "" : " #" + m.matchId())
                    + ChatColor.WHITE + " " + m.nameOf(DuelSide.CHALLENGER) + " vs " + m.nameOf(DuelSide.OPPONENT)
                    + ChatColor.GRAY + " — " + m.arena().name() + ", " + m.phase() + ", " + m.gearMode()
                    + ", coins " + m.contract().challenger().coins() + "/" + m.contract().opponent().coins());
        }
        List<DuelEscrowSpool.Entry> waiting = duels.escrowEntries().stream()
                .filter(e -> duels.match(e.token()).isEmpty())
                .filter(e -> e.state() == DuelEscrowState.HELD || e.owesAnything())
                .toList();
        if (!waiting.isEmpty()) {
            sender.sendMessage(ChatColor.GOLD + "Held or unclaimed escrow: " + waiting.size());
            for (DuelEscrowSpool.Entry e : waiting) {
                sender.sendMessage(ChatColor.YELLOW + " " + shortToken(e.token()) + (e.matchId() == null ? "" : " #" + e.matchId())
                        + ChatColor.WHITE + " " + e.party(DuelSide.CHALLENGER).name() + " vs " + e.party(DuelSide.OPPONENT).name()
                        + ChatColor.GRAY + " — " + e.state());
            }
        }
    }

    private void cancel(CommandSender sender, DuelService duels, String ref, String action) {
        Optional<DuelMatch> match = findRunning(duels, ref);
        if (match.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No running duel " + ref + "."
                    + ("refund".equals(action) ? " A finished result is reverted in the web app; a held one with /knk duel review." : ""));
            return;
        }
        if (duels.cancel(match.get().token())) {
            sender.sendMessage(ChatColor.GREEN + "Duel " + shortToken(match.get().token()) + " cancelled; both sides are refunded.");
        } else {
            sender.sendMessage(ChatColor.RED + "That duel is still locking in or already over; try again in a moment.");
        }
    }

    private void review(CommandSender sender, DuelService duels, String ref, String decision, String reason) {
        DuelMatchesApi matches = api.get();
        if (matches == null) {
            sender.sendMessage(ChatColor.RED + "The API isn't available.");
            return;
        }
        if (!(sender instanceof Player staff)) {
            sender.sendMessage(ChatColor.RED + "Review in game as a staff member, or in the web app.");
            return;
        }
        Integer actingUser = userIds.apply(staff.getUniqueId());
        if (actingUser == null) {
            sender.sendMessage(ChatColor.RED + "Your profile is still loading; try again in a moment.");
            return;
        }
        Optional<DuelEscrowSpool.Entry> entry = findEntry(duels, ref);
        Integer matchId = entry.map(DuelEscrowSpool.Entry::matchId).orElseGet(() -> parseInt(ref));
        if (matchId == null) {
            sender.sendMessage(ChatColor.RED + "No duel " + ref + ". Use the match id from /knk duel list or the web app.");
            return;
        }
        String why = reason != null ? reason : (decision.equals("void") ? "Voided in game by " : "Released in game by ") + staff.getName();
        CompletableFuture<Result> call;
        try {
            call = decision.equals("release") ? matches.release(matchId, actingUser, why) : matches.voidHeld(matchId, actingUser, why);
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        call.whenComplete((result, error) -> mainThread.execute(() -> {
            if (error != null) {
                DuelRefusedException refused = DuelRefusedException.find(error);
                sender.sendMessage(ChatColor.RED + "Not done: " + (refused != null ? refused.playerMessage() : "the API can't be reached."));
                return;
            }
            sender.sendMessage(ChatColor.GREEN + "Duel #" + matchId + " " + (decision.equals("release") ? "released" : "voided")
                    + (result != null && result.alreadyDone() ? " (it already was)" : "") + ".");
            entry.ifPresent(e -> duels.refreshEscrow(e.token()));
        }));
    }

    private static Optional<DuelMatch> findRunning(DuelService duels, String ref) {
        Integer id = parseInt(ref);
        String prefix = ref.toLowerCase(Locale.ROOT);
        return duels.matches().stream()
                .filter(m -> (id != null && id.equals(m.matchId())) || (prefix.length() >= 4 && m.token().toString().startsWith(prefix)))
                .findFirst();
    }

    private static Optional<DuelEscrowSpool.Entry> findEntry(DuelService duels, String ref) {
        Integer id = parseInt(ref);
        String prefix = ref.toLowerCase(Locale.ROOT);
        return duels.escrowEntries().stream()
                .filter(e -> (id != null && id.equals(e.matchId())) || (prefix.length() >= 4 && e.token().toString().startsWith(prefix)))
                .findFirst();
    }

    private static Integer parseInt(String raw) {
        try {
            return Integer.parseInt(raw.startsWith("#") ? raw.substring(1) : raw);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    static String shortToken(UUID token) {
        return token.toString().substring(0, 8);
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return ACTIONS.stream().filter(a -> a.startsWith(prefix)).toList();
        }
        DuelService duels = service.get();
        if (args.length == 2 && duels != null && !"list".equalsIgnoreCase(args[0])) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return ("review".equalsIgnoreCase(args[0])
                    ? duels.escrowEntries().stream().filter(e -> e.state() == DuelEscrowState.HELD).map(e -> shortToken(e.token()))
                    : duels.matches().stream().map(m -> shortToken(m.token())))
                    .filter(t -> t.startsWith(prefix)).toList();
        }
        if (args.length == 3 && "review".equalsIgnoreCase(args[0])) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return List.of("release", "void").stream().filter(a -> a.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
