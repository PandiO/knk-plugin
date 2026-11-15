package net.knightsandkings.knk.paper.scenario;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.connectivity.ApiConnectivity;
import net.knightsandkings.knk.core.dataaccess.MenuTemplatesDataAccess;
import net.knightsandkings.knk.core.dataaccess.PermissionsDataAccess;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.npc.nav.NpcMoverFactory;
import net.knightsandkings.knk.paper.navigation.NavigationService;
import net.knightsandkings.knk.paper.gates.GateDisplayManager;
import net.knightsandkings.knk.paper.gates.GateLoaderAdapter;
import net.knightsandkings.knk.paper.menu.MenuService;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.net.InetSocketAddress;
import java.util.function.Supplier;

/**
 * The live plugin services scenarios use, read lazily because some start after the command is
 * registered. A scenario drives the real wiring (the gate manager the animation tasks tick, the
 * listeners the server calls) and feeds it fake data instead of starting knk-web-api: see "Fake
 * data" in knk-workspace {@code docs/guides/developer/plugin-scenarios.md}.
 * Add a supplier here when a new scenario needs another service.
 */
public record ScenarioServices(
    Plugin plugin,
    Supplier<GateManager> gateManager,
    Supplier<GateLoaderAdapter> gateLoader,
    Supplier<GateDisplayManager> gateDisplays,
    Supplier<InetSocketAddress> regionHttpAddress,
    Supplier<NavigationService> roadNavigationService,
    Supplier<NpcMoverFactory> npcMoverFactory,
    Supplier<String> npcUnavailableReason,
    // Scripted players (PandiO/knk-workspace#540): the caches a fake player's fake account and grants go into, the
    // menu runtime a menu scenario opens, and the API state (no fake player joins while the API is reachable).
    Supplier<UserCache> userCache,
    Supplier<PermissionsDataAccess> permissionsDataAccess,
    Supplier<MenuService> menuService,
    Supplier<MenuTemplatesDataAccess> menuTemplatesDataAccess,
    Supplier<ApiConnectivity> apiConnectivity
) {

    public FileConfiguration config() {
        return plugin.getConfig();
    }

    /** The live gate manager; fails the step when gates did not start. */
    public GateManager gates() {
        return required(gateManager.get(), "the gate manager");
    }

    /** The loader that turns API gate DTOs into cached doors; fails the step when gates did not start. */
    public GateLoaderAdapter gateLoaderAdapter() {
        return required(gateLoader.get(), "the gate loader");
    }

    /** The gate hover displays; may be null. */
    public GateDisplayManager gateDisplayManager() {
        return gateDisplays.get();
    }

    /**
     * Where the plugin's region callback server listens (the one knk-web-api calls for {@code POST /Regions/rename});
     * fails the step when it did not start.
     */
    public InetSocketAddress regionHttp() {
        return required(regionHttpAddress.get(), "the region HTTP server");
    }

    /**
     * The live road navigation service (/navigate), whose walk capture prepares an NPC's local legs; fails the step
     * when road navigation did not start ({@code navigation.enabled}).
     */
    public NavigationService roadNavigation() {
        return required(roadNavigationService.get(), "road navigation (navigation.enabled)");
    }

    /**
     * The live NPC engine (Citizens) that spawns and moves NPCs (PandiO/knk-workspace#543); fails the step with the
     * reason {@code /knk npc status} gives when NPC navigation is off (no Citizens, {@code npc.navigation.enabled}).
     */
    public NpcMoverFactory npcMovers() {
        NpcMoverFactory factory = npcMoverFactory.get();
        if (factory == null) {
            String reason = npcUnavailableReason.get();
            throw new net.knightsandkings.knk.core.scenario.ScenarioAssertionError("NPC navigation is not running: "
                + (reason == null ? "it did not start" : reason));
        }
        return factory;
    }

    /** The live user cache (UUID to account summary); a fake player's fake account goes in here. */
    public UserCache users() {
        return required(userCache.get(), "the user cache");
    }

    /** The live in-house permission cache; a fake player's grants are primed in here. */
    public PermissionsDataAccess permissions() {
        return required(permissionsDataAccess.get(), "the permission cache");
    }

    /** The live InventoryMenu runtime (opens menus, handles their clicks). */
    public MenuService menus() {
        return required(menuService.get(), "the menu service");
    }

    /** The live menu template cache; a scenario's fake menu templates are primed in here. */
    public MenuTemplatesDataAccess menuTemplates() {
        return required(menuTemplatesDataAccess.get(), "the menu template cache");
    }

    private static <T> T required(T service, String what) {
        if (service == null) {
            throw new net.knightsandkings.knk.core.scenario.ScenarioAssertionError(what + " is not running");
        }
        return service;
    }
}
