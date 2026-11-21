package net.knightsandkings.knk.paper.production.harvest;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.metrics.PluginMetrics;
import net.knightsandkings.knk.core.ports.api.HarvestApi;
import net.knightsandkings.knk.core.production.harvest.BlockStates;
import net.knightsandkings.knk.core.production.harvest.HarvestArea;
import net.knightsandkings.knk.core.production.harvest.HarvestAreaIndex;
import net.knightsandkings.knk.core.production.harvest.HarvestCatalog;
import net.knightsandkings.knk.core.production.harvest.HarvestCooldowns;
import net.knightsandkings.knk.core.production.harvest.HarvestGate;
import net.knightsandkings.knk.core.production.harvest.HarvestOutbox;
import net.knightsandkings.knk.core.production.harvest.HarvestRule;
import net.knightsandkings.knk.core.production.harvest.HarvestSettings;
import net.knightsandkings.knk.core.production.harvest.OwedPay;
import net.knightsandkings.knk.core.production.harvest.RegenJob;
import net.knightsandkings.knk.core.production.harvest.RegenQueue;
import net.knightsandkings.knk.core.production.harvest.RegenWorld;
import net.knightsandkings.knk.core.production.harvest.RequirementEvaluator;
import net.knightsandkings.knk.core.production.harvest.TreeFeller;
import net.knightsandkings.knk.core.production.survey.AreaShape;
import net.knightsandkings.knk.core.production.survey.AreaSpec;
import net.knightsandkings.knk.core.production.survey.PlaceholderOverlay;
import net.knightsandkings.knk.paper.production.terminal.BlueprintItems;

/**
 * Player harvesting in yield areas and block regeneration (PandiO/knk-workspace#134, PRODUCTION_KINDS § 6-§ 7, A16),
 * only with {@code production.enabled: true}:
 * <ol>
 *   <li>reads the harvest catalogue ({@code GET api/Harvest/catalog}) every {@code catalog-refresh-minutes}: the enabled
 *       rules and the areas they apply in. Until it is read, or while the API's flag is off, nothing is harvested and
 *       breaks behave as without harvesting;</li>
 *   <li>a break or kill of a rule's target in an area ({@link HarvestGate}): a qualified player's break is let through
 *       WorldGuard's deny of the production region ({@link HarvestWorldGuardListener}); its vanilla drops are dropped, the
 *       block gets its placeholder (depleted block, replanted crop, air, or a sapling for a whole felled tree) and a
 *       regeneration job, and the break joins a report entry. A target the player may not harvest is refused with the
 *       reason; staff with {@code bypass} are left to WorldGuard;</li>
 *   <li>sends the entries every {@code report-flush-seconds} ({@code POST api/Harvest/reports}, one key per entry, sent
 *       again after a failure with the same key) and hands each player the answer's {@code workerUnits} of the rule's
 *       blueprint (in the inventory; offline players get it when they join); a Rejected entry pays nothing;</li>
 *   <li>restores due jobs within {@code regen-blocks-per-tick} (timed as {@code production.harvest.regen}), only when
 *       their chunks are loaded and every position still holds its placeholder, never into a player or mob;</li>
 *   <li>keeps jobs, unanswered entries and owed shares on disk ({@link HarvestStore}), so they survive restarts.</li>
 * </ol>
 * Its {@link #overlay()} lets surveys count blocks waiting to regenerate as their original (L12).
 */
public final class HarvestModule implements Listener {

    /** A decision made at LOW for one break, used by the later handlers of the same event. */
    private record Pending(UUID player, HarvestGate.Decision decision) {
    }

    private record BlockKey(UUID world, int x, int y, int z) {
    }

