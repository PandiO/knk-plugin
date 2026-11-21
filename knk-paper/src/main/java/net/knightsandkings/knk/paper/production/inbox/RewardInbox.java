package net.knightsandkings.knk.paper.production.inbox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.items.identity.ItemIdentityResolution;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.LootboxDeliveryMethod;
import net.knightsandkings.knk.core.ports.api.ItemDeliveryApi;
import net.knightsandkings.knk.core.production.inbox.DeliveryRequest;
import net.knightsandkings.knk.core.production.inbox.DeliveryResult;
import net.knightsandkings.knk.core.production.inbox.InboxText;
import net.knightsandkings.knk.core.production.inbox.RewardKeys;
import net.knightsandkings.knk.paper.kit.KitGrantPlacer;
import net.knightsandkings.knk.paper.kit.KitRewardRouter;
import net.knightsandkings.knk.paper.lootbox.LootboxDelivery;

/**
 * Rewards that don't fit go to the player's reward inbox (PandiO/knk-workspace#684, production DESIGN § 4.6, D7, A15):
 * the plugin places what fits first, then sends the rest with {@code POST api/ItemDeliveries} under a key that is stable
 * per reward, so a retry applies once. Used by the kit flows ({@link KitRewardRouter}) and lootbox delivery
 * ({@link LootboxDelivery.LeftoverRouter}) only while {@code production.enabled} is on.
 * <ol>
 *   <li>Each leftover is resolved with item identity (#141: instance tag, blueprint tag, vanilla match); when item
 *       identity is off or can't resolve it, the blueprint it was built from is used. Unique items (an instance) and
 *       items without a blueprint are not sent: they can't be claimed back in game yet (#897) and fall back.</li>
 *   <li>No answer or a server error: sent once more with the same key after a few seconds.</li>
 *   <li>A refusal (409 {@code ProductionDisabled} while the API's flag is off, or any other error): the fallback runs,
 *       which is today's delivery: kits drop at the player's feet, lootbox leftovers drop owner-locked.</li>
 *   <li>The player is told where the items went (and what the full inbox lost); the matching {@code InboxUpdated}
 *       notice then only adds its warning ({@link #wasReported}).</li>
 * </ol>
 * The items are held here while the API answers; a server stop in that moment loses them (logged with the key).
 */
public final class RewardInbox implements KitRewardRouter, LootboxDelivery.LeftoverRouter {

    private static final Logger LOGGER = Logger.getLogger(RewardInbox.class.getName());
    static final long RETRY_DELAY_SECONDS = 3;
    private static final int REPORTED_LIMIT = 1_000;

    /** A leftover stack and the blueprint it was built from (null when unknown). */
    public record Leftover(ItemStack item, Integer blueprintId) {
    }

    /** The lines for the API, and the stacks that can't go to the inbox. */
    record Lines(List<DeliveryRequest.Line> lines, List<ItemStack> refused) {
    }

    /** What happened: {@code result} null when nothing reached the API; {@code fellBack} the stacks the fallback got. */
    public record Outcome(DeliveryResult result, List<ItemStack> fellBack, String why) {
        public boolean inboxed() {
            return result != null;
        }
    }

