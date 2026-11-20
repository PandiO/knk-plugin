package net.knightsandkings.knk.paper.siege.points;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.ParticipantResult;
import net.knightsandkings.knk.core.metrics.PluginMetrics;
import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;
import net.knightsandkings.knk.core.siege.SiegeObjectiveBoard.BoardStep;
import net.knightsandkings.knk.core.siege.economy.LedgerResult;
import net.knightsandkings.knk.core.siege.economy.SiegePointsEarnCalculator;
import net.knightsandkings.knk.core.siege.economy.SiegePointsEarnCalculator.Award;
import net.knightsandkings.knk.core.siege.economy.SiegePointsEconomy;
import net.knightsandkings.knk.core.siege.economy.SiegePointsEconomy.Credit;
import net.knightsandkings.knk.core.siege.economy.SiegePointsEconomy.TransferOutcome;
import net.knightsandkings.knk.core.siege.economy.SiegePointsText;
import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import net.knightsandkings.knk.paper.siege.SiegeMatch;
import net.knightsandkings.knk.paper.siege.SiegeMatchObserver;
import net.knightsandkings.knk.paper.siege.SiegeMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Siege points in PvP Siege (siege shop DESIGN § 5.1, § 5.2, § 7.2.1, P12–P18, P27; PandiO/knk-workspace#866): one
 * {@link SiegePointsEconomy} per running match, fed from the match's events, shown on the action bar and the
 * per-player scoreboard line, with {@code /siege points} and {@code /siege pay}. Registered only with
 * {@code siege.points.enabled} (default false); without it nothing here exists and Siege behaves as before.
 * <p>
 * Balances live in memory only (P12): a restart ends the match anyway. Everything runs on the main thread; the
 * per-second work is bounded by the match's members (one location read each, the board's presence lists).
 */
public final class SiegePointsService implements SiegeMatchObserver {

    /** The scoreboard line's label (P18). */
    public static final String SCOREBOARD_LABEL = "Siege points: ";

    /** The match of a player for the commands; {@code SiegeService::runningMatchOf}. */
    public interface MatchLookup {
        Optional<SiegeMatch> runningMatchOf(UUID playerId);
    }

    private final Logger logger;
    private final MatchLookup matches;
    private final Function<UUID, Instant> afkSince;
    private final SiegePointsTelemetry telemetry;
    private final Map<String, SiegePointsEconomy> economies = new HashMap<>();
    private final Map<String, Map<UUID, Location>> lastPositions = new HashMap<>();
    private long transferSequence;

