package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * The members of one running match with their team and match stats (DESIGN §6.6, §6.8, §3.10
 * {@code SiegeMatchParticipant}): kills, deaths, kill streak, highest streak, captures and the
 * member's current spawn choice. Built from the {@link TeamPartitioner} split at match start; a
 * member who leaves is removed (their stats are returned for the {@code left} checkpoint).
 * <p>
 * Kill announcements follow {@code SiegeConfiguration} (fixes N12: the streak message uses the
 * streak, not the kill total). Not thread-safe: main thread only.
 */
public final class SiegeMatchRoster {

    public enum SpawnKind {
        /** A team spawnpoint ({@code SiegeSpawnpoint.id}). */
        SPAWNPOINT,
        /** A held objective with {@code SpawnWhenHeld} ({@code SiegeObjective.id}). */
        OBJECTIVE
    }

    /** Where a member respawns (DESIGN §6.6); {@code null} choice means the team's default spawnpoint. */
    public record SpawnChoice(SpawnKind kind, int id) {
        public SpawnChoice {
            Objects.requireNonNull(kind, "kind");
        }

        public static SpawnChoice spawnpoint(int id) {
            return new SpawnChoice(SpawnKind.SPAWNPOINT, id);
        }

        public static SpawnChoice objective(int id) {
            return new SpawnChoice(SpawnKind.OBJECTIVE, id);
        }
    }

    /** A read-only copy of one member's state (menus, scoreboards, the match API). */
    public record MemberView(
            UUID playerId,
            int teamId,
            int kills,
            int deaths,
            int killStreak,
            int highestKillStreak,
            int captures,
            SpawnChoice spawnChoice
    ) {}

    /**
     * What a kill should announce to the killer's team.
     *
     * @param killsMilestone the killer's kill total when it is one of the configured thresholds, else empty
     * @param streak         the killer's current streak when it is above the configured value, else empty
     */
    public record KillCredit(UUID killerId, int kills, int killStreak, OptionalInt killsMilestone, OptionalInt streak) {}

    private static final class Member {
        final UUID playerId;
        final int teamId;
        int kills;
        int deaths;
        int killStreak;
        int highestKillStreak;
        int captures;
        SpawnChoice spawnChoice;

        Member(UUID playerId, int teamId) {
            this.playerId = playerId;
            this.teamId = teamId;
        }

        MemberView view() {
            return new MemberView(playerId, teamId, kills, deaths, killStreak, highestKillStreak, captures, spawnChoice);
        }
    }

    private final List<Integer> teamIds;
    private final Map<UUID, Member> members = new LinkedHashMap<>();

    /**
     * @param teams every scenario team id (in scenario order) with its players, e.g. the
     *              {@link TeamPartitioner#partition} result; teams may be empty
     */
    public SiegeMatchRoster(Map<Integer, List<UUID>> teams) {
        this.teamIds = List.copyOf(teams.keySet());
        teams.forEach((teamId, players) -> players.forEach(p -> members.put(p, new Member(p, teamId))));
    }

    public boolean contains(UUID playerId) {
        return members.containsKey(playerId);
    }

    public OptionalInt teamOf(UUID playerId) {
        Member m = members.get(playerId);
        return m == null ? OptionalInt.empty() : OptionalInt.of(m.teamId);
    }

    public Optional<MemberView> member(UUID playerId) {
        return Optional.ofNullable(members.get(playerId)).map(Member::view);
    }

    /** Every member in split order. */
    public List<MemberView> members() {
        return members.values().stream().map(Member::view).toList();
    }

    public List<UUID> membersOf(int teamId) {
        return members.values().stream().filter(m -> m.teamId == teamId).map(m -> m.playerId).toList();
    }

    public int size() {
        return members.size();
    }

    /** The team ids this roster was built with, in scenario order. */
    public List<Integer> teamIds() {
        return teamIds;
    }

    /** Members per team, including teams with none (0), in scenario order (for {@link WinResolver#membershipEnd}). */
    public Map<Integer, Integer> membersPerTeam() {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        teamIds.forEach(id -> counts.put(id, 0));
        members.values().forEach(m -> counts.merge(m.teamId, 1, Integer::sum));
        return Collections.unmodifiableMap(counts);
    }

    /** Removes a member (leave, quit, kick) and returns their final state. */
    public Optional<MemberView> remove(UUID playerId) {
        Member m = members.remove(playerId);
        return Optional.ofNullable(m).map(Member::view);
    }

    /**
     * A member died (DESIGN §6.6). The victim gets a death and loses their streak; a killer who is
     * another member of this match gets a kill and a streak step. A null, unknown or self killer
     * credits nothing (fixes v2's NPE on non-PvP deaths).
     *
     * @return the killer's credit, or empty when no member was credited
     */
    public Optional<KillCredit> recordDeath(UUID victimId, UUID killerId, KnkSiegeConfiguration configuration) {
        Member victim = members.get(victimId);
        if (victim != null) {
            victim.deaths++;
            victim.killStreak = 0;
        }
        if (killerId == null || killerId.equals(victimId)) return Optional.empty();
        Member killer = members.get(killerId);
        if (killer == null) return Optional.empty();

        killer.kills++;
        killer.killStreak++;
        killer.highestKillStreak = Math.max(killer.highestKillStreak, killer.killStreak);

        OptionalInt milestone = configuration.killAnnouncementThresholds().contains(killer.kills)
                ? OptionalInt.of(killer.kills) : OptionalInt.empty();
        OptionalInt streak = killer.killStreak > configuration.killStreakAnnounceAbove()
                ? OptionalInt.of(killer.killStreak) : OptionalInt.empty();
        return Optional.of(new KillCredit(killerId, killer.kills, killer.killStreak, milestone, streak));
    }

    /** A capture by this member (DESIGN §7.3: participant {@code Captures + 1}). */
    public void recordCapture(UUID playerId) {
        Member m = members.get(playerId);
        if (m != null) m.captures++;
    }

    public Optional<SpawnChoice> spawnChoice(UUID playerId) {
        Member m = members.get(playerId);
        return m == null ? Optional.empty() : Optional.ofNullable(m.spawnChoice);
    }

    /** Sets (or with null clears) the member's respawn choice; no-op for non-members. */
    public void setSpawnChoice(UUID playerId, SpawnChoice choice) {
        Member m = members.get(playerId);
        if (m != null) m.spawnChoice = choice;
    }

    /**
     * An objective changed hands (playtest 2026-09-26): every member outside {@code newHolderTeamId}
     * whose choice is that objective gets their team's default choice stored instead ({@code null}
     * from {@code defaultForTeam} clears it), so their respawn choice stays remembered and they aren't
     * asked again. Returns the members that were reset, in roster order.
     */
    public List<UUID> resetObjectiveChoice(int objectiveId, int newHolderTeamId, IntFunction<SpawnChoice> defaultForTeam) {
        SpawnChoice lost = SpawnChoice.objective(objectiveId);
        List<UUID> reset = new ArrayList<>();
        for (Member m : members.values()) {
            if (m.teamId == newHolderTeamId || !lost.equals(m.spawnChoice)) continue;
            m.spawnChoice = defaultForTeam.apply(m.teamId);
            reset.add(m.playerId);
        }
        return reset;
    }

    /** Players in team order, for iteration that must not see concurrent removal. */
    public List<UUID> playerIds() {
        return new ArrayList<>(members.keySet());
    }
}
