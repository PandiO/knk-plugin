package net.knightsandkings.knk.api.mapper;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.knightsandkings.knk.api.dto.GameSettingsDto;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkGroupOverride;
import net.knightsandkings.knk.core.domain.settings.KnkHubRules;
import net.knightsandkings.knk.core.domain.settings.KnkHubSettings;
import net.knightsandkings.knk.core.domain.settings.KnkRespawnPolicy;
import net.knightsandkings.knk.core.domain.settings.KnkSpawnReference;
import net.knightsandkings.knk.core.domain.settings.KnkWeather;
import net.knightsandkings.knk.core.domain.settings.KnkWeatherSettings;
import net.knightsandkings.knk.core.domain.settings.KnkWorldRuntime;
import net.knightsandkings.knk.core.domain.settings.KnkWorldSettings;

/** knk-web-api Game Settings DTOs ↔ core records (docs/specs/game-settings/DESIGN.md). */
public final class GameSettingsMapper {

    /** The API's per-world defaults ({@code WorldGameSettingsDto}). */
    static final long DEFAULT_LOCKED_TIME = 18000L;

    private GameSettingsMapper() {
    }

    public static KnkGameSettings toCore(GameSettingsDto dto) {
        if (dto == null) {
            return null;
        }
        List<KnkWorldSettings> worlds = dto.worldSettings() == null ? List.of()
            : dto.worldSettings().stream().filter(Objects::nonNull).map(GameSettingsMapper::toCore).toList();
        List<KnkGroupOverride> groups = new java.util.ArrayList<>();
        if (dto.groupOverrides() != null) {
            int position = 0;
            for (GameSettingsDto.GroupOverride o : dto.groupOverrides()) {
                position++;
                if (o == null) {
                    continue;
                }
                groups.add(new KnkGroupOverride(o.permissionGroupId(), o.groupName(),
                    o.precedence() != null ? o.precedence() : position, o.joinAnnouncement(), o.leaveAnnouncement(),
                    toCore(o.joinSpawnReference()), toCore(o.respawnPolicy()), Boolean.TRUE.equals(o.joinAtLastLocation()),
                    KnkHubSettings.OutageAction.parse(o.outageAction()), o.outageDelaySeconds(),
                    Boolean.TRUE.equals(o.exemptInStaffMode()), Boolean.TRUE.equals(o.exemptInOwnerMode())));
            }
        }
        return new KnkGameSettings(dto.joinSpawnMode(), toCore(dto.joinSpawnReference()), dto.joinAnnouncement(),
            dto.leaveAnnouncement(), toCore(dto.defaultRespawnPolicy()), worlds, dto.updatedAt(),
            dto.motd() == null || dto.motd().isBlank() ? null : dto.motd(), groups, toCore(dto.hub()));
    }

    /** The hub block; {@link KnkHubSettings#none()} when the API sent none. */
    static KnkHubSettings toCore(GameSettingsDto.Hub hub) {
        if (hub == null) {
            return KnkHubSettings.none();
        }
        return new KnkHubSettings(hub.domainId(), hub.domainName(), hub.domainType(), hub.worldName(), hub.wgRegionId(),
            toCore(hub.spawnReference()), KnkHubSettings.OutageAction.parse(hub.outageAction()),
            hub.outageDelaySeconds() != null ? hub.outageDelaySeconds() : 0, toCore(hub.rules()), hub.portalRegionIds());
    }

    static KnkHubRules toCore(GameSettingsDto.HubRules rules) {
        KnkHubRules d = KnkHubRules.defaults();
        if (rules == null) {
            return d;
        }
        return new KnkHubRules(or(rules.damagePlayers(), d.damagePlayers()), or(rules.damageEntities(), d.damageEntities()),
            or(rules.takeDamage(), d.takeDamage()), or(rules.buildBreak(), d.buildBreak()), or(rules.dropPickup(), d.dropPickup()),
            or(rules.useDoors(), d.useDoors()), or(rules.useNpcs(), d.useNpcs()), or(rules.useStorage(), d.useStorage()),
            or(rules.useItemFramesPaintings(), d.useItemFramesPaintings()),
            rules.healAmount() != null ? rules.healAmount() : d.healAmount(),
            rules.healIntervalTicks() != null ? rules.healIntervalTicks() : d.healIntervalTicks(),
            rules.feedAmount() != null ? rules.feedAmount() : d.feedAmount(),
            rules.feedIntervalTicks() != null ? rules.feedIntervalTicks() : d.feedIntervalTicks(),
            rules.saturationAmount() != null ? rules.saturationAmount() : d.saturationAmount(),
            rules.saturationIntervalTicks() != null ? rules.saturationIntervalTicks() : d.saturationIntervalTicks());
    }

    private static boolean or(Boolean value, boolean fallback) {
        return value != null ? value : fallback;
    }

    public static GameSettingsDto.RuntimeWorld toDto(KnkWorldRuntime world) {
        return new GameSettingsDto.RuntimeWorld(world.worldName(), world.folderName(), world.environment(),
            world.loaded(), world.playerCount(), world.primary());
    }

    static KnkWorldSettings toCore(GameSettingsDto.WorldSettings dto) {
        return new KnkWorldSettings(dto.worldName(), dto.worldFolderName(), dto.defaultGameMode(),
            Boolean.TRUE.equals(dto.lockTime()), dto.lockedTime() != null ? dto.lockedTime() : DEFAULT_LOCKED_TIME,
            toCore(dto.weather()), toCore(dto.worldSpawnReference()), toCore(dto.respawnPolicy()));
    }

    static KnkWeatherSettings toCore(GameSettingsDto.WeatherSettings dto) {
        if (dto == null) {
            return null;
        }
        Set<KnkWeather> blocked = EnumSet.noneOf(KnkWeather.class);
        if (dto.blockedWeatherTypes() != null) {
            dto.blockedWeatherTypes().stream().map(KnkWeather::parse).filter(Objects::nonNull).forEach(blocked::add);
        }
        KnkWeatherSettings defaults = KnkWeatherSettings.normal();
        return new KnkWeatherSettings(KnkWeatherSettings.Mode.parse(dto.mode()), KnkWeather.parse(dto.forcedWeather()),
            blocked,
            dto.clearWeight() != null ? dto.clearWeight() : defaults.clearWeight(),
            dto.rainWeight() != null ? dto.rainWeight() : defaults.rainWeight(),
            dto.thunderWeight() != null ? dto.thunderWeight() : defaults.thunderWeight());
    }

    static KnkRespawnPolicy toCore(GameSettingsDto.RespawnPolicy dto) {
        if (dto == null) {
            return null;
        }
        return new KnkRespawnPolicy(KnkRespawnPolicy.Mode.parse(dto.mode()), toCore(dto.locationReference()),
            dto.maxNearestTownDistance(), !Boolean.FALSE.equals(dto.useWorldSpawnFallback()));
    }

    public static KnkSpawnReference toCore(GameSettingsDto.LocationReference reference) {
        if (reference == null) {
            return null;
        }
        GameSettingsDto.LocationSnapshot snapshot = reference.location();
        KnkLocation location = snapshot == null ? null : new KnkLocation(snapshot.locationId(), snapshot.name(),
            snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(), snapshot.pitch(), snapshot.world());
        return new KnkSpawnReference(KnkSpawnReference.SourceType.parse(reference.sourceType()),
            reference.sourceId() != null ? reference.sourceId() : 0, reference.displayLabel(), location);
    }
}
