package net.knightsandkings.knk.paper.bootstrap;

import org.bukkit.Server;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnchantmentBootstrapTest {

    @Test
    void initializeBuildsRuntimeAndRegistersListeners() {
        AtomicInteger listenerRegistrations = new AtomicInteger(0);

        PluginManager pluginManager = (PluginManager) Proxy.newProxyInstance(
                PluginManager.class.getClassLoader(),
                new Class[]{PluginManager.class},
                (proxy, method, args) -> {
                    if ("registerEvents".equals(method.getName()) && args != null && args.length == 2
                            && args[0] instanceof Listener) {
                        listenerRegistrations.incrementAndGet();
                    }
                    return defaultValue(method.getReturnType());
                }
        );

        Server server = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class[]{Server.class},
                (proxy, method, args) -> {
                    if ("getPluginManager".equals(method.getName())) {
                        return pluginManager;
                    }
                    if ("getPluginCommand".equals(method.getName())) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                }
        );

        Plugin plugin = (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class[]{Plugin.class},
                (proxy, method, args) -> {
                    if ("getServer".equals(method.getName())) {
                        return server;
                    }
                    if ("getLogger".equals(method.getName())) {
                        return Logger.getLogger("EnchantmentBootstrapTest");
                    }
                    if ("getConfig".equals(method.getName())) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                }
        );

        EnchantmentBootstrap bootstrap = new EnchantmentBootstrap(plugin);
        EnchantmentBootstrap.EnchantmentRuntime runtime = bootstrap.initialize();

        assertNotNull(runtime);
        assertNotNull(runtime.configManager());
        assertNotNull(runtime.enchantmentRepository());
        assertNotNull(runtime.cooldownManager());
        assertNotNull(runtime.commandHandler());
        // #169/#170: the effect displays exist and listen for death and quit.
        assertNotNull(runtime.effectBossBars());
        assertNotNull(runtime.nameTagBlackout());
        assertTrue(listenerRegistrations.get() >= 6);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0.0f;
        }
        if (returnType == double.class) {
            return 0.0d;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }
}
