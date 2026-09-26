package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeSpawnpoint;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.menu.BannerPatternSpec;
import net.knightsandkings.knk.core.siege.CaptureProgressGradient;
import net.knightsandkings.knk.core.siege.ObjectiveState;
import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;
import net.knightsandkings.knk.core.siege.SiegeDisplayText;
import net.knightsandkings.knk.core.siege.SiegeObjectiveLabels;
import net.knightsandkings.knk.core.siege.SiegeObjectiveBoard.BoardStep;
import net.knightsandkings.knk.paper.clan.BannerDesignBukkitMapper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The match in the world (DESIGN §6.5, §7.1): per objective a banner block showing the 8-stage capture
 * gradient in the holder's and the leading attacker's banner colours, floating {@link TextDisplay} labels
 * (not the legacy invisible ArmorStand), a flame capture ring, and happy-villager rings around every
 * spawnpoint's safe zone. Particles are sent with {@link Player#spawnParticle} to <b>members only</b>.
 * <p>
 * Labels are viewer-relative ({@link SiegeObjectiveLabels}): one display per alliance, hidden by default
 * and shown only to that alliance's members ("Your side holds this - defend it" vs "Held by X - capture
 * it"), plus a neutral display for everyone outside the match, hidden from members.
 * <p>
 * Crash safety: displays are non-persistent (never saved to disk). A banner is placed only where the
 * capture point is air, and every placed block is written to {@code siege-vault/world-blocks.yml}
 * until the match ends; on enable, leftovers that are still banners are set back to air.
 */
public final class SiegeWorldPresenter implements SiegeMatchObserver {

    /** Members further away than this don't get the rings. */
    private static final double PARTICLE_RANGE = 64.0;
    private static final double DISPLAY_HEIGHT = 2.3;

    private final Plugin plugin;
    private final Logger logger;
    private final File blockLog;
    private final NamespacedKey displayKey;
    private final Map<String, Map<Integer, ObjectiveVisual>> visualsByMatch = new HashMap<>();
    private final Map<String, String> warnedPatterns = new HashMap<>();

    private static final class ObjectiveVisual {
        Location center;
        Block placedBanner;
        /** Seen by everyone outside the match. */
        TextDisplay neutral;
        /** Per alliance group, seen only by that alliance's members. */
        final Map<Integer, TextDisplay> byAlliance = new HashMap<>();
        /** Last label per audience (alliance group; {@code null} key = neutral), to skip unchanged updates. */
        final Map<Integer, SiegeObjectiveLabels.Label> lastLabels = new HashMap<>();
        int lastHolder = -1;
        BannerPatternSpec lastBanner;
    }

