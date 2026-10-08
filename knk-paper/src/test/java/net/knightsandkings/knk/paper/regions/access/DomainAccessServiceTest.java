package net.knightsandkings.knk.paper.regions.access;

import net.knightsandkings.knk.core.regions.RegionTransitionType;
import net.knightsandkings.knk.core.regions.access.RefusalGuard;
import net.knightsandkings.knk.core.regions.access.RegionAccessRules.AccessState;
import net.knightsandkings.knk.core.regions.access.RegionAccessRules.Refusal;
import net.knightsandkings.knk.core.regions.access.RegionAccessRules.RegionView;
import net.knightsandkings.knk.paper.events.UserDataLoadedEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KNG-56: domain AllowEntry/AllowExit from the flags on the WorldGuard regions - what WorldGuard's
 * session reports, mounting, respawning, the join check, the message throttle and the load guard.
 *
 * <p>The world is a line along x: 0 &lt;= x &lt; 10 is a district nobody may enter ("Old Quarter"),
 * -200 &lt; x &lt;= -100 a district nobody may leave ("Jail"); everything else has no regions.
 */
class DomainAccessServiceTest {

    // Location only holds its World through a WeakReference: keep the mock strongly reachable.
    private final World world = mock(World.class);
    private final Set<UUID> residents = new HashSet<>();
    private final List<Runnable> nextTick = new ArrayList<>();
    private long now = 1_000_000;
    private boolean worldGuardBypass;

    private record View(String regionId, String displayName, AccessState entry, AccessState exit, boolean isMember)
        implements RegionView {
    }

    private final DomainAccessService.RegionLookup lookup = new DomainAccessService.RegionLookup() {
        @Override
        public List<RegionView> at(Location location, Player player) {
            double x = location.getX();
            boolean resident = residents.contains(player.getUniqueId());
            if (x >= 0 && x < 10) {
                return List.of(new View("domain_7", "Old Quarter", AccessState.DENY, AccessState.ALLOW, resident));
            }
            if (x > -200 && x <= -100) {
                return List.of(new View("domain_8", "Jail", AccessState.ALLOW, AccessState.DENY, resident));
            }
            return List.of();
        }

        @Override
        public boolean hasWorldGuardBypass(Player player, Location location) {
            return worldGuardBypass;
        }
    };

    private final DomainAccessService access = new DomainAccessService(lookup,
        new RefusalGuard(RefusalGuard.Settings.defaults()), () -> now, nextTick::add);

    private Location at(double x) {
        return new Location(world, x, 64, 0);
    }

    private Player player(double x) {
        Player player = mock(Player.class);
        Location[] position = {at(x)};
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Noble");
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenAnswer(inv -> position[0].clone());
        when(player.teleport(any(Location.class))).thenAnswer(inv -> {
            position[0] = ((Location) inv.getArgument(0)).clone();
            return true;
        });
        return player;
    }

    private Optional<Refusal> cross(Player player, double fromX, double toX) {
        List<RegionView> before = lookup.at(at(fromX), player);
        List<RegionView> after = lookup.at(at(toX), player);
        List<RegionView> entered = after.stream().filter(v -> before.stream().noneMatch(b -> b.regionId().equals(v.regionId()))).toList();
        List<RegionView> exited = before.stream().filter(v -> after.stream().noneMatch(a -> a.regionId().equals(v.regionId()))).toList();
        return access.onCrossing(player, at(toX), entered, exited, true);
    }

    // ---- what WorldGuard's session reports (DomainAccessHandler) ----

    @Test
    void enteringAClosedDistrictIsRefusedOnEveryStepWithOneMessagePerTwoSeconds() {
        Player noble = player(-1);

        for (int step = 0; step < 10; step++) {
            assertTrue(cross(noble, -1, 0).isPresent(), "step " + step);
            now += 100;
        }

        verify(noble, times(1)).sendActionBar(any(Component.class));
        now += 2000;
        cross(noble, -1, 0);
        verify(noble, times(2)).sendActionBar(any(Component.class));
    }

