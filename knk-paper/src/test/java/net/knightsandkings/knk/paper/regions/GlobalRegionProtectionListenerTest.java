package net.knightsandkings.knk.paper.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;

/**
 * The global-region rule (QOL hotfix 2026-10-25). Everything at x &lt; 1000 is outside every region (only
 * {@code __global__}); from x = 1000 on a domain region applies and the rule stays out of it.
 */
class GlobalRegionProtectionListenerTest {
    private static final int IN_TOWN = 1000;

    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicBoolean bypass = new AtomicBoolean(false);
    private final AtomicLong now = new AtomicLong(10_000L);
    private GlobalRegionProtectionListener listener;
    private World world;
    private Player player;

    @BeforeEach
    void setUp() {
        listener = new GlobalRegionProtectionListener(enabled::get, null,
            location -> location.getBlockX() >= IN_TOWN ? Set.of("domain_12") : Set.of(), p -> bypass.get(), now::get);
        world = mock(World.class);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    }

    private Location at(int x) {
        return new Location(world, x, 64, 7);
    }

    private Block block(Material type, int x) {
        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(at(x));
        when(block.getType()).thenReturn(type);
        return block;
    }

    private <E extends Entity> E entity(Class<E> type, int x) {
        E entity = mock(type);
        when(entity.getLocation()).thenReturn(at(x));
        return entity;
    }

