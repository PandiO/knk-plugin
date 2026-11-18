package net.knightsandkings.knk.paper.realtime;

import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.BanList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PandiO/knk-workspace#574: a web ban is a permanent entry on the server's profile ban list (what /pardon removes),
 * with the reason and the staff member as source. MockBukkit 26.3 has no ban lists ({@code getBanList} is not
 * implemented), so the list is a Mockito mock.
 */
class BukkitProfileBansTest {

    private static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @SuppressWarnings("unchecked")
    private final BanList<PlayerProfile> list = mock(BanList.class);
    private final PlayerProfile profile = mock(PlayerProfile.class);

    private BukkitProfileBans bans() {
        return new BukkitProfileBans(() -> list, (uuid, name) -> STEVE.equals(uuid) ? profile : null);
    }

    @Test
    void banIsPermanentWithReasonAndSource() {
        bans().ban(STEVE, "Steve", "X-ray", "Mod");
        verify(list).addBan(eq(profile), eq("X-ray"), isNull(Instant.class), eq("Mod"));
    }

    @Test
    void isBannedAsksTheListByProfile() {
        when(list.isBanned(profile)).thenReturn(true);
        assertTrue(bans().isBanned(STEVE, null));
    }
}