    private final Plugin plugin;
    private final HarvestSettings settings;
    private final HarvestApi api;
    private final BlueprintItems items;
    private final Function<UUID, Integer> userIds;
    private final RequirementEvaluator requirements;
    private final Runnable refreshRequirements;
    private final AreaSpec.RegionShapes regions;
    private final Predicate<Player> bypass;
    private final BiConsumer<Player, Collection<String>> warmPermissions;
    private final HarvestStore store;
    private final Clock clock;
    private final RegenWorld world;
    private final RegenQueue queue;
    private final HarvestOutbox outbox;
    private final OwedPay owed = new OwedPay();
    private final HarvestCooldowns cooldowns = new HarvestCooldowns();
    private final Map<BlockKey, Pending> pending = new HashMap<>();
    private final Map<Integer, Double> liveFill = new ConcurrentHashMap<>();
    private final Map<String, Long> lastMessage = new HashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final ExecutorService saver;
    private volatile HarvestGate gate;
    private volatile String lastCatalog = "never";
    private volatile String lastReport = "never";
    private boolean flushing;
    private long harvests;
    private long paidUnits;
    private Listener worldGuardListener;

    /**
     * @param userIds      a player's API user id from the user cache, or null when unknown
     * @param bypass       staff whose breaks of targets are left to WorldGuard (not refused, not harvested)
     * @param warmPermissions fills a player's permission cache for the rules' nodes (join, catalogue refresh)
     * @param dataDir      where the harvest files live
     */
    public HarvestModule(Plugin plugin, HarvestSettings settings, HarvestApi api, BlueprintItems items,
                         Function<UUID, Integer> userIds, RequirementEvaluator requirements, Runnable refreshRequirements,
                         AreaSpec.RegionShapes regions, Predicate<Player> bypass,
                         BiConsumer<Player, Collection<String>> warmPermissions, String serverId, Path dataDir,
                         Clock clock) {
        this(plugin, settings, api, items, userIds, requirements, refreshRequirements, regions, bypass, warmPermissions,
                serverId, dataDir, clock, new BukkitRegenWorld());
    }

