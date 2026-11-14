package net.knightsandkings.knk.paper.scenario;

import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.paper.gates.GateDisplayManager;
import net.knightsandkings.knk.paper.gates.GateLoaderAdapter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

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
    Supplier<GateDisplayManager> gateDisplays
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

    private static <T> T required(T service, String what) {
        if (service == null) {
            throw new net.knightsandkings.knk.core.scenario.ScenarioAssertionError(what + " is not running");
        }
        return service;
    }
}
