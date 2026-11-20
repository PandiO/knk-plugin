package net.knightsandkings.knk.core.production.terminal;

/**
 * {@code production.storage.*} (PandiO/knk-workspace#74): how often the bound containers are re-read (a binding made in
 * the web app shows after at most this long) and how often one display may refresh. Only with {@code production.enabled}.
 */
public record TerminalSettings(boolean enabled, int refreshMinutes, int displayRefreshSeconds) {

    public static final int DEFAULT_REFRESH_MINUTES = 5;
    public static final int DEFAULT_DISPLAY_REFRESH_SECONDS = 30;

    public TerminalSettings {
        refreshMinutes = Math.max(1, refreshMinutes);
        displayRefreshSeconds = Math.max(1, displayRefreshSeconds);
    }

    public static TerminalSettings defaults(boolean enabled) {
        return new TerminalSettings(enabled, DEFAULT_REFRESH_MINUTES, DEFAULT_DISPLAY_REFRESH_SECONDS);
    }
}
