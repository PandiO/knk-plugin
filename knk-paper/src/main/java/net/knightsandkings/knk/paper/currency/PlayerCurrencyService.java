package net.knightsandkings.knk.paper.currency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.domain.currency.AlreadyReversed;
import net.knightsandkings.knk.core.domain.currency.AmountParser;
import net.knightsandkings.knk.core.domain.currency.Balances;
import net.knightsandkings.knk.core.domain.currency.CurrencyAlert;
import net.knightsandkings.knk.core.domain.currency.CurrencyAlertPage;
import net.knightsandkings.knk.core.domain.currency.CurrencyError;
import net.knightsandkings.knk.core.domain.currency.CurrencyException;
import net.knightsandkings.knk.core.domain.currency.CurrencyFormat;
import net.knightsandkings.knk.core.domain.currency.LeaderboardEntry;
import net.knightsandkings.knk.core.domain.currency.LeaderboardPage;
import net.knightsandkings.knk.core.domain.currency.LedgerLine;
import net.knightsandkings.knk.core.domain.currency.LedgerPage;
import net.knightsandkings.knk.core.domain.currency.PendingTransfer;
import net.knightsandkings.knk.core.domain.currency.ReversalOutcome;
import net.knightsandkings.knk.core.domain.currency.TransferLock;
import net.knightsandkings.knk.core.domain.currency.TransferOutcome;
import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;
import net.knightsandkings.knk.core.domain.users.BalanceCurrency;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.CurrencyApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * The player side of the currency ledger (currency DESIGN.md §3.6, IMPLEMENTATION_PLAN.md
 * Phase 3): /pay (with its confirmation step), /balance, /baltop, /transactions and the staff
 * {@code /knk user <player> history}; plus the staff {@code /knk currency reverse|history|lock|unlock}
 * (Phase 4). Parses nothing and decides nothing that matters: knk-web-api
 * applies every rule and returns every number; this class checks the in-game nodes, resolves
 * names, calls {@link CurrencyApi} off the main thread and renders the answer on it.
 * <p>
 * Name lookups are vanish-safe ({@link VisiblePlayers}): a player the sender can't see is looked up
 * like an offline one, and no answer says whether the other player is online.
 */
public class PlayerCurrencyService implements Listener {
    private static final Logger LOGGER = Logger.getLogger(PlayerCurrencyService.class.getName());

    public static final String PAY_NODE = "knk.pay";
    public static final String PAY_GEMS_NODE = "knk.pay.gems";
    public static final String PAY_BYPASS_NODE = "knk.pay.bypass";
    public static final String BALANCE_NODE = "knk.balance";
    public static final String BALANCE_OTHERS_NODE = "knk.balance.others";
    public static final String BALTOP_NODE = "knk.baltop";
    public static final String TRANSACTIONS_NODE = "knk.transactions";
    public static final String TRANSACTIONS_OTHERS_NODE = "knk.transactions.others";
    /** {@code /knk user <player> history} (KNG-23). */
    public static final String USER_HISTORY_NODE = "knk.admin.user.history";
    /** Staff (currency DESIGN.md §3.8), the same strings the API checks for web callers. */
    public static final String CURRENCY_HISTORY_NODE = "knk.admin.currency.history";
    public static final String CURRENCY_REVERSE_NODE = "knk.admin.currency.reverse";
    public static final String CURRENCY_LOCK_NODE = "knk.admin.currency.lock";
    /** Currency anomaly alerts: /knk currency alerts and the in-game alert notices (Phase 5). */
    public static final String CURRENCY_ALERTS_NODE = "knk.admin.currency.alerts";
    /** A staff note (reversal) must say more than "fix" - the API requires 10 characters too. */
    public static final int MIN_STAFF_NOTE_LENGTH = 10;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final Pattern BUTTON = Pattern.compile("\\{(confirm|cancel)}");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final int MAX_REASON_LENGTH = 40;

    /**
     * An in-game node check (KnkPermissible#checkAsync): UNAVAILABLE when it couldn't be made
     * (API unreachable), which is refused with "can't be reached" rather than "no permission".
     */
    @FunctionalInterface
    public interface PermissionCheck {
        CompletableFuture<PermissionDecision> has(Player player, String node);
    }

    /** Message keys for "the economy service can't be reached" (for /pay: also "nothing was paid"). */
    static final String SERVICE_UNAVAILABLE = "service-unavailable";
    static final String PAY_SERVICE_UNAVAILABLE = "pay-service-unavailable";

    /** Runs {@code task} on the main thread after {@code delay}; returns what cancels it. */
    @FunctionalInterface
    public interface Scheduler {
        Runnable runLater(Duration delay, Runnable task);
    }

    /** Pending confirmations live this long when the API doesn't say (its default). */
    static final int DEFAULT_CONFIRM_SECONDS = 60;
    /** The expiry notice waits this much past the API's deadline, so a last-second confirm isn't told it expired. */
    static final Duration EXPIRY_GRACE = Duration.ofSeconds(1);

    /** A resolved player: their knk user id and name. */
    record Target(int userId, String username) {
    }

    private final Executor mainThread;
    private final CurrencyApi currencyApi;
    private final UsersDataAccess usersDataAccess;
    private final UserCache userCache;
    private final PermissionCheck permissions;
    private final VisiblePlayers visiblePlayers;
    private final CurrencySettings settings;
    private final Clock clock;
    private final Scheduler scheduler;
    private java.util.function.BiPredicate<Player, String> cachedPermissions = (player, node) -> false;

    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Instant> lastPaidAt = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastPendingId = new ConcurrentHashMap<>();
    /** Cancels the expiry notice of the sender's open prompt ({@link #lastPendingId}). Main thread. */
    private final Map<UUID, Runnable> expiryNotices = new ConcurrentHashMap<>();