    public SiegeWorldPresenter(Plugin plugin, File vaultDirectory) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.blockLog = new File(vaultDirectory, "world-blocks.yml");
        this.displayKey = new NamespacedKey(plugin, "siege_display");
        recoverPlacedBlocks();
    }

    // ==================== Observer ====================

    @Override
    public void matchStarted(SiegeLobbyRuntime lobby, SiegeMatch match) {
        Map<Integer, ObjectiveVisual> visuals = new HashMap<>();
        for (KnkSiegeObjective objective : match.scenario().objectives()) {
            Optional<Location> center = SiegeBukkit.toLocation(objective.captureLocation());
            if (center.isEmpty()) {
                logger.warning("[Siege] Objective " + objective.id() + " has no loadable capture location; not shown");
                continue;
            }
            ObjectiveVisual v = new ObjectiveVisual();
            // Smoke test 2026-09-26: the banner stands on the floor under the capture point, like the ring.
            v.center = SiegeBukkit.floorOf(center.get());
            Block block = v.center.getWorld().getBlockAt(v.center.getBlockX(),
                    (int) Math.ceil(v.center.getY() - 1e-6), v.center.getBlockZ());
            if (block.getType().isAir()) {
                v.placedBanner = block;
            } else {
                logger.warning("[Siege] Objective " + objective.id() + " banner spot " + block.getX() + "," + block.getY()
                        + "," + block.getZ() + " isn't air (" + block.getType() + "); no banner placed - clear it or "
                        + "re-capture the objective's location");
            }
            v.neutral = spawnLabel(v.center, match.matchToken(), true);
            for (Integer alliance : match.alliances().alliances()) {
                v.byAlliance.put(alliance, spawnLabel(v.center, match.matchToken(), false));
            }
            visuals.put(objective.id(), v);
        }
        visualsByMatch.put(match.matchToken(), visuals);
        for (UUID id : match.roster().playerIds()) {
            Player p = Bukkit.getPlayer(id);
            int teamId = match.roster().teamOf(id).orElse(-1);
            if (p == null || !match.alliances().knows(teamId)) continue;
            int alliance = match.alliances().allianceOf(teamId);
            for (ObjectiveVisual v : visuals.values()) {
                p.hideEntity(plugin, v.neutral);
                TextDisplay own = v.byAlliance.get(alliance);
                if (own != null) p.showEntity(plugin, own);
            }
        }
        writeBlockLog();
        paint(match, Map.of());
    }

    @Override
    public void secondTicked(SiegeLobbyRuntime lobby, SiegeMatch match, BoardStep step, Map<Integer, List<Presence>> presence) {
        paint(match, presence);
        rings(match);
    }

    /** A member who leaves sees the neutral labels again, like everyone outside the match. */
    @Override
    public void memberRemoved(SiegeLobbyRuntime lobby, SiegeMatch match, Player player) {
        Map<Integer, ObjectiveVisual> visuals = visualsByMatch.get(match.matchToken());
        if (visuals == null) return;
        for (ObjectiveVisual v : visuals.values()) {
            v.byAlliance.values().forEach(d -> player.hideEntity(plugin, d));
            if (v.neutral != null) player.showEntity(plugin, v.neutral);
        }
    }

    @Override
    public void matchEnded(SiegeLobbyRuntime lobby, SiegeMatch match) {
        Map<Integer, ObjectiveVisual> visuals = visualsByMatch.remove(match.matchToken());
        if (visuals != null) visuals.values().forEach(this::clear);
        writeBlockLog();
    }

    @Override
    public void shutdown() {
        visualsByMatch.values().forEach(m -> m.values().forEach(this::clear));
        visualsByMatch.clear();
        writeBlockLog();
    }

    // ==================== Painting ====================

    private void paint(SiegeMatch match, Map<Integer, List<Presence>> presence) {
        Map<Integer, ObjectiveVisual> visuals = visualsByMatch.get(match.matchToken());
        if (visuals == null) return;
        KnkSiegeScenario scenario = match.scenario();
        List<Integer> teamOrder = scenario.teams().stream().map(KnkSiegeTeam::id).toList();
        for (ObjectiveState state : match.board().objectives()) {
            ObjectiveVisual v = visuals.get(state.objectiveId());
            if (v == null) continue;
            Integer attacker = CaptureProgressGradient.leadingAttacker(presence.get(state.objectiveId()),
                    state.holderTeamId(), match.alliances(), teamOrder,
                    match.leadingAttackerByObjective().get(state.objectiveId()));
            if (attacker != null) match.leadingAttackerByObjective().put(state.objectiveId(), attacker);

            KnkSiegeTeam holder = scenario.team(state.holderTeamId()).orElse(null);
            KnkSiegeTeam attackerTeam = attacker == null ? null : scenario.team(attacker).orElse(null);
            // Smoke test 2026-09-26: the holder's full team banner when fully held (also from match
            // start), the v2 gradient towards the attacker's colour while it's being captured.
            BannerPatternSpec banner = CaptureProgressGradient.objectiveBanner(state.points(),
                    state.objective().capturePoints(), state.isCapturedFinal(),
                    bannerDesign(holder), bannerColor(holder), bannerDesign(attackerTeam), bannerColor(attackerTeam));
            if (v.placedBanner != null && !banner.equals(v.lastBanner)) {
                paintBanner(v.placedBanner, banner);
                v.lastBanner = banner;
            }

            String name = SiegeDisplayText.clean(state.objective().name(), "#" + state.objectiveId())
                    + (state.objective().instantVictory() ? " (main)" : "");
            boolean holderChanged = v.lastHolder != state.holderTeamId();
            v.lastHolder = state.holderTeamId();
            updateLabel(v, null, v.neutral, SiegeObjectiveLabels.forViewer(state, null, match.alliances()), name, holder, holderChanged);
            for (Map.Entry<Integer, TextDisplay> e : v.byAlliance.entrySet()) {
                updateLabel(v, e.getKey(), e.getValue(), SiegeObjectiveLabels.forViewer(state, e.getKey(), match.alliances()),
                        name, holder, holderChanged);
            }
        }
    }

    private static void updateLabel(ObjectiveVisual v, Integer audience, TextDisplay display, SiegeObjectiveLabels.Label label,
                                    String name, KnkSiegeTeam holder, boolean holderChanged) {
        if (display == null || !display.isValid()) return;
        if (!holderChanged && label.equals(v.lastLabels.get(audience))) return;
        v.lastLabels.put(audience, label);
        display.text(labelText(label, name, holder));
    }

    /** The label text for one audience (developer request: the holders must see it's theirs). */
    static Component labelText(SiegeObjectiveLabels.Label label, String name, KnkSiegeTeam holder) {
        int pct = label.capturePercent();
        Component title = Component.text(name, NamedTextColor.GOLD);
        Component who;
        Component state;
        switch (label.relation()) {
            case OURS -> {
                who = Component.text("\u2714 Your side holds this", NamedTextColor.GREEN);
                state = switch (label.status()) {
                    case SECURE -> Component.text("Secure - defend it", NamedTextColor.GREEN);
                    case WEAKENED -> Component.text("Enemy progress " + pct + "% - stand here to restore it", NamedTextColor.YELLOW);
                    case UNDER_ATTACK -> Component.text("Under attack! Enemy progress " + pct + "%", NamedTextColor.RED);
                    case FINAL -> Component.text("Captured - yours for this match", NamedTextColor.GREEN);
                };
            }
            case ENEMY -> {
                who = Component.text("\u2716 Held by ", NamedTextColor.RED).append(SiegeBukkit.teamComponent(holder));
                state = switch (label.status()) {
                    case SECURE -> Component.text("Stand in the ring to capture it", NamedTextColor.YELLOW);
                    case WEAKENED -> Component.text("Captured " + pct + "% - keep pushing", NamedTextColor.GOLD);
                    case UNDER_ATTACK -> Component.text("Being captured: " + pct + "%", NamedTextColor.GOLD);
                    case FINAL -> Component.text("Lost for this match", NamedTextColor.RED);
                };
            }
            default -> {
                who = Component.text("Held by ", NamedTextColor.GRAY).append(SiegeBukkit.teamComponent(holder));
                state = Component.text(label.status() == SiegeObjectiveLabels.Status.FINAL ? "Captured"
                        : pct + "% captured", NamedTextColor.WHITE);
            }
        }
        return title.append(Component.newline()).append(who).append(Component.newline()).append(state);
    }

    private TextDisplay spawnLabel(Location center, String matchToken, boolean visibleByDefault) {
        return center.getWorld().spawn(center.clone().add(0, DISPLAY_HEIGHT, 0), TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
            d.setShadowed(true);
            d.setVisibleByDefault(visibleByDefault);
            d.getPersistentDataContainer().set(displayKey, PersistentDataType.STRING, matchToken);
        });
    }

    /** The team's banner base colour for the gradient; its chat colour as a dye when it has no banner. */
    private static BannerPatternSpec bannerDesign(KnkSiegeTeam team) {
        return team == null || team.bannerDesign() == null ? null : team.bannerDesign().toPatternSpec();
    }

    private static String bannerColor(KnkSiegeTeam team) {
        if (team == null) return null;
        String base = BannerDesignBukkitMapper.baseColor(team.bannerDesign());
        if (base != null) return base;
        return dyeForChatColor(team.chatColor());
    }

    static String dyeForChatColor(String chatColor) {
        if (chatColor == null) return "WHITE";
        return switch (chatColor.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "BLACK" -> "BLACK";
            case "DARK_BLUE", "BLUE" -> "BLUE";
            case "DARK_GREEN" -> "GREEN";
            case "GREEN" -> "LIME";
            case "DARK_AQUA" -> "CYAN";
            case "AQUA" -> "LIGHT_BLUE";
            case "DARK_RED", "RED" -> "RED";
            case "DARK_PURPLE" -> "PURPLE";
            case "LIGHT_PURPLE" -> "MAGENTA";
            case "GOLD" -> "ORANGE";
            case "YELLOW" -> "YELLOW";
            case "GRAY" -> "LIGHT_GRAY";
            case "DARK_GRAY" -> "GRAY";
            default -> "WHITE";
        };
    }

    private void paintBanner(Block block, BannerPatternSpec spec) {
        Material material = BannerDesignBukkitMapper.bannerMaterial(spec.baseColor());
        if (block.getType() != material) {
            if (!block.getType().isAir() && !Tag.BANNERS.isTagged(block.getType())) return; // something else took the spot
            block.setType(material, false);
        }
        BlockState state = block.getState();
        if (state instanceof Banner banner) {
            banner.setPatterns(BannerDesignBukkitMapper.toPatterns(spec, warning -> {
                if (warnedPatterns.put(warning, warning) == null) logger.warning("[Siege] Objective banner: " + warning);
            }));
            banner.update(true, false);
        }
    }

    private void rings(SiegeMatch match) {
        List<Player> viewers = new ArrayList<>();
        for (UUID id : match.roster().playerIds()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) viewers.add(p);
        }
        if (viewers.isEmpty()) return;
        for (KnkSiegeObjective objective : match.scenario().objectives()) {
            SiegeBukkit.toLocation(objective.captureLocation()).map(SiegeBukkit::floorOf)
                    .ifPresent(center -> ring(viewers, center, objective.captureRadius(), Particle.FLAME));
        }
        for (KnkSiegeTeam team : match.scenario().teams()) {
            for (KnkSiegeSpawnpoint spawn : team.spawnpoints()) {
                SiegeBukkit.toLocation(spawn.location())
                        .ifPresent(center -> ring(viewers, center, spawn.safeZoneRadius(), Particle.HAPPY_VILLAGER));
            }
        }
    }

    private static void ring(List<Player> viewers, Location center, double radius, Particle particle) {
        if (radius <= 0) return;
        World world = center.getWorld();
        int points = Math.max(12, (int) Math.ceil(radius * 8));
        for (Player viewer : viewers) {
            if (!Objects.equals(viewer.getWorld(), world)) continue;
            if (viewer.getLocation().distanceSquared(center) > PARTICLE_RANGE * PARTICLE_RANGE) continue;
            for (int i = 0; i < points; i++) {
                double angle = 2 * Math.PI * i / points;
                viewer.spawnParticle(particle, center.getX() + radius * Math.cos(angle), center.getY() + 0.15,
                        center.getZ() + radius * Math.sin(angle), 1, 0, 0, 0, 0);
            }
        }
    }

    // ==================== Cleanup and crash safety ====================

    private void clear(ObjectiveVisual v) {
        if (v.neutral != null && v.neutral.isValid()) v.neutral.remove();
        v.byAlliance.values().forEach(d -> {
            if (d.isValid()) d.remove();
        });
        if (v.placedBanner != null && Tag.BANNERS.isTagged(v.placedBanner.getType())) {
            v.placedBanner.setType(Material.AIR, false);
        }
    }

    private void writeBlockLog() {
        List<String> entries = new ArrayList<>();
        visualsByMatch.values().forEach(m -> m.values().forEach(v -> {
            if (v.placedBanner != null) {
                Block b = v.placedBanner;
                entries.add(b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ());
            }
        }));
        try {
            if (entries.isEmpty()) {
                if (blockLog.exists() && !blockLog.delete()) logger.warning("[Siege] Could not delete " + blockLog);
                return;
            }
            File dir = blockLog.getParentFile();
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create " + dir);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("banners", entries);
            yaml.save(blockLog);
        } catch (IOException e) {
            logger.log(Level.WARNING, "[Siege] Could not write " + blockLog + "; a crash now would leave objective banners behind", e);
        }
    }

    /** On enable: objective banners a crash left behind are set back to air. */
    private void recoverPlacedBlocks() {
        if (!blockLog.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(blockLog);
        int cleared = 0;
        for (String entry : yaml.getStringList("banners")) {
            String[] parts = entry.split(";");
            if (parts.length != 4) continue;
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) continue;
            try {
                Block block = world.getBlockAt(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                if (Tag.BANNERS.isTagged(block.getType())) {
                    block.setType(Material.AIR, false);
                    cleared++;
                }
            } catch (NumberFormatException ignored) { }
        }
        if (!blockLog.delete()) logger.warning("[Siege] Could not delete " + blockLog);
        if (cleared > 0) logger.info("[Siege] Removed " + cleared + " objective banner(s) left by an interrupted match");
    }
}
