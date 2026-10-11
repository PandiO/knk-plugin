package net.knightsandkings.knk.paper.locations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.location.LocationOrphanDigest;
import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;
import net.knightsandkings.knk.core.domain.users.PlayerNotification;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** KNG-80: the orphan digest reaches online holders of knk.admin.location.orphans.notify only, or the next one to join. */
class LocationOrphanNotifierTest {

    private final Map<Player, List<String>> seen = new HashMap<>();
    private final Map<Player, Boolean> hasNode = new HashMap<>();
    private final List<Player> online = new ArrayList<>();

    private final LocationOrphanNotifier notifier = new LocationOrphanNotifier(
        (player, node) -> CompletableFuture.completedFuture(
            PermissionDecision.of(LocationOrphanNotifier.NOTIFY_NODE.equals(node) && hasNode.getOrDefault(player, false))),
        () -> (Collection<Player>) List.copyOf(online), Runnable::run);

    private Player player(String name, boolean holder) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        List<String> into = new ArrayList<>();
        seen.put(player, into);
        hasNode.put(player, holder);
        org.mockito.Mockito.doAnswer(inv -> into.add(PlainTextComponentSerializer.plainText().serialize(inv.getArgument(0))))
            .when(player).sendMessage(any(Component.class));
        org.mockito.Mockito.doAnswer(inv -> into.add(inv.getArgument(0))).when(player).sendMessage(anyString());
        return player;
    }

    private static PlayerNotification digest(int newCount, int openCount) {
        return new PlayerNotification(9, 0, null, "", PlayerNotification.TYPE_LOCATION_ORPHAN_DIGEST, null, null, null, null,
            new LocationOrphanDigest(12, newCount, openCount));
    }

    @Test
    void onlyHoldersOfTheNotifyNodeSeeTheDigest() {
        Player staff = player("mod", true);
        Player regular = player("steve", false);
        online.addAll(List.of(staff, regular));

        notifier.handle(digest(3, 7));

        assertEquals(List.of("[Locations] 3 orphaned Locations need review (7 open in total). Click to list them."), seen.get(staff));
        assertTrue(seen.get(regular).isEmpty());
    }

    @Test
    void withNoHolderOnline_theNextHolderToJoinGetsIt_once() {
        Player regular = player("steve", false);
        online.add(regular);
        notifier.handle(digest(1, 1));

        Player anotherRegular = player("alex", false);
        notifier.showUndelivered(anotherRegular);
        Player staff = player("mod", true);
        notifier.showUndelivered(staff);
        notifier.showUndelivered(staff);

        assertTrue(seen.get(regular).isEmpty());
        assertTrue(seen.get(anotherRegular).isEmpty());
        assertEquals(List.of("[Locations] 1 orphaned Location needs review (1 open in total). Click to list them."), seen.get(staff));
    }

    @Test
    void aDeliveredDigest_isNotShownAgainOnJoin() {
        Player staff = player("mod", true);
        online.add(staff);
        notifier.handle(digest(2, 2));

        Player later = player("admin", true);
        notifier.showUndelivered(later);

        assertTrue(seen.get(later).isEmpty());
    }

    @Test
    void aNotificationWithoutItsPayload_isIgnored() {
        Player staff = player("mod", true);
        online.add(staff);

        notifier.handle(new PlayerNotification(4, 0, null, "", PlayerNotification.TYPE_LOCATION_ORPHAN_DIGEST, null));

        assertTrue(seen.get(staff).isEmpty());
    }
}
