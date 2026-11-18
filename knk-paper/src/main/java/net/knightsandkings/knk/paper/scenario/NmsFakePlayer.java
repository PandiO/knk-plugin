package net.knightsandkings.knk.paper.scenario;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * The only class that touches Minecraft server internals (NMS) for the scenarios' fake players
 * (PandiO/knk-workspace#540). Paper 26.3 has no fake-player API, so a fake player is built the way
 * the server builds a real one after login: an NMS {@code ServerPlayer} with a {@code Connection}
 * whose Netty channel is an in-memory {@link EmbeddedChannel}, joined through
 * {@code PlayerList.placeNewPlayer}. From there everything is the server's own code: the join event,
 * permission attachments, the plugin's join handling, command dispatch, chat, teleports, menus and the
 * quit. The connection is added to the server's connection list, so the server ticks it like a real
 * one (keep-alives, the player's base tick, a disconnect noticed on the next tick).
 * <p>
 * Everything here is reflection on Paper's Mojang-mapped server classes: knk-paper compiles against the
 * Paper API only, so nothing else in the plugin depends on the server's internals, and this class is
 * loaded only when a scenario creates a fake player (scenarios exist only with
 * {@code testing.scenarios.enabled}). Netty is a compile-only dependency (the server provides it).
 * <p>
 * <b>On a Paper update</b> a renamed class, field, method or constructor fails the first fake-player step
 * with "Paper's server internals changed: ..." naming the member (never earlier: a real server never loads
 * this class). How to find the new name: knk-workspace {@code docs/guides/developer/plugin-scenarios.md}
 * § 5.4.
 * <p>
 * All methods run on the server thread.
 */
final class NmsFakePlayer {

    /** What the player was sent, decoded from the server's packets. */
    record Received(ScenarioPlayer.Kind kind, Component text) {
    }

    private static Nms nms;

    private final Nms n;
    private final Object serverPlayer;
    private final Object connection;
    private final EmbeddedChannel channel;
    private final Capture capture;
    private final Player bukkit;
    private boolean disconnected;

    private NmsFakePlayer(Nms n, Object serverPlayer, Object connection, EmbeddedChannel channel, Capture capture,
                          Player bukkit) {
        this.n = n;
        this.serverPlayer = serverPlayer;
        this.connection = connection;
        this.channel = channel;
        this.capture = capture;
        this.bukkit = bukkit;
    }

    /**
     * Joins a new fake player through the server's player list (main thread): {@code PlayerJoinEvent} and the
     * plugin's join handling run before this returns. Where the player appears is the server's decision (their
     * saved position, else the world spawn) and then the plugin's.
     */
    static NmsFakePlayer connect(UUID id, String name, World world) {
        Nms n = nms();
        Object profile = n.newInstance(n.gameProfile, id, name);
        Object level = n.invoke(n.craftWorldGetHandle, world);
        Object serverPlayer = n.newInstance(n.serverPlayer, n.minecraftServer, level, profile, n.defaultClientInformation);
        // What the server's spawn preparation (PrepareSpawnTask, in the configuration phase a fake player skips) does:
        // the player's saved data (position, inventory, health, game mode) when the server has it, else the spawn.
        Optional<?> saved = (Optional<?>) n.invoke(n.loadPlayerData, n.playerList, n.newInstance(n.nameAndId, id, name));
        if (saved.isPresent()) {
            n.invoke(n.entityLoad, serverPlayer, n.invokeStatic(n.tagValueInput, n.discardingReporter, n.registryAccess, saved.get()));
        } else {
            Location spawn = world.getSpawnLocation();
            n.invoke(n.snapTo, serverPlayer, spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getYaw(), spawn.getPitch());
        }
        Object connection = n.newInstance(n.connection, n.serverbound);
        Capture capture = new Capture();
        // Registering the channel fires channelActive on the Connection, which takes the channel and its address
        // the way a real connection does; the capture handler sits in front of it and keeps every packet sent.
        EmbeddedChannel channel = new EmbeddedChannel(capture, (ChannelHandler) connection);
        n.set(n.connectionAddress, connection, new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565));
        List<Object> connections = n.connections();
        connections.add(connection);
        try {
            Object cookie = n.invokeStatic(n.cookieCreateInitial, profile, false);
            n.invoke(n.placeNewPlayer, n.playerList, connection, serverPlayer, cookie);
        } catch (RuntimeException e) {
            connections.remove(connection);
            channel.close();
            throw e;
        }
        Player bukkit = (Player) n.invoke(n.getBukkitEntity, serverPlayer);
        // A client says when it has loaded the world; until then the server treats the player as still loading
        // (immune to damage) for up to three seconds.
        n.invoke(n.handleAcceptPlayerLoad, n.get(n.serverPlayerConnection, serverPlayer), n.newInstance(n.playerLoaded));
        return new NmsFakePlayer(n, serverPlayer, connection, channel, capture, bukkit);
    }

    Player bukkit() {
        return bukkit;
    }

    /** Whether the server still has this connection open (false after {@link #disconnect} or a kick). */
    boolean connected() {
        return !disconnected && channel.isOpen();
    }

    /**
     * Reads the packets sent since the last call: answers keep-alives and teleport confirmations as a client
     * would (so movement packets are accepted) and hands every message to {@code sink}.
     */
    void poll(Consumer<Received> sink) {
        Object packet;
        while ((packet = capture.packets.poll()) != null) {
            handle(packet, sink);
        }
    }

    private void handle(Object packet, Consumer<Received> sink) {
        if (n.bundlePacket.isInstance(packet)) {
            for (Object sub : (Iterable<?>) n.invoke(n.bundleSubPackets, packet)) {
                handle(sub, sink);
            }
            return;
        }
        Class<?> type = packet.getClass();
        if (type == n.keepAliveOut) {
            if (connected()) {
                long keepAliveId = (long) n.invoke(n.keepAliveOutId, packet);
                n.invoke(n.handleKeepAlive, listener(), n.newInstance(n.keepAliveIn, keepAliveId));
            }
        } else if (type == n.positionOut) {
            if (connected()) {
                int teleportId = (int) n.invoke(n.positionOutId, packet);
                Location at = bukkit.getLocation();
                n.invoke(n.handleAcceptTeleport, listener(), n.newInstance(n.acceptTeleport, teleportId,
                    at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch()));
            }
        } else if (type == n.systemChat) {
            boolean overlay = (boolean) n.invoke(n.systemChatOverlay, packet);
            sink.accept(new Received(overlay ? ScenarioPlayer.Kind.ACTION_BAR : ScenarioPlayer.Kind.SYSTEM,
                n.adventure(n.invoke(n.systemChatContent, packet))));
        } else if (type == n.playerChat) {
            Object content = ((Optional<?>) n.invoke(n.playerChatUnsigned, packet)).map(Object.class::cast)
                .orElseGet(() -> n.invokeStatic(n.literal, n.invoke(n.packedContent, n.invoke(n.playerChatBody, packet))));
            Object line = n.invoke(n.boundDecorate, n.invoke(n.playerChatType, packet), content);
            sink.accept(new Received(ScenarioPlayer.Kind.CHAT, n.adventure(line)));
        } else if (type == n.disguisedChat) {
            Object line = n.invoke(n.boundDecorate, n.invoke(n.disguisedChatType, packet), n.invoke(n.disguisedChatMessage, packet));
            sink.accept(new Received(ScenarioPlayer.Kind.CHAT, n.adventure(line)));
        } else if (type == n.actionBar) {
            sink.accept(new Received(ScenarioPlayer.Kind.ACTION_BAR, n.adventure(n.invoke(n.actionBarText, packet))));
        } else if (type == n.title) {
            sink.accept(new Received(ScenarioPlayer.Kind.TITLE, n.adventure(n.invoke(n.titleText, packet))));
        } else if (type == n.subtitle) {
            sink.accept(new Received(ScenarioPlayer.Kind.SUBTITLE, n.adventure(n.invoke(n.subtitleText, packet))));
        } else if (type == n.disconnect) {
            sink.accept(new Received(ScenarioPlayer.Kind.DISCONNECT, n.adventure(n.invoke(n.disconnectReason, packet))));
        }
    }

    /**
     * A movement packet as the client sends one when the player walks: the server's movement checks run and
     * {@code PlayerMoveEvent} fires. Keep steps short (under a block per tick) or the server refuses them as
     * "moved too quickly". Call {@link #poll} first after a teleport, so it is confirmed.
     */
    void move(double x, double y, double z, boolean onGround) {
        n.invoke(n.handleMovePlayer, listener(), n.newInstance(n.movePos, x, y, z, onGround, false));
    }

    /**
     * A click in the open inventory as the client sends it ({@code ServerboundContainerClickPacket}): the
     * server's click handling runs, with {@code InventoryClickEvent}. {@code rawSlot} counts the top inventory
     * first, as Bukkit's raw slots do.
     *
     * @param input  {@code PICKUP} (a plain click) or {@code QUICK_MOVE} (shift-click)
     * @param button 0 left, 1 right
     */
    void click(int rawSlot, int button, String input) {
        Object menu = n.get(n.containerMenu, serverPlayer);
        int containerId = (int) n.get(n.containerId, menu);
        int stateId = (int) n.invoke(n.getStateId, menu);
        Object packet = n.newInstance(n.containerClick, containerId, stateId, (short) rawSlot, (byte) button,
            n.containerInput(input), n.newInstance(n.int2ObjectMap), n.emptyHashedStack);
        n.invoke(n.handleContainerClick, listener(), packet);
    }

    /**
     * Ends the connection like a client that closes the game: the channel closes and the server's
     * disconnect handling runs now (quit event, player saved, removed from the world).
     */
    void disconnect() {
        if (disconnected) {
            return;
        }
        disconnected = true;
        n.connections().remove(connection);
        channel.close();
        n.invoke(n.handleDisconnection, connection);
        channel.finishAndReleaseAll();
        capture.packets.clear();
    }

    private Object listener() {
        return n.get(n.serverPlayerConnection, serverPlayer);
    }

    private static synchronized Nms nms() {
        if (nms == null) {
            nms = new Nms();
        }
        return nms;
    }

    /** Keeps every packet the server sends this player; nothing goes further down the (empty) pipeline. */
    private static final class Capture extends ChannelOutboundHandlerAdapter {
        final Queue<Object> packets = new ConcurrentLinkedQueue<>();

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof io.netty.util.ReferenceCounted) {
                ReferenceCountUtil.release(msg); // raw buffers; a fake client reads only packet objects
            } else if (isPacket(msg)) {
                packets.add(msg);
            }
            promise.trySuccess();
        }

        /** Packets only: the pipeline also gets the server's configuration tasks, which need no real pipeline. */
        private static boolean isPacket(Object msg) {
            Nms resolved = nms;
            return resolved != null && resolved.packet.isInstance(msg);
        }
    }

    /**
     * Every server class, constructor, field and method the fake player uses, looked up once. A lookup that
     * fails names the member, so a Paper update that renamed it fails with that name.
     */
    private static final class Nms {
        final Class<?> packet = type("net.minecraft.network.protocol.Packet");
        final Object minecraftServer;
        final Object playerList;
        final Object serverbound;
        final Object defaultClientInformation;
        final Object emptyHashedStack;
        final Object discardingReporter;
        final Object registryAccess;

        final Method craftServerGetServer = method(Bukkit.getServer().getClass(), "getServer");
        final Method craftWorldGetHandle = method(type("org.bukkit.craftbukkit.CraftWorld"), "getHandle");
        final Method getPlayerList = method(type("net.minecraft.server.MinecraftServer"), "getPlayerList");
        final Method getConnection = method(type("net.minecraft.server.MinecraftServer"), "getConnection");
        final Method getConnections = method(type("net.minecraft.server.network.ServerConnectionListener"), "getConnections");

        final Constructor<?> gameProfile = constructor(type("com.mojang.authlib.GameProfile"), UUID.class, String.class);
        final Constructor<?> serverPlayer = constructor(type("net.minecraft.server.level.ServerPlayer"),
            type("net.minecraft.server.MinecraftServer"), type("net.minecraft.server.level.ServerLevel"),
            type("com.mojang.authlib.GameProfile"), type("net.minecraft.server.level.ClientInformation"));
        final Constructor<?> connection = constructor(type("net.minecraft.network.Connection"),
            type("net.minecraft.network.protocol.PacketFlow"));
        final Field connectionAddress = field(type("net.minecraft.network.Connection"), "address");
        final Method cookieCreateInitial = method(type("net.minecraft.server.network.CommonListenerCookie"), "createInitial",
            type("com.mojang.authlib.GameProfile"), boolean.class);
        final Method placeNewPlayer = method(type("net.minecraft.server.players.PlayerList"), "placeNewPlayer",
            type("net.minecraft.network.Connection"), type("net.minecraft.server.level.ServerPlayer"),
            type("net.minecraft.server.network.CommonListenerCookie"));
        final Method loadPlayerData = method(type("net.minecraft.server.players.PlayerList"), "loadPlayerData",
            type("net.minecraft.server.players.NameAndId"));
        final Constructor<?> nameAndId = constructor(type("net.minecraft.server.players.NameAndId"), UUID.class, String.class);
        final Method tagValueInput = method(type("net.minecraft.world.level.storage.TagValueInput"), "create",
            type("net.minecraft.util.ProblemReporter"), type("net.minecraft.core.HolderLookup$Provider"),
            type("net.minecraft.nbt.CompoundTag"));
        final Method entityLoad = method(type("net.minecraft.world.entity.Entity"), "load",
            type("net.minecraft.world.level.storage.ValueInput"));
        final Method snapTo = method(type("net.minecraft.world.entity.Entity"), "snapTo", double.class, double.class,
            double.class, float.class, float.class);
        final Method getBukkitEntity = method(type("net.minecraft.server.level.ServerPlayer"), "getBukkitEntity");
        final Field serverPlayerConnection = field(type("net.minecraft.server.level.ServerPlayer"), "connection");
        final Method handleDisconnection = method(type("net.minecraft.network.Connection"), "handleDisconnection");

        final Class<?> listenerType = type("net.minecraft.server.network.ServerGamePacketListenerImpl");
        final Class<?> keepAliveOut = type("net.minecraft.network.protocol.common.ClientboundKeepAlivePacket");
        final Method keepAliveOutId = method(keepAliveOut, "getId");
        final Class<?> keepAliveInType = type("net.minecraft.network.protocol.common.ServerboundKeepAlivePacket");
        final Constructor<?> keepAliveIn = constructor(keepAliveInType, long.class);
        final Method handleKeepAlive = method(listenerType, "handleKeepAlive", keepAliveInType);
        final Class<?> positionOut = type("net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket");
        final Method positionOutId = method(positionOut, "id");
        final Class<?> acceptTeleportType = type("net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket");
        final Constructor<?> acceptTeleport = constructor(acceptTeleportType, int.class, double.class, double.class,
            double.class, float.class, float.class);
        final Method handleAcceptTeleport = method(listenerType, "handleAcceptTeleportPacket", acceptTeleportType);
        final Class<?> movePosType = type("net.minecraft.network.protocol.game.ServerboundMovePlayerPacket$Pos");
        final Constructor<?> movePos = constructor(movePosType, double.class, double.class, double.class, boolean.class,
            boolean.class);
        final Method handleMovePlayer = method(listenerType, "handleMovePlayer",
            type("net.minecraft.network.protocol.game.ServerboundMovePlayerPacket"));

        final Class<?> playerLoadedType = type("net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket");
        final Constructor<?> playerLoaded = constructor(playerLoadedType);
        final Method handleAcceptPlayerLoad = method(listenerType, "handleAcceptPlayerLoad", playerLoadedType);

        final Class<?> containerInputType = type("net.minecraft.world.inventory.ContainerInput");
        final Class<?> containerClickType = type("net.minecraft.network.protocol.game.ServerboundContainerClickPacket");
        final Constructor<?> containerClick = constructor(containerClickType, int.class, int.class, short.class,
            byte.class, containerInputType, type("it.unimi.dsi.fastutil.ints.Int2ObjectMap"),
            type("net.minecraft.network.HashedStack"));
        final Constructor<?> int2ObjectMap = constructor(type("it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap"));
        final Method handleContainerClick = method(listenerType, "handleContainerClick", containerClickType);
        final Field containerMenu = field(type("net.minecraft.world.entity.player.Player"), "containerMenu");
        final Field containerId = field(type("net.minecraft.world.inventory.AbstractContainerMenu"), "containerId");
        final Method getStateId = method(type("net.minecraft.world.inventory.AbstractContainerMenu"), "getStateId");

        final Class<?> bundlePacket = type("net.minecraft.network.protocol.BundlePacket");
        final Method bundleSubPackets = method(bundlePacket, "subPackets");
        final Class<?> systemChat = type("net.minecraft.network.protocol.game.ClientboundSystemChatPacket");
        final Method systemChatContent = method(systemChat, "content");
        final Method systemChatOverlay = method(systemChat, "overlay");
        final Class<?> playerChat = type("net.minecraft.network.protocol.game.ClientboundPlayerChatPacket");
        final Method playerChatUnsigned = method(playerChat, "unsignedContent");
        final Method playerChatBody = method(playerChat, "body");
        final Method playerChatType = method(playerChat, "chatType");
        final Method packedContent = method(type("net.minecraft.network.chat.SignedMessageBody$Packed"), "content");
        final Class<?> disguisedChat = type("net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket");
        final Method disguisedChatMessage = method(disguisedChat, "message");
        final Method disguisedChatType = method(disguisedChat, "chatType");
        final Method boundDecorate = method(type("net.minecraft.network.chat.ChatType$Bound"), "decorate",
            type("net.minecraft.network.chat.Component"));
        final Method literal = method(type("net.minecraft.network.chat.Component"), "literal", String.class);
        final Class<?> actionBar = type("net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket");
        final Method actionBarText = method(actionBar, "text");
        final Class<?> title = type("net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket");
        final Method titleText = method(title, "text");
        final Class<?> subtitle = type("net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket");
        final Method subtitleText = method(subtitle, "text");
        final Class<?> disconnect = type("net.minecraft.network.protocol.common.ClientboundDisconnectPacket");
        final Method disconnectReason = method(disconnect, "reason");
        final Method asAdventure = method(type("io.papermc.paper.adventure.PaperAdventure"), "asAdventure",
            type("net.minecraft.network.chat.Component"));

        Nms() {
            minecraftServer = invoke(craftServerGetServer, Bukkit.getServer());
            playerList = invoke(getPlayerList, minecraftServer);
            serverbound = enumConstant(type("net.minecraft.network.protocol.PacketFlow"), "SERVERBOUND");
            defaultClientInformation = invokeStatic(method(type("net.minecraft.server.level.ClientInformation"), "createDefault"));
            emptyHashedStack = get(field(type("net.minecraft.network.HashedStack"), "EMPTY"), null);
            discardingReporter = get(field(type("net.minecraft.util.ProblemReporter"), "DISCARDING"), null);
            registryAccess = invoke(method(type("net.minecraft.server.MinecraftServer"), "registryAccess"), minecraftServer);
        }

        @SuppressWarnings("unchecked")
        List<Object> connections() {
            return (List<Object>) invoke(getConnections, invoke(getConnection, minecraftServer));
        }

        Object containerInput(String name) {
            return enumConstant(containerInputType, name);
        }

        Component adventure(Object nmsComponent) {
            return nmsComponent == null ? Component.empty() : (Component) invokeStatic(asAdventure, nmsComponent);
        }

        // ----- reflection with readable failures -----

        private static Class<?> type(String name) {
            try {
                return Class.forName(name, false, Bukkit.getServer().getClass().getClassLoader());
            } catch (ClassNotFoundException e) {
                throw changed("class " + name + " not found", e);
            }
        }

        private static Method method(Class<?> owner, String name, Class<?>... parameters) {
            try {
                Method method = owner.getMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException e) {
                throw changed("method " + owner.getName() + "." + name + signature(parameters) + " not found", e);
            }
        }

        private static Constructor<?> constructor(Class<?> owner, Class<?>... parameters) {
            try {
                Constructor<?> constructor = owner.getDeclaredConstructor(parameters);
                constructor.setAccessible(true);
                return constructor;
            } catch (NoSuchMethodException e) {
                throw changed("constructor " + owner.getName() + signature(parameters) + " not found", e);
            }
        }

        private static Field field(Class<?> owner, String name) {
            for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                    // look in the superclass
                }
            }
            throw changed("field " + owner.getName() + "." + name + " not found", null);
        }

        private static Object enumConstant(Class<?> type, String name) {
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(name)) {
                    return constant;
                }
            }
            throw changed("enum constant " + type.getName() + "." + name + " not found", null);
        }

        Object newInstance(Constructor<?> constructor, Object... args) {
            try {
                return constructor.newInstance(args);
            } catch (InvocationTargetException e) {
                throw rethrow(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw changed("can't call " + constructor, e);
            }
        }

        Object invoke(Method method, Object target, Object... args) {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw rethrow(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw changed("can't call " + method, e);
            }
        }

        Object invokeStatic(Method method, Object... args) {
            return invoke(method, null, args);
        }

        Object get(Field field, Object target) {
            try {
                return field.get(target);
            } catch (IllegalAccessException e) {
                throw changed("can't read " + field, e);
            }
        }

        void set(Field field, Object target, Object value) {
            try {
                field.set(target, value);
            } catch (IllegalAccessException e) {
                throw changed("can't write " + field, e);
            }
        }

        private static RuntimeException rethrow(Throwable cause) {
            if (cause instanceof RuntimeException runtime) {
                return runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            return new IllegalStateException(cause);
        }

        private static String signature(Class<?>[] parameters) {
            StringBuilder text = new StringBuilder("(");
            for (int i = 0; i < parameters.length; i++) {
                text.append(i == 0 ? "" : ", ").append(parameters[i].getSimpleName());
            }
            return text.append(')').toString();
        }

        private static IllegalStateException changed(String what, Throwable cause) {
            return new IllegalStateException("Paper's server internals changed: " + what
                + " (fake players, scenario/NmsFakePlayer; see plugin-scenarios.md § 5.4)", cause);
        }
    }
}
