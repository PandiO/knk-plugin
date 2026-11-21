package net.knightsandkings.knk.paper.menu.opener;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.settings.KnkMenuItemSettings;
import net.knightsandkings.knk.core.menu.opener.MenuItemApiSettings;
import net.knightsandkings.knk.core.menu.opener.MenuItemSituations;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings;

/**
 * Where the menu item's settings come from (siege shop L6, PandiO/knk-workspace#871, DESIGN § 4.3.5, P26): the
 * {@code menus.menu-item} keys of {@code config.yml} from the start, replaced by the game settings' {@code menuItem}
 * section as soon as the API answers, and again by every later answer (the periodic re-read and the realtime
 * {@code GameSettingsChanged} event), without a restart. With the API down at startup nothing arrives and the file's
 * settings stay, exactly as before #871. Once the API has answered, an outage keeps the last API settings.
 * <p>
 * {@link #settings()} is read on every click; {@link #accept} runs on any thread and hands the online players to
 * {@link MenuOpenerService#applyLive} on the main thread when the settings changed.
 */
public final class MenuItemSettingsSync implements Supplier<MenuOpenerSettings> {

    private static final Logger LOGGER = Logger.getLogger(MenuItemSettingsSync.class.getName());

    private final MenuItemSituations situations;
    private final Consumer<Runnable> mainThread;
    private final Supplier<? extends Collection<? extends Player>> online;
    private volatile MenuOpenerSettings current;
    private volatile boolean fromApi;
    private volatile MenuOpenerService service;

    /**
     * @param fileSettings {@code menus.menu-item} from {@code config.yml}: the settings until the API answers
     * @param mainThread   runs a task on the main thread
     * @param online       the online players
     */
    public MenuItemSettingsSync(MenuOpenerSettings fileSettings, MenuItemSituations situations,
                                Consumer<Runnable> mainThread, Supplier<? extends Collection<? extends Player>> online) {
        this.current = Objects.requireNonNull(fileSettings, "fileSettings");
        this.situations = Objects.requireNonNull(situations, "situations");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.online = Objects.requireNonNull(online, "online");
    }

    /** The service whose players {@link #accept} updates. */
    public void attach(MenuOpenerService service) {
        this.service = service;
    }

    /** The settings in force. */
    public MenuOpenerSettings settings() {
        return current;
    }

    @Override
    public MenuOpenerSettings get() {
        return current;
    }

    /** Whether the settings came from the API (false: still {@code config.yml}). */
    public boolean fromApi() {
        return fromApi;
    }

    /** The API's {@code menuItem} section. Any thread; null is ignored. */
    public void accept(KnkMenuItemSettings api) {
        if (api == null) {
            return;
        }
        MenuOpenerSettings next = MenuItemApiSettings.toSettings(api, situations,
            warning -> LOGGER.warning("[MenuItem] " + warning));
        boolean first = !fromApi;
        if (next == null || (!first && next.equals(current))) {
            return;
        }
        fromApi = true;
        if (next.equals(current)) {
            LOGGER.info("[MenuItem] Settings from the game settings page (same as config.yml)");
            return;
        }
        current = next;
        LOGGER.info("[MenuItem] Settings from the game settings page applied: " + (next.enabled()
            ? next.material() + " in slot " + next.slot() + ", for " + next.audience() + " players, "
                + next.overrides().size() + " situation override(s)"
            : "off"));
        MenuOpenerService s = service;
        if (s != null) {
            mainThread.accept(() -> {
                for (Player player : online.get()) {
                    s.applyLive(player);
                }
            });
        }
    }
}