    HarvestModule(Plugin plugin, HarvestSettings settings, HarvestApi api, BlueprintItems items,
                  Function<UUID, Integer> userIds, RequirementEvaluator requirements, Runnable refreshRequirements,
                  AreaSpec.RegionShapes regions, Predicate<Player> bypass,
                  BiConsumer<Player, Collection<String>> warmPermissions, String serverId, Path dataDir, Clock clock,
                  RegenWorld world) {
        this.plugin = plugin;
        this.settings = settings;
        this.api = api;
        this.items = items;
        this.userIds = userIds;
        this.requirements = requirements == null ? RequirementEvaluator.ALWAYS : requirements;
        this.refreshRequirements = refreshRequirements == null ? () -> { } : refreshRequirements;
        this.regions = regions == null ? AreaSpec.RegionShapes.NONE : regions;
        this.bypass = bypass == null ? p -> false : bypass;
        this.warmPermissions = warmPermissions;
        this.store = new HarvestStore(dataDir, plugin.getLogger());
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.world = world;
        this.queue = new RegenQueue(TimeUnit.SECONDS.toMillis(settings.regenRetrySeconds()));
        this.outbox = new HarvestOutbox(serverId);
        this.gate = newGate(HarvestCatalog.EMPTY, HarvestAreaIndex.EMPTY);
        this.saver = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "KnK-harvest-save");
            thread.setDaemon(true);
            return thread;
        });
        queue.setLog(message -> plugin.getLogger().info("Harvest: " + message));
    }

    public void start() {
        int jobs = queue.load(store.read(HarvestStore.REGEN));
        List<String> broken = new ArrayList<>();
        int entries = outbox.load(store.read(HarvestStore.OUTBOX), broken);
        broken.forEach(line -> plugin.getLogger().warning("Harvest: skipped a broken outbox line: " + line));
        int owing = owed.load(store.read(HarvestStore.OWED));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        try {
            worldGuardListener = new HarvestWorldGuardListener(this);
            Bukkit.getPluginManager().registerEvents(worldGuardListener, plugin);
        } catch (LinkageError e) {
            plugin.getLogger().warning("Harvest: WorldGuard events unavailable (" + e + "); production regions keep "
                    + "denying breaks");
        }
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, PluginMetrics.timed("production.harvest.regen", this::tick), 1, 1));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::flush, 40L, settings.reportFlushSeconds() * 20L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::refreshCatalog, 40L,
                TimeUnit.MINUTES.toSeconds(settings.catalogRefreshMinutes()) * 20L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::saveIfDirty, settings.saveSeconds() * 20L,
                settings.saveSeconds() * 20L));
        plugin.getLogger().info("Player harvesting is on (production.enabled): " + jobs + " blocks waiting to regenerate, "
                + entries + " harvests to report, " + owing + " shares owed; catalogue every "
                + settings.catalogRefreshMinutes() + " min.");
    }

    public void stop() {
        HandlerList.unregisterAll(this);
        if (worldGuardListener != null) {
            HandlerList.unregisterAll(worldGuardListener);
        }
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        saver.shutdown();
        try {
            saver.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        store.write(HarvestStore.REGEN, queue.encode());
        store.write(HarvestStore.OUTBOX, outbox.encode());
        store.write(HarvestStore.OWED, owed.encode());
    }

    // ----- state -----

    /** Surveys count blocks waiting to regenerate as their original (L12, PRODUCTION_KINDS § 3.2). */
    public PlaceholderOverlay overlay() {
        return queue.overlay();
    }

    public RegenQueue queue() {
        return queue;
    }

    public HarvestOutbox outbox() {
        return outbox;
    }

    public HarvestGate gate() {
        return gate;
    }

    public HarvestSettings settings() {
        return settings;
    }

    /** One line per fact, for {@code /knk production harvest status}. */
    public List<String> status() {
        HarvestCatalog catalog = gate.catalog();
        List<String> lines = new ArrayList<>();
        lines.add("Catalogue: " + lastCatalog + "; API flag " + (catalog.featureEnabled() ? "on" : "off") + ", "
                + catalog.rules().size() + " rules, " + gate.index().entries().size() + " areas");
        gate.index().skipped().forEach(reason -> lines.add("  area left out: " + reason));
        lines.add("Regenerating: " + queue.size() + " jobs (" + queue.waitingCount() + " waiting for their chunk); "
                + queue.restoredTotal() + " restored, " + queue.droppedTotal() + " dropped since start");
        lines.add("Reports: " + outbox.size() + " entries waiting (" + outbox.inFlight() + " sent); last " + lastReport);
        lines.add("Harvests since start: " + harvests + ", " + paidUnits + " units paid; " + owed.players()
                + " offline players owed a share");
        return lines;
    }

    // ----- catalogue -----

    /** Re-reads the catalogue (and the title brackets) now. */
    public CompletableFuture<HarvestCatalog> refreshCatalog() {
        refreshRequirements.run();
        return api.catalog().whenComplete((catalog, error) -> onMain(() -> {
            if (error != null) {
                lastCatalog = "failed at " + Instant.now(clock) + ": " + describe(error);
                plugin.getLogger().fine("Harvest catalogue read failed: " + describe(error));
                return;
            }
            apply(catalog);
        }));
    }

    /** Main thread: uses {@code catalog} from now on (tests and scenarios call it with a stand-in). */
    public void apply(HarvestCatalog catalog) {
        HarvestAreaIndex index = HarvestAreaIndex.build(catalog.areas(), this::shapeOf, settings.maxAreaChunks());
        liveFill.clear();
        gate = newGate(catalog, index);
        lastCatalog = Instant.now(clock) + " (" + catalog.rules().size() + " rules, " + index.entries().size() + " areas)";
        if (warmPermissions != null) {
            Set<String> nodes = nodes(catalog);
            if (!nodes.isEmpty()) {
                Bukkit.getOnlinePlayers().forEach(player -> warmPermissions.accept(player, nodes));
            }
        }
    }

    private HarvestGate newGate(HarvestCatalog catalog, HarvestAreaIndex index) {
        return new HarvestGate(catalog, index, requirements, cooldowns, queue::regenerating, settings.denyAtEmptyPool(),
                area -> liveFill.getOrDefault(area.areaId(), area.poolFill()));
    }

    private AreaShape shapeOf(HarvestArea area) {
        World w = area.spec().worldName() == null ? null : Bukkit.getWorld(area.spec().worldName());
        if (w == null) {
            throw new IllegalArgumentException("world " + area.spec().worldName() + " is not loaded");
        }
        return area.spec().resolve(regions, w.getMinHeight(), w.getMaxHeight());
    }

    private static Set<String> nodes(HarvestCatalog catalog) {
        Set<String> nodes = new LinkedHashSet<>();
        for (HarvestRule rule : catalog.rules()) {
            if (rule.requiredPermission() != null) {
                nodes.add(rule.requiredPermission());
            }
        }
        return nodes;
    }

    // ----- deciding -----

    /** The decision for {@code player} breaking {@code block} now (main thread). */
    public HarvestGate.Decision decide(Player player, Block block) {
        HarvestGate.Decision decision = gate.block(player.getUniqueId(), block.getWorld().getName(), block.getX(),
                block.getY(), block.getZ(), block.getBlockData().getAsString(), tool(player), clock.millis());
        return withAccount(player, decision);
    }

    /** The decision for {@code player} killing {@code entity} now (main thread). */
    public HarvestGate.Decision decide(Player player, Entity entity) {
        var at = entity.getLocation();
        HarvestGate.Decision decision = gate.entity(player.getUniqueId(), entity.getWorld().getName(), at.getBlockX(),
                at.getBlockY(), at.getBlockZ(), entity.getType().name(), tool(player), clock.millis());
        return withAccount(player, decision);
    }

    private HarvestGate.Decision withAccount(Player player, HarvestGate.Decision decision) {
        if (decision.kind() == HarvestGate.Kind.ALLOW && userIds.apply(player.getUniqueId()) == null) {
            return new HarvestGate.Decision(HarvestGate.Kind.DENY, decision.rule(), decision.area(),
                    "your account is still loading");
        }
        return decision;
    }

    private static HarvestGate.Tool tool(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            return HarvestGate.Tool.HAND;
        }
        return new HarvestGate.Tool(hand.getType().name(), hand.getEnchantmentLevel(Enchantment.FORTUNE));
    }

    /** The LOW decision for this break, if it allowed a harvest by {@code player} (the WorldGuard listener asks). */
    boolean allowed(Player player, Block block) {
        Pending p = pending.get(key(block));
        if (p == null) {
            // WorldGuard asked before this plugin's LOW handler ran: decide now, the later handlers decide again
            return !bypass.test(player) && decide(player, block).kind() == HarvestGate.Kind.ALLOW;
        }
        return p.player().equals(player.getUniqueId()) && p.decision().kind() == HarvestGate.Kind.ALLOW;
    }

    /** Whether {@code player} may harvest {@code entity} (the WorldGuard damage check asks). */
    boolean allowed(Player player, Entity entity) {
        return !bypass.test(player) && decide(player, entity).kind() == HarvestGate.Kind.ALLOW;
    }

    // ----- block breaks -----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreakDecide(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        pending.remove(key(block));
        if (bypass.test(player)) {
            return;
        }
        HarvestGate.Decision decision = decide(player, block);
        switch (decision.kind()) {
            case IGNORE -> { }
            case DENY -> {
                event.setCancelled(true);
                tell(player, decision.rule() == null ? settings.messages().regenerating()
                        : settings.messages().denied().replace("{reason}", decision.reason()));
            }
            case ALLOW -> pending.put(key(block), new Pending(player.getUniqueId(), decision));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreakDrops(BlockBreakEvent event) {
        if (allowed(event.getPlayer(), event.getBlock())) {
            event.setDropItems(false); // the rule's blueprint items replace the vanilla drops (§ 6.2)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakDone(BlockBreakEvent event) {
        Pending p = pending.remove(key(event.getBlock()));
        if (p == null || event.isCancelled() || !p.player().equals(event.getPlayer().getUniqueId())) {
            return;
        }
        harvest(event.getPlayer(), event.getBlock(), p.decision());
    }

    /** Records a harvest of {@code block} (still holding the original) by {@code player}: job, placeholder, report entry. */
    void harvest(Player player, Block block, HarvestGate.Decision decision) {
        HarvestRule rule = decision.rule();
        HarvestArea area = decision.area();
        Integer userId = userIds.apply(player.getUniqueId());
        if (userId == null) {
            return;
        }
        long now = clock.millis();
        String worldName = block.getWorld().getName();
        List<RegenJob.Block> blocks = new ArrayList<>();
        int breaks = 1;
        String original = block.getBlockData().getAsString();
        switch (rule.placeholder()) {
            case DEPLETED_BLOCK -> blocks.add(new RegenJob.Block(block.getX(), block.getY(), block.getZ(), original,
                    depleted(rule, block.getWorld())));
            case REPLANT -> blocks.add(new RegenJob.Block(block.getX(), block.getY(), block.getZ(), original,
                    BlockStates.withAgeZero(original)));
            case AIR -> blocks.add(new RegenJob.Block(block.getX(), block.getY(), block.getZ(), original, AIR));
            case SAPLING -> {
                AreaShape shape = shapeIn(area);
                TreeFeller.Tree tree = TreeFeller.find((x, y, z) -> block.getWorld().getBlockAt(x, y, z).getBlockData()
                                .getAsString(), shape == null ? (x, y, z) -> true : shape::contains,
                        block.getX(), block.getY(), block.getZ(), settings.treeMaxLogs(), settings.treeMaxLeaves());
                if (tree == null) {
                    blocks.add(new RegenJob.Block(block.getX(), block.getY(), block.getZ(), original,
                            sapling(block.getWorld(), block.getX(), block.getY(), block.getZ(),
                                    TreeFeller.saplingOf(species(BlockStates.material(original))))));
                } else {
                    String sapling = sapling(block.getWorld(), tree.base().x(), tree.base().y(), tree.base().z(),
                            TreeFeller.saplingOf(tree.species()));
                    for (TreeFeller.Pos pos : TreeFeller.all(tree)) {
                        boolean base = pos.equals(tree.base());
                        blocks.add(new RegenJob.Block(pos.x(), pos.y(), pos.z(), pos.blockData(), base ? sapling : AIR));
                    }
                    breaks = tree.logs().size();
                }
            }
            case NONE -> { }
        }
        String entryKey = outbox.add(player.getUniqueId(), userId, rule.id(), area.areaId(), tool(player).fortuneLevel(),
                now, breaks);
        cooldowns.started(player.getUniqueId(), area.areaId(), rule, now);
        harvests++;
        if (blocks.isEmpty()) {
            return;
        }
        RegenJob job = new RegenJob(UUID.randomUUID().toString(), worldName, now, now + rule.regenSeconds() * 1000L,
                entryKey, blocks);
        queue.add(job);
        // the server removes the broken block after this event: placeholders go down on the next tick
        Bukkit.getScheduler().runTask(plugin, () -> placePlaceholders(job));
    }

    private void placePlaceholders(RegenJob job) {
        for (RegenJob.Block b : job.blocks()) {
            String now = BlockStates.material(world.blockData(job.world(), b.x(), b.y(), b.z()));
            if (now == null) {
                continue;
            }
            // the broken block is air now; the rest of a felled tree still holds its original
            if (now.equals(b.originalMaterial()) || now.equals("AIR") || now.equals(b.placeholderMaterial())) {
                world.set(job.world(), b.x(), b.y(), b.z(), b.placeholder());
            }
        }
    }

    private AreaShape shapeIn(HarvestArea area) {
        for (HarvestAreaIndex.Entry entry : gate.index().entries()) {
            if (entry.area().areaId() == area.areaId()) {
                return entry.shape();
            }
        }
        return null;
    }

    static final String AIR = "minecraft:air";

    /** The rule's depleted block, else the world's default: netherrack in the nether, end stone in the end, else stone. */
    static String depleted(HarvestRule rule, World world) {
        Material material = rule.placeholderMaterial() == null ? null : Material.matchMaterial(rule.placeholderMaterial());
        if (material == null || !material.isBlock()) {
            material = switch (world.getEnvironment()) {
                case NETHER -> Material.NETHERRACK;
                case THE_END -> Material.END_STONE;
                default -> Material.STONE;
            };
        }
        return material.createBlockData().getAsString();
    }

    /** A sapling of {@code saplingName} when the ground under (x, y, z) takes one, else air. */
    static String sapling(World world, int x, int y, int z, String saplingName) {
        Material sapling = Material.matchMaterial(saplingName);
        if (sapling == null || !sapling.isBlock()) {
            sapling = Material.OAK_SAPLING;
        }
        Material ground = world.getBlockAt(x, y - 1, z).getType();
        boolean fungus = sapling == Material.CRIMSON_FUNGUS || sapling == Material.WARPED_FUNGUS;
        boolean fits = fungus ? (ground == Material.CRIMSON_NYLIUM || ground == Material.WARPED_NYLIUM || Tag.DIRT.isTagged(ground))
                : Tag.DIRT.isTagged(ground) || ground == Material.FARMLAND;
        return fits ? sapling.createBlockData().getAsString() : AIR;
    }

    private static String species(String material) {
        for (String suffix : new String[] {"_LOG", "_WOOD", "_STEM", "_HYPHAE"}) {
            if (material != null && material.endsWith(suffix)) {
                return material.substring(0, material.length() - suffix.length());
            }
        }
        return "OAK";
    }

    /** A sapling standing in for a felled tree must not grow into a new tree (the snapshot comes back instead, P-D8). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent event) {
        var at = event.getLocation();
        if (queue.regenerating(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        queue.chunkLoaded(event.getWorld().getName(), event.getChunk().getX(), event.getChunk().getZ(), clock.millis());
    }

    // ----- entities -----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player player = attacker(event.getDamager());
        if (player == null || bypass.test(player) || !(event.getEntity() instanceof LivingEntity)) {
            return;
        }
        HarvestGate.Decision decision = decide(player, event.getEntity());
        if (decision.kind() == HarvestGate.Kind.DENY) {
            event.setCancelled(true);
            tell(player, settings.messages().denied().replace("{reason}", decision.reason()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        Player player = event.getEntity().getKiller();
        if (player == null || bypass.test(player) || event.getEntity() instanceof Player) {
            return;
        }
        HarvestGate.Decision decision = decide(player, event.getEntity());
        if (decision.kind() != HarvestGate.Kind.ALLOW) {
            return;
        }
        Integer userId = userIds.apply(player.getUniqueId());
        if (userId == null) {
            return;
        }
        event.getDrops().clear(); // the rule's blueprint items replace the vanilla drops (§ 6.2)
        long now = clock.millis();
        outbox.add(player.getUniqueId(), userId, decision.rule().id(), decision.area().areaId(), tool(player).fortuneLevel(),
                now);
        cooldowns.started(player.getUniqueId(), decision.area().areaId(), decision.rule(), now);
        harvests++;
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    // ----- regeneration -----

    void tick() {
        RegenQueue.TickResult result = queue.tick(world, clock.millis(), settings.regenBlocksPerTick());
        if (result.dropped() > 0) {
            plugin.getLogger().fine("Harvest: " + result.dropped() + " regeneration jobs dropped (blocks changed)");
        }
        if (clock.millis() % 60_000 < 50) {
            cooldowns.prune(clock.millis());
        }
    }

    // ----- reports -----

    /** Sends what is waiting now (unless a report is on its way). */
    public void flush() {
        if (flushing) {
            return;
        }
        List<HarvestOutbox.Entry> batch = outbox.take(settings.reportBatch(), clock.millis());
        if (batch.isEmpty()) {
            return;
        }
        flushing = true;
        List<HarvestApi.ReportEntry> entries = batch.stream()
                .map(e -> new HarvestApi.ReportEntry(e.key(), e.userId(), e.ruleId(), e.areaId(), e.breaks(),
                        e.fortuneLevel(), Instant.ofEpochMilli(e.harvestedAt())))
                .toList();
        Map<String, HarvestOutbox.Entry> byKey = new HashMap<>();
        batch.forEach(e -> byKey.put(e.key(), e));
        CompletableFuture<HarvestApi.ReportResult> sent;
        try {
            sent = api.report(entries);
        } catch (RuntimeException e) {
            sent = CompletableFuture.failedFuture(e);
        }
        sent.whenComplete((result, error) -> onMain(() -> {
            flushing = false;
            if (error != null) {
                failed(byKey, error);
            } else {
                answered(byKey, result);
            }
            saveNow();
        }));
    }

    private void answered(Map<String, HarvestOutbox.Entry> sent, HarvestApi.ReportResult result) {
        int booked = 0;
        int rejected = 0;
        Map<String, HarvestOutbox.Entry> unanswered = new HashMap<>(sent);
        for (HarvestApi.EntryResult row : result.entries()) {
            HarvestOutbox.Entry entry = unanswered.remove(row.idempotencyKey());
            if (entry == null) {
                continue;
            }
            outbox.answered(entry.key());
            if (row.booked()) {
                booked++;
                queue.setRegenSeconds(entry.key(), row.regenSeconds());
                if (row.poolCapacity() > 0) {
                    liveFill.put(entry.areaId(), Math.max(0, Math.min(1, row.poolAfter() / row.poolCapacity())));
                }
                if (row.itemBlueprintId() != null && row.workerUnits() > 0) {
                    pay(entry.player(), row.itemBlueprintId(), row.workerUnits());
                }
            } else {
                rejected++;
                plugin.getLogger().info("Harvest entry " + entry.key() + " rejected: " + row.error());
                Player player = Bukkit.getPlayer(entry.player());
                if (player != null) {
                    tell(player, settings.messages().rejected().replace("{area}", areaName(entry.areaId()))
                            .replace("{reason}", row.error() == null ? "refused" : row.error()));
                }
            }
        }
        if (!unanswered.isEmpty()) {
            outbox.failed(unanswered.keySet(), clock.millis());
        }
        lastReport = Instant.now(clock) + ": " + booked + " booked, " + rejected + " rejected"
                + (unanswered.isEmpty() ? "" : ", " + unanswered.size() + " without an answer (sent again)");
    }

    private void failed(Map<String, HarvestOutbox.Entry> sent, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        int status = cause instanceof ApiException api ? api.getStatusCode() : 0;
        if (status == 409 || status == 400 || status == 403 || status == 404) {
            // the API refuses the whole report (409 ProductionDisabled, a malformed or forbidden report): nothing was
            // booked and sending it again changes nothing, so the entries are given up and nobody is paid
            sent.keySet().forEach(outbox::answered);
            lastReport = Instant.now(clock) + ": refused (" + status + "), " + sent.size() + " entries not booked";
            plugin.getLogger().warning("Harvest report refused by the API (" + status + "): " + describe(cause)
                    + "; " + sent.size() + " entries not booked");
            Set<UUID> told = new java.util.HashSet<>();
            for (HarvestOutbox.Entry entry : sent.values()) {
                Player player = Bukkit.getPlayer(entry.player());
                if (player != null && told.add(entry.player())) {
                    tell(player, settings.messages().rejected().replace("{area}", areaName(entry.areaId()))
                            .replace("{reason}", status == 409 ? "production is switched off" : "the report was refused"));
                }
            }
            return;
        }
        outbox.failed(sent.keySet(), clock.millis());
        lastReport = Instant.now(clock) + ": failed (" + describe(cause) + "), " + sent.size() + " entries sent again later";
        plugin.getLogger().fine("Harvest report failed: " + describe(cause));
    }

    private String areaName(int areaId) {
        for (HarvestAreaIndex.Entry entry : gate.index().entries()) {
            if (entry.area().areaId() == areaId) {
                String name = entry.area().spec().structureName();
                return name == null || name.isBlank() ? "area #" + areaId : name;
            }
        }
        return "area #" + areaId;
    }

    // ----- pay -----

    /** Hands {@code units} of the blueprint to the player (inventory, the rest at their feet), or owes it until they join. */
    void pay(UUID playerId, int blueprintId, long units) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            owed.add(playerId, blueprintId, units);
            return;
        }
        items.template(blueprintId, "Blueprint #" + blueprintId).whenComplete((template, error) -> onMain(() -> {
            Player online = Bukkit.getPlayer(playerId);
            if (error != null || template == null || online == null) {
                owed.add(playerId, blueprintId, units);
                return;
            }
            long left = units;
            while (left > 0) {
                int amount = (int) Math.min(left, Math.max(1, template.maxStack()));
                ItemStack stack = template.copy(amount);
                for (ItemStack over : online.getInventory().addItem(stack).values()) {
                    online.getWorld().dropItem(online.getLocation(), over);
                }
                left -= amount;
            }
            paidUnits += units;
            tell(online, settings.messages().paid().replace("{amount}", String.valueOf(units))
                    .replace("{item}", itemName(template.item())), true);
        }));
    }

    private static String itemName(ItemStack item) {
        var meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return ChatColor.stripColor(meta.getDisplayName());
        }
        String name = item.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (warmPermissions != null) {
            Set<String> nodes = nodes(gate.catalog());
            if (!nodes.isEmpty()) {
                warmPermissions.accept(player, nodes);
            }
        }
        Map<Integer, Long> due = owed.take(player.getUniqueId());
        if (!due.isEmpty()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> due.forEach((blueprint, units) ->
                    pay(player.getUniqueId(), blueprint, units)), 40L);
        }
    }

    // ----- saving and messages -----

    private void saveIfDirty() {
        if (queue.dirty() || outbox.dirty() || owed.dirty()) {
            saveNow();
        }
    }

    private void saveNow() {
        List<String> regen = queue.dirty() ? queue.encode() : null;
        List<String> entries = outbox.dirty() ? outbox.encode() : null;
        List<String> owing = owed.dirty() ? owed.encode() : null;
        if (saver.isShutdown()) {
            return;
        }
        saver.execute(() -> {
            if (regen != null) {
                store.write(HarvestStore.REGEN, regen);
            }
            if (entries != null) {
                store.write(HarvestStore.OUTBOX, entries);
            }
            if (owing != null) {
                store.write(HarvestStore.OWED, owing);
            }
        });
    }

    private void tell(Player player, String message) {
        tell(player, message, false);
    }

    /** At most one of the same message per player every 2 s (a player holding the button breaks many times). */
    private void tell(Player player, String message, boolean always) {
        String key = player.getUniqueId() + "|" + message;
        long now = clock.millis();
        Long last = lastMessage.get(key);
        if (!always && last != null && now - last < 2000) {
            return;
        }
        if (lastMessage.size() > 1000) {
            lastMessage.clear();
        }
        lastMessage.put(key, now);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    private static BlockKey key(Block block) {
        return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    static String describe(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }

    private void onMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