    public PlayerCurrencyService(Executor mainThread, CurrencyApi currencyApi, UsersDataAccess usersDataAccess, UserCache userCache,
                                 PermissionCheck permissions, VisiblePlayers visiblePlayers, CurrencySettings settings, Clock clock) {
        this(mainThread, currencyApi, usersDataAccess, userCache, permissions, visiblePlayers, settings, clock,
            (delay, task) -> {
                CompletableFuture<Void> later = CompletableFuture.runAsync(task,
                    CompletableFuture.delayedExecutor(delay.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS, mainThread));
                return () -> later.cancel(false);
            });
    }

    /** @param scheduler main-thread delayed tasks (the Bukkit scheduler), for the /pay confirmation expiry notice */
    public PlayerCurrencyService(Executor mainThread, CurrencyApi currencyApi, UsersDataAccess usersDataAccess, UserCache userCache,
                                 PermissionCheck permissions, VisiblePlayers visiblePlayers, CurrencySettings settings, Clock clock,
                                 Scheduler scheduler) {
        this.mainThread = mainThread;
        this.currencyApi = currencyApi;
        this.usersDataAccess = usersDataAccess;
        this.userCache = userCache;
        this.permissions = permissions;
        this.visiblePlayers = visiblePlayers;
        this.settings = settings;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    public CurrencySettings settings() {
        return settings;
    }

    public VisiblePlayers visiblePlayers() {
        return visiblePlayers;
    }

    /**
     * The immediate (cache-only) check tab completion uses (KNG-107) - e.g. KnkPermissible's
     * {@code hasPermission}. Until set, a Bukkit grant.
     */
    public void setCachedPermissions(java.util.function.BiPredicate<Player, String> cachedPermissions) {
        this.cachedPermissions = java.util.Objects.requireNonNull(cachedPermissions, "cachedPermissions must not be null");
    }

    /**
     * Whether tab completion offers what {@code node} gates: the console always, a player through
     * {@link #setCachedPermissions} (never waits on the API; the command itself checks for real).
     */
    public boolean holds(CommandSender sender, String node) {
        return !(sender instanceof Player player) || player.hasPermission(node) || cachedPermissions.test(player, node);
    }

    // ===== /pay =====

    /** {@code /pay <player> <amount> [coins|gems]}. Main thread. */
    public void pay(Player sender, String targetName, String amountText, BalanceCurrency currency) {
        OptionalLong parsed = AmountParser.parse(amountText);
        if (parsed.isEmpty()) {
            send(sender, "pay-invalid-amount");
            return;
        }
        long amount = parsed.getAsLong();
        Integer senderId = cachedUserId(sender);
        if (senderId == null) {
            send(sender, "account-not-loaded");
            return;
        }
        if (targetName.equalsIgnoreCase(sender.getName())) {
            send(sender, "pay-self");
            return;
        }
        UUID uuid = sender.getUniqueId();
        Duration wait = clientCooldownLeft(uuid);
        if (wait != null) {
            send(sender, "pay-cooldown", "when", CurrencyFormat.duration(wait));
            return;
        }
        if (!inFlight.add(uuid)) {
            send(sender, "pay-busy");
            return;
        }

        CompletableFuture<Target> target = resolve(sender, targetName); // Bukkit part runs here, on the main thread
        requireAll(sender, PAY_NODE, currency == BalanceCurrency.GEMS ? PAY_GEMS_NODE : null)
            .thenCompose(ignored -> target)
            .thenCompose(recipient -> {
                if (recipient == null) {
                    throw new Refusal("pay-unknown-player", "player", targetName);
                }
                if (recipient.userId() == senderId) {
                    throw new Refusal("pay-self");
                }
                return permissions.has(sender, PAY_BYPASS_NODE)
                    .thenCompose(bypass -> currencyApi.transfer(senderId, recipient.userId(), currency, amount, bypass == PermissionDecision.ALLOWED));
            })
            .whenComplete((outcome, ex) -> mainThread.execute(() -> {
                inFlight.remove(uuid);
                if (ex == null && outcome != null && outcome.completed()) {
                    lastPaidAt.put(uuid, clock.instant());
                }
                if (!sender.isOnline()) {
                    return;
                }
                if (ex != null) {
                    renderPayError(sender, ex, currency, targetName);
                } else {
                    renderTransfer(sender, outcome);
                }
            }));
    }

    /** {@code /pay confirm [id]} - without an id, the sender's latest prompt. Main thread. */
    public void confirm(Player sender, String pendingId) {
        String id = pendingId != null ? pendingId : lastPendingId.get(sender.getUniqueId());
        Integer senderId = cachedUserId(sender);
        if (id == null) {
            send(sender, "pay-no-pending");
            return;
        }
        if (senderId == null) {
            send(sender, "account-not-loaded");
            return;
        }
        UUID uuid = sender.getUniqueId();
        if (!inFlight.add(uuid)) {
            send(sender, "pay-busy");
            return;
        }
        // The prompt this plugin still shows as open: a replayed answer for it is our own retry
        // (the first attempt went through, its answer got lost) and still news to the player.
        // For any other id (a stale [Confirm] clicked again, /pay confirm <old id>) the API only
        // replays the stored result - nothing was paid now.
        boolean openHere = id.equals(lastPendingId.get(uuid));
        requireAll(sender, PAY_NODE, null)
            .thenCompose(ignored -> permissions.has(sender, PAY_BYPASS_NODE))
            .thenCompose(bypass -> currencyApi.confirmTransfer(senderId, id, bypass == PermissionDecision.ALLOWED))
            .whenComplete((outcome, ex) -> mainThread.execute(() -> {
                inFlight.remove(uuid);
                if (ex == null || isPendingGone(ex)) {
                    forgetPrompt(uuid, id);
                }
                boolean alreadySent = ex == null && outcome != null && outcome.completed() && outcome.replayed() && !openHere;
                if (ex == null && outcome != null && outcome.completed() && !alreadySent) {
                    lastPaidAt.put(uuid, clock.instant());
                }
                if (!sender.isOnline()) {
                    return;
                }
                if (ex != null) {
                    renderPayError(sender, ex, BalanceCurrency.COINS, null);
                } else if (alreadySent) {
                    if (outcome.senderBalances() != null) {
                        updateCache(uuid, outcome.senderBalances());
                    }
                    send(sender, "pay-already-sent");
                } else {
                    renderTransfer(sender, outcome);
                }
            }));
    }

    /** {@code /pay cancel [id]}. Main thread. */
    public void cancel(Player sender, String pendingId) {
        String id = pendingId != null ? pendingId : lastPendingId.get(sender.getUniqueId());
        Integer senderId = cachedUserId(sender);
        if (id == null) {
            send(sender, "pay-no-pending");
            return;
        }
        if (senderId == null) {
            send(sender, "account-not-loaded");
            return;
        }
        UUID uuid = sender.getUniqueId();
        currencyApi.cancelTransfer(senderId, id).whenComplete((pending, ex) -> mainThread.execute(() -> {
            if (ex == null || isPendingGone(ex)) {
                forgetPrompt(uuid, id);
            }
            if (!sender.isOnline()) {
                return;
            }
            if (ex != null) {
                renderError(sender, ex, BalanceCurrency.COINS, null);
            } else if (pending != null && "Expired".equalsIgnoreCase(pending.status())) {
                send(sender, "pay-expired");
            } else {
                send(sender, "pay-cancelled", "player", pending != null ? pending.recipientUsername() : "?");
            }
        }));
    }

    // ===== /balance =====

    /** {@code /balance} (own) or {@code /balance <player>}. Always read from the API, never the cache. Main thread. */
    public void balance(CommandSender viewer, String targetName) {
        if (targetName == null) {
            if (!(viewer instanceof Player player)) {
                viewer.sendMessage(settings.template("pay-usage"));
                return;
            }
            Integer userId = cachedUserId(player);
            if (userId == null) {
                send(viewer, "account-not-loaded");
                return;
            }
            requireAll(viewer, BALANCE_NODE, null)
                .thenCompose(ignored -> currencyApi.getBalances(userId))
                .whenComplete((balances, ex) -> mainThread.execute(() -> {
                    if (ex != null) {
                        renderError(viewer, ex, BalanceCurrency.COINS, null);
                        return;
                    }
                    updateCache(player.getUniqueId(), balances);
                    viewer.sendMessage(balanceSelfLine(settings, balances.coins(), balances.gems()));
                }));
            return;
        }

        CompletableFuture<Target> target = resolve(viewer, targetName);
        requireAll(viewer, BALANCE_OTHERS_NODE, null)
            .thenCompose(ignored -> target)
            .thenCompose(found -> {
                if (found == null) {
                    throw new Refusal("pay-unknown-player", "player", targetName);
                }
                return currencyApi.getBalances(found.userId()).thenApply(b -> Map.entry(found, b));
            })
            .whenComplete((result, ex) -> mainThread.execute(() -> {
                if (ex != null) {
                    renderError(viewer, ex, BalanceCurrency.COINS, targetName);
                    return;
                }
                Balances b = result.getValue();
                send(viewer, "balance-other", "player", result.getKey().username(),
                    "coins", CurrencyFormat.amount(b.coins()), "gems", CurrencyFormat.amount(b.gems()));
            }));
    }

    /**
     * The join message's balance line: the same {@code balance-self} line as {@code /balance},
     * read fresh from the API (the join lookup can predate an offline payment or the salary paid
     * on join). If the API doesn't answer, the join lookup's numbers in the same format. Never
     * fails; may complete off the main thread.
     */
    public CompletableFuture<String> joinBalanceLine(UUID uuid, int userId, long fallbackCoins, long fallbackGems) {
        return currencyApi.getBalances(userId)
            .thenApply(balances -> {
                if (balances == null) {
                    return balanceSelfLine(settings, fallbackCoins, fallbackGems);
                }
                updateCache(uuid, balances);
                return balanceSelfLine(settings, balances.coins(), balances.gems());
            })
            .exceptionally(ex -> {
                LOGGER.fine("Join balance read failed, showing the join lookup's numbers: " + ex);
                return balanceSelfLine(settings, fallbackCoins, fallbackGems);
            });
    }

    /** {@code /balance}'s own line for {@code coins} and {@code gems}. */
    public static String balanceSelfLine(CurrencySettings settings, long coins, long gems) {
        return settings.message("balance-self", "coins", CurrencyFormat.amount(coins), "gems", CurrencyFormat.amount(gems));
    }

    // ===== /baltop =====

    /** {@code /baltop [coins|gems] [page]}. Main thread. */
    public void baltop(CommandSender viewer, BalanceCurrency currency, int page) {
        requireAll(viewer, BALTOP_NODE, null)
            .thenCompose(ignored -> currencyApi.getLeaderboard(currency, page, settings.baltopPageSize()))
            .whenComplete((board, ex) -> mainThread.execute(() -> {
                if (ex != null) {
                    renderError(viewer, ex, currency, null);
                    return;
                }
                renderLeaderboard(viewer, board, currency);
            }));
    }

    private void renderLeaderboard(CommandSender viewer, LeaderboardPage board, BalanceCurrency currency) {
        if (board.entries().isEmpty()) {
            send(viewer, "baltop-empty");
            return;
        }
        send(viewer, "baltop-header", "currency", CurrencyFormat.name(currency, 2), "page", String.valueOf(board.page()),
            "pages", String.valueOf(board.totalPages()));
        for (LeaderboardEntry entry : board.entries()) {
            send(viewer, "baltop-line", "rank", String.valueOf(entry.rank()), "player", entry.username(),
                "amount", CurrencyFormat.amount(entry.balance()));
        }
    }

    // ===== /transactions and /knk user <player> history =====

    /** {@code /transactions [player] [coins|gems|xp] [page]}. Main thread. */
    public void transactions(CommandSender viewer, String targetName, BalanceCurrency filter, int page) {
        if (targetName == null || (viewer instanceof Player self && targetName.equalsIgnoreCase(self.getName()))) {
            if (!(viewer instanceof Player player)) {
                viewer.sendMessage(settings.template("pay-usage"));
                return;
            }
            Integer userId = cachedUserId(player);
            if (userId == null) {
                send(viewer, "account-not-loaded");
                return;
            }
            requireAll(viewer, TRANSACTIONS_NODE, null)
                .thenCompose(ignored -> currencyApi.getTransactions(userId, filter, page, settings.transactionsPageSize()))
                .whenComplete((ledger, ex) -> mainThread.execute(() -> renderLedger(viewer, player.getName(), filter, ledger, ex)));
            return;
        }
        CompletableFuture<Target> target = resolve(viewer, targetName);
        requireAll(viewer, TRANSACTIONS_OTHERS_NODE, null)
            .thenCompose(ignored -> target)
            .thenCompose(found -> {
                if (found == null) {
                    throw new Refusal("pay-unknown-player", "player", targetName);
                }
                return currencyApi.getTransactions(found.userId(), filter, page, settings.transactionsPageSize())
                    .thenApply(ledger -> Map.entry(found, ledger));
            })
            .whenComplete((result, ex) -> mainThread.execute(() ->
                renderLedger(viewer, result != null ? result.getKey().username() : targetName, filter,
                    result != null ? result.getValue() : null, ex)));
    }

    /**
     * {@code /knk user <player> history [coins|gems|xp] [page]}: gated on {@link #USER_HISTORY_NODE}
     * through KnkPermissible (in-house grants incl. wildcards; ops pass), like /knk currency - not
     * the Bukkit node, which neither ops nor in-house grants hold. Main thread.
     */
    public void staffUserHistory(CommandSender viewer, String targetName, BalanceCurrency filter, int page) {
        staffHistory(viewer, USER_HISTORY_NODE, targetName, filter, page);
    }

    private void renderLedger(CommandSender viewer, String username, BalanceCurrency filter, LedgerPage ledger, Throwable ex) {
        renderLedger(viewer, username, filter, ledger, ex, false);
    }

    /** {@code staff}: each line ends with its transaction id, which suggests {@code /knk currency reverse <id>} when clicked. */
    private void renderLedger(CommandSender viewer, String username, BalanceCurrency filter, LedgerPage ledger, Throwable ex,
                              boolean staff) {
        if (ex != null) {
            renderError(viewer, ex, BalanceCurrency.COINS, username);
            return;
        }
        if (ledger == null || ledger.items().isEmpty()) {
            send(viewer, "transactions-empty");
            return;
        }
        send(viewer, "transactions-header", "player", username,
            "filter", filter == null ? "" : CurrencyFormat.name(filter, 2) + " ",
            "page", String.valueOf(ledger.page()), "pages", String.valueOf(ledger.totalPages()));
        for (LedgerLine line : ledger.items()) {
            if (staff && line.publicId() != null) {
                viewer.sendMessage(LEGACY.deserialize(ledgerLine(line) + " ")
                    .append(LEGACY.deserialize("§8#" + line.publicId())
                        .clickEvent(ClickEvent.suggestCommand("/knk currency reverse " + line.publicId() + " "))
                        .hoverEvent(HoverEvent.showText(Component.text("Transaction " + line.publicId() + " - click to reverse it")))));
            } else {
                viewer.sendMessage(ledgerLine(line));
            }
        }
    }

    String ledgerLine(LedgerLine line) {
        String color = line.amount() > 0 ? "§a" : line.amount() < 0 ? "§c" : "§7";
        // Built from numbers and fixed words only, so it may keep its colour (fill() strips § from values).
        String change = color + CurrencyFormat.signed(line.amount()) + " " + CurrencyFormat.name(line.currency(), line.amount());
        return CurrencySettings.fill(settings.template("transactions-line").replace("{change}", change),
            "time", line.createdAt() != null ? TIME.format(line.createdAt()) : "",
            "what", describe(line),
            "balance", CurrencyFormat.amount(line.balanceAfter()));
    }

    /** "to Bob" / "from Bob" for transfers, else the ledger's reason (shortened). */
    static String describe(LedgerLine line) {
        if ("Transfer".equalsIgnoreCase(line.kind())) {
            String other = line.counterpartyUsername() != null ? line.counterpartyUsername() : "a player";
            return (line.amount() < 0 ? "to " : "from ") + other;
        }
        String reason = line.reason() != null && !line.reason().isBlank() ? line.reason().trim() : line.reasonCode();
        if (reason == null) {
            reason = "";
        }
        return reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH - 3) + "..." : reason;
    }

