package net.knightsandkings.knk.paper.teleport;

import net.knightsandkings.knk.core.regions.RegionTransitionDecision;
import net.knightsandkings.knk.core.regions.RegionTransitionType;
import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.core.teleport.TeleportKind;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** FreezeTeleportRestriction and RegionTeleportRestriction (docs/specs/teleport/DESIGN.md §3.4 step 1). */
class TeleportRestrictionsTest {

    private final World world = mock(World.class);
    private final Player alice = player("Alice");
    private final Player bob = player("Bob");
    private final Location from = new Location(world, 0, 64, 0);
    private final Location to = new Location(world, 100, 64, 100);

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        return player;
    }

    private TeleportCheck check(TeleportKind kind, Player visited, Set<String> bypasses) {
        return new TeleportCheck(alice, from, to, kind, alice, visited, bypasses::contains);
    }

    @Test
    void frozenSubjectOrFrozenRequestTargetIsRefusedForPlayerTeleports() {
        FreezeTeleportRestriction aliceFrozen = new FreezeTeleportRestriction(id -> id.equals(alice.getUniqueId()));
        FreezeTeleportRestriction bobFrozen = new FreezeTeleportRestriction(id -> id.equals(bob.getUniqueId()));

        assertEquals(TeleportDenial.FROZEN, aliceFrozen.deny(check(TeleportKind.SPAWN, null, Set.of())).orElseThrow().code());
        assertEquals("Bob is frozen.", bobFrozen.deny(check(TeleportKind.REQUEST, bob, Set.of())).orElseThrow().message());
        assertTrue(aliceFrozen.deny(check(TeleportKind.STAFF, null, Set.of())).isEmpty(), "staff may move frozen players");
    }

    @Test
    void closedDomainIsRefusedWithTheListenersMessage() {
        RegionTeleportRestriction restriction = new RegionTeleportRestriction((player, destination) ->
            RegionTransitionDecision.deny(RegionTransitionType.ENTER, "You are not allowed to enter Kardenna."));

        Optional<TeleportDenial> denial = restriction.deny(check(TeleportKind.STAFF, null, Set.of()));

        assertEquals("You are not allowed to enter Kardenna.", denial.orElseThrow().message());
        assertEquals(Set.of(TeleportNodes.REGION_BYPASS), restriction.bypassNodes());
    }

    @Test
    void regionBypassOrAnAllowedMovePasses() {
        RegionTeleportRestriction closed = new RegionTeleportRestriction((player, destination) ->
            RegionTransitionDecision.deny(RegionTransitionType.EXIT, "You are not allowed to leave Jail."));
        RegionTeleportRestriction unknown = new RegionTeleportRestriction((player, destination) -> null);

        assertTrue(closed.deny(check(TeleportKind.STAFF, null, Set.of(TeleportNodes.REGION_BYPASS))).isEmpty());
        assertTrue(unknown.deny(check(TeleportKind.SPAWN, null, Set.of())).isEmpty());
    }
}
