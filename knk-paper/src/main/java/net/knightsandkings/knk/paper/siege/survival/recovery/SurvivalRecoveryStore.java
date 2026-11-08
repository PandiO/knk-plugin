package net.knightsandkings.knk.paper.siege.survival.recovery;

import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MatchResultDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.PlayerResultDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.SettlementDto;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The survival recovery file (DESIGN §9 "persist an accrued result or pending settlement before attempting the API
 * payout", §12, S61; link S8b): one YAML file per API match under {@code <data folder>/siege-survival/matches/}.
 * While the match runs it holds a provisional {@code ServerRestart} result ({@link State#OPEN}), refreshed at every
 * round change; at the end it holds the real result until the API has it ({@link State#RESULT}); a posted result
 * deletes it. On startup the entries are replayed: a RESULT is posted again (idempotent), an OPEN one is posted as
 * the restart result (the API answers {@code ResultAlreadyStored} when the Siege abort already wrote its row - fine).
 * Writes are atomic (temp file + move). Main thread or startup only.
 */
public final class SurvivalRecoveryStore {

    public enum State { OPEN, RESULT }

    public record Entry(long matchId, State state, MatchResultDto result) { }

    private final File directory;
    private final Logger logger;

    public SurvivalRecoveryStore(File directory, Logger logger) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.logger = logger;
    }

    public File directory() {
        return directory;
    }

    public boolean save(long matchId, State state, MatchResultDto result) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("match-id", matchId);
        yaml.set("state", state.name());
        yaml.set("saved-at", OffsetDateTime.now().toString());
        write(yaml.createSection("result"), result);
        File target = file(matchId);
        File temp = new File(directory, matchId + ".yml.tmp");
        try {
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IOException("could not create " + directory);
            }
            yaml.save(temp);
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException | RuntimeException e) {
            if (logger != null) logger.log(Level.WARNING, "[Survival] Could not write the recovery file of match " + matchId, e);
            temp.delete();
            return false;
        }
    }

    public boolean delete(long matchId) {
        File f = file(matchId);
        return !f.isFile() || f.delete();
    }

    public boolean has(long matchId) {
        return file(matchId).isFile();
    }

    /** Every stored entry (unreadable files are logged and skipped). */
    public List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return out;
        java.util.Arrays.sort(files);
        for (File f : files) {
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
                long matchId = yaml.getLong("match-id", -1);
                if (matchId <= 0) continue;
                State state = State.valueOf(yaml.getString("state", State.OPEN.name()));
                out.add(new Entry(matchId, state, read(yaml.getConfigurationSection("result"))));
            } catch (RuntimeException e) {
                if (logger != null) logger.log(Level.WARNING, "[Survival] Unreadable recovery file " + f.getName() + "; skipped", e);
            }
        }
        return out;
    }

    private File file(long matchId) {
        return new File(directory, matchId + ".yml");
    }

    // ---- MatchResultDto <-> YAML (the API-filled fields are not stored) ----

    static void write(ConfigurationSection s, MatchResultDto r) {
        if (r == null) return;
        s.set("siege-match-id", r.siegeMatchId());
        s.set("siege-lobby-id", r.siegeLobbyId());
        s.set("siege-scenario-id", r.siegeScenarioId());
        s.set("difficulty-profile-id", r.difficultyProfileId());
        s.set("configuration-generated-at", text(r.configurationGeneratedAt()));
        s.set("started-at", text(r.startedAt()));
        s.set("ended-at", text(r.endedAt()));
        s.set("end-reason", r.endReason());
        s.set("reached-wave", r.reachedWave());
        s.set("completed-wave", r.completedWave());
        s.set("earned-kills", r.earnedKills());
        s.set("system-kills", r.systemKills());
        s.set("equipment-mode", r.equipmentMode());
        if (r.settlement() != null) {
            ConfigurationSection st = s.createSection("settlement");
            st.set("settles-rewards", r.settlement().settlesRewards());
            st.set("total-coins", r.settlement().totalCoins());
            st.set("total-xp", r.settlement().totalXp());
            st.set("ledger-key", r.settlement().ledgerKey());
        }
        List<java.util.Map<String, Object>> players = new ArrayList<>();
        if (r.players() != null) {
            for (PlayerResultDto p : r.players()) {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("user-id", p.userId());
                m.put("present-at-end", p.presentAtEnd());
                m.put("left-at", text(p.leftAt()));
                m.put("kit-id", p.kitId());
                m.put("kills", p.kills());
                m.put("bonus-kills", p.bonusKills());
                m.put("deaths", p.deaths());
                m.put("repair-work", p.repairWork());
                m.put("match-currency-earned", p.matchCurrencyEarned());
                m.put("match-currency-spent", p.matchCurrencySpent());
                m.put("final-match-balance", p.finalMatchBalance());
                m.put("coins-accrued", p.coinsAccrued() == null ? null : p.coinsAccrued().toPlainString());
                m.put("xp-accrued", p.xpAccrued() == null ? null : p.xpAccrued().toPlainString());
                m.put("coins-settled", p.coinsSettled());
                m.put("xp-settled", p.xpSettled());
                players.add(m);
            }
        }
        s.set("players", players);
    }

    static MatchResultDto read(ConfigurationSection s) {
        if (s == null) return null;
        List<PlayerResultDto> players = new ArrayList<>();
        for (java.util.Map<?, ?> m : s.getMapList("players")) {
            players.add(new PlayerResultDto(intOf(m.get("user-id")), boolOf(m.get("present-at-end")), time(m.get("left-at")),
                intOf(m.get("kit-id")), intOf(m.get("kills")), intOf(m.get("bonus-kills")), intOf(m.get("deaths")),
                doubleOf(m.get("repair-work")), longOf(m.get("match-currency-earned")), longOf(m.get("match-currency-spent")),
                longOf(m.get("final-match-balance")), decimal(m.get("coins-accrued")), decimal(m.get("xp-accrued")),
                intOf(m.get("coins-settled")), intOf(m.get("xp-settled")), null, null, null));
        }
        ConfigurationSection st = s.getConfigurationSection("settlement");
        SettlementDto settlement = st == null ? null : new SettlementDto(st.getBoolean("settles-rewards"),
            st.getInt("total-coins"), st.getInt("total-xp"), st.getString("ledger-key"), null, null, null);
        return new MatchResultDto(intOrNull(s, "siege-match-id"), intOrNull(s, "siege-lobby-id"), intOrNull(s, "siege-scenario-id"),
            intOrNull(s, "difficulty-profile-id"), time(s.getString("configuration-generated-at")), time(s.getString("started-at")),
            time(s.getString("ended-at")), s.getString("end-reason"), intOrNull(s, "reached-wave"), intOrNull(s, "completed-wave"),
            intOrNull(s, "earned-kills"), intOrNull(s, "system-kills"), players, settlement, s.getString("equipment-mode"),
            null, null, null);
    }

    private static String text(OffsetDateTime t) {
        return t == null ? null : t.toString();
    }

    private static OffsetDateTime time(Object value) {
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value.toString());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Integer intOrNull(ConfigurationSection s, String key) {
        return s.contains(key) && s.get(key) != null ? s.getInt(key) : null;
    }

    private static Integer intOf(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    private static Long longOf(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static Double doubleOf(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static Boolean boolOf(Object v) {
        return v instanceof Boolean b ? b : null;
    }

    private static BigDecimal decimal(Object v) {
        if (v == null) return null;
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
