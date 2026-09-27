package net.knightsandkings.knk.paper.listeners;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
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
import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.dataaccess.TownsDataAccess;
import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.domain.towns.TownDetail;
import net.knightsandkings.knk.core.domain.users.UserDetail;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.KitsCommandApi;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.paper.KnKPlugin;
import net.knightsandkings.knk.paper.cache.CacheManager;
import net.knightsandkings.knk.paper.chat.ChatLineFormat;
import net.knightsandkings.knk.paper.kit.KitGrantPlacer;
import net.knightsandkings.knk.paper.modes.ModeService;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import net.knightsandkings.knk.paper.user.IgnoreService;
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
	private final KnkPermissible knkPermissible;
	private final UsersCommandApi usersCommandApi;
	private final KitsCommandApi kitsCommandApi;
	private final ItemBlueprintsDataAccess itemBlueprintsDataAccess;
	private final MinecraftMaterialRefsDataAccess minecraftMaterialRefsDataAccess;
	/** Null-safe: chat works unfiltered without it. */
	private final IgnoreService ignoreService;

	public PlayerListener(
			UsersDataAccess usersDataAccess,
			TownsDataAccess townsDataAccess,
			CacheManager cacheManager,
			KnkPermissible knkPermissible,
			UsersCommandApi usersCommandApi,
			KitsCommandApi kitsCommandApi,
			ItemBlueprintsDataAccess itemBlueprintsDataAccess,
			MinecraftMaterialRefsDataAccess minecraftMaterialRefsDataAccess,
			IgnoreService ignoreService
	) {
		this.usersDataAccess = usersDataAccess;
		this.townsDataAccess = townsDataAccess;
		this.cacheManager = cacheManager;
		this.knkPermissible = knkPermissible;
		this.usersCommandApi = usersCommandApi;
		this.kitsCommandApi = kitsCommandApi;
		this.itemBlueprintsDataAccess = itemBlueprintsDataAccess;
		this.minecraftMaterialRefsDataAccess = minecraftMaterialRefsDataAccess;
		this.ignoreService = ignoreService;
	}

	@EventHandler
	public void onValidateLogin(AsyncPlayerPreLoginEvent e) {
		UUID uuid = e.getUniqueId();
		String username = e.getName();

		try {
			// API first, so a relog always picks up changes made since the cache was filled (a premium
			// tier or title changed from the web-app, the Player manager, or an expired temporary
			// tier). STALE_OK served any unexpired cache entry without asking the API. The cached
			// value is still used when the API can't be reached.
			FetchResult<UserSummary> result = usersDataAccess.getByUuidAsync(uuid, FetchPolicy.API_THEN_CACHE_REFRESH).join();
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

		// The vanilla "X joined the game" broadcast is replaced with this custom line; the
		// personal "Welcome back"/balance greeting is UserAccountListener's job (it fetches
		// fresh userData asynchronously and also handles duplicate-account/link prompts) — this
		// listener used to send its own, duplicate "Welcome back" message from a synchronous,
		// possibly-stale cache read. Removed rather than kept in sync with two sources of truth.
		e.joinMessage(Component.text("► " + "Player " + player.getName() + " joined").color(ColorOptions.message));

		if (!knkPermissible.hasPermission(player, "knk.mode.owner")) {
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
		ScoreboardUtil.setScoreboard(Arrays.asList(player), knkPermissible, user);

		// Salary is paid by SalaryPayoutScheduler (its own join handler plus an hourly check).
		if (user != null) {
			// docs/specs/kits/DESIGN.md §4.4: unifies starter-kit granting into the general Kit
			// system - a brand-new account, not a real "if (user.isNewUser())" branch that
			// predates this (none existed here; UserAccountListener's welcome message doesn't
			// distinguish new/returning either - verified by reading both listeners directly).
			if (user.isNewUser()) {
				triggerBackgroundFirstJoinKits(player, user.id());
			}
		}
	}

	/**
	 * Grants every GrantOnFirstJoin kit the account is gated to receive (docs/specs/kits/
	 * DESIGN.md §4.4). Runs off the main thread and never blocks or delays the join; a failure
	 * here is logged and otherwise invisible to the player.
	 */
	private void triggerBackgroundFirstJoinKits(Player player, int userId) {
		if (kitsCommandApi == null) {
			return;
		}
		UUID uuid = player.getUniqueId();
		kitsCommandApi.grantFirstJoinKitsAsync(userId)
			.thenCompose(claimResults -> {
				if (claimResults == null || claimResults.isEmpty()) {
					return CompletableFuture.completedFuture(List.<KitGrantPlacer.ResolvedItem>of());
				}
				List<CompletableFuture<List<KitGrantPlacer.ResolvedItem>>> resolveFutures = claimResults.stream()
					.map(claimResult -> KitGrantPlacer.resolveAsync(claimResult, itemBlueprintsDataAccess, minecraftMaterialRefsDataAccess))
					.toList();
				return CompletableFuture.allOf(resolveFutures.toArray(new CompletableFuture[0]))
					.thenApply(unused -> resolveFutures.stream()
						.flatMap(f -> f.join().stream())
						.toList());
			})
			.thenAccept(resolvedItems -> {
				if (resolvedItems.isEmpty()) {
					return;
				}
				Bukkit.getScheduler().runTask(KnKPlugin.getPlugin(KnKPlugin.class), () -> {
					Player online = Bukkit.getPlayer(uuid);
					if (online == null) {
						return;
					}
					KitGrantPlacer.place(online, resolvedItems);
					online.sendMessage(Component.text("You received your starter kit!").color(ColorOptions.messageachievement));
				});
			})
			.exceptionally(ex -> {
				LOGGER.log(Level.WARNING, "Failed to grant first-join kits for user " + userId, ex);
				return null;
			});
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
		if (user == null || user.id() == null || usersCommandApi == null) {
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
			if (!knkPermissible.hasPermission(player, "knk.mode.owner")) {
				e.setCancelled(true);
			}
		}
	}

	@EventHandler(priority = EventPriority.NORMAL)
	public void onChat(AsyncChatEvent e) {
		Player player = e.getPlayer();
		UUID senderUuid = player.getUniqueId();

		// KNG-18 Phase 2: players ignoring the sender don't see the line (DESIGN.md §4 D6). The
		// ignore lists are concurrent snapshots - safe to read on this async chat thread.
		if (ignoreService != null) {
			e.viewers().removeIf(viewer -> viewer instanceof Player viewerPlayer && ignoreService.ignores(viewerPlayer.getUniqueId(), senderUuid));
		}

		String rawMessage = PlainTextComponentSerializer.plainText().serialize(e.message());
		String dn = player.getName();
		String capitalizedMessage = rawMessage.isEmpty() ? rawMessage : ("" + rawMessage.charAt(0)).toUpperCase() + rawMessage.substring(1);
		
		// Convert legacy color codes (&c, &4, etc.) to Adventure Component
		String legacyFormattedMessage = ChatColor.translateAlternateColorCodes('&', capitalizedMessage);
		Component messageComponent = LegacyComponentSerializer.legacySection().deserialize(legacyFormattedMessage);

		// Title, premium tier and tier colors come from the cached summary (KNG-7/KNG-8) — stale
		// is fine for display, and chat must not wait on an API call.
		UserSummary user = cacheManager.getUserCache().getStale(player.getUniqueId()).orElse(null);
		ChatLineFormat.Rank rank = knkPermissible.hasPermission(player, ModeService.OWNER_NODE) ? ChatLineFormat.Rank.OWNER
				: knkPermissible.hasPermission(player, ModeService.STAFF_NODE) ? ChatLineFormat.Rank.STAFF
				: ChatLineFormat.Rank.MEMBER;
		Component finalMessage = ChatLineFormat.render(rank, dn, user, messageComponent);
		e.renderer((source, sourceDisplayName, message, viewer) -> finalMessage);

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
					if (ignoreService != null && ignoreService.ignores(p.getUniqueId(), senderUuid)) {
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

	/**
	 * Forces respawn to the default town for regular (default-rank or premium-tier) players.
	 * Staff/owner-mode players are exempt, matching the same {@code knk.mode.*} gate the join
	 * handler above already uses for its gamemode/teleport reset — an elevated-rank player who
	 * dies should respawn normally (bed/anchor/world spawn), not get routed back to town.
	 *
	 * {@code PlayerRespawnEvent} requires the location to be set before the handler returns, so
	 * this blocks on the fetch rather than using {@code .thenAccept(...)} (the previous version's
	 * bug — its async callback always ran after Bukkit had already finished processing the
	 * respawn, so {@code setRespawnLocation} was never actually reachable). {@code CACHE_FIRST}
	 * means this only round-trips to the API on a cold cache, matching the blocking {@code .join()}
	 * already used for {@link #onValidateLogin}'s comparable bounded lookups.
	 */
	@EventHandler
	public void onPlayerRespawn(PlayerRespawnEvent e) {
		Player player = e.getPlayer();

		if (knkPermissible.hasPermission(player, ModeService.OWNER_NODE)
				|| knkPermissible.hasPermission(player, ModeService.STAFF_NODE)) {
			return;
		}

		FetchResult<TownDetail> result = townsDataAccess.getByIdAsync(DEFAULT_RESPAWN_TOWN_ID, FetchPolicy.CACHE_FIRST).join();
		if (!result.isSuccess()) {
			LOGGER.severe("Failed to load default town (id " + DEFAULT_RESPAWN_TOWN_ID + ") for respawn");
			return;
		}

		TownDetail.Location location = result.value().orElseThrow().location();
		if (location == null || location.world() == null || location.x() == null || location.y() == null || location.z() == null) {
			LOGGER.warning("Default town (id " + DEFAULT_RESPAWN_TOWN_ID + ") has no location configured; respawn not overridden");
			return;
		}

		World world = Bukkit.getWorld(location.world());
		if (world == null) {
			LOGGER.warning("Default town respawn world '" + location.world() + "' is not loaded; respawn not overridden");
			return;
		}

		float yaw = location.yaw() != null ? location.yaw() : 0f;
		float pitch = location.pitch() != null ? location.pitch() : 0f;
		e.setRespawnLocation(new Location(world, location.x(), location.y(), location.z(), yaw, pitch));
	}

	@EventHandler
	public void onItemPickup(PlayerPickupItemEvent e) {
		if (e.getPlayer().isOp()) {
			return;
		}
		e.setCancelled(true);
	}
}
