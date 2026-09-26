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

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.domain.currency.AmountParser;
import net.knightsandkings.knk.core.domain.currency.Balances;
import net.knightsandkings.knk.core.domain.currency.CurrencyError;
import net.knightsandkings.knk.core.domain.currency.CurrencyException;
import net.knightsandkings.knk.core.domain.currency.CurrencyFormat;
import net.knightsandkings.knk.core.domain.currency.LeaderboardEntry;
import net.knightsandkings.knk.core.domain.currency.LeaderboardPage;
import net.knightsandkings.knk.core.domain.currency.LedgerLine;
import net.knightsandkings.knk.core.domain.currency.LedgerPage;
import net.knightsandkings.knk.core.domain.currency.PendingTransfer;
import net.knightsandkings.knk.core.domain.currency.TransferOutcome;
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
 * {@code /knk user <player> history}. Parses nothing and decides nothing that matters: knk-web-api
 * applies every rule and returns every number; this class checks the in-game nodes, resolves
 * names, calls {@link CurrencyApi} off the main thread and renders the answer on it.
 * <p>
 * Name lookups are vanish-safe ({@link VisiblePlayers}): a player the sender can't see is looked up
 * like an offline one, and no answer says whether the other player is online.
 */
public class PlayerCurrencyService {
    private static final Logger LOGGER = Logger.getLogger(PlayerCurrencyService.class.getName());

    public static final String PAY_NODE = "knk.pay";
    public static final String PAY_GEMS_NODE = "knk.pay.gems";
    public static final String PAY_BYPASS_NODE = "knk.pay.bypass";
    public static final String BALANCE_NODE = "knk.balance";
    public static final String BALANCE_OTHERS_NODE = "knk.balance.others";
    public static final String BALTOP_NODE = "knk.baltop";
    public static final String TRANSACTIONS_NODE = "knk.transactions";
    public static final String TRANSACTIONS_OTHERS_NODE = "knk.transactions.others";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private static final Pattern BUTTON = Pattern.compile("\\{(confirm|cancel)}");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final int MAX_REASON_LENGTH = 40;

    /** An in-game node check; true for the console. */
    @FunctionalInterface
    public interface PermissionCheck {
        CompletableFuture<Boolean> has(Player player, String node);
    }

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

    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Instant> lastPaidAt = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastPendingId = new ConcurrentHashMap<>();

    public PlayerCurrencyService(Executor mainThread, CurrencyApi currencyApi, UsersDataAccess usersDataAccess, UserCache userCache,
                                 PermissionCheck permissions, VisiblePlayers visiblePlayers, CurrencySettings settings, Clock clock) {
        this.mainThread = mainThread;
        this.currencyApi = currencyApi;
        this.usersDataAccess = usersDataAccess;
        this.userCache = userCache;
        this.permissions = permissions;
        this.visiblePlayers = visiblePlayers;
        this.settings = settings;
        this.clock = clock;
    }

    public CurrencySettings settings() {
        return settings;
    }

    public VisiblePlayers visiblePlayers() {
        return visiblePlayers;
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
                    .thenCompose(bypass -> currencyApi.transfer(senderId, recipient.userId(), currency, amount, Boolean.TRUE.equals(bypass)));
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
                    renderError(sender, ex, currency, targetName);
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
        requireAll(sender, PAY_NODE, null)
            .thenCompose(ignored -> permissions.has(sender, PAY_BYPASS_NODE))
            .thenCompose(bypass -> currencyApi.confirmTransfer(senderId, id, Boolean.TRUE.equals(bypass)))
            .whenComplete((outcome, ex) -> mainThread.execute(() -> {
                inFlight.remove(uuid);
                if (ex == null || isPendingGone(ex)) {
                    lastPendingId.remove(uuid, id);
                }
                if (ex == null && outcome != null && outcome.completed()) {
                    lastPaidAt.put(uuid, clock.instant());
                }
                if (!sender.isOnline()) {
                    return;
                }
                if (ex != null) {
                    renderError(sender, ex, BalanceCurrency.COINS, null);
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
                lastPendingId.remove(uuid, id);
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
                    send(viewer, "balance-self", "coins", CurrencyFormat.amount(balances.coins()), "gems", CurrencyFormat.amount(balances.gems()));
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

    /** {@code /knk user <player> history [coins|gems|xp] [page]} - the caller checked the staff node. Main thread. */
    public void staffHistory(CommandSender viewer, UserSummary target, BalanceCurrency filter, int page) {
        if (target.id() == null) {
            send(viewer, "pay-unknown-player", "player", target.username());
            return;
        }
        currencyApi.getTransactions(target.id(), filter, page, settings.transactionsPageSize())
            .whenComplete((ledger, ex) -> mainThread.execute(() -> renderLedger(viewer, target.username(), filter, ledger, ex)));
    }

    private void renderLedger(CommandSender viewer, String username, BalanceCurrency filter, LedgerPage ledger, Throwable ex) {
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
            viewer.sendMessage(ledgerLine(line));
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
            lastPendingId.put(player.getUniqueId(), pending.publicId());
        }
        sender.sendMessage(confirmPrompt(outcome, pending));
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
            send(sender, "error-generic");
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
            if (result == null || !result.isSuccess() || result.value().isEmpty() || result.value().get().id() == null) {
                return null;
            }
            UserSummary user = result.value().get();
            return new Target(user.id(), user.username() != null ? user.username() : name);
        });
    }

    /** Completes when {@code viewer} holds every given node (null entries skipped); fails with a no-permission refusal otherwise. */
    private CompletableFuture<Void> requireAll(CommandSender viewer, String node, String extraNode) {
        if (!(viewer instanceof Player player)) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Boolean> first = permissions.has(player, node);
        CompletableFuture<Boolean> both = extraNode == null
            ? first
            : first.thenCompose(ok -> Boolean.TRUE.equals(ok) ? permissions.has(player, extraNode) : CompletableFuture.completedFuture(false));
        return both.thenAccept(ok -> {
            if (!Boolean.TRUE.equals(ok)) {
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
