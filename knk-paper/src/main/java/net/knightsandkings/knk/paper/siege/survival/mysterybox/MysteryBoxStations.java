package net.knightsandkings.knk.paper.siege.survival.mysterybox;

import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MysteryBoxRollDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MysteryBoxRolledEnchantmentDto;
import net.knightsandkings.knk.api.impl.SiegeSurvivalMatchApiImpl;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.survival.SurvivalPoint;
import net.knightsandkings.knk.core.survival.economy.LedgerResult;
import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxReelPlan;
import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxRollIds;
import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxRollState;
import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxRollState.ClaimOutcome;
import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxRollState.ForfeitReason;
import net.knightsandkings.knk.core.survival.purchase.PurchaseRules;
import net.knightsandkings.knk.paper.siege.SiegeMessages;
import net.knightsandkings.knk.paper.siege.survival.MatchHook;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.BoxEntrySpec;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.MysteryBoxPointSpec;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.WorldPoint;
import net.knightsandkings.knk.paper.siege.survival.SurvivalMatch;
import net.knightsandkings.knk.paper.siege.survival.SurvivalSpawner;
import net.knightsandkings.knk.paper.siege.survival.items.MatchItems;
import net.knightsandkings.knk.paper.siege.survival.items.SurvivalItemFactory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Mystery Box points of one survival match (DESIGN §8a; S24, S34, S35, S39, S59; link S7a's contract): the box is the
 * block at the point (checked at match start, never placed), a living member right-clicks it, the ledger is charged
 * (charge id = roll id), the API rolls authoritatively off the main thread, the {@link MysteryBoxAnimation} plays the reel,
 * the roller claims by right-clicking the shown item within the claim timeout, else the roll is forfeited (also on death,
 * leave and match end, no refund). One roll at a time per box; pending roll ids are kept for S8's checkpoint.
 */
public final class MysteryBoxStations implements MatchHook {

    /** One box point at runtime. */
    public final class Station {
        private final MysteryBoxPointSpec spec;
        private final Location block;
        private MysteryBoxRollState roll;
        private MysteryBoxRollDto rolled;
        private MysteryBoxAnimation animation;
        private CompletableFuture<Optional<ItemStack>> prize;
        private int rolls;
        private int claims;
        private int forfeits;

        Station(MysteryBoxPointSpec spec, Location block) {
            this.spec = spec;
            this.block = block;
        }

        public MysteryBoxPointSpec spec() {
            return spec;
        }

        public Optional<MysteryBoxRollState> roll() {
            return Optional.ofNullable(roll);
        }

        public boolean busy() {
            return roll != null && !roll.over();
        }

        boolean at(Block b) {
            return block != null && block.getWorld() != null && block.getWorld().equals(b.getWorld()) && block.getBlockX() == b.getX()
                && block.getBlockY() == b.getY() && block.getBlockZ() == b.getZ();
        }
    }

    private static final int API_RETRIES = 3;

    private final SurvivalMatch match;
    private final SiegeSurvivalMatchApiImpl api;
    private final Supplier<OptionalLong> apiMatchId;
    private final Function<UUID, OptionalInt> userIdOf;
    private final SurvivalItemFactory factory;
    private final MatchItems items;
    private final NamespacedKey entityTag;
    private final SurvivalSpawner.Worlds worlds;
    private final MysteryBoxAnimation.TickScheduler scheduler;
    private final MysteryBoxAnimation.Effects effects;
    private final Executor mainThread;
    private final int maxStepTicks;
    private final Logger logger;
    private final List<Station> stations = new ArrayList<>();
    private final Map<Integer, ItemStack> faces = new LinkedHashMap<>();
    private boolean ended;

    public MysteryBoxStations(SurvivalMatch match, SiegeSurvivalMatchApiImpl api, Supplier<OptionalLong> apiMatchId,
                              Function<UUID, OptionalInt> userIdOf, SurvivalItemFactory factory, NamespacedKey entityTag,
                              SurvivalSpawner.Worlds worlds, MysteryBoxAnimation.TickScheduler scheduler, MysteryBoxAnimation.Effects effects,
                              Executor mainThread, int maxStepTicks, Logger logger) {
        this.match = Objects.requireNonNull(match, "match");
        this.api = api;
        this.apiMatchId = apiMatchId;
        this.userIdOf = userIdOf;
        this.factory = factory;
        this.items = factory.items();
        this.entityTag = Objects.requireNonNull(entityTag, "entityTag");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.effects = Objects.requireNonNull(effects, "effects");
        this.mainThread = mainThread;
        this.maxStepTicks = Math.max(1, maxStepTicks);
        this.logger = logger;
        for (MysteryBoxPointSpec spec : match.config().mysteryBoxPoints()) {
            stations.add(new Station(spec, spec.location().flatMap(p -> toLocation(p, worlds)).orElse(null)));
        }
    }

    public List<Station> stations() {
        return List.copyOf(stations);
    }

    /** Match start: the block check (WARNING, never placed) and the reel faces, assembled off the main thread. */
    public void start() {
        for (Station s : stations) {
            if (s.block == null) {
                warn("Mystery Box point " + s.spec.id() + " '" + s.spec.name() + "' has no loadable location; it cannot be used");
                continue;
            }
            Material expected = Material.matchMaterial(s.spec.blockType());
            Material actual = s.block.getBlock().getType();
            if (expected == null) {
                warn("Mystery Box point " + s.spec.id() + " '" + s.spec.name() + "': unknown blockType '" + s.spec.blockType() + "'; the block is "
                    + actual.getKey() + " (used as is)");
            } else if (actual != expected) {
                warn("Mystery Box point " + s.spec.id() + " '" + s.spec.name() + "' at " + describe(s.block) + " is " + actual.getKey()
                    + ", configured " + expected.getKey() + " (not placed; right-click the block that is there)");
            }
            if (!s.spec.pool().isValid()) {
                warn("Mystery Box point " + s.spec.id() + " '" + s.spec.name() + "': pool invalid (" + s.spec.pool().validate() + "); rolls will be refused by the API");
            }
            for (BoxEntrySpec entry : s.spec.entries()) prepareFace(entry);
        }
        if (api == null) warn("Mystery Box: no match API client; rolls are not possible in this match");
    }

    private void prepareFace(BoxEntrySpec entry) {
        if (faces.containsKey(entry.id())) return;
        CompletableFuture<Optional<ItemStack>> future = entry.isBook()
            ? factory.enchantedBook(entry.enchantmentDefinitionId().getAsInt(), entry.enchantmentLevel(), entry.enchantmentKey(), match.matchKey())
            : entry.itemBlueprintId().isPresent()
                ? factory.blueprintItem(entry.itemBlueprintId().getAsInt(), 1, List.of(), match.matchKey())
                : CompletableFuture.completedFuture(Optional.empty());
        future.whenComplete((stack, ex) -> mainThread.execute(() -> {
            if (ended) return;
            if (ex != null || stack == null || stack.isEmpty()) {
                warn("Mystery Box entry " + entry.id() + " (" + entry.describe() + ") has no displayable item; the reel shows a placeholder");
                return;
            }
            faces.put(entry.id(), stack.get());
        }));
    }

    // ---- lookups ----

    public Optional<Station> stationAt(Block block) {
        if (block == null) return Optional.empty();
        return stations.stream().filter(s -> s.at(block)).findFirst();
    }

    /** The station whose shown item (claim hitbox) this entity is. */
    public Optional<Station> stationOf(Entity entity) {
        return stations.stream().filter(s -> s.animation != null && s.animation.owns(entity)).findFirst();
    }

    public List<String> pendingRollIds() {
        List<String> out = new ArrayList<>();
        for (Station s : stations) {
            if (s.busy()) out.add(s.roll.rollId());
        }
        return out;
    }

    public Optional<String> pendingRollOf(UUID player) {
        return stations.stream().filter(s -> s.busy() && s.roll.roller().equals(player)).map(s -> s.roll.rollId()).findFirst();
    }

    // ---- roll ----

    /** A living member right-clicked the box: price check, charge, API roll, animation. */
    public Component roll(Player player, Station station) {
        UUID id = player.getUniqueId();
        if (ended || match.ending()) return SiegeMessages.bad("The match is over.");
        if (!match.economy().isMember(id)) return SiegeMessages.bad("You are not in this match.");
        if (match.ghosts().isGhost(id)) return SiegeMessages.bad("Ghosts cannot roll the Mystery Box.");
        if (api == null) return SiegeMessages.bad("The Mystery Box is not available (no match API).");
        if (station.busy()) {
            return station.roll.roller().equals(id)
                ? SiegeMessages.info("Your roll is still running: right-click the shown item to claim it.")
                : SiegeMessages.bad("The box is busy with another roll; wait for it.");
        }
        if (station.block != null && !withinRange(player, station)) return SiegeMessages.bad("You are too far from " + station.spec.name() + ".");
        long price = station.spec.rollPrice();
        if (!match.economy().canAfford(id, price)) {
            return SiegeMessages.bad("Mystery Box: " + price + " match coins per roll; you have " + match.economy().balance(id) + ".");
        }
        OptionalLong matchId = apiMatchId.get();
        if (matchId.isEmpty()) return SiegeMessages.bad("The match is not registered with the API yet; try again in a moment.");
        OptionalInt userId = userIdOf.apply(id);
        if (userId.isEmpty()) return SiegeMessages.bad("Your user id is unknown; the box cannot roll for you.");
        String rollId = MysteryBoxRollIds.rollId(matchId.getAsLong(), station.spec.id());
        LedgerResult charge = match.economy().charge(rollId, id, price, "mystery-box");
        if (charge instanceof LedgerResult.Rejected rejected) {
            return SiegeMessages.bad("Roll refused: " + rejected.reason());
        }
        MysteryBoxRollState state = new MysteryBoxRollState(rollId, id, station.spec.claimTimeoutMillis());
        station.roll = state;
        station.rolled = null;
        station.animation = null;
        station.prize = null;
        station.rolls++;
        long balanceAfter = ((LedgerResult.Accepted) charge).balanceAfter();
        log("roll " + rollId + " by " + player.getName() + " at box " + station.spec.id() + " charged " + price + " (balance " + balanceAfter + ")");
        player.sendActionBar(Component.text("Rolling the Mystery Box for " + price + " · balance " + balanceAfter, SiegeMessages.HIGHLIGHT));
        callRoll(station, state, matchId.getAsLong(), userId.getAsInt(), (int) Math.min(Integer.MAX_VALUE, price), 0);
        return null;
    }

    private void callRoll(Station station, MysteryBoxRollState state, long matchId, int userId, int price, int attempt) {
        api.roll(matchId, station.spec.id(), state.rollId(), userId, price).whenComplete((dto, ex) -> mainThread.execute(() -> {
            if (station.roll != state) return; // superseded (match ended and reset)
            if (ex != null) {
                Throwable cause = unwrap(ex);
                String code = cause instanceof ApiException apiEx ? api.conflictCode(apiEx) : null;
                if (code != null) {
                    warn("roll " + state.rollId() + " refused by the API (409 " + code + "); cancelled locally, the charge stands (S59)");
                    cancelLocally(station, state, "api-conflict:" + code);
                    return;
                }
                if (isNetwork(cause) && attempt < API_RETRIES) {
                    long delay = 1000L << attempt;
                    warn("roll " + state.rollId() + ": network error (" + cause + "); retrying the same call in " + delay + " ms");
                    CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> {
                        if (station.roll == state && !state.over()) callRoll(station, state, matchId, userId, price, attempt + 1);
                    });
                    return;
                }
                if (logger != null) logger.log(Level.WARNING, "[Survival] roll " + state.rollId() + " failed; cancelled locally, the charge stands (S59)", cause);
                cancelLocally(station, state, "api-error");
                return;
            }
            if (dto == null) {
                warn("roll " + state.rollId() + ": the API answered 404 (match, point or user unknown); cancelled locally, the charge stands (S59)");
                cancelLocally(station, state, "api-404");
                return;
            }
            if (state.over()) {
                // The roller died/left or the match ended while the API rolled: forfeit server-side too.
                callForfeit(matchId, state.rollId(), apiReason(state.forfeitReason().orElse(ForfeitReason.MATCH_ENDED)), 0);
                return;
            }
            startAnimation(station, state, dto, matchId);
        }));
    }

    private void startAnimation(Station station, MysteryBoxRollState state, MysteryBoxRollDto dto, long matchId) {
        station.rolled = dto;
        int resultId = dto.entry() == null || dto.entry().id() == null ? -1 : dto.entry().id();
        MysteryBoxReelPlan plan = MysteryBoxReelPlan.of(dto.reel(), resultId, maxStepTicks);
        station.prize = prize(station, dto);
        Player roller = match.onlinePlayer().apply(state.roller());
        if (station.block == null || station.block.getWorld() == null) {
            warn("roll " + state.rollId() + ": the box has no world; forfeited");
            forfeit(station, state, ForfeitReason.MATCH_ENDED, matchId);
            return;
        }
        MysteryBoxAnimation animation = new MysteryBoxAnimation(scheduler, effects, entityTag, station.block, plan, this::face, this::faceName, () -> {
            if (station.roll != state || state.over()) return;
            state.animationDone(now());
            Player p = match.onlinePlayer().apply(state.roller());
            if (p != null) {
                p.sendActionBar(Component.text("You rolled " + plainName(resultId) + "! Right-click it within "
                    + (state.claimTimeoutMillis() / 1000) + " s to claim.", SiegeMessages.GOOD));
            }
            log("roll " + state.rollId() + " landed on entry " + resultId + " (" + plainName(resultId) + "); claim window " + state.claimTimeoutMillis() + " ms");
        });
        station.animation = animation;
        try {
            animation.start();
        } catch (RuntimeException e) {
            if (logger != null) logger.log(Level.WARNING, "[Survival] roll " + state.rollId() + ": the animation could not start; forfeited", e);
            forfeit(station, state, ForfeitReason.MATCH_ENDED, matchId);
            return;
        }
        if (roller != null) roller.sendActionBar(Component.text("The Mystery Box is rolling…", SiegeMessages.INFO));
        log("roll " + state.rollId() + " animation: " + plan.steps() + " steps, " + plan.totalTicks() + " ticks, reel " + plan.entryIds());
    }

    private CompletableFuture<Optional<ItemStack>> prize(Station station, MysteryBoxRollDto dto) {
        var entry = dto.entry();
        if (entry == null) return CompletableFuture.completedFuture(Optional.empty());
        boolean book = "EnchantedBooks".equalsIgnoreCase(dto.variant()) || (entry.itemBlueprintId() == null && entry.enchantmentDefinitionId() != null);
        if (book) {
            if (entry.enchantmentDefinitionId() == null) return CompletableFuture.completedFuture(Optional.empty());
            return factory.enchantedBook(entry.enchantmentDefinitionId(), entry.enchantmentLevel() == null ? 1 : entry.enchantmentLevel(),
                entry.enchantmentKey(), match.matchKey());
        }
        if (entry.itemBlueprintId() == null) return CompletableFuture.completedFuture(Optional.empty());
        List<SurvivalItemFactory.Rolled> rolled = new ArrayList<>();
        if (dto.rolledEnchantments() != null) {
            for (MysteryBoxRolledEnchantmentDto r : dto.rolledEnchantments()) {
                if (r != null && r.enchantmentDefinitionId() != null) rolled.add(new SurvivalItemFactory.Rolled(r.enchantmentDefinitionId(), r.level() == null ? 1 : r.level()));
            }
        }
        return factory.blueprintItem(entry.itemBlueprintId(), null, rolled, match.matchKey());
    }

    // ---- claim ----

    /** A right-click on a shown item: only the roller, only while shown, within the timeout (S39). */
    public Component claim(Player player, Station station) {
        MysteryBoxRollState state = station.roll;
        if (state == null || state.over()) return SiegeMessages.bad("Nothing to claim here.");
        if (match.ghosts().isGhost(player.getUniqueId())) return SiegeMessages.bad("Ghosts cannot claim anything.");
        ClaimOutcome outcome = state.claim(player.getUniqueId(), now());
        switch (outcome) {
            case NOT_ROLLER -> {
                return SiegeMessages.bad("Only " + rollerName(state) + " can claim this roll.");
            }
            case NOT_SHOWN_YET -> {
                return SiegeMessages.info("Wait for the reel to stop.");
            }
            case ALREADY_OVER -> {
                finishForfeit(station, state);
                return SiegeMessages.bad("Too late: the item was forfeited.");
            }
            case CLAIMED -> {
                deliver(station, state, player);
                return null;
            }
        }
        return null;
    }

    private void deliver(Station station, MysteryBoxRollState state, Player player) {
        station.claims++;
        OptionalLong matchId = apiMatchId.get();
        if (station.animation != null) station.animation.remove();
        station.animation = null;
        CompletableFuture<Optional<ItemStack>> prize = station.prize != null ? station.prize : CompletableFuture.completedFuture(Optional.empty());
        prize.whenComplete((stack, ex) -> mainThread.execute(() -> {
            Player p = match.onlinePlayer().apply(player.getUniqueId());
            String kind;
            if (ex != null || stack == null || stack.isEmpty()) {
                warn("roll " + state.rollId() + " claimed but its item could not be assembled; nothing given (flag S7b)");
                kind = SiegeSurvivalMatchApiImpl.DELIVERY_INVENTORY;
            } else if (p == null || !p.isOnline()) {
                kind = SiegeSurvivalMatchApiImpl.DELIVERY_DROP;
                if (station.block != null && station.block.getWorld() != null) {
                    items.drop(station.block.clone().add(0.5, 1.2, 0.5), stack.get(), player.getUniqueId(), match.matchKey());
                }
            } else {
                int dropped = items.give(p, List.of(stack.get()), match.matchKey());
                kind = dropped > 0 ? SiegeSurvivalMatchApiImpl.DELIVERY_DROP : SiegeSurvivalMatchApiImpl.DELIVERY_INVENTORY;
                p.sendActionBar(Component.text("Claimed: " + name(stack.get()) + (dropped > 0 ? " (inventory full: dropped at your feet)" : ""), SiegeMessages.GOOD));
            }
            log("roll " + state.rollId() + " claimed by " + player.getName() + " (" + kind + ")");
            if (matchId.isPresent() && api != null) callClaim(matchId.getAsLong(), state.rollId(), kind, 0);
        }));
    }

    // ---- forfeits ----

    private void forfeit(Station station, MysteryBoxRollState state, ForfeitReason reason, long matchId) {
        if (!state.forfeit(reason)) return;
        finishForfeit(station, state);
        log("roll " + state.rollId() + " forfeited: " + reason + " (no refund, S59)");
        Player roller = match.onlinePlayer().apply(state.roller());
        if (roller != null && reason == ForfeitReason.TIMEOUT) roller.sendActionBar(Component.text("The Mystery Box item was not claimed in time.", SiegeMessages.BAD));
        if (api != null) callForfeit(matchId, state.rollId(), apiReason(reason), 0);
    }

    private void finishForfeit(Station station, MysteryBoxRollState state) {
        station.forfeits++;
        if (station.animation != null) station.animation.remove();
        station.animation = null;
        station.prize = null;
    }

    private void cancelLocally(Station station, MysteryBoxRollState state, String why) {
        state.forfeit(ForfeitReason.MATCH_ENDED);
        if (station.animation != null) station.animation.remove();
        station.animation = null;
        station.prize = null;
        station.forfeits++;
        Player roller = match.onlinePlayer().apply(state.roller());
        if (roller != null) roller.sendActionBar(Component.text("The Mystery Box could not roll (" + why + "); tell an admin.", SiegeMessages.BAD));
    }

    private void forfeitAll(java.util.function.Predicate<MysteryBoxRollState> which, ForfeitReason reason) {
        OptionalLong matchId = apiMatchId.get();
        for (Station s : stations) {
            if (s.busy() && which.test(s.roll)) forfeit(s, s.roll, reason, matchId.orElse(-1));
        }
    }

    // ---- hook ----

    @Override
    public void ticked(long nowMillis) {
        if (ended) return;
        OptionalLong matchId = null;
        for (Station s : stations) {
            if (!s.busy()) continue;
            Optional<ForfeitReason> timeout = s.roll.tick(nowMillis);
            if (timeout.isPresent()) {
                if (matchId == null) matchId = apiMatchId.get();
                finishForfeit(s, s.roll);
                log("roll " + s.roll.rollId() + " forfeited: TIMEOUT (no refund, S59)");
                Player roller = match.onlinePlayer().apply(s.roll.roller());
                if (roller != null) roller.sendActionBar(Component.text("The Mystery Box item was not claimed in time.", SiegeMessages.BAD));
                if (api != null) callForfeit(matchId.orElse(-1), s.roll.rollId(), SiegeSurvivalMatchApiImpl.FORFEIT_TIMEOUT, 0);
            }
        }
    }

    @Override
    public void playerBecameGhost(UUID playerId) {
        forfeitAll(r -> r.concerns(playerId), ForfeitReason.ROLLER_DIED);
    }

    @Override
    public void memberLeft(UUID playerId) {
        forfeitAll(r -> r.concerns(playerId), ForfeitReason.ROLLER_LEFT);
    }

    @Override
    public void ended() {
        if (ended) return;
        ended = true;
        forfeitAll(r -> true, ForfeitReason.MATCH_ENDED);
        for (Station s : stations) {
            if (s.animation != null) s.animation.remove();
            s.animation = null;
        }
        faces.clear();
    }

    @Override
    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        for (Station s : stations) {
            lines.add("Mystery Box " + s.spec.id() + " '" + s.spec.name() + "' (" + s.spec.variant() + ", " + s.spec.rollPrice() + " coins, "
                + s.spec.entries().size() + " entries, faces ready " + s.spec.entries().stream().filter(e -> faces.containsKey(e.id())).count()
                + "): rolls " + s.rolls + ", claims " + s.claims + ", forfeits " + s.forfeits
                + (s.busy() ? "; pending " + s.roll.rollId() + " " + s.roll.phase() + " by " + rollerName(s.roll) : ""));
        }
        return lines;
    }

    // ---- API calls (fire-and-forget, retried on network errors; idempotent server-side) ----

    private void callClaim(long matchId, String rollId, String kind, int attempt) {
        api.claim(matchId, rollId, kind).whenComplete((dto, ex) -> {
            if (ex == null) return;
            Throwable cause = unwrap(ex);
            if (isNetwork(cause) && attempt < API_RETRIES) {
                CompletableFuture.delayedExecutor(1000L << attempt, TimeUnit.MILLISECONDS).execute(() -> callClaim(matchId, rollId, kind, attempt + 1));
                return;
            }
            String code = cause instanceof ApiException apiEx ? api.conflictCode(apiEx) : null;
            warn("claim " + rollId + " not recorded by the API" + (code != null ? " (409 " + code + ")" : ": " + cause));
        });
    }

    private void callForfeit(long matchId, String rollId, String reason, int attempt) {
        if (matchId < 0) {
            warn("forfeit " + rollId + " (" + reason + ") not sent: the API match id is unknown");
            return;
        }
        api.forfeit(matchId, rollId, reason).whenComplete((dto, ex) -> {
            if (ex == null) return;
            Throwable cause = unwrap(ex);
            if (isNetwork(cause) && attempt < API_RETRIES) {
                CompletableFuture.delayedExecutor(1000L << attempt, TimeUnit.MILLISECONDS).execute(() -> callForfeit(matchId, rollId, reason, attempt + 1));
                return;
            }
            String code = cause instanceof ApiException apiEx ? api.conflictCode(apiEx) : null;
            warn("forfeit " + rollId + " (" + reason + ") not recorded by the API" + (code != null ? " (409 " + code + ")" : ": " + cause));
        });
    }

    static String apiReason(ForfeitReason reason) {
        return switch (reason) {
            case TIMEOUT -> SiegeSurvivalMatchApiImpl.FORFEIT_TIMEOUT;
            case ROLLER_DIED -> SiegeSurvivalMatchApiImpl.FORFEIT_ROLLER_DIED;
            case ROLLER_LEFT -> SiegeSurvivalMatchApiImpl.FORFEIT_ROLLER_LEFT;
            case MATCH_ENDED -> SiegeSurvivalMatchApiImpl.FORFEIT_MATCH_ENDED;
        };
    }

    static Throwable unwrap(Throwable ex) {
        Throwable t = ex;
        while ((t instanceof CompletionException || (t instanceof RuntimeException && !(t instanceof ApiException))) && t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    /** A transport failure (the api-client keeps the IOException as the ApiException's cause) or a 5xx. */
    static boolean isNetwork(Throwable cause) {
        if (cause instanceof IOException) return true;
        if (cause instanceof ApiException apiEx) {
            if (apiEx.getCause() instanceof IOException) return true;
            return apiEx.getStatusCode() >= 500 || apiEx.getStatusCode() <= 0;
        }
        return false;
    }

    // ---- helpers ----

    private ItemStack face(int entryId) {
        ItemStack face = faces.get(entryId);
        if (face != null) return face.clone();
        ItemStack barrier = new ItemStack(Material.BARRIER);
        ItemMeta meta = barrier.getItemMeta();
        meta.displayName(Component.text(entryName(entryId), NamedTextColor.GRAY));
        barrier.setItemMeta(meta);
        return barrier;
    }

    private Component faceName(int entryId) {
        return Component.text(plainName(entryId), NamedTextColor.GOLD);
    }

    private String plainName(int entryId) {
        ItemStack face = faces.get(entryId);
        return face != null ? name(face) : entryName(entryId);
    }

    private String entryName(int entryId) {
        for (Station s : stations) {
            Optional<BoxEntrySpec> e = s.spec.entry(entryId);
            if (e.isPresent()) return e.get().describe();
        }
        return "entry #" + entryId;
    }

    private static String name(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }
        if (meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta books && !books.getStoredEnchants().isEmpty()) {
            var first = books.getStoredEnchants().entrySet().iterator().next();
            return "Enchanted Book (" + first.getKey().getKey().getKey() + " " + first.getValue() + ")";
        }
        return stack.getType().getKey().getKey();
    }

    private String rollerName(MysteryBoxRollState state) {
        Player p = match.onlinePlayer().apply(state.roller());
        return p == null ? state.roller().toString().substring(0, 8) : p.getName();
    }

    private boolean withinRange(Player player, Station station) {
        Location me = player.getLocation();
        if (station.block.getWorld() == null || !station.block.getWorld().equals(me.getWorld())) return false;
        return PurchaseRules.withinRange(new SurvivalPoint(station.block.getX() + 0.5, station.block.getY() + 0.5, station.block.getZ() + 0.5),
            new SurvivalPoint(me.getX(), me.getY(), me.getZ()), station.spec.interactionRange());
    }

    private long now() {
        return match.now();
    }

    static Optional<Location> toLocation(WorldPoint point, SurvivalSpawner.Worlds worlds) {
        if (point == null || point.world().isBlank()) return Optional.empty();
        var world = worlds.byName(point.world());
        return world == null ? Optional.empty() : Optional.of(new Location(world, Math.floor(point.x()), Math.floor(point.y()), Math.floor(point.z())));
    }

    private static String describe(Location at) {
        return (at.getWorld() == null ? "?" : at.getWorld().getName()) + " " + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ();
    }

    private void log(String message) {
        if (logger != null) logger.info("[Survival] Lobby " + match.lobby().key() + ": " + message);
    }

    private void warn(String message) {
        if (logger != null) logger.warning("[Survival] Lobby " + match.lobby().key() + ": " + message);
    }
}
