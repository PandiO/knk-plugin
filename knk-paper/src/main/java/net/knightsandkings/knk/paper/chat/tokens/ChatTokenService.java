package net.knightsandkings.knk.paper.chat.tokens;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.MatchResult;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.knightsandkings.knk.core.chat.tokens.ChatToken;
import net.knightsandkings.knk.core.chat.tokens.ChatTokenCooldowns;
import net.knightsandkings.knk.core.chat.tokens.ChatTokenParser;
import net.knightsandkings.knk.core.chat.tokens.ChatTokenRef;
import net.knightsandkings.knk.core.chat.tokens.ChatTokenViews;
import net.knightsandkings.knk.core.chat.tokens.ShareConsent;
import net.knightsandkings.knk.core.domain.settings.KnkChatTokenSettings;
import net.knightsandkings.knk.core.domain.statistics.PlayerStatistics;
import net.knightsandkings.knk.core.domain.statistics.StatisticVisibility;
import net.knightsandkings.knk.core.domain.statistics.StatisticsVisibilitySettings;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.StatisticsApi;
import net.knightsandkings.knk.core.statistics.StatisticsLines;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Chat hashtag tokens (PandiO/knk-workspace#233): turns {@code #hand}, {@code #kills}, {@code #kills@Steve}, … in a
 * message into hoverable values. Public chat ({@link ChatTokenListener}), private messages and staff chat
 * ({@link ChatTokenExpander}) all come through {@link #expand}.
 * <ul>
 *   <li>Tokens are found with {@link ChatTokenParser#PATTERN} on the message component, so colour codes don't
 *   matter. Unknown tokens, tokens the sender has no {@code knk.chat.token.<name>} for, and tokens past the Game
 *   Settings limit stay as typed.</li>
 *   <li>Statistics come from the {@code audience=public} view - what {@code /stats <player>} shows another player -
 *   cached per player ({@link ChatStatisticsCache}); a value that isn't there in {@link #LOOKUP_TIMEOUT} stays as
 *   typed, so chat never waits longer.</li>
 *   <li>The sender's own private statistic: the message is held and the sender is asked
 *   {@code [Make public & send] [Send without] [Never ask]} ({@link #answer}); "never ask" is remembered per setting.
 *   Someone else's private statistic shows as {@code [Steve: kills private]}.</li>
 *   <li>{@code #tokens} lists the tokens to the sender only; a message that is only {@code #tokens} isn't sent.</li>
 * </ul>
 */
public final class ChatTokenService implements ChatTokenExpander {

    private static final Logger LOGGER = Logger.getLogger(ChatTokenService.class.getName());
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    /** The longest a token waits for a statistics read or the main thread; then it stays as typed. */
    public static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(1);
    /** How long the share prompt's buttons work. */
    public static final Duration PROMPT_TTL = Duration.ofSeconds(60);
    public static final String COMMAND = "chattoken";

    public enum Outcome {
        /** No tokens: send the message as it is. */
        UNCHANGED,
        /** Send {@link Result#message()}. */
        EXPANDED,
        /** Don't send now: the sender was asked about a private statistic, or the message was only {@code #tokens}. */
        HELD
    }

    public record Result(Outcome outcome, Component message) {
    }

    /** One answer of the share prompt. */
    public enum Answer {
        PUBLIC, PLAIN, NEVER;

        static Optional<Answer> parse(String text) {
            try {
                return Optional.of(valueOf(text.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException | NullPointerException e) {
                return Optional.empty();
            }
        }
    }

    private record Pending(UUID sender, List<String> settingKeys, Runnable resend, Instant expiresAt) {
    }

    /** What one accepted token became: a component, its own private statistics (ask), or nothing (stays as typed). */
    private record Resolved(Component component, List<String> askAbout) {
        static final Resolved NONE = new Resolved(null, List.of());
    }

    private final Executor mainThread;
    private final BiPredicate<Player, String> permission;
    private final Supplier<KnkChatTokenSettings> limits;
    private final Function<UUID, Optional<UserSummary>> cachedUser;
    private final Function<String, CompletableFuture<Optional<UserSummary>>> userByName;
    private final Function<String, Player> onlineByName;
    private final ChatStatisticsCache statistics;
    private final StatisticsApi statisticsApi;
    private final ShareConsent consent;
    private final Runnable saveConsent;
    private final ChatTokenCooldowns cooldowns;
    private final Clock clock;

    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Set<UUID> plainOnce = ConcurrentHashMap.newKeySet();

    /**
     * @param permission    cache-only {@code knk.chat.token.*} check (safe on the async chat thread)
     * @param limits        the Game Settings limits, read per message
     * @param cachedUser    the user summary cache (stale is fine)
     * @param userByName    the API lookup for {@code #kills@Steve} when Steve is offline
     * @param onlineByName  an online player by exact name, or null
     */
    public ChatTokenService(Executor mainThread, BiPredicate<Player, String> permission, Supplier<KnkChatTokenSettings> limits,
                            Function<UUID, Optional<UserSummary>> cachedUser,
                            Function<String, CompletableFuture<Optional<UserSummary>>> userByName,
                            Function<String, Player> onlineByName, ChatStatisticsCache statistics, StatisticsApi statisticsApi,
                            ShareConsent consent, Runnable saveConsent, Clock clock) {
        this.mainThread = mainThread;
        this.permission = permission;
        this.limits = limits;
        this.cachedUser = cachedUser;
        this.userByName = userByName;
        this.onlineByName = onlineByName;
        this.statistics = statistics;
        this.statisticsApi = statisticsApi;
        this.consent = consent;
        this.saveConsent = saveConsent;
        this.cooldowns = new ChatTokenCooldowns(clock);
        this.clock = clock;
    }

    // ------------------------------------------------------------------ expanding

    @Override
    public CompletableFuture<Component> expand(Player sender, String text, Runnable resend) {
        Component body = Component.text(text);
        return expand(sender, body, resend, true).thenApply(result -> switch (result.outcome()) {
            case UNCHANGED -> body;
            case EXPANDED -> result.message();
            case HELD -> null;
        });
    }

    /**
     * @param onMainThread the caller is on the main thread: the held item and position are read directly, otherwise
     *                     through the main thread (at most {@link #LOOKUP_TIMEOUT})
     * @param resend       sends the message again (main thread) after a share-prompt answer
     */
    public CompletableFuture<Result> expand(Player sender, Component message, Runnable resend, boolean onMainThread) {
        List<Optional<ChatTokenRef>> matches = matches(message);
        if (matches.stream().noneMatch(Optional::isPresent)) {
            return CompletableFuture.completedFuture(new Result(Outcome.UNCHANGED, message));
        }

        KnkChatTokenSettings settings = Optional.ofNullable(limits.get()).orElse(KnkChatTokenSettings.off());
        boolean bypass = permission.test(sender, ChatToken.BYPASS_NODE);
        boolean sendPlain = plainOnce.remove(sender.getUniqueId());

        // Which matches count, in message order (null = stays as typed).
        List<ChatTokenRef> accepted = new ArrayList<>();
        int count = 0;
        for (Optional<ChatTokenRef> match : matches) {
            ChatTokenRef ref = match.orElse(null);
            if (ref != null && !permission.test(sender, ref.token().node())) {
                ref = null;
            }
            if (ref != null && !bypass && settings.maxTokensPerMessage() > 0 && count >= settings.maxTokensPerMessage()) {
                ref = null;
            }
            if (ref != null) {
                count++;
            }
            accepted.add(ref);
        }

        if (accepted.stream().anyMatch(r -> r != null && r.token() == ChatToken.TOKENS)) {
            sender.sendMessage(tokenList(sender));
            String plain = PlainTextComponentSerializer.plainText().serialize(message).strip();
            if (plain.equalsIgnoreCase("#tokens")) {
                return CompletableFuture.completedFuture(new Result(Outcome.HELD, message));
            }
        }

        if (!bypass && accepted.stream().anyMatch(r -> r != null && r.token().kind() == ChatToken.Kind.STATISTICS)) {
            Duration left = cooldowns.tryUse(sender.getUniqueId(), settings.statCooldownSeconds());
            if (!left.isZero()) {
                sender.sendMessage(Component.text("Statistics tokens can be used again in " + Math.max(1, (left.toMillis() + 999) / 1000)
                    + "s; they were sent as text.", NamedTextColor.GRAY));
                for (int i = 0; i < accepted.size(); i++) {
                    if (accepted.get(i) != null && accepted.get(i).token().kind() == ChatToken.Kind.STATISTICS) {
                        accepted.set(i, null);
                    }
                }
            }
        }

        // Resolve each distinct token once.
        Map<String, CompletableFuture<Resolved>> resolving = new HashMap<>();
        for (ChatTokenRef ref : accepted) {
            if (ref != null && ref.token() != ChatToken.TOKENS) {
                resolving.computeIfAbsent(ref.key(), k -> resolve(sender, ref, onMainThread, sendPlain)
                    .completeOnTimeout(Resolved.NONE, LOOKUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .exceptionally(error -> {
                        LOGGER.log(Level.FINE, "chat token " + ref.raw() + " of " + sender.getName() + " failed", error);
                        return Resolved.NONE;
                    }));
            }
        }

        return CompletableFuture.allOf(resolving.values().toArray(CompletableFuture[]::new)).thenApply(done -> {
            Map<String, Resolved> resolved = new HashMap<>();
            resolving.forEach((key, future) -> resolved.put(key, future.join()));

            Set<String> askAbout = new LinkedHashSet<>();
            resolved.values().forEach(r -> askAbout.addAll(r.askAbout()));
            if (!askAbout.isEmpty()) {
                prompt(sender, List.copyOf(askAbout), resend);
                return new Result(Outcome.HELD, message);
            }
            int[] index = {0};
            Component expanded = message.replaceText(TextReplacementConfig.builder()
                .match(ChatTokenParser.PATTERN)
                .replacement((MatchResult match, TextComponent.Builder original) -> {
                    int i = index[0]++;
                    ChatTokenRef ref = i < accepted.size() ? accepted.get(i) : null;
                    Resolved r = ref == null ? null : resolved.get(ref.key());
                    return r != null && r.component() != null ? r.component() : original;
                })
                .build());
            return new Result(Outcome.EXPANDED, expanded);
        });
    }

    /** Every match of the token pattern in {@code message}, in order; empty for one that is not a token. */
    static List<Optional<ChatTokenRef>> matches(Component message) {
        List<Optional<ChatTokenRef>> found = new ArrayList<>();
        message.replaceText(TextReplacementConfig.builder()
            .match(ChatTokenParser.PATTERN)
            .replacement((MatchResult match, TextComponent.Builder original) -> {
                found.add(ChatTokenParser.parse(match));
                return original;
            })
            .build());
        return found;
    }

    private CompletableFuture<Resolved> resolve(Player sender, ChatTokenRef ref, boolean onMainThread, boolean sendPlain) {
        return switch (ref.token().kind()) {
            case ITEM -> onMain(onMainThread, () -> heldItem(sender));
            case LOCATION -> onMain(onMainThread, () -> location(sender));
            case STATISTICS -> statistic(sender, ref, sendPlain);
            case HELP -> CompletableFuture.completedFuture(Resolved.NONE);
        };
    }

    private CompletableFuture<Resolved> onMain(boolean onMainThread, Supplier<Resolved> read) {
        if (onMainThread) {
            return CompletableFuture.completedFuture(read.get());
        }
        return CompletableFuture.supplyAsync(read, mainThread);
    }

    private static Resolved heldItem(Player sender) {
        ItemStack item = sender.getInventory().getItemInMainHand();
        if (item == null || item.isEmpty()) {
            return Resolved.NONE;
        }
        Component name = item.displayName();
        if (item.getAmount() > 1) {
            name = name.append(Component.text(" x" + item.getAmount(), NamedTextColor.GRAY));
        }
        return new Resolved(name, List.of());
    }

    private static Resolved location(Player sender) {
        Location at = sender.getLocation();
        String world = at.getWorld() != null ? at.getWorld().getName() : "?";
        String coordinates = at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ();
        Component hover = LEGACY.deserialize("&6" + sender.getName() + "'s location\n&7World: &f" + world
            + "\n&7X Y Z: &f" + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ());
        return new Resolved(LEGACY.deserialize("&b[&f" + world + " " + coordinates + "&b]").hoverEvent(HoverEvent.showText(hover)),
            List.of());
    }

    private CompletableFuture<Resolved> statistic(Player sender, ChatTokenRef ref, boolean sendPlain) {
        boolean own = ref.isAbout(sender.getName());
        Integer actingId = cachedUser.apply(sender.getUniqueId()).map(UserSummary::id).orElse(null);
        CompletableFuture<Optional<UserSummary>> target = own
            ? CompletableFuture.completedFuture(cachedUser.apply(sender.getUniqueId()))
            : targetUser(ref.target());
        return target.thenCompose(user -> {
            if (user.isEmpty() || user.get().id() == null) {
                return CompletableFuture.completedFuture(Resolved.NONE);
            }
            return statistics.get(user.get().id(), actingId).thenApply(stats -> {
                ChatTokenViews.View view = ChatTokenViews.view(ref.token(), stats, !own);
                if (view instanceof ChatTokenViews.Shown shown) {
                    return new Resolved(component(shown), List.of());
                }
                List<String> keys = ((ChatTokenViews.Private) view).settingKeys();
                if (!own) {
                    return new Resolved(privateLabel(stats.username(), keys), List.of());
                }
                if (sendPlain || consent.neverAskAll(sender.getUniqueId(), keys)) {
                    return Resolved.NONE;
                }
                return new Resolved(null, keys);
            });
        });
    }

    private CompletableFuture<Optional<UserSummary>> targetUser(String name) {
        Player online = onlineByName.apply(name);
        if (online != null) {
            Optional<UserSummary> cached = cachedUser.apply(online.getUniqueId());
            if (cached.isPresent()) {
                return CompletableFuture.completedFuture(cached);
            }
        }
        return userByName.apply(name);
    }

    static Component component(ChatTokenViews.Shown shown) {
        Component hover = Component.join(JoinConfiguration.newlines(), shown.hover().stream().map(LEGACY::deserialize).toList());
        return LEGACY.deserialize(shown.label()).hoverEvent(HoverEvent.showText(hover));
    }

    private static Component privateLabel(String name, List<String> settingKeys) {
        String what = settingKeys.stream().map(ChatTokenService::settingLabel).reduce((a, b) -> a + " and " + b).orElse("this");
        return Component.text("[" + name + ": " + what + " private]", NamedTextColor.GRAY)
            .hoverEvent(HoverEvent.showText(Component.text(name + " keeps this statistic private.", NamedTextColor.GRAY)));
    }

    /** "player kills", "deaths", "discoveries". */
    static String settingLabel(String settingKey) {
        if (ChatTokenViews.DISCOVERIES_SETTING.equals(settingKey)) {
            return "discoveries";
        }
        return StatisticsLines.label(settingKey).toLowerCase(Locale.ROOT);
    }

    private Component tokenList(Player sender) {
        List<Component> lines = new ArrayList<>();
        lines.add(LEGACY.deserialize("&6Chat tokens &7- type them in chat, private messages or staff chat; others can hover the result:"));
        for (ChatToken token : ChatToken.all()) {
            if (permission.test(sender, token.node())) {
                lines.add(LEGACY.deserialize("&e#" + token.tokenName() + " &7- " + token.description()));
            }
        }
        lines.add(LEGACY.deserialize("&7Someone else's statistics: add &e@name&7, e.g. &e#kills@" + sender.getName()
            + "&7. Only what &e/stats <name> &7shows is shown."));
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    // ------------------------------------------------------------------ share prompt

    private void prompt(Player sender, List<String> settingKeys, Runnable resend) {
        int id = nextId.getAndIncrement();
        Instant now = clock.instant();
        pending.values().removeIf(p -> p.expiresAt().isBefore(now) || p.sender().equals(sender.getUniqueId()));
        pending.put(id, new Pending(sender.getUniqueId(), settingKeys, resend, now.plus(PROMPT_TTL)));

        String what = settingKeys.stream().map(ChatTokenService::settingLabel).reduce((a, b) -> a + " and " + b).orElse("");
        Component message = Component.text()
            .append(Component.text("Your " + what + (settingKeys.size() == 1 ? " is" : " are")
                + " private, so your message wasn't sent yet. ", NamedTextColor.YELLOW))
            .append(button("[Make public & send]", NamedTextColor.GREEN, id, Answer.PUBLIC,
                "Everyone can then see your " + what + ", here and in /stats " + sender.getName() + "."))
            .append(Component.space())
            .append(button("[Send without]", NamedTextColor.GRAY, id, Answer.PLAIN, "Send it with the token as plain text."))
            .append(Component.space())
            .append(button("[Never ask]", NamedTextColor.DARK_GRAY, id, Answer.NEVER,
                "Send it as plain text now and don't ask again for your " + what + ". Change it any time with /stats settings."))
            .build();
        sender.sendMessage(message);
    }

    private static Component button(String text, NamedTextColor color, int id, Answer answer, String hover) {
        return Component.text(text, color)
            .clickEvent(ClickEvent.runCommand("/" + COMMAND + " " + id + " " + answer.name().toLowerCase(Locale.ROOT)))
            .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }

    /** {@code /chattoken <id> <public|plain|never>} from the prompt's buttons (main thread). */
    public void answer(Player sender, String idText, String answerText) {
        Optional<Answer> answer = Answer.parse(answerText);
        Integer id = parseId(idText);
        Pending prompt = id == null ? null : pending.get(id);
        if (answer.isEmpty() || prompt == null || !prompt.sender().equals(sender.getUniqueId())
                || prompt.expiresAt().isBefore(clock.instant())) {
            sender.sendMessage(Component.text("That question has expired; send your message again.", NamedTextColor.GRAY));
            return;
        }
        pending.remove(id);
        switch (answer.get()) {
            case PLAIN -> {
                plainOnce.add(sender.getUniqueId());
                prompt.resend().run();
            }
            case NEVER -> {
                consent.addNeverAsk(sender.getUniqueId(), prompt.settingKeys());
                saveConsent.run();
                sender.sendMessage(Component.text("You won't be asked again; /stats settings changes who sees your statistics.",
                    NamedTextColor.GRAY));
                prompt.resend().run();
            }
            case PUBLIC -> makePublic(sender, prompt);
        }
    }

    private void makePublic(Player sender, Pending prompt) {
        Integer userId = cachedUser.apply(sender.getUniqueId()).map(UserSummary::id).orElse(null);
        if (userId == null) {
            sender.sendMessage(Component.text("Your account isn't loaded yet; try again in a moment.", NamedTextColor.RED));
            return;
        }
        statisticsApi.getVisibility(userId, userId)
            .thenCompose(current -> {
                List<StatisticsVisibilitySettings.Change> changes = publicChanges(current, prompt.settingKeys());
                return changes.isEmpty() ? CompletableFuture.completedFuture(current)
                    : statisticsApi.updateVisibility(userId, userId, changes);
            })
            .thenCompose(updated -> {
                statistics.invalidate(userId);
                // Read the new public view before resending, so the resent message finds it cached.
                return statistics.get(userId, userId).handle((stats, error) -> updated);
            })
            .whenComplete((updated, error) -> mainThread.execute(() -> {
                if (!sender.isOnline()) {
                    return;
                }
                if (error != null) {
                    LOGGER.log(Level.WARNING, "Couldn't make " + prompt.settingKeys() + " public for " + sender.getName(), error);
                    sender.sendMessage(Component.text("Couldn't change your statistics settings right now; your message wasn't sent. "
                        + "Try /stats settings.", NamedTextColor.RED));
                    return;
                }
                consent.clear(sender.getUniqueId(), prompt.settingKeys());
                saveConsent.run();
                prompt.resend().run();
            }));
    }

    /**
     * The changes that make each setting fully public: its metric level, and every context override that isn't
     * "everyone" (a hidden context keeps the total hidden, DESIGN.md §F.4).
     */
    static List<StatisticsVisibilitySettings.Change> publicChanges(StatisticsVisibilitySettings current, List<String> settingKeys) {
        List<StatisticsVisibilitySettings.Change> changes = new ArrayList<>();
        for (String key : settingKeys) {
            StatisticsVisibilitySettings.Setting setting = current.setting(key).orElse(null);
            StatisticVisibility level = setting != null ? setting.visibility() : StatisticVisibility.NOBODY;
            if (level != StatisticVisibility.EVERYONE) {
                changes.add(new StatisticsVisibilitySettings.Change(key, "", level, StatisticVisibility.EVERYONE));
            }
            if (setting != null) {
                for (StatisticsVisibilitySettings.ContextValue context : setting.contexts()) {
                    if (context.isOverride() && context.visibility() != StatisticVisibility.EVERYONE) {
                        changes.add(new StatisticsVisibilitySettings.Change(key, context.context(), context.visibility(),
                            StatisticVisibility.EVERYONE));
                    }
                }
            }
        }
        return changes;
    }

    private static Integer parseId(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Warms the cache with a joining player's public view, so their first token needn't wait. */
    public void prefetch(UUID player) {
        cachedUser.apply(player).map(UserSummary::id).ifPresent(id -> statistics.get(id, id));
    }

    public void forget(UUID player) {
        cooldowns.forget(player);
        plainOnce.remove(player);
        pending.values().removeIf(p -> p.sender().equals(player));
    }
}
