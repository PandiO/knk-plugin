package net.knightsandkings.knk.paper.scenario;

import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioHost;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gives each scenario its own empty box in the scenario world (PandiO/knk-workspace#421). Scene
 * {@code i} of a run sits at {@code origin + i × }{@value ScenarioSettings#SPACING}{@code  blocks} along
 * x. Opening a scene force-loads its chunks (so blocks and entities tick without a player nearby),
 * clears the box to air and removes the entities in it; closing it does the same and releases the
 * chunks it force-loaded.
 */
public final class PaperScenarioHost implements ScenarioHost<ScenarioScene> {

    private final ScenarioSettings settings;
    private final ScenarioServices services;
    private final Map<ScenarioScene, List<long[]>> forcedChunks = new IdentityHashMap<>();

    public PaperScenarioHost(ScenarioSettings settings, ScenarioServices services) {
        this.settings = settings;
        this.services = services;
    }

    @Override
    public ScenarioScene open(Scenario<ScenarioScene> scenario, int index) {
        World world = resolveWorld();
        int x = settings.originX() + index * ScenarioSettings.SPACING;
        int y = settings.originY();
        int z = settings.originZ();
        if (y < world.getMinHeight() || y + ScenarioScene.HEIGHT > world.getMaxHeight()) {
            throw new IllegalStateException("testing.scenarios.origin.y " + y + " puts the scene outside world '"
                + world.getName() + "' (" + world.getMinHeight() + " to " + world.getMaxHeight() + ")");
        }
        ScenarioScene scene = new ScenarioScene(scenario.name(), index, world, x, y, z, services);
        List<long[]> forced = new ArrayList<>();
        for (int cx = x >> 4; cx <= (x + ScenarioScene.SIZE - 1) >> 4; cx++) {
            for (int cz = z >> 4; cz <= (z + ScenarioScene.SIZE - 1) >> 4; cz++) {
                if (!world.isChunkForceLoaded(cx, cz)) {
                    world.setChunkForceLoaded(cx, cz, true);
                    forced.add(new long[] {cx, cz});
                }
                world.getChunkAt(cx, cz); // loads it now
            }
        }
        forcedChunks.put(scene, forced);
        reset(scene);
        return scene;
    }

    @Override
    public void close(ScenarioScene scene) {
        try {
            try {
                scene.closePlayers();
            } finally {
                scene.unregisterListeners();
            }
            for (Entity entity : scene.spawned()) {
                if (entity.isValid()) {
                    entity.remove();
                }
            }
            reset(scene);
        } finally {
            List<long[]> forced = forcedChunks.remove(scene);
            if (forced != null) {
                for (long[] chunk : forced) {
                    scene.world().setChunkForceLoaded((int) chunk[0], (int) chunk[1], false);
                }
            }
        }
    }

    /** Clears the box to air (no physics) and removes every entity in it except players. */
    private static void reset(ScenarioScene scene) {
        World world = scene.world();
        BoundingBox box = new BoundingBox(scene.originX(), scene.originY(), scene.originZ(),
            scene.originX() + ScenarioScene.SIZE, scene.originY() + ScenarioScene.HEIGHT, scene.originZ() + ScenarioScene.SIZE);
        for (Entity entity : world.getNearbyEntities(box)) {
            if (!(entity instanceof Player)) {
                entity.remove();
            }
        }
        for (int dx = 0; dx < ScenarioScene.SIZE; dx++) {
            for (int dy = ScenarioScene.HEIGHT - 1; dy >= 0; dy--) {
                for (int dz = 0; dz < ScenarioScene.SIZE; dz++) {
                    Block block = scene.block(dx, dy, dz);
                    if (block.getType() != Material.AIR) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    private World resolveWorld() {
        String name = settings.world();
        if (name == null || name.isBlank()) {
            List<World> worlds = Bukkit.getWorlds();
            if (worlds.isEmpty()) {
                throw new IllegalStateException("no world is loaded");
            }
            return worlds.get(0);
        }
        World world = Bukkit.getWorld(name);
        if (world == null) {
            throw new IllegalStateException("testing.scenarios.world '" + name + "' is not loaded");
        }
        return world;
    }
}
