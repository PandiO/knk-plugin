package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.NavigationCompassSettingsDto;
import net.knightsandkings.knk.core.navigation.compass.CompassItemRule;
import net.knightsandkings.knk.core.navigation.compass.CompassItemRule.NameMatch;
import net.knightsandkings.knk.core.navigation.compass.CompassSettings;

/** {@link NavigationCompassSettingsDto} → {@link CompassSettings} (KNG-234). */
public final class NavigationCompassSettingsMapper {
    private NavigationCompassSettingsMapper() {
    }

    public static CompassSettings toCore(NavigationCompassSettingsDto dto) {
        if (dto == null) {
            return CompassSettings.defaults();
        }
        NameMatch match = NameMatch.parse(dto.nameMatch());
        CompassItemRule item = new CompassItemRule(dto.enabled(), dto.material(), dto.displayName(), match);
        return new CompassSettings(item, dto.siegeEnabled(), dto.generalEnabled(), CompassSettings.parseTargets(dto.generalTargets()));
    }
}
