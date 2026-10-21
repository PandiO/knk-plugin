package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Settlement;
import net.knightsandkings.knk.core.duel.DuelEndReason;
import net.knightsandkings.knk.core.duel.DuelResult;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.duel.view.ArenaMenuView;
import net.knightsandkings.knk.core.duel.view.DuelAnnouncements;
import net.knightsandkings.knk.core.duel.view.DuelAnnouncements.Payout;
import net.knightsandkings.knk.core.duel.view.DuelMatchSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The duel chat lines (arena duels DESIGN §9.1, §8.6; plan Phase 6), a {@link DuelObserver} of the runtime:
 * <ul>
 *   <li>at warmup start, a broadcast with {@code [Spectate]} (the spectator area's warp, when it has one) and
 *       {@code [Details]} (the match detail menu), when the arena announces this kind of match and neither duelist
 *       is on the {@code duels.announce.cooldown-seconds} spam cooldown;</li>
 *   <li>the result after the API's answer (a held result says "pending review"; a friendly duel at once), and draws,
 *       under the same arena toggles;</li>
 *   <li>cancels, aborts and the death line only to the duelists and the players in the arena's spectator area.</li>
 * </ul>
 * The texts are {@link DuelAnnouncements} templates, overridable in {@code duels.announce.templates}. The buttons run
 * {@code /duels spectate <arenaId>} and {@code /duels view <token>} (P5's {@code /duels}, which opens the menus
 * through {@code DuelMenus}).
 */
public final class DuelAnnouncer implements DuelObserver {

    public static final String SPECTATE_COMMAND = "/duels spectate ";
    public static final String DETAILS_COMMAND = "/duels view ";

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Logger LOGGER = Logger.getLogger(DuelAnnouncer.class.getName());

    private final DuelService service;
    private final DuelSpectators spectators;
    private final Consumer<Component> broadcaster;
    private final Function<UUID, Player> players;
    private final Supplier<DuelAnnouncements.Templates> templates;
    private final DuelAnnouncements.Cooldown cooldown = new DuelAnnouncements.Cooldown();
    /** Decisive value-moving results waiting for the API's answer, as they were when the match ended. */
    private final Map<UUID, DuelMatchSnapshot> awaitingSettlement = new HashMap<>();

    public DuelAnnouncer(DuelService service, DuelSpectators spectators, Consumer<Component> broadcaster,
                         Function<UUID, Player> players, Supplier<DuelAnnouncements.Templates> templates) {
        this.service = Objects.requireNonNull(service, "service");
        this.spectators = Objects.requireNonNull(spectators, "spectators");
        this.broadcaster = Objects.requireNonNull(broadcaster, "broadcaster");
        this.players = Objects.requireNonNull(players, "players");
        this.templates = templates != null ? templates : DuelAnnouncements.Templates::defaults;
    }

    /** {@code duels.announce.templates} (warmup, result, forfeit, draw, cancel, death); blank keeps the default. */
    public static DuelAnnouncements.Templates templatesFrom(ConfigurationSection announce) {
        ConfigurationSection t = announce == null ? null : announce.getConfigurationSection("templates");
        if (t == null) return DuelAnnouncements.Templates.defaults();
        return new DuelAnnouncements.Templates(t.getString("warmup"), t.getString("result"), t.getString("forfeit"),
                t.getString("draw"), t.getString("cancel"), t.getString("death"));
    }

    // ==================== DuelObserver ====================

    @Override
    public void warmupStarted(DuelMatch match, int warmupSeconds) {
        safely(() -> announceWarmup(match));
    }

    @Override
    public void matchEnded(DuelMatch match, DuelResult result) {
        safely(() -> ended(match, result));
    }

    @Override
    public void settled(DuelMatch match, Settlement settlement) {
        safely(() -> settledWith(match, settlement));
    }

    @Override
    public void released(DuelMatch match) {
        safely(() -> {
            // A result the API never answered for (no match id): announce it once, the payout still to follow.
            DuelMatchSnapshot s = awaitingSettlement.remove(match.token());
            if (s != null) announceResult(match.arena(), s, Payout.PENDING);
        });
    }

    /** The runtime calls observers inside its own steps: a chat failure is logged, never thrown into a duel. */
    private static void safely(Runnable body) {
        try {
            body.run();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.log(Level.WARNING, "[Duel] announcement failed", e);
        }
    }

    private void announceWarmup(DuelMatch match) {
        DuelMatchSnapshot s = DuelSnapshots.of(service, match);
        if (!announces(match.arena(), s)) return;
        if (!cooldown.tryBroadcast(s.challenger().playerId(), s.opponent().playerId(), service.now(),
                service.config().announceCooldownSeconds())) {
            return;
        }
        broadcaster.accept(warmupLine(DuelAnnouncements.warmup(templates.get(), s), match.arena(), s.token()));
    }

    private void ended(DuelMatch match, DuelResult result) {
        if (result.reason() == DuelEndReason.LOCK_REFUSED) return;
        DuelMatchSnapshot s = DuelSnapshots.of(service, match);
        DuelAnnouncements.Templates t = templates.get();
        if (result.isDecisive()) {
            if (result.reason() == DuelEndReason.WIN) local(match.arena(), s, DuelAnnouncements.death(t, s));
            if (s.friendly()) {
                announceResult(match.arena(), s, Payout.PAID);
            } else {
                awaitingSettlement.put(s.token(), s);
            }
        } else if (result.reason().isDraw()) {
            send(match.arena(), s, DuelAnnouncements.draw(t, s));
        } else {
            local(match.arena(), s, DuelAnnouncements.cancel(t, s));
        }
    }

    private void settledWith(DuelMatch match, Settlement settlement) {
        DuelMatchSnapshot s = awaitingSettlement.remove(match.token());
        if (s == null) return;
        Payout payout = settlement == null ? Payout.PENDING
                : switch (settlement) {
                    case HELD -> Payout.HELD;
                    case REFUNDED -> Payout.NONE;
                    default -> Payout.PAID;
                };
        announceResult(match.arena(), s, payout);
    }

    // ==================== Sending ====================

    private void announceResult(KnkArena arena, DuelMatchSnapshot s, Payout payout) {
        send(arena, s, DuelAnnouncements.result(templates.get(), s, payout));
    }

    /** Broadcast when the arena announces this kind of match, else only to the arena. */
    private void send(KnkArena arena, DuelMatchSnapshot s, String text) {
        if (text == null) return;
        if (announces(arena, s)) {
            broadcaster.accept(LEGACY.deserialize(text));
        } else {
            local(arena, s, text);
        }
    }

    /** To the two duelists and the players in the arena's spectator areas. */
    private void local(KnkArena arena, DuelMatchSnapshot s, String text) {
        if (text == null) return;
        Component line = LEGACY.deserialize(text);
        Set<Player> audience = new LinkedHashSet<>(spectators.spectatorsOf(arena));
        for (DuelSide side : DuelSide.values()) {
            Player p = players.apply(s.side(side).playerId());
            if (p != null && p.isOnline()) audience.add(p);
        }
        audience.forEach(p -> p.sendMessage(line));
    }

    static boolean announces(KnkArena arena, DuelMatchSnapshot s) {
        return arena != null && DuelAnnouncements.arenaAnnounces(arena.announceMatches(), arena.announceFriendly(), s.friendly());
    }

    /** The warmup line with its buttons; [Spectate] only when the arena has a spectator warp. */
    static Component warmupLine(String text, KnkArena arena, UUID token) {
        Component line = LEGACY.deserialize(text);
        var warp = ArenaMenuView.spectatorWarp(arena);
        if (warp != null) {
            line = line.append(Component.text("  "))
                    .append(Component.text("[Spectate]", NamedTextColor.GREEN)
                            .clickEvent(ClickEvent.runCommand(SPECTATE_COMMAND + arena.id()))
                            .hoverEvent(HoverEvent.showText(Component.text("Teleport to " + warp.name(), NamedTextColor.GREEN))));
        }
        return line.append(Component.text(" "))
                .append(Component.text("[Details]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand(DETAILS_COMMAND + token))
                        .hoverEvent(HoverEvent.showText(Component.text("Stakes and both players' statistics", NamedTextColor.AQUA))));
    }
}
