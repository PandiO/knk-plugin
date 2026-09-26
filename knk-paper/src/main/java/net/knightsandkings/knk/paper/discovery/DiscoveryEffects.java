package net.knightsandkings.knk.paper.discovery;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrant;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrantResult;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.paper.commands.support.PromotionEffects;
import net.knightsandkings.knk.paper.config.KnkConfig;

/**
 * The discovery moment (docs/specs/domain-discovery DESIGN.md §3.6): per newly discovered place, in
 * the API's order (Town, District, Structure), a sound, particles (a firework burst too for a Town)
 * and the chat lines of {@link DiscoveryMessages}, {@code spacing-ticks} apart. A title change from
 * the discovery XP is shown once after the last place, like any other promotion
 * ({@link PromotionEffects}); the API doesn't queue it for the notification poller. Then the balance
 * is re-read and the scoreboard redrawn, as after a salary payout.
 *
 * <p>Main thread only.
 */
public final class DiscoveryEffects {
    private static final Logger LOGGER = Logger.getLogger(DiscoveryEffects.class.getName());

    private final Plugin plugin;
    private final KnkConfig.DiscoveryConfig config;
    private final UsersDataAccess usersDataAccess;
    private final BiConsumer<Player, UserSummary> displayRefresher;
    private final Sound sound;
    private final String soundKey;
    private final Particle particle;

    public DiscoveryEffects(Plugin plugin, KnkConfig.DiscoveryConfig config, UsersDataAccess usersDataAccess,
                            BiConsumer<Player, UserSummary> displayRefresher) {
        this.plugin = plugin;
        this.config = config;
        this.usersDataAccess = usersDataAccess;
        this.displayRefresher = displayRefresher;
        String soundName = config.effects().sound() == null ? "" : config.effects().sound().trim();
        if (soundName.contains(".") || soundName.contains(":")) {
            this.sound = null;
            this.soundKey = soundName;
        } else {
            this.sound = resolveSound(soundName);
            this.soundKey = null;
        }
        this.particle = resolveParticle(config.effects().particle());
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static Sound resolveSound(String name) {
        if (name.isEmpty()) {
            return null;
        }
        try {
            return Sound.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            LOGGER.warning("discovery.effects.sound: unknown sound '" + name + "', using ENTITY_PLAYER_LEVELUP");
            return Sound.ENTITY_PLAYER_LEVELUP;
        }
    }

    private static Particle resolveParticle(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            Particle resolved = Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
            if (resolved.getDataType() != Void.class) {
                LOGGER.warning("discovery.effects.particle: '" + name + "' needs extra data, using HAPPY_VILLAGER");
                return Particle.HAPPY_VILLAGER;
            }
            return resolved;
        } catch (RuntimeException e) {
            LOGGER.warning("discovery.effects.particle: unknown particle '" + name + "', using HAPPY_VILLAGER");
            return Particle.HAPPY_VILLAGER;
        }
    }

    /** Everything, for a discovery the player just made. */
    public void show(Player player, DiscoveryGrantResult result) {
        if (result == null || !result.hasGrants()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        List<DiscoveryGrant> granted = result.granted();
        for (int i = 0; i < granted.size(); i++) {
            DiscoveryGrant grant = granted.get(i);
            boolean last = i == granted.size() - 1;
            Runnable moment = () -> {
                Player online = Bukkit.getPlayer(uuid);
                if (online == null) {
                    return;
                }
                playMoment(online, grant);
                DiscoveryMessages.grantLines(config.messages().discovered(), grant, result).forEach(online::sendMessage);
                if (last && result.titleChange() != null) {
                    PromotionEffects.show(online, result.titleChange());
                }
            };
            long delay = (long) i * config.effectSpacingTicks();
            if (delay == 0) {
                moment.run();
            } else {
                Bukkit.getScheduler().runTaskLater(plugin, moment, delay);
            }
        }
        refreshDisplay(uuid);
    }

    /**
     * For discoveries delivered late (the API was down): one sound, the count and the totals, then a
     * promotion if there was one.
     */
    public void showSummary(Player player, DiscoveryGrantResult result) {
        if (result == null || !result.hasGrants()) {
            return;
        }
        playSound(player);
        DiscoveryMessages.replaySummary(config.messages().replaySummary(), result).forEach(player::sendMessage);
        if (result.titleChange() != null) {
            PromotionEffects.show(player, result.titleChange());
        }
        refreshDisplay(player.getUniqueId());
    }

    private void playMoment(Player player, DiscoveryGrant grant) {
        playSound(player);
        Location at = player.getLocation().add(0, 1, 0);
        double spread = config.effects().particleSpread();
        if (particle != null && config.effects().particleCount() > 0) {
            player.getWorld().spawnParticle(particle, at, config.effects().particleCount(), spread, spread, spread, 0.0);
        }
        if (config.effects().townFirework() && "Town".equalsIgnoreCase(grant.domainType())) {
            player.getWorld().spawnParticle(Particle.FIREWORK, at, 40, 0.4, 0.6, 0.4, 0.15);
        }
    }

    private void playSound(Player player) {
        float volume = config.effects().soundVolume();
        float pitch = config.effects().soundPitch();
        if (sound != null) {
            player.playSound(player.getLocation(), sound, volume, pitch);
        } else if (soundKey != null && !soundKey.isEmpty()) {
            player.playSound(player.getLocation(), soundKey, volume, pitch);
        }
    }

    /** Re-reads the balance into the user cache and redraws the scoreboard with it (as after a salary payout). */
    private void refreshDisplay(UUID uuid) {
        if (usersDataAccess == null || displayRefresher == null) {
            return;
        }
        usersDataAccess.refreshAsync(uuid).whenComplete((fetch, ex) -> {
            UserSummary fresh = ex == null && fetch != null ? fetch.value().orElse(null) : null;
            if (fresh == null || !plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    displayRefresher.accept(player, fresh);
                }
            });
        });
    }
}
