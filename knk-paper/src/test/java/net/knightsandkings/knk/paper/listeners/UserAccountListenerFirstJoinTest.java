package net.knightsandkings.knk.paper.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.paper.config.KnkConfig;
import net.knightsandkings.knk.paper.onboarding.NewPlayerWindow;
import net.knightsandkings.knk.paper.user.JoinLoadingGuard;
import net.knightsandkings.knk.paper.user.PlayerUserData;
import net.knightsandkings.knk.paper.user.UserManager;

/**
 * PandiO/knk-workspace#486: the first join is decided once the join's sync knows the account, with nothing in the user
 * cache (the state right after an erasure: its "user caches" step dropped the player, and a website account without
 * a UUID leaves the pre-login lookup nothing to cache). On {@code next} the decision lived in
 * {@code PlayerListener.onJoin} behind {@code if (cachedUser != null)}: with an empty cache it logged "no cached user
 * ... skipped", took no erasure mark, marked nothing, and the player got "Welcome back" with no quiet minutes.
 */
class UserAccountListenerFirstJoinTest {
    private static final UUID PLAYER = UUID.fromString("0f3c5a5e-1b7c-4d2a-9f1e-2a4b6c8d0e1f");

    private NewPlayerWindow window;
    private UserAccountListener listener;
    private final Set<UUID> erasureMarks = new HashSet<>();
    private final List<String> kitRequests = new ArrayList<>();

    @BeforeEach
    void setUp() {
        window = new NewPlayerWindow(Clock.systemUTC(), Duration.ofMinutes(10));
        // No user cache anywhere in this listener: the decision must not need one.
        listener = new UserAccountListener(null, mock(UserManager.class), mock(JoinLoadingGuard.class),
            mock(KnkConfig.MessagesConfig.class), Logger.getLogger("test"), null, window, KnkConfig.OnboardingConfig.defaults());
        listener.setFirstJoinHooks(erasureMarks::remove, (uuid, userId) -> kitRequests.add(uuid + " " + userId));
    }

    private static PlayerUserData account(Integer userId) {
        return PlayerUserData.minimal(PLAYER, "MrBedue", userId);
    }

    /** S5 steps 4/5/5a: erased, caught up (or pushed), then the same Minecraft account joins with player files kept. */
    @Test
    void anErasedPlayer_WithAnEmptyUserCache_GetsTheFirstJoin() {
        erasureMarks.add(PLAYER);

        boolean first = listener.decideFirstJoin(PLAYER, "MrBedue", account(11), true);

        assertTrue(first);
        assertFalse(erasureMarks.contains(PLAYER), "the mark is taken at this join");
        assertTrue(window.takeFirstJoinWelcome(PLAYER), "first-join welcome, not Welcome back");
        assertTrue(window.isQuiet(PLAYER), "quiet discovery minutes");
        assertEquals(List.of(PLAYER + " 11"), kitRequests, "first-join kits asked for once");
    }

    /**
     * Realtime off (S5 step 1): nothing was erased on this server, so there is no mark and the player has played
     * before, but the API created a fresh account for them while they connected.
     */
    @Test
    void aFreshAccountTheApiCreatedWhileConnecting_IsAFirstJoinWithoutAnErasureMark() {
        window.accountCreated(PLAYER);

        assertTrue(listener.decideFirstJoin(PLAYER, "MrBedue", account(12), true));
        assertTrue(window.takeFirstJoinWelcome(PLAYER));
        assertTrue(window.isQuiet(PLAYER));
        assertEquals(List.of(PLAYER + " 12"), kitRequests);
    }

    @Test
    void aReturningPlayer_GetsWelcomeBackAndNoKitRequest() {
        assertFalse(listener.decideFirstJoin(PLAYER, "MrBedue", account(11), true));
        assertFalse(window.takeFirstJoinWelcome(PLAYER));
        assertFalse(window.isQuiet(PLAYER));
        assertEquals(List.of(), kitRequests);
    }

    @Test
    void aPlayerTheServerHasNeverSeen_GetsTheFirstJoin() {
        assertTrue(listener.decideFirstJoin(PLAYER, "MrBedue", account(11), false));
        assertTrue(window.takeFirstJoinWelcome(PLAYER));
    }

    /** Created and erased both apply: both notes are used up, so neither makes a later join a first join again. */
    @Test
    void bothNotesAreTakenAtTheJoin() {
        window.accountCreated(PLAYER);
        erasureMarks.add(PLAYER);

        assertTrue(listener.decideFirstJoin(PLAYER, "MrBedue", account(11), true));

        assertFalse(erasureMarks.contains(PLAYER));
        assertFalse(window.takeAccountCreated(PLAYER));
    }

    /** The API didn't answer: no account, nothing decided, and the erasure mark waits for the next join. */
    @Test
    void withoutAnAccount_NothingIsDecidedAndTheMarkStays() {
        erasureMarks.add(PLAYER);

        assertFalse(listener.decideFirstJoin(PLAYER, "MrBedue", account(null), true));

        assertTrue(erasureMarks.contains(PLAYER));
        assertFalse(window.takeFirstJoinWelcome(PLAYER));
        assertEquals(List.of(), kitRequests);
    }

    /** The welcome is given once per first join, however often the join's messages run. */
    @Test
    void theWelcomeIsTakenOnce() {
        erasureMarks.add(PLAYER);
        listener.decideFirstJoin(PLAYER, "MrBedue", account(11), true);

        assertTrue(window.takeFirstJoinWelcome(PLAYER));
        assertFalse(window.takeFirstJoinWelcome(PLAYER));
    }
}
