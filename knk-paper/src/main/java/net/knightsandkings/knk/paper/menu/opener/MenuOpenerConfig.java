package net.knightsandkings.knk.paper.menu.opener;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;

import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings.Audience;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings.Click;

/**
 * Reads {@code menus.menu-item} (PandiO/knk-workspace#160). A missing section (an older {@code config.yml}) gives the
 * defaults; an unknown value is logged and its default used, so a typo never switches the item off silently.
 */
public final class MenuOpenerConfig {

    private MenuOpenerConfig() {
    }

    public static MenuOpenerSettings read(ConfigurationSection section, Logger logger) {
        MenuOpenerSettings d = MenuOpenerSettings.defaults();
        if (section == null) {
            return d;
        }
        Audience audience = Audience.parse(section.getString("give-to", "all"));
        if (audience == null) {
            logger.warning("menus.menu-item.give-to '" + section.getString("give-to") + "' is not all or bedrock; using all");
            audience = Audience.ALL;
        }
        Set<Click> openOn = d.openOn();
        if (section.isList("open-on")) {
            openOn = EnumSet.noneOf(Click.class);
            for (String text : section.getStringList("open-on")) {
                Click click = Click.parse(text);
                if (click == null) {
                    logger.warning("menus.menu-item.open-on: unknown click '" + text + "' (RIGHT_CLICK, LEFT_CLICK, INVENTORY_CLICK)");
                } else {
                    openOn.add(click);
                }
            }
        }
        int slot = section.getInt("slot", d.slot());
        if (slot < 0 || slot > MenuOpenerSettings.MAX_SLOT) {
            logger.warning("menus.menu-item.slot " + slot + " is not 0-35; using " + d.slot());
        }
        List<String> lore = section.isList("lore") ? section.getStringList("lore") : d.lore();
        return new MenuOpenerSettings(
            section.getBoolean("enabled", d.enabled()),
            audience,
            section.getBoolean("give-on-join", d.giveOnJoin()),
            section.getBoolean("keep-on-death", d.keepOnDeath()),
            section.getString("material", d.material()),
            section.getString("name", d.name()),
            lore,
            slot,
            openOn,
            section.getBoolean("droppable", d.droppable()),
            section.getBoolean("movable", d.movable()));
    }
}
