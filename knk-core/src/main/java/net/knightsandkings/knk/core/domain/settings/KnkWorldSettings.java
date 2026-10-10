package net.knightsandkings.knk.core.domain.settings;

/**
 * One world's block on the Game Settings page (knk-web-api {@code WorldGameSettingsDto}). The API
 * adds a default block for every world the plugin reports, so each loaded world normally has one.
 *
 * @param worldName           Bukkit world name, matched case-insensitively
 * @param worldFolderName     informational
 * @param defaultGameMode     Bukkit {@code GameMode} name a regular player is put in on join; null or unknown
 *                            means SURVIVAL
 * @param lockTime            whether the day/night cycle is stopped at {@code lockedTime}
 * @param lockedTime          world time in ticks (0-23999) while locked
 * @param weather             never null
 * @param worldSpawnReference when set, the world's spawn point is moved here
 * @param respawnPolicy       never null
 */
public record KnkWorldSettings(String worldName, String worldFolderName, String defaultGameMode, boolean lockTime,
                               long lockedTime, KnkWeatherSettings weather, KnkSpawnReference worldSpawnReference,
                               KnkRespawnPolicy respawnPolicy) {

    public KnkWorldSettings {
        weather = weather != null ? weather : KnkWeatherSettings.normal();
        respawnPolicy = respawnPolicy != null ? respawnPolicy : KnkRespawnPolicy.worldSpawn();
        lockedTime = Math.floorMod(lockedTime, 24000L);
    }
}
