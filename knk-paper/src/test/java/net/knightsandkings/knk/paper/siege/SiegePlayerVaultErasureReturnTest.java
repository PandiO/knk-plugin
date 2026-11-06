package net.knightsandkings.knk.paper.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.SiegeDataAccess;
import net.knightsandkings.knk.core.ports.api.SiegeMatchesCommandApi;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;

/**
 * PandiO/knk-workspace#184, developer decision 2026-11-06 on LR1-8: an online player's siege vault goes back to them
 * before the erasure kick, exactly once, and the erasure's discard step then finds nothing to give or delete.
 * <p>
 * The player is dead in these tests: the alive path reads {@code Attribute.MAX_HEALTH}, which needs a running server's
 * registry. The inventory part, the forgetting and the counts are the same on both paths.
 */
class SiegePlayerVaultErasureReturnTest {

    @TempDir
    Path dir;

    /** Kept in a field: a Location holds its world only weakly (CLAUDE.md, knk-plugin tests). */
    private final World world = mock(World.class);
    private final UUID playerId = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final List<LogRecord> logged = new ArrayList<>();
    private SiegePlayerVault vault;
    private int afterRestore;

    @BeforeEach
    void setUp() {
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getInventory()).thenReturn(inventory);
        when(player.isDead()).thenReturn(true);
        when(player.isOnline()).thenReturn(true);
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        vault = new SiegePlayerVault(dir.toFile(), logger);
        vault.setAfterRestore(p -> afterRestore++);
    }

    private static ItemStack item() {
        ItemStack item = mock(ItemStack.class);
        when(item.isEmpty()).thenReturn(false);
        when(item.clone()).thenReturn(item);
        return item;
    }

    private SiegePlayerVault.Snapshot snapshot(boolean inventoryRestored, ItemStack... contents) {
        return new SiegePlayerVault.Snapshot(playerId, 1, "token", Instant.now(), contents, 3, 0.5f, 20.0, 20, 5f,
                List.of(), null, new Location(world, 1, 64, 1), inventoryRestored);
    }

    private SiegeService service() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        return new SiegeService(plugin, mock(SiegeDataAccess.class), null, mock(SiegeMatchesCommandApi.class), vault,
                mock(KnkPermissible.class), mock(UserCache.class), RandomGenerator.of("L64X128MixRandom"));
    }

    @Test
    void heldItemStacks_CountsTheStacksStillToGiveBack() {
        ItemStack empty = mock(ItemStack.class);
        when(empty.isEmpty()).thenReturn(true);
        vault.holdForTest(snapshot(false, item(), null, empty, item()));

        assertEquals(2, vault.heldItemStacks(playerId));
        assertEquals(0, vault.heldItemStacks(UUID.randomUUID()));
        assertEquals(0, vault.heldItemStacks(null));
    }

    @Test
    void theVaultGoesBackOnce_ThenTheQuitAJoinAndTheDiscardFindNothingToGive() {
        vault.holdForTest(snapshot(false, item(), item()));
        SiegeService service = service();

        assertEquals(2, service.returnVaultBeforeErasure(player));

        verify(inventory, times(1)).clear();
        verify(inventory, times(1)).setContents(any(ItemStack[].class));
        assertEquals(1, afterRestore); // the siege-enchantment sweep ran
        assertFalse(vault.has(playerId));
        assertFalse(Files.exists(dir.resolve(playerId + ".yml")));

        // The kick's quit, a later join and a second erasure (push + catch-up) give nothing again.
        vault.restoreOnQuit(player);
        assertFalse(vault.restoreOnJoin(player));
        assertEquals(0, service.returnVaultBeforeErasure(player));
        verify(inventory, times(1)).setContents(any(ItemStack[].class));
        assertEquals(1, afterRestore);
        // The discard step only clears the respawn point a dead player was given; no inventory left to drop.
        assertEquals(1, vault.discard(playerId));
        assertEquals(0, vault.discard(playerId));
    }

    @Test
    void aVaultWhoseInventoryTheQuitAlreadyGaveBack_GivesNoItemsAgain() {
        vault.holdForTest(snapshot(true, item(), item()));

        assertEquals(0, service().returnVaultBeforeErasure(player));

        verify(inventory, never()).setContents(any(ItemStack[].class));
        verify(inventory, never()).clear();
        assertFalse(vault.has(playerId));
    }

    @Test
    void noVault_NothingHappens() {
        assertEquals(0, service().returnVaultBeforeErasure(player));
        assertEquals(0, service().returnVaultBeforeErasure(null));

        verify(inventory, never()).setContents(any(ItemStack[].class));
        assertEquals(0, afterRestore);
    }

    @Test
    void anUnreadableFile_IsNotGivenBack_NorLogged_AndTheDiscardStillRemovesIt() throws Exception {
        Path file = Files.writeString(dir.resolve(playerId + ".yml"), "items: \"%%% not base64 %%%\"\n");

        assertEquals(0, vault.heldItemStacks(playerId));
        assertTrue(logged.isEmpty(), "the erasure path logs counts only, not the vault file's name");

        assertEquals(1, vault.discard(playerId));
        assertFalse(Files.exists(file));
        verify(inventory, never()).setContents(any(ItemStack[].class));
    }
}
