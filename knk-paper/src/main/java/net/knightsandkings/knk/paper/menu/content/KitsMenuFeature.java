package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.dataaccess.KitsDataAccess;
import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.core.domain.item.KnkKit;
import net.knightsandkings.knk.core.domain.item.KnkKitAvailability;
import net.knightsandkings.knk.core.domain.item.KnkKitContent;
import net.knightsandkings.knk.core.domain.material.KnkMinecraftMaterialRef;
import net.knightsandkings.knk.core.menu.ConditionOutcome;
import net.knightsandkings.knk.core.menu.MenuActionException;
import net.knightsandkings.knk.core.menu.MenuSession;
import net.knightsandkings.knk.paper.kit.KitGrantFlow;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuContentSourceContext;
import net.knightsandkings.knk.paper.menu.MenuFeature;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import org.bukkit.entity.Player;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Content port CP2 ({@code docs/specs/inventory-menu/CONTENT_PORT_PLAN.md} §4): the
 * {@code kits.overview} menu. Registers
 * <ul>
 *   <li>row source {@code kits.available} → {@link KitMenuRow}: the viewer's
 *       {@code GET /api/kits/available?userId=} list (cached per viewer for
 *       {@link #AVAILABILITY_TTL_MILLIS}, dropped after a claim/purchase) joined with each kit's
 *       contents ({@link KitsDataAccess}, cache-first by kit id) and their item blueprints
 *       ({@link ItemBlueprintsDataAccess}, cache-first by id) - no per-row-per-tick calls;</li>
 *   <li>action {@code kits.claim {kitId}} → {@link KitGrantFlow#claim} (server-side
 *       {@code ClaimKitAsync} applies every permission/cooldown/cost rule; its denial is shown in
 *       chat), then the menu repaints;</li>
 *   <li>action {@code kits.purchase {kitId}} → {@link KitGrantFlow#purchase}; the template only
 *       ever reaches it through {@code menu.confirm.request};</li>
 *   <li>condition {@code kits.purchase-pending}: allows while this session's pending
 *       confirmation is a {@code kits.purchase} - the template's Confirm/Cancel buttons use it
 *       instead of the generic {@code has-pending-confirmation}, so a confirmation left pending
 *       in another menu never shows (or gets accepted) here.</li>
 * </ul>
 * The command {@code /kit get|purchase} runs the same {@link KitGrantFlow} methods.
 */
public final class KitsMenuFeature implements MenuFeature {

    public static final String MENU_KEY = "kits.overview";
    public static final String ROWS_SOURCE = "kits.available";
    public static final String CLAIM_ACTION = "kits.claim";
    public static final String PURCHASE_ACTION = "kits.purchase";
    public static final String PURCHASE_PENDING_CONDITION = "kits.purchase-pending";
    static final long AVAILABILITY_TTL_MILLIS = 5_000L;

    private static final Logger LOGGER = Logger.getLogger(KitsMenuFeature.class.getName());

    private record CachedAvailability(long fetchedAtMillis, List<KnkKitAvailability> kits) {
    }

    private final KitsDataAccess kitsDataAccess;
    private final ItemBlueprintsDataAccess itemBlueprintsDataAccess;
    private final MinecraftMaterialRefsDataAccess materialRefsDataAccess;
    private final KitGrantFlow kitGrantFlow;
    private final Clock clock;
    private final Map<Integer, CachedAvailability> availabilityByUser = new ConcurrentHashMap<>();

    public KitsMenuFeature(KitsDataAccess kitsDataAccess, ItemBlueprintsDataAccess itemBlueprintsDataAccess,
                           MinecraftMaterialRefsDataAccess materialRefsDataAccess, KitGrantFlow kitGrantFlow, Clock clock) {
        this.kitsDataAccess = kitsDataAccess;
        this.itemBlueprintsDataAccess = itemBlueprintsDataAccess;
        this.materialRefsDataAccess = materialRefsDataAccess;
        this.kitGrantFlow = kitGrantFlow;
        this.clock = clock;
    }

    @Override
    public void registerMenuHandlers(MenuFeatureRegistries registries) {
        registries.contentSources().registerRows(ROWS_SOURCE, KitMenuRow.class,
                (context, params, query) -> fetchRows(context));
        registries.actions().register(CLAIM_ACTION, this::claim);
        registries.actions().register(PURCHASE_ACTION, this::purchase);
        registries.conditions().register(PURCHASE_PENDING_CONDITION, (context, params) -> purchasePending(context));
    }

    // ===== row source =====

    /** Main thread. Completed future when everything is cached, else completes when the lookups do. */
    CompletableFuture<Page<KitMenuRow>> fetchRows(MenuContentSourceContext context) {
        Player player = context.player();
        Integer userId = player != null ? kitGrantFlow.resolveUserId(player) : null;
        if (userId == null) {
            return CompletableFuture.completedFuture(page(List.of(KitMenuRow.none(clock))));
        }
        return availability(userId)
                .thenCompose(this::toRows)
                .thenApply(KitsMenuFeature::page)
                .exceptionally(ex -> {
                    LOGGER.log(Level.WARNING, "kits.available: failed to load kits for user " + userId, ex);
                    return page(List.of());
                });
    }

    private CompletableFuture<List<KnkKitAvailability>> availability(int userId) {
        CachedAvailability cached = availabilityByUser.get(userId);
        long now = clock.millis();
        if (cached != null && now - cached.fetchedAtMillis() < AVAILABILITY_TTL_MILLIS) {
            return CompletableFuture.completedFuture(cached.kits());
        }
        return kitsDataAccess.getAvailableForUserAsync(userId).thenApply(kits -> {
            List<KnkKitAvailability> list = kits != null ? List.copyOf(kits) : List.of();
            availabilityByUser.put(userId, new CachedAvailability(clock.millis(), list));
            return list;
        });
    }

    /** Drops the viewer's cached availability so the next render shows the new cooldown/purchase state. */
    void invalidate(int userId) {
        availabilityByUser.remove(userId);
    }

    private CompletableFuture<List<KitMenuRow>> toRows(List<KnkKitAvailability> kits) {
        Map<Integer, CompletableFuture<KnkKit>> kitFutures = new HashMap<>();
        for (KnkKitAvailability availability : kits) {
            kitFutures.put(availability.kitId(), kitsDataAccess.getByIdAsync(availability.kitId())
                    .thenApply(result -> valueOrNull(result))
                    .exceptionally(ex -> null));
        }
        return CompletableFuture.allOf(kitFutures.values().toArray(new CompletableFuture[0]))
                .thenCompose(unused -> {
                    Set<Integer> blueprintIds = new LinkedHashSet<>();
                    kitFutures.values().forEach(future -> blueprintIds.addAll(blueprintIds(future.join())));
                    Map<Integer, CompletableFuture<KitMenuRow.BlueprintInfo>> infoFutures = new HashMap<>();
                    for (Integer id : blueprintIds) {
                        infoFutures.put(id, blueprintInfo(id));
                    }
                    return CompletableFuture.allOf(infoFutures.values().toArray(new CompletableFuture[0]))
                            .thenApply(done -> {
                                Map<Integer, KitMenuRow.BlueprintInfo> infos = new HashMap<>();
                                infoFutures.forEach((id, future) -> {
                                    KitMenuRow.BlueprintInfo info = future.join();
                                    if (info != null) {
                                        infos.put(id, info);
                                    }
                                });
                                List<KitMenuRow> rows = new ArrayList<>();
                                if (kits.isEmpty()) {
                                    rows.add(KitMenuRow.none(clock));
                                }
                                for (KnkKitAvailability availability : kits) {
                                    rows.add(KitMenuRow.of(availability, kitFutures.get(availability.kitId()).join(), infos, clock));
                                }
                                return rows;
                            });
                });
    }

    private static Set<Integer> blueprintIds(KnkKit kit) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (kit == null) {
            return ids;
        }
        for (Integer id : new Integer[] {kit.helmetId(), kit.chestplateId(), kit.leggingsId(), kit.bootsId(),
                kit.handId(), kit.shieldId()}) {
            if (id != null) {
                ids.add(id);
            }
        }
        if (kit.contents() != null) {
            for (KnkKitContent content : kit.contents()) {
                ids.add(content.itemBlueprintId());
            }
        }
        return ids;
    }

    private CompletableFuture<KitMenuRow.BlueprintInfo> blueprintInfo(int blueprintId) {
        return itemBlueprintsDataAccess.getByIdAsync(blueprintId)
                .thenCompose(result -> {
                    KnkItemBlueprint blueprint = valueOrNull(result);
                    if (blueprint == null) {
                        return CompletableFuture.completedFuture((KitMenuRow.BlueprintInfo) null);
                    }
                    String name = blueprint.defaultDisplayName() != null && !blueprint.defaultDisplayName().isBlank()
                            ? blueprint.defaultDisplayName() : blueprint.name();
                    return materialKey(blueprint).thenApply(key -> new KitMenuRow.BlueprintInfo(name, key));
                })
                .exceptionally(ex -> null);
    }

    /** Same resolution as {@code KitGrantPlacer}: the material ref's namespace key, else the blueprint's own. */
    private CompletableFuture<String> materialKey(KnkItemBlueprint blueprint) {
        if (blueprint.iconMaterialRefId() != null && blueprint.iconMaterialRefId() > 0) {
            return materialRefsDataAccess.getByIdAsync(blueprint.iconMaterialRefId()).thenApply(result -> {
                KnkMinecraftMaterialRef ref = valueOrNull(result);
                return ref != null && ref.namespaceKey() != null && !ref.namespaceKey().isBlank()
                        ? ref.namespaceKey() : blueprint.iconNamespaceKey();
            });
        }
        return CompletableFuture.completedFuture(blueprint.iconNamespaceKey());
    }

    private static <T> T valueOrNull(FetchResult<T> result) {
        return result != null ? result.value().orElse(null) : null;
    }

    private static <T> Page<T> page(List<T> rows) {
        return new Page<>(rows, rows.size(), 1, Math.max(1, rows.size()));
    }

    // ===== actions =====

    private void claim(MenuActionContext context, Map<String, String> params) {
        Player player = context.player();
        int kitId = kitId(params, CLAIM_ACTION);
        Integer userId = kitGrantFlow.requireUserId(player);
        if (userId == null) {
            return;
        }
        kitGrantFlow.claim(player, userId, kitId, kitName(userId, kitId))
                .whenComplete((ok, ex) -> afterMutation(context, userId));
    }

    private void purchase(MenuActionContext context, Map<String, String> params) {
        Player player = context.player();
        int kitId = kitId(params, PURCHASE_ACTION);
        Integer userId = kitGrantFlow.requireUserId(player);
        if (userId == null) {
            return;
        }
        kitGrantFlow.purchase(player, userId, kitId, kitName(userId, kitId))
                .whenComplete((ok, ex) -> afterMutation(context, userId));
    }

    /** Main thread (the flow completes there): new state on the next render, and repaint now. */
    private void afterMutation(MenuActionContext context, int userId) {
        invalidate(userId);
        if (context.menuService() != null) {
            context.menuService().refreshOpenMenu(context.player());
        }
    }

    /** The name the viewer saw on the row (cached availability), for the chat feedback. */
    private String kitName(int userId, int kitId) {
        CachedAvailability cached = availabilityByUser.get(userId);
        if (cached != null) {
            for (KnkKitAvailability availability : cached.kits()) {
                if (availability.kitId() == kitId && availability.name() != null && !availability.name().isBlank()) {
                    return availability.name();
                }
            }
        }
        return "Kit #" + kitId;
    }

    private static int kitId(Map<String, String> params, String actionTypeId) {
        String raw = params.get("kitId");
        try {
            return Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            throw new MenuActionException(actionTypeId + " action needs a numeric 'kitId' param, got '" + raw + "'");
        }
    }

    private static ConditionOutcome purchasePending(MenuActionContext context) {
        MenuSession session = context.session();
        boolean pending = session != null && session.getPendingConfirmation()
                .map(p -> PURCHASE_ACTION.equals(p.actionTypeId()))
                .orElse(false);
        return pending ? ConditionOutcome.allow() : ConditionOutcome.deny("Nothing to confirm.");
    }
}
