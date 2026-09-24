package net.knightsandkings.knk.paper.listeners;

import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitRunnable;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.knightsandkings.knk.core.dataaccess.FetchPolicy;
import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.FetchStatus;
import net.knightsandkings.knk.core.dataaccess.TownsDataAccess;
import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.domain.towns.TownDetail;
import net.knightsandkings.knk.core.domain.users.UserDetail;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.paper.KnKPlugin;
import net.knightsandkings.knk.paper.cache.CacheManager;
import net.knightsandkings.knk.paper.utils.ColorOptions;
import net.knightsandkings.knk.paper.utils.ScoreboardUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Legacy player listener for join events.
 * 
 * NOTE: User creation is now handled by UserAccountListener + UserManager.
 * This listener only handles join greeting and teleportation.
 */
public class PlayerListener implements Listener {
	private static final Logger LOGGER = Logger.getLogger(PlayerListener.class.getName());
	private static final long MENTION_SOUND_COOLDOWN_MILLIS = 5_000L;
	private static final int DEFAULT_RESPAWN_TOWN_ID = 4;
	private static final Map<UUID, Long> mentionSoundCooldowns = new ConcurrentHashMap<>();

	private final UsersDataAccess usersDataAccess;
	private final TownsDataAccess townsDataAccess;
	private final CacheManager cacheManager;
	private final UsersCommandApi usersCommandApi;

	public PlayerListener(UsersDataAccess usersDataAccess, TownsDataAccess townsDataAccess, CacheManager cacheManager,
			UsersCommandApi usersCommandApi) {
		this.usersDataAccess = usersDataAccess;
		this.townsDataAccess = townsDataAccess;
		this.cacheManager = cacheManager;
		this.usersCommandApi = usersCommandApi;
	}

	@EventHandler
	public void onValidateLogin(AsyncPlayerPreLoginEvent e) {
		UUID uuid = e.getUniqueId();
		String username = e.getName();

		try {
			FetchResult<UserSummary> result = usersDataAccess.getByUuidAsync(uuid, FetchPolicy.STALE_OK).join();
			if (result.isStale()) {
				triggerBackgroundUserRefresh(uuid);
			}

			if (result.isSuccess()) {
				LOGGER.fine("User " + uuid + " loaded via " + result.source() + " (" + result.status() + ")");
				return;
			}

			if (result.status() == FetchStatus.NOT_FOUND) {
				FetchResult<UserSummary> usernameLookup = usersDataAccess.getByUsernameAsync(username).join();
				if (usernameLookup.isSuccess()) {
					LOGGER.info("Loaded user " + username + " via username lookup (UUID: " + uuid + ")");
					return;
				}

				UserDetail newUser = new UserDetail(null, username, uuid, null, -1, new Date(), true);
				FetchResult<UserSummary> created = usersDataAccess.getOrCreateAsync(uuid, true, newUser).join();
				if (created.isSuccess()) {
					LOGGER.info("Created and cached new user " + username + " (UUID: " + uuid + ")");
				} else if (created.status() == FetchStatus.ERROR) {
					LOGGER.warning("Failed to create user " + uuid + ": " + created.error().map(Throwable::getMessage).orElse("unknown error"));
				}
				return;
			}

			if (result.status() == FetchStatus.ERROR) {
				LOGGER.warning("Failed to load user " + uuid + " from API: " + result.error().map(Throwable::getMessage).orElse("unknown error"));
			}
		} catch (Exception ex) {
			LOGGER.log(Level.WARNING, "Unexpected error loading user " + uuid + " from data access", ex);
			// Allow login to proceed even if data fetch fails
		}
	}

