package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.domain.duels.DuelRefusedException;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Completion;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.ItemSummary;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Result;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.SideTelemetry;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Telemetry;
import net.knightsandkings.knk.core.duel.DuelEndReason;
import net.knightsandkings.knk.core.ports.api.DuelMatchesApi;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Match results on their way to the API (arena duels DESIGN §7.4 step 1 and 4, §7.5): every result is written to
 * {@code duel-escrow/pending-results/<matchToken>.yml} <b>before</b> it is sent, and the file goes once the API
 * answered. A result the API couldn't take (network, 5xx, outage, restart) stays and is sent again by
 * {@link #replay}: on startup before {@code abort-unfinished} (so a finished match isn't aborted), when the API is
 * back after an outage, and from the duel ticker. The API is idempotent per match, so a second send returns the
 * stored result.
 * <p>
 * Items are never paid out from here: the caller settles the escrow from the API's answer (it may hold the result
 * for review). A refusal ({@link DuelRefusedException}, e.g. 409 WrongState) also removes the file; the caller then
 * reads the match ({@code by-token}) and settles from what the API has.
 * <p>
 * Thread-safe: the API answers off the main thread; callers hop back before touching the world.
 */
public final class DuelPendingResults {

    private static final int FORMAT_VERSION = 1;

    /** One result: decisive (a win or forfeit) goes to {@code complete}, anything else to {@code cancel}. */
    public record Pending(UUID token, int matchId, DuelEndReason reason, Integer winnerUserId, Telemetry telemetry,
                          List<ItemSummary> spoils, Instant endedAt) {
        public Pending {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(reason, "reason");
            if (reason.apiName() == null) throw new IllegalArgumentException(reason + " has no API result");
            if (reason.isDecisive() && winnerUserId == null) throw new IllegalArgumentException("a win needs a winner");
            spoils = spoils == null ? List.of() : List.copyOf(spoils);
            endedAt = endedAt == null ? Instant.now() : endedAt;
        }

        public boolean decisive() {
            return reason.isDecisive();
        }
    }

    private final DuelMatchesApi api;
    private final File directory;
    private final Logger logger;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public DuelPendingResults(DuelMatchesApi api, File directory, Logger logger) {
        this.api = Objects.requireNonNull(api, "api");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Write {@code pending}, then send it. Completes with the API's result, or exceptionally: a
     * {@link DuelRefusedException} (file removed) or a network/server error (file kept for {@link #replay}).
     */
    public CompletableFuture<Result> report(Pending pending) {
        try {
            write(pending);
        } catch (IOException | RuntimeException e) {
            // Still send it: the API is the record; only a crash before the answer would lose it.
            logger.log(Level.SEVERE, "[Duel] Could not spool the result of match " + pending.token()
                    + "; sending it without a spool", e);
        }
        return send(pending);
    }

    /**
     * Send every spooled result that isn't in flight. {@code settled} gets each answered one (off the main thread).
     * Completes when every send was answered or failed.
     */
    public CompletableFuture<Void> replay(BiConsumer<Pending, Result> settled) {
        List<CompletableFuture<?>> sends = new ArrayList<>();
        for (Pending pending : list()) {
            if (inFlight.contains(pending.token())) continue;
            sends.add(send(pending).handle((result, error) -> {
                if (error == null) {
                    settled.accept(pending, result);
                } else if (DuelRefusedException.find(error) != null) {
                    settled.accept(pending, null);
                }
                return null;
            }));
        }
        return CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new));
    }

    public boolean isEmpty() {
        String[] names = directory.list((dir, name) -> name.endsWith(".yml"));
        return names == null || names.length == 0;
    }

    /** Whether a result for {@code token} waits to be sent. */
    public boolean has(UUID token) {
        return file(token).isFile();
    }

    /** Every spooled result; unreadable files are logged and left in place. */
    public List<Pending> list() {
        List<Pending> result = new ArrayList<>();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return result;
        for (File file : files) {
            try {
                result.add(read(file));
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "[Duel] Pending result " + file.getName() + " is unreadable; left in place", e);
            }
        }
        return result;
    }

