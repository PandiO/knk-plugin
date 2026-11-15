package net.knightsandkings.knk.paper.scenario;

import net.knightsandkings.knk.core.domain.permissions.EffectivePermission;
import net.knightsandkings.knk.core.domain.permissions.EffectivePermissionSet;
import net.knightsandkings.knk.core.domain.permissions.PermissionHolder;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.scenario.ScenarioAssertionError;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A scripted player in a scenario (PandiO/knk-workspace#540): a fake player that joins the real server
 * (an NMS {@code ServerPlayer} on an in-memory connection, see {@link NmsFakePlayer}), so join and quit
 * events, permission attachments and the plugin's own join handling run as for a real player. Get one
 * with {@link ScenarioScene#player(String)}; it is configured before it joins and quits with the scene.
 * <p>
 * <b>Fake data.</b> {@link #account} puts a fake account (a negative user id) in the plugin's live user
 * cache, and {@link #grant}/{@link #deny} give that account an in-house permission set in the live
 * permission cache, as if knk-web-api had answered. The plugin's own permission checks
 * ({@code KnkPermissible}) then answer from that data: a command refuses a player without the node and
 * runs for one with it. Without an account a non-op player's in-house checks can't be made ("can't be
 * checked right now"), as on a server whose API is down. {@link #op} makes the player a server operator
 * (ops pass every in-house check); the scene takes it back.
 * <p>
 * <b>Actions</b> go the way a client's would: {@link #command} and {@link #chat} through
 * {@code Player#chat} (command preprocess event, dispatch with permission checks; the chat events and
 * renderers), {@link #click} as a container-click packet, {@link #move} as a movement packet,
 * {@link #teleport} through Bukkit's teleport (the teleport event fires). {@link #messages} holds every
 * chat line, system message, action bar and title the server sent the player.
 * <p>
 * Every method runs on the server thread (scenario steps do). The server must not be connected to a
 * reachable knk-web-api: the plugin's join handling would create real accounts for fake players, so
 * {@link #join} fails the step while the API is up.
 */
public final class ScenarioPlayer {

    /** What kind of message the player was sent. */
    public enum Kind { SYSTEM, CHAT, ACTION_BAR, TITLE, SUBTITLE, DISCONNECT }

    /** One message the player was sent. */
    public record Message(Kind kind, Component text) {
        /** The text without colours or formatting. */
        public String plain() {
            return PlainTextComponentSerializer.plainText().serialize(text);
        }

        /**
         * The colour the first piece of text containing {@code part} is shown in (its own or inherited), or null when
         * no piece contains it or it has no colour.
         */
        public TextColor colorOf(String part) {
            return colorOf(text, null, part);
        }

        private static TextColor colorOf(Component component, TextColor inherited, String part) {
            TextColor color = component.color() != null ? component.color() : inherited;
            if (component instanceof TextComponent piece && piece.content().contains(part)) {
                return color;
            }
            List<Component> parts = new ArrayList<>();
            if (component instanceof TranslatableComponent translatable) {
                // a chat type's decoration ("%s" or "<%s> %s") carries the line in its arguments
                translatable.arguments().forEach(argument -> parts.add(argument.asComponent()));
            }
            parts.addAll(component.children());
            for (Component child : parts) {
                TextColor found = colorOf(child, color, part);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return kind + ": " + plain();
        }
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final ScenarioScene scene;
    private final String name;
    private final UUID uuid;
    private final List<Message> messages = new CopyOnWriteArrayList<>();
    private final Map<String, Boolean> nodes = new LinkedHashMap<>();
    private UserSummary account;
    private boolean op;
    private boolean preLogin = true;

    private NmsFakePlayer handle;
    private BukkitTask poller;
    private boolean connecting;
    private boolean joinedBefore;
    private boolean madeOp;
    private boolean closed;
    private volatile String loginRefusal;

    ScenarioPlayer(ScenarioScene scene, String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("a player name is 3 to 16 letters, digits or _, was '" + name + "'");
        }
        this.scene = scene;
        this.name = name;
        this.uuid = uuidOf(name);
    }

    /** The UUID a server in offline mode gives this name (the fake player's UUID). */
    public static UUID uuidOf(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public String name() {
        return name;
    }

    public UUID uuid() {
        return uuid;
    }

    // ----- set-up (before the join) -----

    /**
     * A fake account with this (negative) user id and nothing else set: the plugin's user cache answers it for
     * this player while the scene is open. Use {@code scene.fakeId(n)}.
     */
    public ScenarioPlayer account(int userId) {
        return account(new UserSummary(userId, name, uuid, null, 0, 0, 0, false, false, null, null, null, null, 0,
            null, null, null, false, null, null, null, null, null, List.of()));
    }

    /** A fake account as given (its uuid must be {@link #uuid()}, its id negative). */
    public ScenarioPlayer account(UserSummary summary) {
        Objects.requireNonNull(summary, "summary");
        if (summary.id() == null || summary.id() >= 0 || !uuid.equals(summary.uuid())) {
            throw new IllegalArgumentException("a fake account needs a negative id and the player's uuid " + uuid);
        }
        this.account = summary;
        return this;
    }

    /** In-house grants for the fake account (needs {@link #account}); every other node is undeclared, so denied. */
    public ScenarioPlayer grant(String... grantedNodes) {
        for (String node : grantedNodes) {
            nodes.put(node, true);
        }
        return this;
    }

    /** In-house denies for the fake account (needs {@link #account}). */
    public ScenarioPlayer deny(String... deniedNodes) {
        for (String node : deniedNodes) {
            nodes.put(node, false);
        }
        return this;
    }

    /** Joins as a server operator (the scene removes the op again). */
    public ScenarioPlayer op() {
        this.op = true;
        return this;
    }

    /**
     * Skips {@code AsyncPlayerPreLoginEvent} (by default it fires before the join, off the server thread, as for
     * a real login: the plugin's admission gate and account lookup run there).
     */
    public ScenarioPlayer withoutPreLogin() {
        this.preLogin = false;
        return this;
    }

    /** The in-house permission set {@link #grant}/{@link #deny} describe, for the given account id. */
    static EffectivePermissionSet permissionSet(int userId, String name, Map<String, Boolean> nodes) {
        PermissionHolder self = new PermissionHolder(userId, "User", name);
        List<EffectivePermission> entries = new ArrayList<>();
        nodes.forEach((node, value) -> entries.add(new EffectivePermission(node, value, self, 0)));
        return new EffectivePermissionSet(userId, List.copyOf(entries));
    }

    // ----- joining and leaving -----

    /**
     * Starts the join: {@code AsyncPlayerPreLoginEvent} off the server thread (unless {@link #withoutPreLogin}),
     * then, if it allowed the login, the join on the server thread. Await {@link #online()} (or
     * {@link #loginRefusal()} for a refused login) in the next step.
     */
    public void join() {
        if (online() || connecting) {
            throw new ScenarioAssertionError(name + " is already online");
        }
        requireNoReachableApi();
        applyFakeData();
        if (!joinedBefore) {
            deletePlayerFiles(); // a fresh player: no saved position, "never played before"
        }
        loginRefusal = null;
        messages.clear();
        if (!preLogin) {
            connect();
            return;
        }
        connecting = true;
        var plugin = scene.services().plugin();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            @SuppressWarnings("deprecation") // the newest constructor needs a server-side login connection
            AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(name, loopback, loopback, uuid, false,
                Bukkit.createProfile(uuid, name));
            Bukkit.getPluginManager().callEvent(event);
            Bukkit.getScheduler().runTask(plugin, () -> {
                connecting = false;
                if (closed) {
                    return;
                }
                if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                    loginRefusal = PlainTextComponentSerializer.plainText().serialize(event.kickMessage());
                    return;
                }
                connect();
            });
        });
    }

    private void connect() {
        handle = NmsFakePlayer.connect(uuid, name, scene.world());
        joinedBefore = true;
        poller = Bukkit.getScheduler().runTaskTimer(scene.services().plugin(), this::poll, 1L, 1L);
    }

    /** Whether the player is on the server. */
    public boolean online() {
        return handle != null && handle.connected() && handle.bukkit().isOnline();
    }

    /** Whether the login was refused at pre-login; {@link #loginRefusal()} says why. */
    public boolean refused() {
        return loginRefusal != null;
    }

    /** The refusal screen's text when pre-login refused the player, else null. */
    public String loginRefusal() {
        return loginRefusal;
    }

    /** Leaves like a client that closes the game: {@code PlayerQuitEvent} fires and the player is saved, now. */
    public void quit() {
        if (handle == null) {
            return;
        }
        poll();
        if (poller != null) {
            poller.cancel();
            poller = null;
        }
        handle.disconnect();
        handle = null;
    }

    // ----- what the player does -----

    /** Runs a command as the player typed it (leading / optional): preprocess event, dispatch, permission checks. */
    public void command(String commandLine) {
        player().chat(commandLine.startsWith("/") ? commandLine : "/" + commandLine);
    }

    /** Says something in public chat through the server's chat path (chat events, renderers, viewers). */
    public void chat(String text) {
        if (text.startsWith("/")) {
            throw new IllegalArgumentException("use command(...) for commands");
        }
        player().chat(text);
    }

    /** Teleports the player to a scene position (cause PLUGIN; the teleport event fires). */
    public void teleport(double x, double y, double z) {
        teleport(scene.location(x, y, z));
    }

    /** Teleports the player (cause PLUGIN; the teleport event fires). */
    public void teleport(Location location) {
        player().teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    /**
     * Moves the player to a scene position with a movement packet, as walking does (the server's movement checks
     * and {@code PlayerMoveEvent}). Keep each step under a block; a fake player has no gravity of its own.
     */
    public void move(double x, double y, double z) {
        requireOnline().poll(this::record); // confirms a pending teleport first, as a client does
        Location to = scene.location(x, y, z);
        requireOnline().move(to.getX(), to.getY(), to.getZ(), true);
    }

    /** A left click on a slot of the open inventory ({@code rawSlot}: top inventory first, from 0). */
    public void click(int rawSlot) {
        requireOnline().click(rawSlot, 0, "PICKUP");
    }

    /** A right click on a slot of the open inventory. */
    public void rightClick(int rawSlot) {
        requireOnline().click(rawSlot, 1, "PICKUP");
    }

    /** A shift-click on a slot of the open inventory. */
    public void shiftClick(int rawSlot) {
        requireOnline().click(rawSlot, 0, "QUICK_MOVE");
    }

    // ----- what the player sees -----

    /** The live Bukkit player (fails the step when the player is not online). */
    public Player player() {
        return requireOnline().bukkit();
    }

    public Location location() {
        return player().getLocation();
    }

    public PlayerInventory inventory() {
        return player().getInventory();
    }

    /** The open inventory view (the player's own inventory when no other is open). */
    public InventoryView openInventory() {
        return player().getOpenInventory();
    }

    /** Every message the server sent the player since they joined, oldest first. */
    public List<Message> messages() {
        poll();
        return List.copyOf(messages);
    }

    /** The first message whose plain text contains {@code text}. */
    public Optional<Message> find(String text) {
        return find(message -> message.plain().contains(text));
    }

    public Optional<Message> find(Predicate<Message> test) {
        return messages().stream().filter(test).findFirst();
    }

    /** Whether any message's plain text contains {@code text}. */
    public boolean received(String text) {
        return find(text).isPresent();
    }

    /** Forgets the messages received so far (a later {@link #received} only sees new ones). */
    public void clearMessages() {
        poll();
        messages.clear();
    }

    /** For a failure reason: the last few messages. */
    public String recentMessages() {
        List<Message> all = messages();
        return all.subList(Math.max(0, all.size() - 6), all.size()).toString();
    }

    // ----- internals -----

    private void poll() {
        if (handle != null) {
            handle.poll(this::record);
        }
    }

    private void record(NmsFakePlayer.Received received) {
        messages.add(new Message(received.kind(), received.text()));
    }

    private NmsFakePlayer requireOnline() {
        if (!online()) {
            throw new ScenarioAssertionError(name + " is not online");
        }
        return handle;
    }

    private void requireNoReachableApi() {
        var connectivity = scene.services().apiConnectivity().get();
        if (connectivity != null && !connectivity.isApiDown()) {
            throw new ScenarioAssertionError("knk-web-api is reachable (connectivity " + connectivity.state()
                + "): fake players would get real accounts through the plugin's join handling; run player scenarios"
                + " on a server without the API, as CI does");
        }
    }

    private void applyFakeData() {
        if (account != null) {
            scene.services().users().put(account);
            scene.services().permissions().prime(permissionSet(account.id(), name, nodes));
        } else if (!nodes.isEmpty()) {
            throw new ScenarioAssertionError(name + ": grant/deny needs a fake account (account(scene.fakeId(n)))");
        }
        if (op && !madeOp) {
            Bukkit.getOfflinePlayer(uuid).setOp(true);
            madeOp = true;
        }
    }

    /** Called by the scene when it closes: leaves, and takes back every piece of fake data. */
    void close() {
        closed = true;
        try {
            if (handle != null) {
                // In the server log, so a failed scenario shows what the player saw.
                scene.services().plugin().getLogger().info("Scenario player " + name + " received: " + recentMessages());
            }
            quit();
        } finally {
            if (account != null) {
                scene.services().users().invalidate(uuid);
                scene.services().permissions().forget(account.id());
            }
            if (madeOp) {
                Bukkit.getOfflinePlayer(uuid).setOp(false);
                madeOp = false;
            }
            deletePlayerFiles();
        }
    }

    /** The server's saved data for this fake player (position, inventory, stats, advancements). */
    private void deletePlayerFiles() {
        Path players = Bukkit.getServer().getLevelDirectory().resolve("players");
        for (Path file : List.of(players.resolve("data").resolve(uuid + ".dat"),
                players.resolve("data").resolve(uuid + ".dat_old"),
                players.resolve("stats").resolve(uuid + ".json"),
                players.resolve("advancements").resolve(uuid + ".json"))) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                scene.services().plugin().getLogger().warning("Scenario player " + name + ": can't delete " + file + ": " + e);
            }
        }
    }
}
