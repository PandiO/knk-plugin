package net.knightsandkings.knk.paper.onboarding;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A brand-new player's first minutes on the server (KNG-128). {@code PlayerListener} marks a player's
 * first join: an account the API created at this join (the same {@code isNewUser} signal as the
 * first-join kits, KNG-81), or an account that already existed, made on the website, whose player the
 * server has never seen (KNG-214, {@link #isFirstJoin}); then:
 * <ul>
 *   <li>{@code UserAccountListener} sends the first-join welcome instead of "Welcome back", once
 *   ({@link #takeFirstJoinWelcome});</li>
 *   <li>{@code DiscoveryEffects} holds discovery announcements while {@link #isQuiet} and shows one
 *   summary afterwards. The discoveries themselves are granted and rewarded as usual.</li>
 * </ul>
 * In memory only: a restart forgets the window, and the player's later joins are ordinary ones.
 * Thread safe; the clock is injectable for tests.
 */
public final class NewPlayerWindow {
    private final Clock clock;
    private final Duration quiet;
    private final Map<UUID, Instant> firstJoins = new ConcurrentHashMap<>();
    private final Set<UUID> welcomed = ConcurrentHashMap.newKeySet();

    public NewPlayerWindow(Clock clock, Duration quiet) {
        this.clock = clock;
        this.quiet = quiet == null || quiet.isNegative() ? Duration.ZERO : quiet;
    }

    /**
     * Whether this join is the player's first (KNG-214): the API created the account at this join
     * (game-first sign-up), or the account was made on the website first (web-first invite, or
     * game-first through {@code /link}) and the server has no player data for them yet. A stranger
     * refused at pre-login leaves no player data, so their admitted join still counts as the first.
     * An old account whose player data was deleted gets one more first join; the API grants the
     * kits only once, so that costs a welcome and a quiet period, nothing else.
     */
    public static boolean isFirstJoin(boolean accountCreatedAtThisJoin, boolean hasPlayedBefore) {
        return accountCreatedAtThisJoin || !hasPlayedBefore;
    }

    /** The player's first join (see {@link #isFirstJoin}). A relog in the same server session keeps the first time. */
    public void markFirstJoin(UUID playerId) {
        firstJoins.putIfAbsent(playerId, clock.instant());
    }

    /** True once per marked player: the join that gets the first-join welcome. */
    public boolean takeFirstJoinWelcome(UUID playerId) {
        return firstJoins.containsKey(playerId) && welcomed.add(playerId);
    }

    /** Within the quiet minutes after the first join (never when they are 0). */
    public boolean isQuiet(UUID playerId) {
        Instant firstJoin = firstJoins.get(playerId);
        return firstJoin != null && clock.instant().isBefore(firstJoin.plus(quiet));
    }

    public Duration quiet() {
        return quiet;
    }
}