    @Test
    void leavingAClosedDistrictIsRefused() {
        Optional<Refusal> refusal = cross(player(-150), -150, -250);

        assertEquals(RegionTransitionType.EXIT, refusal.orElseThrow().type());
        assertEquals("You are not allowed to leave Jail.", refusal.get().message());
    }

    @Test
    void residentsAndBypassHoldersPass() {
        Player resident = player(-1);
        residents.add(resident.getUniqueId());
        Player staff = player(-1);
        access.setBypass(p -> p == staff);
        Player op = player(-1);

        assertTrue(cross(resident, -1, 0).isEmpty());
        assertTrue(cross(staff, -1, 0).isEmpty());
        worldGuardBypass = true;
        assertTrue(cross(op, -1, 0).isEmpty(), "WorldGuard's region bypass counts too");
    }

    @Test
    void aMoveWorldGuardCannotCancelIsNotRefused() {
        assertTrue(access.onCrossing(player(-1), at(0), lookup.at(at(0), player(-1)), List.of(), false).isEmpty());
    }

    @Test
    void aFloodSendsThePlayerToSpawnAndThenKicksThem() {
        Player cheater = player(-1);
        DomainAccessService.Enforcer enforcer = mock(DomainAccessService.Enforcer.class);
        access.setEnforcer(enforcer);

        while (nextTick.isEmpty()) {
            cross(cheater, -1, 0);
            now += 5;  // 200 refusals a second
        }
        nextTick.remove(0).run();
        verify(enforcer).sendToSpawn(cheater);

        while (nextTick.isEmpty()) {
            cross(cheater, -1, 0);
            now += 5;
        }
        nextTick.remove(0).run();
        verify(enforcer).kick(any(Player.class), any(String.class));
    }

    @Test
    void pushingTheBorderAtWalkingPaceIsNeverPunished() {
        Player noble = player(-1);
        DomainAccessService.Enforcer enforcer = mock(DomainAccessService.Enforcer.class);
        access.setEnforcer(enforcer);

        for (int i = 0; i < 300; i++) {  // a minute at five refusals a second
            cross(noble, -1, 0);
            now += 200;
        }

        assertTrue(nextTick.isEmpty());
    }

    @Test
    void theTeleportPreviewUsesTheSameRules() {
        Player noble = player(-50);

        assertEquals("You are not allowed to enter Old Quarter.", access.preview(noble, at(-50), at(5)).orElseThrow().message());
        assertTrue(access.preview(noble, at(-50), at(-60)).isEmpty());
        verify(noble, never()).sendActionBar(any(Component.class));
    }

    @Test
    void aPluginMoveToUndoARefusalIsNotJudged() {
        Player prisoner = player(-150);
        boolean[] judged = {true};

        access.exemptWhile(prisoner, () -> judged[0] = cross(prisoner, -150, -250).isPresent());

        assertFalse(judged[0]);
        assertTrue(cross(prisoner, -150, -250).isPresent(), "only while the plugin moves them");
    }

    // ---- DomainAccessListener ----

    private final List<Player> resynced = new ArrayList<>();
    private final Consumer<Player> resync = resynced::add;