    // ===== Staff: /knk currency (Phase 4) =====

    /** {@code /knk currency history <player> [coins|gems|xp] [page]}. Main thread. */
    public void staffHistory(CommandSender viewer, String targetName, BalanceCurrency filter, int page) {
        staffHistory(viewer, CURRENCY_HISTORY_NODE, targetName, filter, page);
    }

    private void staffHistory(CommandSender viewer, String node, String targetName, BalanceCurrency filter, int page) {
        CompletableFuture<Target> target = resolve(viewer, targetName);
        requireAll(viewer, node, null)
            .thenCompose(ignored -> target)
            .thenCompose(found -> {
                if (found == null) {
                    throw new Refusal("pay-unknown-player", "player", targetName);
                }
                return currencyApi.getTransactions(found.userId(), filter, page, settings.transactionsPageSize())
                    .thenApply(ledger -> Map.entry(found, ledger));
            })
            .whenComplete((result, ex) -> mainThread.execute(() ->
                renderLedger(viewer, result != null ? result.getKey().username() : targetName, filter,
                    result != null ? result.getValue() : null, ex, true)));
    }

    /**
     * {@code /knk currency reverse <txId> [--partial] <reason...>}: the server posts the mirror of
     * the transaction once, never below zero (unless partial). Main thread.
     */
    public void staffReverse(CommandSender viewer, String publicId, String note, boolean allowPartial) {
        String trimmed = note == null ? "" : note.trim();
        if (trimmed.length() < MIN_STAFF_NOTE_LENGTH) {
            send(viewer, "reverse-note-short", "min", String.valueOf(MIN_STAFF_NOTE_LENGTH));
            return;
        }
        String id = publicId == null ? "" : publicId.trim().replaceFirst("^(?i)(tx|#)", "").toUpperCase(java.util.Locale.ROOT);
        Integer actor = staffUserId(viewer);
        if (actor == null) {
            send(viewer, "account-not-loaded");
            return;
        }
        requireAll(viewer, CURRENCY_REVERSE_NODE, null)
            .thenCompose(ignored -> currencyApi.reverseTransaction(actor, id, trimmed, allowPartial))
            .whenComplete((outcome, ex) -> mainThread.execute(() -> {
                if (ex != null) {
                    AlreadyReversed already = alreadyReversed(ex);
                    if (already != null) {
                        send(viewer, "reverse-already", "tx", id, "date", already.reversedAt() != null ? DATE.format(already.reversedAt()) : "?",
                            "name", already.reversedBy(), "reversal", already.reversalPublicId());
                    } else {
                        renderError(viewer, ex, BalanceCurrency.COINS, null);
                    }
                    return;
                }
                renderReversal(viewer, outcome);
            }));
    }

