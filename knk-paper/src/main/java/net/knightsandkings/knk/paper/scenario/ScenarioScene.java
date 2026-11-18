package net.knightsandkings.knk.paper.scenario;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The patch of world one scenario runs in (PandiO/knk-workspace#421): an empty box of
 * {@value #SIZE} × {@value #HEIGHT} × {@value #SIZE} blocks whose chunks stay loaded and ticking for
 * the run. {@link PaperScenarioHost} clears it before and after the scenario and removes every
 * entity in it, so a scenario only builds what it needs and cleans up only what lives outside the
 * box (plugin state such as cached gates or WorldGuard regions).
 * <p>
 * All coordinates a scenario passes are relative to the box's lowest corner: x and z from 0 to
 * {@value #SIZE} - 1, y from 0 to {@value #HEIGHT} - 1.
 * <p>
 * A scenario that needs a bigger box (the whole stair house of PandiO/knk-workspace#670) implements
 * {@link SizedScene}: its box is {@link Box#x()} × {@link Box#y()} × {@link Box#z()} blocks, at most
 * {@value ScenarioSettings#SPACING} along x so it never reaches the next scene.
 */
public final class ScenarioScene {

    /** Width and depth of the box, in blocks. */
    public static final int SIZE = 32;
    /** Height of the box, in blocks. */
    public static final int HEIGHT = 24;

    /**
     * A scene box's size in blocks (PandiO/knk-workspace#670).
     *
     * @param x width, along the row of scenes (1 to {@value ScenarioSettings#SPACING})
     * @param y height (1 to 384)
     * @param z depth (1 to 256)
     */
    public record Box(int x, int y, int z) {
        /** The box every scenario gets unless it asks for another ({@value #SIZE} × {@value #HEIGHT} × {@value #SIZE}). */
        public static final Box DEFAULT = new Box(SIZE, HEIGHT, SIZE);

        public Box {
            if (x < 1 || x > ScenarioSettings.SPACING || y < 1 || y > 384 || z < 1 || z > 256) {
                throw new IllegalArgumentException("a scene box is 1 to " + ScenarioSettings.SPACING
                    + " wide, 1 to 384 high and 1 to 256 deep, was " + x + " x " + y + " x " + z);
            }
        }
    }

    private final String scenarioName;
    private final int index;
    private final World world;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final ScenarioServices services;
    private final Box box;
    private final List<Entity> spawned = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final List<ScenarioPlayer> players = new ArrayList<>();

    public ScenarioScene(String scenarioName, int index, World world, int originX, int originY, int originZ,
                         ScenarioServices services) {
        this(scenarioName, index, world, originX, originY, originZ, services, Box.DEFAULT);
    }

    /** A scene with a box of its own size ({@link SizedScene}, PandiO/knk-workspace#670). */
    public ScenarioScene(String scenarioName, int index, World world, int originX, int originY, int originZ,
                         ScenarioServices services, Box box) {
        this.box = Objects.requireNonNull(box, "box");
        this.scenarioName = Objects.requireNonNull(scenarioName, "scenarioName");
        this.index = index;
        this.world = Objects.requireNonNull(world, "world");
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.services = Objects.requireNonNull(services, "services");
    }

    public String scenarioName() {
        return scenarioName;
    }

    public World world() {
        return world;
    }

    /** The box's size ({@link Box#DEFAULT} unless the scenario asked for another). */
    public Box box() {
        return box;
    }

    /** The plugin services a scenario may use (the live gate manager, the gate loader, ...). */
    public ScenarioServices services() {
        return services;
    }

    /** The block at a scene position. */
    public Block block(int x, int y, int z) {
        return world.getBlockAt(worldX(x), worldY(y), worldZ(z));
    }

    /** A location at a scene position (use 0.5 offsets for the centre of a block). */
    public Location location(double x, double y, double z) {
        return new Location(world, originX + x, originY + y, originZ + z);
    }

    public int worldX(int x) {
        return originX + x;
    }

    public int worldY(int y) {
        return originY + y;
    }

    public int worldZ(int z) {
        return originZ + z;
    }

    /** Spawns an entity at a scene position; it is removed with the scene. */
    public <T extends Entity> T spawn(double x, double y, double z, Class<T> type) {
        T entity = world.spawn(location(x, y, z), type);
        spawned.add(entity);
        return entity;
    }

    /**
     * Calls {@code handler} for every event of that type while the scene is open (at MONITOR
     * priority, cancelled events included, so it sees what the plugin's listeners decided). The
     * listener is unregistered with the scene.
     */
    public <E extends Event> void onEvent(Class<E> type, Consumer<E> handler) {
        Listener listener = new Listener() { };
        services.plugin().getServer().getPluginManager().registerEvent(type, listener, EventPriority.MONITOR, (ignored, event) -> {
            if (type.isInstance(event)) {
                handler.accept(type.cast(event));
            }
        }, services.plugin(), false);
        listeners.add(listener);
    }

    /**
     * A scripted player for this scene (PandiO/knk-workspace#540), not joined yet: configure it (fake account,
     * in-house grants, op), then call {@link ScenarioPlayer#join()} in a step. It quits, and its fake data is taken
     * back, when the scene closes. Names are 3 to 16 letters, digits or _; pick ones no real player has (the
     * shipped scenarios start theirs with "Scn"). Creating it does no work, so it may be called in
     * {@code define}.
     */
    public ScenarioPlayer player(String name) {
        for (ScenarioPlayer existing : players) {
            if (existing.name().equalsIgnoreCase(name)) {
                return existing;
            }
        }
        ScenarioPlayer player = new ScenarioPlayer(this, name);
        players.add(player);
        return player;
    }

    /** {@link #player(String)}, configured by {@code setup} (for example {@code p -> p.account(id).grant("knk.heal")}). */
    public ScenarioPlayer player(String name, Consumer<ScenarioPlayer> setup) {
        ScenarioPlayer player = player(name);
        setup.accept(player);
        return player;
    }

    /** Quits every scripted player and takes their fake data back (the host calls this first when closing). */
    void closePlayers() {
        RuntimeException first = null;
        for (ScenarioPlayer player : players) {
            try {
                player.close();
            } catch (RuntimeException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        players.clear();
        if (first != null) {
            throw first;
        }
    }

    /** Unregisters the listeners added through {@link #onEvent}. */
    void unregisterListeners() {
        listeners.forEach(HandlerList::unregisterAll);
        listeners.clear();
    }

    /**
     * A fake API id for this scene, negative so it never matches a row of a real database (a test
     * server may still be connected to the dev API, and the plugin writes some state back to it).
     * Unique per scenario run and {@code n} (0 to 999).
     */
    public int fakeId(int n) {
        if (n < 0 || n > 999) {
            throw new IllegalArgumentException("n must be 0 to 999, was " + n);
        }
        return -(421_000_000 + index * 1_000 + n);
    }

    /** A name for plugin state this scene creates, such as a WorldGuard region id. */
    public String uniqueName(String suffix) {
        return "knk_scenario_" + scenarioName.replace('-', '_') + "_" + index + "_" + suffix;
    }

    /** The entities spawned through {@link #spawn}. */
    List<Entity> spawned() {
        return spawned;
    }

    int originX() {
        return originX;
    }

    int originY() {
        return originY;
    }

    int originZ() {
        return originZ;
    }
}