    private CompletableFuture<Result> send(Pending pending) {
        if (!inFlight.add(pending.token())) {
            return CompletableFuture.failedFuture(new IllegalStateException("result of " + pending.token() + " is in flight"));
        }
        CompletableFuture<Result> call;
        try {
            call = pending.decisive()
                    ? api.complete(pending.matchId(), pending.token(), new Completion(pending.winnerUserId(),
                    pending.reason() == DuelEndReason.FORFEIT, pending.telemetry(), pending.spoils(), pending.endedAt()))
                    : api.cancel(pending.matchId(), pending.token(), pending.reason(), pending.telemetry());
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        return call.whenComplete((result, error) -> {
            inFlight.remove(pending.token());
            if (error == null) {
                deleteFile(pending.token());
            } else if (DuelRefusedException.find(error) != null) {
                logger.warning("[Duel] The API refused the result of match " + pending.token() + ": "
                        + DuelRefusedException.find(error).code() + "; settling from its record");
                deleteFile(pending.token());
            } else {
                logger.log(Level.WARNING, "[Duel] Result of match " + pending.token()
                        + " not delivered yet; kept in pending-results/ and sent again later", error);
            }
        });
    }

    private void write(Pending p) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create " + directory);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", FORMAT_VERSION);
        yaml.set("token", p.token().toString());
        yaml.set("matchId", p.matchId());
        yaml.set("reason", p.reason().name());
        yaml.set("winnerUserId", p.winnerUserId());
        yaml.set("endedAt", p.endedAt().toString());
        Telemetry t = p.telemetry();
        if (t != null) {
            yaml.set("telemetry.durationSeconds", t.durationSeconds());
            writeSide(yaml, "telemetry.challenger", t.challenger());
            writeSide(yaml, "telemetry.opponent", t.opponent());
        }
        List<Map<String, Object>> spoils = new ArrayList<>();
        for (ItemSummary s : p.spoils()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("ownerUserId", s.ownerUserId());
            m.put("material", s.material());
            m.put("amount", s.amount());
            if (s.name() != null) m.put("name", s.name());
            if (s.grade() != null) m.put("grade", s.grade());
            if (s.instanceId() != null) m.put("instanceId", s.instanceId());
            spoils.add(m);
        }
        yaml.set("spoils", spoils);
        File temp = new File(directory, p.token() + ".yml.tmp");
        yaml.save(temp);
        try {
            Files.move(temp.toPath(), file(p.token()).toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp.toPath(), file(p.token()).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeSide(YamlConfiguration yaml, String key, SideTelemetry s) {
        if (s == null) return;
        yaml.set(key + ".damageDealt", s.damageDealt());
        yaml.set(key + ".damageTaken", s.damageTaken());
        yaml.set(key + ".hits", s.hits());
        yaml.set(key + ".attackAttempts", s.attackAttempts());
        yaml.set(key + ".armorPoints", s.armorPoints());
        yaml.set(key + ".mainHand", s.mainHand());
        yaml.set(key + ".hadWeapon", s.hadWeapon());
    }

    private static Pending read(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Telemetry telemetry = null;
        if (yaml.isConfigurationSection("telemetry")) {
            telemetry = new Telemetry(yaml.getDouble("telemetry.durationSeconds"),
                    readSide(yaml.getConfigurationSection("telemetry.challenger")),
                    readSide(yaml.getConfigurationSection("telemetry.opponent")));
        }
        List<ItemSummary> spoils = new ArrayList<>();
        for (Map<?, ?> m : yaml.getMapList("spoils")) {
            spoils.add(new ItemSummary(((Number) m.get("ownerUserId")).intValue(), (String) m.get("material"),
                    ((Number) m.get("amount")).intValue(), (String) m.get("name"), (String) m.get("grade"),
                    m.get("instanceId") instanceof Number n ? n.longValue() : null));
        }
        Instant endedAt;
        try {
            endedAt = Instant.parse(yaml.getString("endedAt"));
        } catch (RuntimeException e) {
            endedAt = Instant.now();
        }
        return new Pending(UUID.fromString(yaml.getString("token")), yaml.getInt("matchId"),
                DuelEndReason.valueOf(yaml.getString("reason")),
                yaml.get("winnerUserId") != null ? yaml.getInt("winnerUserId") : null, telemetry, spoils, endedAt);
    }

    private static SideTelemetry readSide(ConfigurationSection s) {
        if (s == null) return null;
        return new SideTelemetry(s.getDouble("damageDealt"), s.getDouble("damageTaken"), s.getInt("hits"),
                s.getInt("attackAttempts"), s.get("armorPoints") != null ? s.getInt("armorPoints") : null,
                s.getString("mainHand"), s.get("hadWeapon") != null ? s.getBoolean("hadWeapon") : null);
    }

    private File file(UUID token) {
        return new File(directory, token + ".yml");
    }

    private void deleteFile(UUID token) {
        File file = file(token);
        if (file.exists() && !file.delete()) logger.warning("[Duel] Could not delete pending result " + file.getName());
    }
}
