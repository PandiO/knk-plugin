package net.knightsandkings.knk.paper.user;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.messaging.ParticipantId;
import net.knightsandkings.knk.core.messaging.PrivateMessageNodes;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import net.kyori.adventure.text.Component;

/** KNG-18 Phase 1: social spy audience via KnkPermissible, PDC toggle, owner exemption. */
class SpyServiceTest {

    private static final NamespacedKey KEY = new NamespacedKey("knightsandkings", "socialspy");
    private static final Component LINE = Component.text("[Spy] x -> y: z");

    private final KnkPermissible permissible = mock(KnkPermissible.class);
    private final Map<UUID, Set<String>> grants = new HashMap<>();
    private final Map<UUID, Byte> toggles = new HashMap<>();

    private final Player alice = player("Alice");
    private final Player bob = player("Bob");
    private final Player staff = player("Staff");
    private final Player owner = player("Owner");
    private final Player owner2 = player("Owner2");
    private final SpyService service = new SpyService(permissible, KEY, () -> List.of(alice, bob, staff, owner, owner2));

    SpyServiceTest() {
        when(permissible.hasPermissionAsync(any(), anyString())).thenAnswer(inv -> {
            OfflinePlayer who = inv.getArgument(0);
            String node = inv.getArgument(1);
            return CompletableFuture.completedFuture(grants.getOrDefault(who.getUniqueId(), Set.of()).contains(node));
        });
        grant(staff, PrivateMessageNodes.SOCIAL_SPY);
        grant(owner, PrivateMessageNodes.SOCIAL_SPY);
        grant(owner, PrivateMessageNodes.SOCIAL_SPY_EXEMPT);
        grant(owner2, PrivateMessageNodes.SOCIAL_SPY);
        grant(owner2, PrivateMessageNodes.SOCIAL_SPY_EXEMPT);
    }

    private Player player(String name) {
        Player player = mock(Player.class);
        UUID uuid = UUID.nameUUIDFromBytes(name.getBytes());
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.isOnline()).thenReturn(true);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(pdc.get(KEY, PersistentDataType.BYTE)).thenAnswer(inv -> toggles.get(uuid));
        org.mockito.Mockito.doAnswer(inv -> toggles.put(uuid, inv.getArgument(2)))
                .when(pdc).set(eq(KEY), eq(PersistentDataType.BYTE), any(Byte.class));
        when(player.getPersistentDataContainer()).thenReturn(pdc);
        return player;
    }

    private void grant(Player player, String node) {
        grants.computeIfAbsent(player.getUniqueId(), key -> new HashSet<>()).add(node);
    }

    private static ParticipantId id(Player player) {
        return ParticipantId.player(player.getUniqueId());
    }

    @Test
    void staffSeesOrdinaryPm_participantsAndPlayersDoNot() {
        service.refreshAll();

        service.broadcast(id(alice), id(bob), LINE);

        verify(staff).sendMessage(LINE);
        verify(owner).sendMessage(LINE);
        verify(alice, never()).sendMessage(any(Component.class));
        verify(bob, never()).sendMessage(any(Component.class));
    }

    @Test
    void spyingParticipant_doesNotGetTheirOwnPmTwice() {
        service.refreshAll();

        service.broadcast(id(staff), id(alice), LINE);

        verify(staff, never()).sendMessage(any(Component.class));
        verify(owner).sendMessage(LINE);
    }

    @Test
    void ownersPm_hiddenFromStaff_shownToOtherOwner() {
        service.refreshAll();

        service.broadcast(id(owner), id(alice), LINE);

        verify(staff, never()).sendMessage(any(Component.class));
        verify(owner2).sendMessage(LINE);
    }

    @Test
    void toggleOff_isRemembered_andStopsTheFeed() {
        service.refreshAll();
        assertTrue(service.isEnabled(staff));

        service.setEnabled(staff, false);
        service.broadcast(id(alice), id(bob), LINE);

        assertFalse(service.isEnabled(staff));
        verify(staff, never()).sendMessage(any(Component.class));
    }

    @Test
    void notRefreshedYet_orForgotten_seesNothing() {
        service.broadcast(id(alice), id(bob), LINE);
        verify(staff, never()).sendMessage(any(Component.class));

        service.refreshAll();
        service.forget(staff.getUniqueId());
        service.broadcast(id(alice), id(bob), LINE);
        verify(staff, never()).sendMessage(any(Component.class));
    }

    @Test
    void lateAnswerForAPlayerWhoQuit_doesNotReAddThem() {
        when(staff.isOnline()).thenReturn(false);

        service.refresh(staff);

        assertFalse(service.isInAudience(staff.getUniqueId()));
    }

    @Test
    void consoleIsNeverExempt() {
        service.refreshAll();

        assertFalse(service.isExempt(ParticipantId.CONSOLE));
        assertTrue(service.isExempt(id(owner)));
    }
}