	private void triggerBackgroundUserRefresh(UUID uuid) {
		usersDataAccess.refreshAsync(uuid)
			.thenAccept(refreshResult -> {
				if (refreshResult.isSuccess()) {
					LOGGER.fine("Background refresh completed for user " + uuid);
				} else if (refreshResult.status() == FetchStatus.ERROR) {
					LOGGER.fine("Background refresh failed for user " + uuid + ": "
						+ refreshResult.error().map(Throwable::getMessage).orElse("unknown error"));
				}
			})
			.exceptionally(ex -> {
				LOGGER.log(Level.WARNING, "Background refresh error for user " + uuid, ex);
				return null;
			});
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent e) {
		Player player = e.getPlayer();
        UserSummary user = cacheManager.getUserCache().getByUuid(player.getUniqueId()).orElse(null);
        reportPresence(user, true);

		e.joinMessage(Component.text("► " + "Player " + player.getName() + " joined").color(ColorOptions.message));
        
        player.sendMessage(Component.text("Welcome back " + player.getName()).color(ColorOptions.messageachievement));
		if (user != null) {
			player.sendMessage(Component.text("You have " + user.coins() + " coins.").color(ColorOptions.messageachievement));
			if (user.isNewUser()) {
				player.sendMessage(Component.text("It looks like this is your first time playing! Enjoy your adventure!").color(ColorOptions.messageachievement));
			}
		}

		if (!player.hasPermission("k&k.join.owner")) {
			player.setGameMode(GameMode.SURVIVAL);
			player.setFlying(false);
			// Town town = (Town) RepositoryManager.getInstance().getRepository(Town.class, Dominion.KEY_CLASS).getList().get(0);
			// if (town != null) {
			// 	user.teleport(town.getLocation().getLocation(), town.getName());
			// } else {
			// 	user.teleport(Bukkit.getWorld(KNK.WORLD_NAME_DEF).getSpawnLocation(), null);
			// }
            player.teleport(Bukkit.getWorld(Bukkit.getWorlds().get(0).getName()).getSpawnLocation());
		}
		ScoreboardUtil.setScoreboard(Arrays.asList(player));
	}

	@EventHandler
	public void onLeave(PlayerQuitEvent e) {
		Player player = e.getPlayer();
        UserSummary user = cacheManager.getUserCache().getByUuid(player.getUniqueId()).orElse(null);
        reportPresence(user, false);

		e.quitMessage(Component.text(ColorOptions.messageArrow + "Player " + player.getName() + " left").color(ColorOptions.message));
		e.quitMessage(Component.text(ColorOptions.messageArrow + "Player " + player.getName() + " left").color(ColorOptions.message));
	}

	/**
	 * Reports online presence to knk-web-api (docs/specs/user-management/DESIGN.md §5/§7 item 2)
	 * for the moderation view's "currently online" filter. A dedicated PUT
	 * /api/users/{id}/presence call rather than piggybacking on a periodic sync — there is no
	 * such sync loop for users to attach to (UsersDataAccess only refreshes on-demand when a
	 * lookup finds the cache stale), so this is the only mechanism that gets presence reported at
	 * all. Silently no-ops if the user isn't cached yet (e.g. a brand new account created moments
	 * ago by onValidateLogin, whose UserSummary the join event's cache read may race) — a missed
	 * presence ping is not worth failing login over, and the next join/quit will catch up.
	 */
	private void reportPresence(UserSummary user, boolean isOnline) {
		if (user == null || user.id() == null) {
			return;
		}
		usersCommandApi.setPresenceById(user.id(), isOnline)
			.exceptionally(ex -> {
				LOGGER.log(Level.WARNING, "Failed to report presence (isOnline=" + isOnline + ") for user " + user.id(), ex);
				return null;
			});
	}

	@EventHandler
	public void onCommand(PlayerCommandPreprocessEvent e) {
		Player player = e.getPlayer();
		String cmd = e.getMessage();
		if (cmd.equalsIgnoreCase("/help")
				|| cmd.equalsIgnoreCase("/plugins")
				|| cmd.equalsIgnoreCase("/pl")
				|| cmd.equalsIgnoreCase("/plugin")
				|| cmd.equalsIgnoreCase("/v")
				|| cmd.equalsIgnoreCase("/version")) {
			if (!player.hasPermission("k&k.owner")) {
				e.setCancelled(true);
			}
		}
	}

