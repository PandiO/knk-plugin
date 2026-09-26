package net.knightsandkings.knk.core.siege.menu;

/** Siege Phase 8b (DESIGN §10.3): server-wide siege facts - root {@code siegeServer} (C.1, C.2 header). */
public final class SiegeServerMenuView {

    private final int lobbyCount;
    private final int playingCount;
    private final String ownLobbyName;

    /** @param ownLobbyName the lobby the viewer is in, or null */
    public SiegeServerMenuView(int lobbyCount, int playingCount, String ownLobbyName) {
        this.lobbyCount = lobbyCount;
        this.playingCount = playingCount;
        this.ownLobbyName = ownLobbyName;
    }

    public int getLobbyCount() {
        return lobbyCount;
    }

    public int getPlayingCount() {
        return playingCount;
    }

    public boolean getHasLobbies() {
        return lobbyCount > 0;
    }

    /** "&aYou are in <lobby> - click to open it", or null (line omitted). */
    public String getEntryHintLine() {
        return ownLobbyName == null ? null : "&aYou are in " + ownLobbyName + " - click to open it";
    }
}