    /**
     * @param afkSince  the statistics AFK source (null: nobody is ever AFK); AFK members neither travel nor trickle
     * @param telemetry null without diagnostic telemetry
     */
    public SiegePointsService(Logger logger, MatchLookup matches, Function<UUID, Instant> afkSince,
                              SiegePointsTelemetry telemetry) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.matches = Objects.requireNonNull(matches, "matches");
        this.afkSince = afkSince == null ? id -> null : afkSince;
        this.telemetry = telemetry;
    }

    /** The match's economy, while it runs or waits at the hub after its end. */
    public Optional<SiegePointsEconomy> economyOf(SiegeMatch match) {
        return match == null ? Optional.empty() : Optional.ofNullable(economies.get(match.matchToken()));
    }

    // ==================== Match lifecycle ====================

    @Override
    public void matchStarted(SiegeLobbyRuntime lobby, SiegeMatch match) {
        SiegePointsEconomy economy = new SiegePointsEconomy(match.matchToken(), match.configuration().points());
        match.roster().playerIds().forEach(economy::join);
        economies.put(match.matchToken(), economy);
        lastPositions.put(match.matchToken(), new HashMap<>());
    }

    @Override
    public void memberJoinedMatch(SiegeLobbyRuntime lobby, SiegeMatch match, Player player) {
        economyOf(match).ifPresent(e -> e.join(player.getUniqueId()));
    }

    /** A member who leaves the running match loses their balance (§ 5.1.3); their totals stay for the result. */
    @Override
    public void memberLeft(SiegeLobbyRuntime lobby, UUID playerId, Integer userId, String cause, boolean duringMatch) {
        if (!duringMatch || "round_over".equals(cause) || "shutdown".equals(cause)) return;
        lobby.match().flatMap(this::economyOf).ifPresent(e -> {
            if (!e.ended()) e.leave(playerId);
        });
        lobby.match().map(m -> lastPositions.get(m.matchToken())).ifPresent(p -> p.remove(playerId));
    }

    /** The match ended: no more earning; each member is told their totals (§ 4.1.8). */
    @Override
    public void matchEnded(SiegeLobbyRuntime lobby, SiegeMatch match) {
        SiegePointsEconomy economy = economies.get(match.matchToken());
        if (economy == null) return;
        if (telemetry != null) telemetry.flush(lobby);
        for (UUID id : match.roster().playerIds()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(SiegeMessages.info("Siege points this match: earned " + economy.earned(id)
                    + ", spent " + economy.spent(id) + "."));
        }
        economy.end();
    }

    /** Everyone was released: the match's balances go (P12). */
    @Override
    public void roundReleased(SiegeLobbyRuntime lobby) {
        lobby.match().ifPresent(m -> {
            economies.remove(m.matchToken());
            lastPositions.remove(m.matchToken());
        });
    }

    @Override
    public void shutdown() {
        economies.clear();
        lastPositions.clear();
    }

    // ==================== Earning ====================

    @Override
    public void memberDied(SiegeLobbyRuntime lobby, SiegeMatch match, UUID victimId, UUID killerId, int killerStreak) {
        SiegePointsEconomy economy = economies.get(match.matchToken());
        if (economy == null || economy.ended()) return;
        List<UUID> assisters = economy.assists().assistersOnDeath(victimId, killerId, System.currentTimeMillis());
        List<Award> awards = economy.calculator().death(
                new SiegePointsEarnCalculator.Death(victimId, killerId, killerStreak, assisters),
                id -> teamOf(match, id), match.alliances());
        deliver(lobby, economy.apply(awards));
    }

    @Override
    public void gateDamaged(SiegeLobbyRuntime lobby, SiegeMatch match, UUID playerId, double loss) {
        SiegePointsEconomy economy = economies.get(match.matchToken());
        if (economy == null || economy.ended()) return;
        deliver(lobby, economy.apply(economy.calculator().gateDamage(playerId, loss)));
    }

    /**
     * A hit between two players that the siege rules allowed (the damage listener, MONITOR): logged for assists when
     * both are enemies in the same running match; the attacker counts as active for the trickle.
     */
    public void recordDamage(Player attacker, Player victim, double finalDamage) {
        if (attacker == null || victim == null || attacker.equals(victim) || !(finalDamage > 0)) return;
        Optional<SiegeMatch> match = matches.runningMatchOf(victim.getUniqueId());
        if (match.isEmpty() || !match.get().roster().contains(attacker.getUniqueId())) return;
        SiegePointsEconomy economy = economies.get(match.get().matchToken());
        if (economy == null || economy.ended()) return;
        int a = teamOf(match.get(), attacker.getUniqueId());
        int v = teamOf(match.get(), victim.getUniqueId());
        if (!match.get().alliances().areEnemies(a, v)) return;
        economy.assists().recordDamage(victim.getUniqueId(), attacker.getUniqueId(), finalDamage, System.currentTimeMillis());
        economy.calculator().markActive(attacker.getUniqueId());
    }

    /** Capture progress, captures, travel and the trickle, once a second (wrapped for the task metrics). */
    @Override
    public void secondTicked(SiegeLobbyRuntime lobby, SiegeMatch match, BoardStep step, Map<Integer, List<Presence>> presence) {
        SiegePointsEconomy economy = economies.get(match.matchToken());
        if (economy == null || economy.ended()) return;
        PluginMetrics.timed("siege.points_tick", () -> tick(lobby, match, economy, step, presence)).run();
    }

    private void tick(SiegeLobbyRuntime lobby, SiegeMatch match, SiegePointsEconomy economy, BoardStep step,
                      Map<Integer, List<Presence>> presence) {
        SiegePointsEarnCalculator calculator = economy.calculator();
        Map<UUID, Location> positions = lastPositions.computeIfAbsent(match.matchToken(), k -> new HashMap<>());
        List<UUID> eligible = new ArrayList<>();
        for (UUID id : match.roster().playerIds()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                positions.remove(id);
                continue;
            }
            if (safeAfk(id) != null) {
                positions.remove(id);
                continue;
            }
            eligible.add(id);
            if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) continue;
            Location now = p.getLocation();
            Location before = positions.put(id, now);
            if (before != null && Objects.equals(before.getWorld(), now.getWorld())) {
                calculator.recordTravel(id, horizontal(before, now));
            }
        }
        List<Credit> credits = new ArrayList<>(economy.apply(
                calculator.boardStep(step, presence, match.board()::objective, match.alliances())));
        credits.addAll(economy.apply(calculator.secondTicked(eligible)));
        deliver(lobby, credits);
    }

    private static double horizontal(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Action bar per player (one line for all credits of the moment) and telemetry (P27). */
    private void deliver(SiegeLobbyRuntime lobby, List<Credit> credits) {
        if (credits.isEmpty()) return;
        Map<UUID, List<Credit>> byPlayer = new LinkedHashMap<>();
        for (Credit c : credits) byPlayer.computeIfAbsent(c.player(), k -> new ArrayList<>()).add(c);
        byPlayer.forEach((id, list) -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendActionBar(Component.text(SiegePointsText.actionBar(list), NamedTextColor.GOLD));
        });
        if (telemetry != null) {
            try {
                credits.forEach(c -> telemetry.earned(lobby, c));
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "[Siege] siege points telemetry failed", e);
            }
        }
    }

    // ==================== Display and commands ====================

    /** The scoreboard line for a member (P18): "Siege points: N"; null when the match has no economy. */
    public Component scoreboardLine(SiegeMatch match, UUID playerId) {
        SiegePointsEconomy economy = economies.get(match.matchToken());
        if (economy == null) return null;
        return Component.text(SCOREBOARD_LABEL, NamedTextColor.GRAY)
                .append(Component.text(SiegePointsText.balance(economy.balance(playerId)), NamedTextColor.GOLD));
    }

    /** The match result's totals (P27, A11): siege points earned and spent by the member. */
    public ParticipantResult withTotals(SiegeMatch match, UUID playerId, ParticipantResult result) {
        SiegePointsEconomy economy = economies.get(match.matchToken());
        if (economy == null || result == null) return result;
        return result.withSiegePoints(economy.earned(playerId), economy.spent(playerId));
    }

    /** {@code /siege points}: balance, earned and spent this match (A10). */
    public Component points(Player player) {
        Optional<SiegeMatch> match = matches.runningMatchOf(player.getUniqueId());
        Optional<SiegePointsEconomy> economy = match.flatMap(this::economyOf);
        if (economy.isEmpty()) return SiegeMessages.bad("You have siege points only during a running siege match.");
        UUID id = player.getUniqueId();
        SiegePointsEconomy e = economy.get();
        return SiegeMessages.info("Siege points: " + e.balance(id) + " (earned " + e.earned(id) + ", spent "
                + e.spent(id) + " this match)");
    }

    /** {@code /siege pay <player> <amount>} (P07): only with the mode's transfer switch on, to a teammate in the match. */
    public Component pay(Player player, String targetName, String amountText) {
        if (targetName == null || amountText == null) return SiegeMessages.bad("Usage: /siege pay <player> <amount>");
        Optional<SiegeMatch> match = matches.runningMatchOf(player.getUniqueId());
        Optional<SiegePointsEconomy> found = match.flatMap(this::economyOf);
        if (found.isEmpty()) return SiegeMessages.bad("You can send siege points only during a running siege match.");
        SiegePointsEconomy economy = found.get();
        if (!economy.settings().transfersEnabled()) {
            return SiegeMessages.bad("Sending siege points to teammates is turned off in this game mode.");
        }
        long amount;
        try {
            amount = Long.parseLong(amountText.trim());
        } catch (NumberFormatException e) {
            return SiegeMessages.bad("'" + amountText + "' is not a whole number of siege points.");
        }
        Player target = Bukkit.getPlayerExact(targetName);
        UUID from = player.getUniqueId();
        UUID to = target == null ? null : target.getUniqueId();
        if (to == null || !match.get().roster().contains(to)) {
            return SiegeMessages.bad(targetName + " is not in your siege match.");
        }
        OptionalInt fromTeam = match.get().roster().teamOf(from);
        OptionalInt toTeam = match.get().roster().teamOf(to);
        boolean sameTeam = fromTeam.isPresent() && toTeam.isPresent() && fromTeam.getAsInt() == toTeam.getAsInt();
        String requestId = "pay:" + match.get().matchToken() + ":" + from + ":" + (++transferSequence);
        TransferOutcome outcome = economy.transfer(requestId, from, to, amount, sameTeam);
        if (outcome.status() == TransferOutcome.Status.NOT_TEAMMATE) {
            return SiegeMessages.bad("You can only send siege points to a teammate.");
        }
        if (outcome.status() == TransferOutcome.Status.TRANSFERS_OFF) {
            return SiegeMessages.bad("Sending siege points to teammates is turned off in this game mode.");
        }
        if (outcome.ledgerResult() instanceof LedgerResult.Rejected rejected) {
            return SiegeMessages.bad(refusal(rejected.reason(), economy.balance(from), amount));
        }
        target.sendMessage(SiegeMessages.info(player.getName() + " sent you " + amount + " siege points. Balance: "
                + economy.balance(to)));
        return SiegeMessages.info("Sent " + amount + " siege points to " + target.getName() + ". Balance: " + economy.balance(from));
    }

    static String refusal(LedgerResult.Reason reason, long balance, long amount) {
        return switch (reason) {
            case INSUFFICIENT_BALANCE -> "Not enough siege points: " + balance + "/" + amount;
            case AMOUNT_NOT_POSITIVE -> "Send at least 1 siege point.";
            case SELF_TRANSFER -> "You can't send siege points to yourself.";
            case MATCH_ENDED -> "The match is over.";
            case PAYER_NOT_IN_MATCH, RECIPIENT_NOT_IN_MATCH, WRONG_MATCH -> "Both players must be in the same running match.";
            case PAYER_IS_GHOST, RECIPIENT_IS_GHOST -> "Ghosts can't send or receive siege points.";
            case RETRY_ID_CONFLICT -> "That transfer was already handled.";
        };
    }

    // ==================== Helpers ====================

    private static int teamOf(SiegeMatch match, UUID playerId) {
        return playerId == null ? -1 : match.roster().teamOf(playerId).orElse(-1);
    }

    private Instant safeAfk(UUID playerId) {
        try {
            return afkSince.apply(playerId);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