    private final ItemDeliveryApi api;
    private final Executor mainThread;
    private final Supplier<Function<ItemStack, ItemIdentityResolution>> identity;
    private final Set<Long> reported = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > REPORTED_LIMIT;
        }
    });

    public RewardInbox(ItemDeliveryApi api, Executor mainThread, Supplier<Function<ItemStack, ItemIdentityResolution>> identity) {
        this.api = api;
        this.mainThread = mainThread;
        this.identity = identity != null ? identity : () -> null;
    }

    // ===== kits =====

    @Override
    public KitGrantPlacer.PlacementSummary place(Player player, int userId, List<KitGrantPlacer.ResolvedItem> items,
                                                 String idempotencyKey, String kitName) {
        List<Leftover> leftovers = new ArrayList<>();
        KitGrantPlacer.PlacementSummary summary = KitGrantPlacer.place(player, items, (item, stack) -> {
            leftovers.add(new Leftover(stack, item.itemBlueprintId()));
            return true;
        });
        if (!leftovers.isEmpty()) {
            send(player, userId, DeliveryRequest.Reason.KIT, "Kit", kitName, idempotencyKey, leftovers,
                    stacks -> dropAtFeet(player, stacks));
        }
        return summary;
    }

    @Override
    public void deliverOffline(int userId, List<KitGrantPlacer.ResolvedItem> items, String idempotencyKey, String kitName) {
        List<Leftover> all = new ArrayList<>();
        for (KitGrantPlacer.ResolvedItem item : items) {
            all.add(new Leftover(item.itemStack(), item.itemBlueprintId()));
        }
        send(null, userId, DeliveryRequest.Reason.KIT, "Kit", kitName, idempotencyKey, all, stacks ->
                LOGGER.severe("Kit \"" + kitName + "\" for offline user " + userId + " (key " + idempotencyKey + "): "
                        + stacks.size() + " stack(s) could not go to the reward inbox and are lost: " + describe(stacks)));
    }

    /** Kits: what the inbox refused drops at the player's feet, as before #684. */
    static void dropAtFeet(Player player, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            player.getWorld().dropItemNaturally(player.getLocation(), stack);
        }
        if (player.isOnline() && !stacks.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "Some of it didn't fit and was dropped at your feet.");
        }
    }

    // ===== lootboxes =====

    @Override
    public void route(Player player, KnkLootboxClaimResult claim, List<ItemStack> leftovers, LootboxDeliveryMethod whenAllFit,
                      java.util.function.BiConsumer<LootboxDeliveryMethod, String> settled) {
        List<Leftover> list = new ArrayList<>();
        for (ItemStack stack : leftovers) {
            list.add(new Leftover(stack, claim.itemBlueprintId()));
        }
        send(player, claim.userId(), DeliveryRequest.Reason.LOOTBOX, "Lootbox", String.valueOf(claim.claimId()),
                RewardKeys.lootboxClaim(claim.claimId()), list, stacks -> LootboxDelivery.dropOwned(player, stacks))
                .thenAccept(outcome -> {
                    if (!outcome.inboxed()) {
                        settled.accept(LootboxDeliveryMethod.DROPPED_OWNED, "reward inbox refused: " + outcome.why());
                        return;
                    }
                    DeliveryResult r = outcome.result();
                    String note = r.inboxedUnits() + " to the reward inbox (delivery #" + r.deliveryId() + ")"
                            + (r.lostUnits() > 0 ? ", " + r.lostUnits() + " lost at the inbox cap" : "");
                    if (!outcome.fellBack().isEmpty()) {
                        settled.accept(LootboxDeliveryMethod.DROPPED_OWNED, note + "; the rest dropped");
                    } else {
                        settled.accept(whenAllFit, note);
                    }
                });
    }

    // ===== sending =====

    /**
     * Main thread: sends {@code leftovers} to {@code userId}'s inbox. {@code player} null = offline (no messages).
     * {@code fallback} gets every stack that did not reach the inbox; the future completes on the main thread.
     */
    public CompletableFuture<Outcome> send(Player player, int userId, DeliveryRequest.Reason reason, String sourceType,
                                           String sourceRef, String idempotencyKey, List<Leftover> leftovers,
                                           Consumer<List<ItemStack>> fallback) {
        Lines lines = lines(leftovers, identity.get());
        CompletableFuture<Outcome> done = new CompletableFuture<>();
        if (lines.lines().isEmpty()) {
            runFallback(fallback, lines.refused());
            done.complete(new Outcome(null, lines.refused(), "nothing the inbox can hold"));
            return done;
        }
        String key = RewardKeys.sanitize(idempotencyKey);
        DeliveryRequest request = new DeliveryRequest(userId, reason, sourceType, sourceRef, null, key, lines.lines());
        attempt(request, 1).whenComplete((result, error) -> mainThread.execute(() -> {
            if (error != null) {
                String why = why(error);
                List<ItemStack> all = new ArrayList<>();
                for (Leftover leftover : leftovers) {
                    if (leftover.item() != null && !leftover.item().getType().isAir() && leftover.item().getAmount() > 0) {
                        all.add(leftover.item());
                    }
                }
                LOGGER.log(productionDisabled(error) ? Level.INFO : Level.WARNING, "Reward inbox: delivery " + key
                        + " for user " + userId + " not taken (" + why + "); today's delivery instead");
                runFallback(fallback, all);
                done.complete(new Outcome(null, all, why));
                return;
            }
            reported.add(result.deliveryId());
            if (player != null && player.isOnline()) {
                tell(player, result);
            }
            runFallback(fallback, lines.refused());
            done.complete(new Outcome(result, lines.refused(), null));
        }));
        return done;
    }

    private CompletableFuture<DeliveryResult> attempt(DeliveryRequest request, int attempt) {
        CompletableFuture<DeliveryResult> call;
        try {
            call = api.deliver(request);
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        return call.handle((result, error) -> {
            if (error == null) {
                return CompletableFuture.completedFuture(result);
            }
            if (attempt >= 2 || !retryable(error)) {
                return CompletableFuture.<DeliveryResult>failedFuture(error);
            }
            CompletableFuture<DeliveryResult> again = new CompletableFuture<>();
            CompletableFuture.delayedExecutor(RETRY_DELAY_SECONDS, TimeUnit.SECONDS).execute(() ->
                    attempt(request, attempt + 1).whenComplete((r, e) -> {
                        if (e != null) {
                            again.completeExceptionally(e);
                        } else {
                            again.complete(r);
                        }
                    }));
            return again;
        }).thenCompose(f -> f);
    }

    /** Whether the notice of {@code deliveryId} was already told by this server (the player saw where the items went). */
    public boolean wasReported(Long deliveryId) {
        return deliveryId != null && reported.contains(deliveryId);
    }

    static void tell(Player player, DeliveryResult result) {
        for (String line : InboxText.delivered(result.inboxedUnits(), result.lostUnits(), null)) {
            player.sendMessage((line.startsWith("Your reward inbox is full") ? ChatColor.RED : ChatColor.YELLOW) + line);
        }
    }

    static Lines lines(List<Leftover> leftovers, Function<ItemStack, ItemIdentityResolution> resolver) {
        Map<Integer, Long> amounts = new LinkedHashMap<>();
        List<ItemStack> refused = new ArrayList<>();
        for (Leftover leftover : leftovers) {
            ItemStack stack = leftover.item();
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                continue;
            }
            Integer blueprintId = leftover.blueprintId();
            ItemIdentityResolution resolution = null;
            if (resolver != null) {
                try {
                    resolution = resolver.apply(stack);
                } catch (RuntimeException e) {
                    LOGGER.log(Level.FINE, "Reward inbox: item identity failed for " + stack.getType(), e);
                }
            }
            if (resolution != null && resolution.kind() == ItemIdentityResolution.Kind.INSTANCE) {
                refused.add(stack); // unique: can't be claimed back in game yet (#897)
                continue;
            }
            if (resolution != null && resolution.resolved() && resolution.blueprintId() != null) {
                blueprintId = resolution.blueprintId();
            }
            if (blueprintId == null) {
                refused.add(stack);
                continue;
            }
            amounts.merge(blueprintId, (long) stack.getAmount(), Long::sum);
        }
        List<DeliveryRequest.Line> lines = new ArrayList<>();
        amounts.forEach((id, amount) -> lines.add(DeliveryRequest.Line.of(id, amount)));
        return new Lines(lines, refused);
    }

    private static void runFallback(Consumer<List<ItemStack>> fallback, List<ItemStack> stacks) {
        if (stacks.isEmpty() || fallback == null) {
            return;
        }
        try {
            fallback.accept(stacks);
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Reward inbox: the fallback delivery failed for " + describe(stacks), e);
        }
    }

    static ApiException apiException(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ApiException found) {
                return found;
            }
        }
        return null;
    }

    /** No answer (no status) or a server error: worth one more try with the same key. */
    static boolean retryable(Throwable error) {
        ApiException api = apiException(error);
        return api == null || api.getStatusCode() <= 0 || api.getStatusCode() >= 500;
    }

    static boolean productionDisabled(Throwable error) {
        ApiException api = apiException(error);
        return api != null && api.getStatusCode() == 409 && api.getResponseBody() != null
                && api.getResponseBody().contains("ProductionDisabled");
    }

    static String why(Throwable error) {
        if (productionDisabled(error)) {
            return "the API's Production:Enabled is off";
        }
        ApiException api = apiException(error);
        if (api != null && api.getStatusCode() > 0) {
            return "HTTP " + api.getStatusCode();
        }
        Throwable t = error;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    static String describe(List<ItemStack> stacks) {
        List<String> parts = new ArrayList<>();
        for (ItemStack stack : stacks) {
            parts.add(stack.getAmount() + "x " + stack.getType());
        }
        return String.join(", ", parts);
    }
}
