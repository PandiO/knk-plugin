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
        return isFirstJoin(accountCreatedAtThisJoin, hasPlayedBefore, false);
    }

    /**
     * {@link #isFirstJoin(boolean, boolean)}, and PandiO/knk-workspace#486: the player's previous knk account was
     * erased since their last join ({@code ErasedPlayerMarks}). An erasure keeps Minecraft's own player files, so
     * {@code hasPlayedBefore} is still true for them, and an account they get back through an invitation claim or the
     * website exists before the join; the mark is what makes this join a first join.
     */
    public static boolean isFirstJoin(boolean accountCreatedAtThisJoin, boolean hasPlayedBefore, boolean accountErasedSince) {
        return accountCreatedAtThisJoin || !hasPlayedBefore || accountErasedSince;
    }

    /** The player's first join (see {@link #isFirstJoin}). A relog in the same server session keeps the first time. */
    public void markFirstJoin(UUID playerId) {
        firstJoins.putIfAbsent(playerId, clock.instant());
    }

    /** True once per marked player: the join that gets the first-join welcome. */
    public boolean takeFirstJoinWelcome(UUID playerId) {
        return firstJoins.containsKey(playerId) && welcomed.add(playerId);
    }

    /**
     * The player's account was erased (PandiO/knk-workspace#486): forget their window, so a return in this server
     * session gets a new first join (welcome and quiet minutes) instead of the old one's leftovers. Returns 1 if there
     * was one (a count for the erasure log).
     */
    public int forget(UUID playerId) {
        boolean had = firstJoins.remove(playerId) != null;
        welcomed.remove(playerId);
        return had ? 1 : 0;
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