    private BlockPlaceEvent place(int x) {
        Block placed = block(Material.STONE, x); // stub the block before stubbing the event (no nested stubbing)
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlock()).thenReturn(placed);
        return event;
    }

    // ---- blocks ----

    @Test
    void breakingOutsideEveryRegionIsRefusedAndAnnounced() {
        BlockBreakEvent event = new BlockBreakEvent(block(Material.STONE, 5), player);
        listener.onBreak(event);

        assertTrue(event.isCancelled());
        verify(player).sendActionBar(any(Component.class));
    }

    @Test
    void placingOutsideEveryRegionIsRefused() {
        BlockPlaceEvent event = place(5);
        listener.onPlace(event);

        verify(event).setCancelled(true);
    }

    @Test
    void insideARegionTheRegionDecides() {
        BlockBreakEvent breaking = new BlockBreakEvent(block(Material.STONE, IN_TOWN), player);
        BlockPlaceEvent placing = place(IN_TOWN + 3);
        listener.onBreak(breaking);
        listener.onPlace(placing);

        assertFalse(breaking.isCancelled());
        verify(placing, never()).setCancelled(true);
        verify(player, never()).sendActionBar(any(Component.class));
    }

    @Test
    void bypassHoldersAreNotRestricted() {
        bypass.set(true);
        BlockBreakEvent breaking = new BlockBreakEvent(block(Material.STONE, 5), player);
        PlayerInteractEvent crafting = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null,
            block(Material.CRAFTING_TABLE, 5), BlockFace.UP);
        listener.onBreak(breaking);
        listener.onInteract(crafting);

        assertFalse(breaking.isCancelled());
        assertEquals(Event.Result.ALLOW, crafting.useInteractedBlock());
    }

    @Test
    void switchedOffInConfigDoesNothing() {
        enabled.set(false);
        BlockBreakEvent breaking = new BlockBreakEvent(block(Material.STONE, 5), player);
        listener.onBreak(breaking);
        EntityExplodeEvent explosion = explosion(List.of(block(Material.STONE, 5)));
        listener.onEntityExplode(explosion);

        assertFalse(breaking.isCancelled());
        assertEquals(1, explosion.blockList().size());
    }

    // ---- explosions ----

    private EntityExplodeEvent explosion(List<Block> blocks) {
        return new EntityExplodeEvent(entity(TNTPrimed.class, 5), at(5), new ArrayList<>(blocks), 4f, ExplosionResult.DESTROY);
    }

    @Test
    void explosionsBreakNothingOutsideARegionButStillBreakInsideOne() {
        Block outside = block(Material.STONE, 5);
        Block inside = block(Material.STONE, IN_TOWN);
        EntityExplodeEvent event = explosion(List.of(outside, inside));
        listener.onEntityExplode(event);

        assertFalse(event.isCancelled());
        assertEquals(List.of(inside), event.blockList());
    }

    // ---- item frames, paintings, armor stands ----

    @Test
    void decorationsCannotBePlacedBrokenOrUsedOutsideARegion() {
        ItemFrame frame = entity(ItemFrame.class, 5);
        Painting painting = entity(Painting.class, 5);
        ArmorStand stand = entity(ArmorStand.class, 5);

        HangingPlaceEvent placing = new HangingPlaceEvent(painting, player, block(Material.STONE, 5), BlockFace.NORTH, EquipmentSlot.HAND);
        listener.onHangingPlace(placing);
        assertTrue(placing.isCancelled());

        EntityPlaceEvent standing = new EntityPlaceEvent(stand, player, block(Material.STONE, 5), BlockFace.UP);
        listener.onEntityPlace(standing);
        assertTrue(standing.isCancelled());

        HangingBreakByEntityEvent breaking = new HangingBreakByEntityEvent(frame, player, mock(DamageSource.class));
        listener.onHangingBreak(breaking);
        assertTrue(breaking.isCancelled());

        EntityDamageByEntityEvent hitting = new EntityDamageByEntityEvent(player, frame,
            EntityDamageEvent.DamageCause.ENTITY_ATTACK, mock(DamageSource.class), 1.0);
        listener.onDamage(hitting);
        assertTrue(hitting.isCancelled());

        PlayerInteractEntityEvent rotating = new PlayerInteractEntityEvent(player, frame);
        listener.onInteractEntity(rotating);
        assertTrue(rotating.isCancelled());

        PlayerArmorStandManipulateEvent dressing = new PlayerArmorStandManipulateEvent(player, stand,
            mock(ItemStack.class), mock(ItemStack.class), EquipmentSlot.HEAD);
        listener.onArmorStandManipulate(dressing);
        assertTrue(dressing.isCancelled());
    }

    @Test
    void anArrowOrAnExplosionCannotTakeAFrameDownOutsideARegion() {
        ItemFrame frame = entity(ItemFrame.class, 5);
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(player);

        HangingBreakByEntityEvent shot = new HangingBreakByEntityEvent(frame, arrow, mock(DamageSource.class));
        listener.onHangingBreak(shot);
        assertTrue(shot.isCancelled());

        HangingBreakByEntityEvent blown = new HangingBreakByEntityEvent(frame, entity(TNTPrimed.class, 5),
            mock(DamageSource.class), HangingBreakByEntityEvent.RemoveCause.EXPLOSION);
        listener.onHangingBreak(blown);
        assertTrue(blown.isCancelled());

        EntityDamageByEntityEvent blast = new EntityDamageByEntityEvent(entity(TNTPrimed.class, 5), entity(ArmorStand.class, 5),
            EntityDamageEvent.DamageCause.ENTITY_EXPLOSION, mock(DamageSource.class), 9.0);
        listener.onDamage(blast);
        assertTrue(blast.isCancelled());
    }

    @Test
    void decorationsInsideARegionAreLeftToTheRegion() {
        ItemFrame frame = entity(ItemFrame.class, IN_TOWN);
        PlayerInteractEntityEvent rotating = new PlayerInteractEntityEvent(player, frame);
        listener.onInteractEntity(rotating);

        assertFalse(rotating.isCancelled());
    }

    // ---- crafting blocks; doors and chests are not looked at ----

    @Test
    void craftingBlocksCannotBeUsedOutsideARegionDoorsAndChestsCan() {
        PlayerInteractEvent crafting = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null,
            block(Material.CRAFTING_TABLE, 5), BlockFace.UP);
        PlayerInteractEvent smelting = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null,
            block(Material.FURNACE, 5), BlockFace.UP);
        PlayerInteractEvent door = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null,
            block(Material.OAK_DOOR, 5), BlockFace.UP);
        PlayerInteractEvent chest = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null,
            block(Material.CHEST, 5), BlockFace.UP);
        PlayerInteractEvent inTown = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null,
            block(Material.CRAFTING_TABLE, IN_TOWN), BlockFace.UP);

        listener.onInteract(crafting);
        listener.onInteract(smelting);
        listener.onInteract(door);
        listener.onInteract(chest);
        listener.onInteract(inTown);

        assertEquals(Event.Result.DENY, crafting.useInteractedBlock());
        assertEquals(Event.Result.DENY, smelting.useInteractedBlock());
        assertEquals(Event.Result.ALLOW, door.useInteractedBlock());
        assertEquals(Event.Result.ALLOW, chest.useInteractedBlock());
        assertEquals(Event.Result.ALLOW, inTown.useInteractedBlock());
    }

    @Test
    void theConfigListReplacesTheDefaultCraftingBlocksAndWarnsAboutUnknownNames() {
        List<String> warnings = new ArrayList<>();
        Set<Material> parsed = GlobalRegionProtectionListener.craftingBlocks(List.of("crafting_table", "no_such_block"), warnings::add);

        assertEquals(Set.of(Material.CRAFTING_TABLE), parsed);
        assertEquals(1, warnings.size());
        assertEquals(GlobalRegionProtectionListener.DEFAULT_CRAFTING_BLOCKS,
            GlobalRegionProtectionListener.craftingBlocks(List.of(), warnings::add));
    }

    // ---- notice ----

    @Test
    void everyActionIsRefusedButTheNoticeWaitsForTheCooldown() {
        BlockBreakEvent first = new BlockBreakEvent(block(Material.STONE, 1), player);
        BlockBreakEvent second = new BlockBreakEvent(block(Material.STONE, 2), player);
        BlockBreakEvent third = new BlockBreakEvent(block(Material.STONE, 3), player);

        listener.onBreak(first);
        now.addAndGet(GlobalRegionProtectionListener.NOTICE_COOLDOWN_MILLIS - 1);
        listener.onBreak(second);
        now.addAndGet(1);
        listener.onBreak(third);

        assertTrue(first.isCancelled());
        assertTrue(second.isCancelled());
        assertTrue(third.isCancelled());
        verify(player, times(2)).sendActionBar(any(Component.class));
    }
}