    @Test
    void mountingAHorseInsideAClosedDistrictIsRefused() {
        DomainAccessListener listener = new DomainAccessListener(access, resync, p -> false);
        Player noble = player(-1);
        Horse horse = mock(Horse.class);
        when(horse.getLocation()).thenReturn(at(1));

        EntityMountEvent event = new EntityMountEvent(noble, horse);
        listener.onMount(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void mountingAHorseOutsideADistrictYouMayNotLeaveIsRefused() {
        DomainAccessListener listener = new DomainAccessListener(access, resync, p -> false);
        Player prisoner = player(-199);
        Horse horse = mock(Horse.class);
        when(horse.getLocation()).thenReturn(at(-201));

        EntityMountEvent event = new EntityMountEvent(prisoner, horse);
        listener.onMount(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void mountingWithinTheSameDomainIsAllowed() {
        DomainAccessListener listener = new DomainAccessListener(access, resync, p -> false);
        Player prisoner = player(-150);
        Entity horse = mock(Horse.class);
        when(horse.getLocation()).thenReturn(at(-151));

        EntityMountEvent event = new EntityMountEvent(prisoner, horse);
        listener.onMount(event);

        assertFalse(event.isCancelled());
    }

    private PlayerRespawnEvent respawn(DomainAccessListener listener, Player player, double diedAtX, Location respawnPoint) {
        when(player.getLocation()).thenReturn(at(diedAtX));
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(player);
        listener.onDeath(death);
        PlayerRespawnEvent event = mock(PlayerRespawnEvent.class);
        Location[] location = {respawnPoint};
        when(event.getPlayer()).thenReturn(player);
        when(event.getRespawnLocation()).thenAnswer(inv -> location[0]);
        org.mockito.Mockito.doAnswer(inv -> location[0] = inv.getArgument(0)).when(event).setRespawnLocation(any(Location.class));
        listener.onRespawn(event);
        return event;
    }

    @Test
    void aBedInsideAClosedDistrictDoesNotRespawnYouThere() {
        DomainAccessListener listener = new DomainAccessListener(access, resync, p -> false);
        when(world.getSpawnLocation()).thenReturn(at(-50));
        Player noble = player(-50);

        PlayerRespawnEvent event = respawn(listener, noble, -50, at(5));

        assertEquals(-50, event.getRespawnLocation().getX(), "the world spawn instead");
        assertEquals(List.of(noble), resynced, "WorldGuard's session follows the corrected point");
    }

    @Test
    void dyingInADistrictYouMayNotLeaveRespawnsYouInsideIt() {
        DomainAccessListener listener = new DomainAccessListener(access, resync, p -> false);
        when(world.getSpawnLocation()).thenReturn(at(-50));
        Block top = mock(Block.class);
        when(top.isSolid()).thenReturn(true);
        when(top.getLocation()).thenReturn(at(-150).add(-0.5, 0, -0.5));
        when(world.getHighestBlockAt(any(Location.class))).thenReturn(top);
        Player prisoner = player(-150);

        PlayerRespawnEvent event = respawn(listener, prisoner, -150, at(-50));

        assertEquals(-150, event.getRespawnLocation().getX(), "on top of the death spot");
    }

    @Test
    void anAllowedRespawnPointIsLeftAlone() {
        DomainAccessListener listener = new DomainAccessListener(access, resync, p -> false);
        Player noble = player(-50);

        PlayerRespawnEvent event = respawn(listener, noble, -50, at(-60));

        assertEquals(-60, event.getRespawnLocation().getX());
        assertTrue(resynced.isEmpty());
    }

    @Test
    void siegeParticipantsKeepTheMatchRespawn() {
        DomainAccessListener listener = new DomainAccessListener(access, resync, p -> true);
        Player fighter = player(-50);

        PlayerRespawnEvent event = respawn(listener, fighter, -50, at(5));

        assertEquals(5, event.getRespawnLocation().getX());
    }

    @Test
    void joiningInsideAClosedDistrictSendsYouToTheWorldSpawnEvenWithoutTheApi() {
        DomainAccessListener listener = new DomainAccessListener(access, resync, p -> false);
        when(world.getSpawnLocation()).thenReturn(at(-50));
        Player noble = player(5);

        listener.onUserDataLoaded(new UserDataLoadedEvent(noble, null));

        assertEquals(-50, noble.getLocation().getX());
    }

    @Test
    void aResidentJoiningInsideStaysPut() {
        DomainAccessListener listener = new DomainAccessListener(access, resync, p -> false);
        Player resident = player(5);
        residents.add(resident.getUniqueId());

        listener.onUserDataLoaded(new UserDataLoadedEvent(resident, null));

        assertEquals(5, resident.getLocation().getX());
    }
}