    private void renderReversal(CommandSender viewer, ReversalOutcome outcome) {
        if (outcome == null) {
            send(viewer, "error-generic");
            return;
        }
        outcome.balances().forEach(this::updateCacheByUserId);
        if (outcome.replayed()) {
            // A repeat of an earlier reversal: nothing moved now, so no success line and no legs.
            send(viewer, "reverse-replayed", "tx", outcome.reversedPublicId(), "reversal", outcome.reversalPublicId());
            return;
        }
        send(viewer, outcome.partial() ? "reverse-partial" : "reverse-done",
            "tx", outcome.reversedPublicId(), "reversal", outcome.reversalPublicId());
        for (ReversalOutcome.Leg leg : outcome.legs()) {
            String color = leg.amount() > 0 ? "§a" : leg.amount() < 0 ? "§c" : "§7";
            String change = color + CurrencyFormat.signed(leg.amount()) + " " + CurrencyFormat.name(leg.currency(), leg.amount());
            viewer.sendMessage(CurrencySettings.fill(settings.template("reverse-leg").replace("{change}", change),
                "user", "#" + leg.userId(), "balance", CurrencyFormat.amount(leg.balanceAfter())));
        }
    }

    /** {@code /knk currency lock <player> <reason...>}: the player can neither send nor receive /pay. Main thread. */
    public void staffLock(CommandSender viewer, String targetName, String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            send(viewer, "currency-admin-usage");
            return;
        }
        changeLock(viewer, targetName, (actor, target) -> currencyApi.lockTransfers(actor, target.userId(), trimmed));
    }

    /** {@code /knk currency unlock <player>}. Main thread. */
    public void staffUnlock(CommandSender viewer, String targetName) {
        changeLock(viewer, targetName, (actor, target) -> currencyApi.unlockTransfers(actor, target.userId()));
    }

    private void changeLock(CommandSender viewer, String targetName,
                            java.util.function.BiFunction<Integer, Target, CompletableFuture<TransferLock>> call) {
        Integer actor = staffUserId(viewer);
        if (actor == null) {
            send(viewer, "account-not-loaded");
            return;
        }
        CompletableFuture<Target> target = resolve(viewer, targetName);
        requireAll(viewer, CURRENCY_LOCK_NODE, null)
            .thenCompose(ignored -> target)
            .thenCompose(found -> {
                if (found == null) {
                    throw new Refusal("pay-unknown-player", "player", targetName);
                }
                return call.apply(actor, found);
            })
            .whenComplete((lock, ex) -> mainThread.execute(() -> {
                if (ex != null) {
                    renderError(viewer, ex, BalanceCurrency.COINS, targetName);
                    return;
                }
                String name = lock != null && lock.username() != null ? lock.username() : targetName;
                if (lock != null && lock.locked()) {
                    send(viewer, "lock-done", "player", name, "reason", lock.reason());
                } else {
                    send(viewer, "unlock-done", "player", name);
                }
            }));
    }

    // ===== Staff: currency alerts (Phase 5) =====

    /** {@code /knk currency alerts [all] [page]}: open alerts (or all), newest first; open ones offer a clickable ack. Main thread. */
    public void staffAlerts(CommandSender viewer, boolean includeAcknowledged, int page) {
        requireAll(viewer, CURRENCY_ALERTS_NODE, null)
            .thenCompose(ignored -> currencyApi.getAlerts(includeAcknowledged, page, settings.transactionsPageSize()))
            .whenComplete((alerts, ex) -> mainThread.execute(() -> renderAlerts(viewer, alerts, ex, includeAcknowledged)));
    }

    /** {@code /knk currency alerts ack <id>}: marks the alert handled by this staff member (in-game only). Main thread. */
    public void staffAcknowledgeAlert(CommandSender viewer, String idText) {
        long id;
        try {
            id = Long.parseLong(idText == null ? "" : idText.trim().replaceFirst("^#", ""));
        } catch (NumberFormatException ex) {
            send(viewer, "alert-invalid-id");
            return;
        }
        Integer actor = staffUserId(viewer);
        if (actor == null) {
            send(viewer, "account-not-loaded");
            return;
        }
        if (actor == 0) {
            // The API records who acknowledged an alert; the console has no account to name.
            send(viewer, "alert-ack-console");
            return;
        }
        requireAll(viewer, CURRENCY_ALERTS_NODE, null)
            .thenCompose(ignored -> currencyApi.acknowledgeAlert(actor, id))
            .whenComplete((alert, ex) -> mainThread.execute(() -> {
                if (ex != null) {
                    CurrencyException error = CurrencyException.find(ex);
                    if (error != null && error.httpStatus() == 404) {
                        send(viewer, "alert-not-found", "id", String.valueOf(id));
                    } else {
                        renderError(viewer, ex, BalanceCurrency.COINS, null);
                    }
                    return;
                }
                send(viewer, "alert-acked", "id", String.valueOf(id), "rule", alert != null ? alert.rule() : "");
            }));
    }

    private void renderAlerts(CommandSender viewer, CurrencyAlertPage alerts, Throwable ex, boolean includeAcknowledged) {
        if (ex != null) {
            renderError(viewer, ex, BalanceCurrency.COINS, null);
            return;
        }
        if (alerts == null || alerts.items().isEmpty()) {
            send(viewer, "alerts-empty");
            return;
        }
        send(viewer, "alerts-header", "status", includeAcknowledged ? "all" : "open", "page", String.valueOf(alerts.page()),
            "pages", String.valueOf(alerts.totalPages()), "open", String.valueOf(alerts.openCount()));
        for (CurrencyAlert alert : alerts.items()) {
            String line = alertLine(alert);
            if (alert.acknowledged()) {
                viewer.sendMessage(line + settings.message("alerts-acked-suffix", "by",
                    alert.ackedByUsername() != null ? alert.ackedByUsername() : "staff"));
            } else if (viewer instanceof Player) {
                viewer.sendMessage(LEGACY.deserialize(line + " ")
                    .append(LEGACY.deserialize("§a[ack]")
                        .clickEvent(ClickEvent.suggestCommand("/knk currency alerts ack " + alert.id()))
                        .hoverEvent(HoverEvent.showText(Component.text("Mark alert #" + alert.id() + " as handled")))));
            } else {
                viewer.sendMessage(line);
            }
        }
    }

    String alertLine(CurrencyAlert alert) {
        return settings.message("alerts-line",
            "id", String.valueOf(alert.id()),
            "time", alert.createdAt() != null ? TIME.format(alert.createdAt()) : "",
            "severity", alert.severity(),
            "rule", alert.rule(),
            "player", alert.username() != null ? alert.username() + ": " : "",
            "summary", alert.summary());
    }

    /** The staff member's user id for X-Acting-User-Id; 0 for the console (the API records the game server). Null: not loaded. */
    private Integer staffUserId(CommandSender viewer) {
        if (viewer instanceof Player player) {
            return cachedUserId(player);
        }
        return 0;
    }

    private void updateCacheByUserId(Integer userId, Balances balances) {
        if (userId == null || balances == null) {
            return;
        }
        for (Player online : visiblePlayers.online()) {
            if (userId.equals(cachedUserId(online))) {
                updateCache(online.getUniqueId(), balances);
            }
        }
    }

    // ===== Rendering =====

    private void renderTransfer(CommandSender sender, TransferOutcome outcome) {
        if (outcome == null) {
            send(sender, "pay-unknown");
            return;
        }
        if (sender instanceof Player player && outcome.senderBalances() != null) {
            updateCache(player.getUniqueId(), outcome.senderBalances());
        }
        if (outcome.completed()) {
            long balance = outcome.senderBalances() != null ? outcome.senderBalances().of(outcome.currency()) : 0;
            send(sender, "pay-sent",
                "amount", CurrencyFormat.amount(outcome.amount()),
                "currency", CurrencyFormat.name(outcome.currency(), outcome.amount()),
                "player", outcome.recipientUsername(),
                "balance", CurrencyFormat.amount(balance));
            return;
        }
        PendingTransfer pending = outcome.pending();
        if (pending == null || !pending.isOpen()) {
            send(sender, pending != null && "Expired".equalsIgnoreCase(pending.status()) ? "pay-expired" : "pay-closed");
            return;
        }
        if (sender instanceof Player player) {
            openPrompt(player, pending, outcome.recipientUsername());
        }
        sender.sendMessage(confirmPrompt(outcome, pending));
    }

    // ===== Open confirmation prompts =====

    /**
     * Makes {@code pending} the sender's open prompt (replacing any earlier one, whose notice is
     * dropped) and schedules the expiry notice for when the API's window has passed. Main thread.
     */
    private void openPrompt(Player sender, PendingTransfer pending, String recipientFallback) {
        UUID uuid = sender.getUniqueId();
        String id = pending.publicId();
        lastPendingId.put(uuid, id);
        Runnable previous = expiryNotices.remove(uuid);
        if (previous != null) {
            previous.run();
        }
        int seconds = pending.expiresInSeconds() > 0 ? pending.expiresInSeconds() : DEFAULT_CONFIRM_SECONDS;
        String recipient = pending.recipientUsername() != null ? pending.recipientUsername() : recipientFallback;
        scheduleExpiryNotice(sender, id, pending, recipient, Duration.ofSeconds(seconds).plus(EXPIRY_GRACE));
    }

    private void scheduleExpiryNotice(Player sender, String id, PendingTransfer pending, String recipient, Duration delay) {
        UUID uuid = sender.getUniqueId();
        expiryNotices.put(uuid, scheduler.runLater(delay, () -> {
            if (!id.equals(lastPendingId.get(uuid))) {
                return; // confirmed, cancelled or replaced by a newer prompt meanwhile
            }
            if (inFlight.contains(uuid)) {
                // A confirm (or a new /pay) is on its way: its answer decides; look again shortly.
                scheduleExpiryNotice(sender, id, pending, recipient, EXPIRY_GRACE);
                return;
            }
            expiryNotices.remove(uuid);
            lastPendingId.remove(uuid, id);
            if (sender.isOnline()) {
                send(sender, "pay-confirm-expired",
                    "amount", CurrencyFormat.amount(pending.amount()),
                    "currency", CurrencyFormat.name(pending.currency(), pending.amount()),
                    "player", recipient != null ? recipient : "?");
            }
        }));
    }

    /** The sender's prompt {@code id} is settled (confirmed, cancelled, expired at the API): no notice for it. Main thread. */
    private void forgetPrompt(UUID uuid, String id) {
        if (lastPendingId.remove(uuid, id)) {
            Runnable notice = expiryNotices.remove(uuid);
            if (notice != null) {
                notice.run();
            }
        }
    }

    /** Drops a leaving player's open prompt and its expiry notice. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastPendingId.remove(uuid);
        Runnable notice = expiryNotices.remove(uuid);
        if (notice != null) {
            notice.run();
        }
    }

    /** "Send 150,000 coins to Bob? [Confirm] [Cancel] (expires in 60s)" with clickable buttons. */
    Component confirmPrompt(TransferOutcome outcome, PendingTransfer pending) {
        String text = settings.message("pay-confirm",
            "amount", CurrencyFormat.amount(pending.amount()),
            "currency", CurrencyFormat.name(pending.currency(), pending.amount()),
            "player", pending.recipientUsername() != null ? pending.recipientUsername() : outcome.recipientUsername(),
            "seconds", String.valueOf(pending.expiresInSeconds()));
        Component prompt = Component.empty();
        Matcher matcher = BUTTON.matcher(text);
        int last = 0;
        while (matcher.find()) {
            prompt = prompt.append(LEGACY.deserialize(text.substring(last, matcher.start())));
            boolean confirm = "confirm".equals(matcher.group(1));
            Component button = LEGACY.deserialize(settings.template(confirm ? "pay-confirm-button" : "pay-cancel-button"))
                .clickEvent(ClickEvent.runCommand("/pay " + (confirm ? "confirm " : "cancel ") + pending.publicId()))
                .hoverEvent(HoverEvent.showText(Component.text(confirm ? "Send this payment" : "Don't send it")));
            prompt = prompt.append(button);
            last = matcher.end();
        }
        return prompt.append(LEGACY.deserialize(text.substring(last)));
    }

    /** Turns a failed call into the player's message; logs anything that isn't a refusal. */
    void renderError(CommandSender sender, Throwable ex, BalanceCurrency currency, String targetName) {
        Refusal refusal = findRefusal(ex);
        if (refusal != null) {
            send(sender, refusal.key, refusal.placeholders);
            return;
        }
        CurrencyException currencyException = CurrencyException.find(ex);
        if (currencyException == null) {
            LOGGER.warning("Currency request failed: " + ex);
            send(sender, isUnreachable(ex) ? SERVICE_UNAVAILABLE : "error-generic");
            return;
        }
        CurrencyError error = currencyException.error();
        String name = CurrencyFormat.name(currency, 2);
        String player = targetName != null ? targetName : "That player";
        switch (error.code()) {
            case CurrencyError.UNKNOWN_OUTCOME -> send(sender, "pay-unknown");
            case CurrencyError.INSUFFICIENT_FUNDS -> send(sender, "pay-insufficient",
                "balance", CurrencyFormat.amount(orZero(error.detailLong("balance"))), "currency", name);
            case CurrencyError.DAILY_CAP_EXCEEDED -> send(sender, "pay-cap",
                "remaining", CurrencyFormat.amount(orZero(error.detailLong("remaining"))), "currency", name,
                "when", until(error.detailString("resetsAt")));
            case CurrencyError.RECIPIENT_DAILY_CAP_EXCEEDED -> send(sender, "pay-recipient-cap", "player", player, "currency", name);
            case CurrencyError.NEW_ACCOUNT_RESTRICTED -> send(sender, "pay-new-account",
                "hours", String.valueOf(orZero(error.detailLong("hours"))),
                "title", error.detailString("title") != null ? error.detailString("title") : "the required title", "currency", name);
            case CurrencyError.NOT_TRANSFERABLE -> send(sender, "pay-not-transferable", "Currency", capitalize(name));
            case CurrencyError.TRANSFERS_DISABLED -> send(sender, "pay-disabled", "Currency", capitalize(CurrencyFormat.name(currency, 1)));
            case CurrencyError.SELF_TRANSFER -> send(sender, "pay-self");
            case CurrencyError.RECIPIENT_NOT_FOUND, CurrencyError.USER_NOT_FOUND -> send(sender, "pay-unknown-player", "player", player);
            case CurrencyError.ACCOUNT_LOCKED -> send(sender,
                "recipient".equalsIgnoreCase(error.detailString("side")) ? "pay-recipient-locked" : "pay-locked", "player", player);
            case CurrencyError.BALANCE_CAP_EXCEEDED -> send(sender, "pay-recipient-full", "player", player, "currency", name);
            case CurrencyError.COOLDOWN_ACTIVE -> send(sender, "pay-cooldown",
                "when", CurrencyFormat.duration(Duration.ofSeconds(Math.max(1, orZero(error.detailLong("seconds"))))));
            case CurrencyError.AMOUNT_OUT_OF_RANGE -> {
                Long min = error.detailLong("min");
                Long max = error.detailLong("max");
                if (min != null && max != null) {
                    send(sender, "pay-amount-range", "min", CurrencyFormat.amount(min), "max", CurrencyFormat.amount(max), "currency", name);
                } else {
                    send(sender, "pay-invalid-amount");
                }
            }
            case CurrencyError.PENDING_TRANSFER_EXPIRED -> send(sender, "pay-expired");
            case CurrencyError.PENDING_TRANSFER_CLOSED -> send(sender, "pay-closed");
            case CurrencyError.PENDING_TRANSFER_NOT_FOUND -> send(sender, "pay-no-pending");
            default -> {
                if (currencyException.httpStatus() == 403 || currencyException.httpStatus() == 401) {
                    LOGGER.warning("knk-web-api refused a currency request (" + currencyException.httpStatus()
                        + "): check api.auth.api-key against the API's Security:PluginApiKey. " + error.plainMessage());
                    send(sender, "error-generic");
                } else if (currencyException.httpStatus() >= 400 && currencyException.httpStatus() < 500 && !error.plainMessage().isBlank()) {
                    sender.sendMessage("§c" + error.plainMessage().replace("§", ""));
                } else {
                    send(sender, "error-generic");
                }
            }
        }
    }

    /** {@link #renderError} for /pay and /pay confirm: "can't be reached" also says nothing was paid. */
    private void renderPayError(CommandSender sender, Throwable ex, BalanceCurrency currency, String targetName) {
        Refusal refusal = findRefusal(ex);
        if ((refusal != null && SERVICE_UNAVAILABLE.equals(refusal.key)) || (refusal == null && CurrencyException.find(ex) == null && isUnreachable(ex))) {
            send(sender, PAY_SERVICE_UNAVAILABLE);
            return;
        }
        renderError(sender, ex, currency, targetName);
    }

    /** A request that never reached the API (connection refused, timeout...). */
    static boolean isUnreachable(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof java.io.IOException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private String until(String isoInstant) {
        Instant at = net.knightsandkings.knk.api.mapper.CurrencyMapper.instant(isoInstant);
        return at == null ? "24h" : CurrencyFormat.duration(Duration.between(clock.instant(), at));
    }

    // ===== Helpers =====

    /**
     * The player {@code name} names, as {@code viewer} may know them: an online player the viewer
     * can see is taken from the cache; anyone else (offline, or vanished to the viewer) is looked
     * up through the API the same way. Call on the main thread; completes with null if unknown.
     */
    CompletableFuture<Target> resolve(CommandSender viewer, String name) {
        Player online = visiblePlayers.find(viewer, name);
        if (online != null) {
            Integer id = cachedUserId(online);
            if (id != null) {
                return CompletableFuture.completedFuture(new Target(id, online.getName()));
            }
        }
        return usersDataAccess.getByUsernameAsync(name).thenApply(result -> {
            if (result != null && result.status() == net.knightsandkings.knk.core.dataaccess.FetchStatus.ERROR) {
                throw new Refusal(SERVICE_UNAVAILABLE); // not "no such player": the API couldn't be asked
            }
            if (result == null || !result.isSuccess() || result.value().isEmpty() || result.value().get().id() == null) {
                return null;
            }
            UserSummary user = result.value().get();
            return new Target(user.id(), user.username() != null ? user.username() : name);
        });
    }

    /**
     * Completes when {@code viewer} holds every given node (null entries skipped); fails with a
     * no-permission refusal on a real "no", or a service-unavailable one when a check couldn't be
     * made (the API is down) - still refused, but not blamed on the player's rights.
     */
    private CompletableFuture<Void> requireAll(CommandSender viewer, String node, String extraNode) {
        if (!(viewer instanceof Player player)) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<PermissionDecision> first = permissions.has(player, node);
        CompletableFuture<PermissionDecision> both = extraNode == null
            ? first
            : first.thenCompose(decision -> decision == PermissionDecision.ALLOWED
                ? permissions.has(player, extraNode) : CompletableFuture.completedFuture(decision));
        return both.thenAccept(decision -> {
            if (decision == PermissionDecision.UNAVAILABLE) {
                throw new Refusal(SERVICE_UNAVAILABLE);
            }
            if (decision != PermissionDecision.ALLOWED) {
                throw new Refusal("no-permission");
            }
        });
    }

    private Duration clientCooldownLeft(UUID uuid) {
        Instant last = lastPaidAt.get(uuid);
        if (last == null || settings.clientCooldownSeconds() <= 0) {
            return null;
        }
        Duration left = Duration.between(clock.instant(), last.plusSeconds(settings.clientCooldownSeconds()));
        return left.isNegative() || left.isZero() ? null : left;
    }

    private Integer cachedUserId(Player player) {
        return userCache.getStale(player.getUniqueId()).map(UserSummary::id).orElse(null);
    }

    private void updateCache(UUID uuid, Balances balances) {
        if (balances != null) {
            userCache.updateBalances(uuid, balances.coins(), balances.gems());
        }
    }

    private static AlreadyReversed alreadyReversed(Throwable ex) {
        CurrencyException error = CurrencyException.find(ex);
        return error == null ? null : AlreadyReversed.from(error.error());
    }

    private static boolean isPendingGone(Throwable ex) {
        CurrencyException error = CurrencyException.find(ex);
        return error != null && (error.error().is(CurrencyError.PENDING_TRANSFER_EXPIRED)
            || error.error().is(CurrencyError.PENDING_TRANSFER_CLOSED)
            || error.error().is(CurrencyError.PENDING_TRANSFER_NOT_FOUND));
    }

    private void send(CommandSender sender, String key, String... placeholders) {
        sender.sendMessage(settings.message(key, placeholders));
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }

    private static String capitalize(String value) {
        return value == null || value.isEmpty() ? "" : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static Refusal findRefusal(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof Refusal refusal) {
                return refusal;
            }
            cause = cause.getCause();
        }
        return null;
    }

    /** A request stopped in the plugin before (or instead of) reaching the API, with the message to show. */
    static final class Refusal extends RuntimeException {
        final String key;
        final String[] placeholders;

        Refusal(String key, String... placeholders) {
            super(key, null, false, false);
            this.key = key;
            this.placeholders = placeholders;
        }
    }
}
