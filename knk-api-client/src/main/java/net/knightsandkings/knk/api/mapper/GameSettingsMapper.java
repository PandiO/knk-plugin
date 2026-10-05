package net.knightsandkings.knk.api.mapper;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.knightsandkings.knk.api.dto.GameSettingsDto;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
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
        return new KnkGameSettings(dto.joinSpawnMode(), toCore(dto.joinSpawnReference()), dto.joinAnnouncement(),
            dto.leaveAnnouncement(), toCore(dto.defaultRespawnPolicy()), worlds, dto.updatedAt());
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
