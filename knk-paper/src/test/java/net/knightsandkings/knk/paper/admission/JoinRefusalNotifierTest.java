package net.knightsandkings.knk.paper.admission;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.Test;

import com.destroystokyo.paper.profile.PlayerProfile;
import net.knightsandkings.knk.core.admission.AdmissionPolicy;
import net.knightsandkings.knk.core.admission.JoinRefusal;
import net.knightsandkings.knk.core.admission.JoinRefusalThrottle;
import net.kyori.adventure.text.Component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PandiO/knk-workspace#159: refused joins reach staff holding knk.notify.joinattempt, with the reason, once per cooldown. */
class JoinRefusalNotifierTest {

    private static final UUID STEVE = UUID.nameUUIDFromBytes("Steve".getBytes());

    private final Player staff = mock(Player.class);
    private final Player player = mock(Player.class);
    private final JoinRefusalReasons reasons = new JoinRefusalReasons();
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private String banReason;
    private boolean ipBanned;
    private boolean whitelistRefuses;
    private boolean full;

    private final JoinRefusalNotifier.VanillaChecks vanilla = new JoinRefusalNotifier.VanillaChecks() {
        @Override public String profileBanReason(PlayerProfile profile) { return banReason; }
        @Override public boolean ipBanned(InetSocketAddress address) { return ipBanned; }
        @Override public boolean whitelistRefuses(UUID uuid) { return whitelistRefuses; }
        @Override public boolean full() { return full; }
    };

    private final JoinRefusalNotifier notifier = new JoinRefusalNotifier(reasons,
        new JoinRefusalThrottle(Duration.ofSeconds(60), Clock.systemUTC()), enabled::get,
        () -> List.of(staff, player), (p, node) -> p == staff && JoinRefusalNotifier.NODE.equals(node), Runnable::run, vanilla);

    private static AsyncPlayerPreLoginEvent preLogin(AsyncPlayerPreLoginEvent.Result result, String kick) {
        // The real event asks Bukkit for a profile; a mock keeps the test server-free.
        AsyncPlayerPreLoginEvent event = mock(AsyncPlayerPreLoginEvent.class);
        when(event.getUniqueId()).thenReturn(STEVE);
        when(event.getName()).thenReturn("Steve");
        when(event.getLoginResult()).thenReturn(result);
        when(event.kickMessage()).thenReturn(kick == null ? Component.empty() : Component.text(kick));
        return event;
    }

    @Test
    void theAdmissionGatesReasonIsUsed_neverItsScreenWithTheClaimCode() {
        reasons.record(STEVE, "Steve", JoinRefusal.fromAdmissionKey(AdmissionPolicy.KICK), null);

        notifier.onPreLogin(preLogin(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, "Enter ABCD-EFGH at the site"));

        verify(staff).sendMessage(contains("invite-only"));
        verify(staff, never()).sendMessage(contains("ABCD"));
        verify(player, never()).sendMessage(anyString());
    }

    @Test
    void anotherPluginsRefusalByItsText_andOnlyOncePerCooldown() {
        notifier.onPreLogin(preLogin(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "Maintenance until 18:00"));
        notifier.onPreLogin(preLogin(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "Maintenance until 18:00"));

        verify(staff, times(1)).sendMessage(contains("Maintenance until 18:00"));
    }

    @Test
    void anAllowedLoginClearsARecordedReasonAndSaysNothing() {
        reasons.record(STEVE, "Steve", JoinRefusal.Reason.WORLD_NOT_READY, "world_hub");

        notifier.onPreLogin(preLogin(AsyncPlayerPreLoginEvent.Result.ALLOWED, null));

        verify(staff, never()).sendMessage(anyString());
        assertEquals(java.util.Optional.empty(), reasons.take(STEVE));
    }

    @Test
    void vanillaRefusalsAreClassified() {
        PlayerProfile profile = mock(PlayerProfile.class);
        when(profile.getId()).thenReturn(STEVE);
        when(profile.getName()).thenReturn("Steve");
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
        Component kick = Component.text("You are not white-listed on this server!");

        whitelistRefuses = true;
        assertEquals(JoinRefusal.Reason.NOT_WHITELISTED, notifier.classify(profile, address, kick).reason());
        ipBanned = true;
        assertEquals("IP ban", notifier.classify(profile, address, kick).detail());
        banReason = "griefing";
        JoinRefusal banned = notifier.classify(profile, address, kick);
        assertEquals(JoinRefusal.Reason.BANNED, banned.reason());
        assertEquals("griefing", banned.detail());
        banReason = null;
        ipBanned = false;
        whitelistRefuses = false;
        full = true;
        assertEquals(JoinRefusal.Reason.SERVER_FULL, notifier.classify(profile, address, kick).reason());
        full = false;
        assertEquals("You are not white-listed on this server!", notifier.classify(profile, address, kick).detail());
    }

    @Test
    void switchedOffSaysNothing() {
        enabled.set(false);
        notifier.onPreLogin(preLogin(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, "Banned"));
        verify(staff, never()).sendMessage(anyString());
    }
}