	@EventHandler(priority = EventPriority.NORMAL)
	public void onChat(AsyncChatEvent e) {
		Player player = e.getPlayer();

		String rawMessage = PlainTextComponentSerializer.plainText().serialize(e.message());
		String dn = player.getName();
		String capitalizedMessage = rawMessage.isEmpty() ? rawMessage : ("" + rawMessage.charAt(0)).toUpperCase() + rawMessage.substring(1);
		
		// Convert legacy color codes (&c, &4, etc.) to Adventure Component
		String legacyFormattedMessage = ChatColor.translateAlternateColorCodes('&', capitalizedMessage);
		Component messageComponent = LegacyComponentSerializer.legacySection().deserialize(legacyFormattedMessage);

		Component finalMessage;
		if (player.hasPermission("k&k.owner")) {
			// Build owner format with proper Components using ColorOptions TextColor objects
			Component prefixComponent = Component.text("[")
					.color(ColorOptions.ownerformat)
					.append(Component.text("OWNER").color(ColorOptions.ownersubjects))
					.append(Component.text("]").color(ColorOptions.ownerformat))
					.append(Component.text(" " + dn + ": ").color(ColorOptions.ownersubjects));
			finalMessage = prefixComponent.append(messageComponent);
			e.renderer((source, sourceDisplayName, message, viewer) -> finalMessage);
		} else {
			// Build default format with proper Components using ColorOptions TextColor objects
			Component prefixComponent = Component.text(" " + dn + ": ")
					.color(ColorOptions.defaultsubjects);
			finalMessage = prefixComponent.append(messageComponent);
			e.renderer((source, sourceDisplayName, message, viewer) -> finalMessage);
		}

		/**
		 * Player mention
		 */
		new BukkitRunnable() {
			@Override
			public void run() {
				long now = System.currentTimeMillis();
				String lowered = rawMessage.toLowerCase();
				for (Player p : Bukkit.getOnlinePlayers()) {
					if (!lowered.contains(p.getName().toLowerCase())) {
						continue;
					}
					Long last = mentionSoundCooldowns.get(p.getUniqueId());
					if (last != null && now - last < MENTION_SOUND_COOLDOWN_MILLIS) {
						continue;
					}
					mentionSoundCooldowns.put(p.getUniqueId(), now);
					p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0F, 1.0F);
				}
			}
		}.runTaskAsynchronously(KnKPlugin.getPlugin(KnKPlugin.class));
	}

	@EventHandler
	public void onPlayerDeath(PlayerDeathEvent e) {
		Player player = e.getEntity();
		Player killer = player.getKiller();
		player.sendMessage(Component.text("You died").color(ColorOptions.message));
	}

	@EventHandler
	public void onPlayerRespawn(PlayerRespawnEvent e) {
		Player player = e.getPlayer();
		
		// Fetch default town using TownsDataAccess (typically cached after server startup)
		townsDataAccess.getByIdAsync(4, FetchPolicy.CACHE_FIRST).thenAccept(result -> {
			if (!result.isSuccess()) {
				LOGGER.severe("Failed to load default town for respawn");
				return;
			}

			TownDetail town = result.value().orElseThrow();
			/**
			 * TODO Implement logic to retrieve Bukkit Location from KnK location entity to set respawn location.
			 */
			// e.setRespawnLocation()
		}).exceptionally(e2 -> {
			LOGGER.log(Level.WARNING, "Error fetching default town for respawn", e2);
			return null;
		});
	}

	@EventHandler
	public void onItemPickup(PlayerPickupItemEvent e) {
		if (e.getPlayer().isOp()) {
			return;
		}
		e.setCancelled(true);
	}
}
