package net.knightsandkings.knk.paper.enchantment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.DataAccessSettings;
import net.knightsandkings.knk.core.dataaccess.FetchPolicy;
import net.knightsandkings.knk.core.dataaccess.PermissionsDataAccess;
import net.knightsandkings.knk.core.dataaccess.RetryPolicy;
import net.knightsandkings.knk.core.domain.permissions.EffectivePermission;
import net.knightsandkings.knk.core.domain.permissions.EffectivePermissionSet;
import net.knightsandkings.knk.core.domain.permissions.PermissionCheckResult;
import net.knightsandkings.knk.core.domain.permissions.PermissionHolder;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.PermissionsApi;
import net.knightsandkings.knk.paper.commands.support.CommandPermissions;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;

/**
 * PandiO/knk-workspace#193: custom enchantments were op-only (Bukkit checks on undeclared nodes). A rank
 * grant - one enchantment's node or the group node {@code customenchantments.use.*} - now lets a non-op use
 * them, through the real in-house resolver (the player's effective set, as at join).
 */
class EnchantmentPermissionsTest {

    private static final PermissionHolder DEFAULT_RANK = new PermissionHolder(1, "Group", "Default");

    private final UserCache userCache = new UserCache(Duration.ofMinutes(15));
    private final Map<Integer, List<EffectivePermission>> grants = new ConcurrentHashMap<>();
    private final PermissionsDataAccess access = new PermissionsDataAccess(Duration.ofSeconds(30), new PermissionsApi() {
        @Override
        public CompletableFuture<PermissionCheckResult> check(int userId, String node) {
            return CompletableFuture.failedFuture(new AssertionError("checks resolve from the effective set"));
        }

        @Override
        public CompletableFuture<EffectivePermissionSet> getEffective(int userId) {
            return CompletableFuture.completedFuture(new EffectivePermissionSet(userId, grants.getOrDefault(userId, List.of())));
        }
    }, new DataAccessSettings(FetchPolicy.CACHE_FIRST, true, RetryPolicy.noRetry()));
    private final KnkPermissible permissible = new KnkPermissible(userCache, access);
    private final EnchantmentPermissions permissions =
        new EnchantmentPermissions(CommandPermissions.of(permissible, Runnable::run));
    private int nextUserId = 1;

    EnchantmentPermissionsTest() {
        access.resolveFromEffectiveSets(Duration.ofSeconds(90));
    }

    /** A non-op player whose rank holds {@code nodes} (a node starting with "-" is a deny), loaded as at join. */
    private Player player(String... nodes) {
        int userId = nextUserId++;
        UUID uuid = UUID.randomUUID();
        userCache.put(new UserSummary(userId, "p" + userId, uuid, 0));
        List<EffectivePermission> set = new ArrayList<>();
        for (String node : nodes) {
            boolean deny = node.startsWith("-");
            set.add(new EffectivePermission(deny ? node.substring(1) : node, !deny, DEFAULT_RANK, 0));
        }
        grants.put(userId, set);
        permissible.preload(uuid).join();

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("p" + userId);
        when(player.hasPermission(anyString())).thenReturn(false); // undeclared/default: op nodes
        return player;
    }

    @Test
    void withoutAGrant_aNonOpCannotUseAnyEnchantment() {
        Player player = player();

        assertFalse(permissions.canUse(player, "chaos"));
        assertFalse(permissions.canUse(player, "poison"));
    }

    @Test
    void aRankGrantOfOneEnchantmentAllowsThatOneOnly() {
        Player player = player("customenchantments.chaos");

        assertTrue(permissions.canUse(player, "chaos"));
        assertTrue(permissions.canUse(player, "CHAOS"), "ids are matched lower-case");
        assertFalse(permissions.canUse(player, "invisibility"));
    }

    @Test
    void theGroupWildcardCoversEveryEnchantment_alsoOnesAddedLater() {
        Player player = player(EnchantmentPermissions.USE_ALL);

        for (String id : List.of("chaos", "flash_chaos", "health_boost", "armor_repair", "resistance", "invisibility",
                "poison", "cleaving", "meteor_shower_added_later")) {
            assertTrue(permissions.canUse(player, id), id);
        }
    }

    @Test
    void theGroupWildcardDoesNotGrantTheCeAdminNodes() {
        Player player = player(EnchantmentPermissions.USE_ALL);

        assertFalse(permissions.commands().has(player, "customenchantments.command.add"));
        assertFalse(permissions.commands().has(player, "customenchantments.command.reload"));
        assertFalse(permissions.commands().has(player, "customenchantments.command.cooldown.clear.others"));
    }

    @Test
    void aDenyOfOneEnchantmentBeatsTheGroupWildcard() {
        Player player = player(EnchantmentPermissions.USE_ALL, "-customenchantments.use.chaos");

        assertFalse(permissions.canUse(player, "chaos"));
        assertTrue(permissions.canUse(player, "invisibility"));
    }

    @Test
    void aRankGrantOfACeSubcommandNodeIsHonoured() {
        Player player = player("customenchantments.command.cooldown.clear");

        assertTrue(permissions.commands().has(player, "customenchantments.command.cooldown.clear"));
        assertFalse(permissions.commands().has(player, "customenchantments.command.cooldown.clear.others"));
    }

    @Test
    void anOpMayUseEveryEnchantment_withoutAnyRankGrant() {
        Player op = mock(Player.class);
        when(op.isOp()).thenReturn(true);
        when(op.getUniqueId()).thenReturn(UUID.randomUUID());
        when(op.hasPermission(anyString())).thenReturn(true); // default: op

        assertTrue(permissions.canUse(op, "chaos"));
        assertTrue(EnchantmentPermissions.bukkitOnly().canUse(op, "chaos"), "Bukkit fallback without the API");
    }

    @Test
    void withoutTheApi_aNonOpIsStillRefused() {
        assertFalse(EnchantmentPermissions.bukkitOnly().canUse(player(EnchantmentPermissions.USE_ALL), "chaos"));
    }

    @Test
    void theConsoleMayAlwaysUseThem() {
        assertTrue(permissions.canUse(mock(CommandSender.class), "chaos"));
    }

    @Test
    void nodeNames() {
        assertEquals("customenchantments.flash_chaos", EnchantmentPermissions.node("flash_chaos"));
        assertEquals("customenchantments.use.flash_chaos", EnchantmentPermissions.useNode(" Flash_Chaos "));
    }
}
