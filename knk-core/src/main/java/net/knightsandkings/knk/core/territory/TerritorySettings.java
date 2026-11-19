package net.knightsandkings.knk.core.territory;

import java.util.Map;

/**
 * Plugin settings of territory (PandiO/knk-workspace#689), read from {@code production.*} in {@code config.yml}. Nothing
 * of territory runs unless {@code production.enabled} is true (A21, G16).
 *
 * @param enabled           {@code production.enabled} (default false)
 * @param refreshMinutes    {@code production.territory.refresh-minutes}: how often the inputs and region shapes are
 *                          checked again besides the {@code TerritoryChanged} event (0 = only on events and commands)
 * @param notifyPlayers     {@code production.territory.notify-players}: the action bar line on entry and exit
 * @param report            {@code production.territory.report}: send the conflict report (also needs the API's flag)
 * @param mapOutlines       {@code production.territory.map-outlines}: send outlines for the overview map
 * @param maxChunksPerWorld {@code production.territory.max-chunks-per-world}
 * @param messages          the entry/exit message templates ({@code production.territory.messages.*})
 */
public record TerritorySettings(boolean enabled, int refreshMinutes, boolean notifyPlayers, boolean report,
                                boolean mapOutlines, int maxChunksPerWorld, Map<String, String> messages) {

    public static final int DEFAULT_REFRESH_MINUTES = 10;

    /** Message keys and their defaults ({@code &} colour codes; {@code {name}}, {@code {holders}}). */
    public static final Map<String, String> DEFAULT_MESSAGES = Map.of(
            "enter-town", "&7Entering the territory of &f{name}",
            "enter-crown-land", "&7Entering the crown land of &f{name}",
            "enter-contested", "&6Entering contested land: &f{holders}",
            "enter-wilderness", "&7Leaving &f{name}&7: wilderness");

    public TerritorySettings {
        refreshMinutes = Math.max(0, refreshMinutes);
        maxChunksPerWorld = maxChunksPerWorld <= 0 ? TerritoryCacheBuilder.DEFAULT_MAX_CHUNKS_PER_WORLD : maxChunksPerWorld;
        java.util.HashMap<String, String> merged = new java.util.HashMap<>(DEFAULT_MESSAGES);
        if (messages != null) {
            messages.forEach((key, value) -> {
                if (key != null && value != null && DEFAULT_MESSAGES.containsKey(key)) {
                    merged.put(key, value);
                }
            });
        }
        messages = Map.copyOf(merged);
    }

    /** Everything off: what a server without the {@code production} block gets. */
    public static TerritorySettings disabled() {
        return new TerritorySettings(false, DEFAULT_REFRESH_MINUTES, true, true, false,
                TerritoryCacheBuilder.DEFAULT_MAX_CHUNKS_PER_WORLD, Map.of());
    }

    /** The line a player sees for a change (with {@code &} codes), or null when there is nothing to say. */
    public String messageFor(TerritoryTracker.Change change) {
        TerritoryVerdict to = change.to();
        TerritoryCache cache = change.cache();
        if (to.isContested()) {
            StringBuilder holders = new StringBuilder();
            for (TerritoryHolder holder : to.contesting()) {
                if (!holders.isEmpty()) {
                    holders.append(" / ");
                }
                holders.append(cache.inputs().nameOf(holder));
            }
            return messages.get("enter-contested").replace("{holders}", holders);
        }
        if (to.owner() != null) {
            String key = to.owner().kind() == TerritoryHolder.Kind.KINGDOM ? "enter-crown-land" : "enter-town";
            return messages.get(key).replace("{name}", cache.inputs().nameOf(to.owner()));
        }
        TerritoryVerdict from = change.from();
        String left = from.owner() != null ? cache.inputs().nameOf(from.owner()) : "contested land";
        return messages.get("enter-wilderness").replace("{name}", left);
    }
}
