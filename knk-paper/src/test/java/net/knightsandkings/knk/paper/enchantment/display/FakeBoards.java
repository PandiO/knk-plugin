package net.knightsandkings.knk.paper.enchantment.display;

import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Small in-memory scoreboards for the name-tag mirror tests: the parts of the API the mirror uses, Bukkit's rules. */
final class FakeBoards {

    private FakeBoards() {
    }

    static Scoreboard board() {
        return new BoardState().proxy;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(FakeBoards.class.getClassLoader(), new Class[]{type}, handler);
    }

    private static final class BoardState {
        final Map<String, TeamState> teams = new LinkedHashMap<>();
        final Map<String, ObjectiveState> objectives = new LinkedHashMap<>();
        final Map<DisplaySlot, ObjectiveState> slots = new EnumMap<>(DisplaySlot.class);
        final Scoreboard proxy;

        BoardState() {
            proxy = FakeBoards.proxy(Scoreboard.class, (p, m, a) -> switch (m.getName()) {
                case "getTeams" -> Set.copyOf(teams.values().stream().map(t -> t.proxy).toList());
                case "getTeam" -> teams.containsKey((String) a[0]) ? teams.get((String) a[0]).proxy : null;
                case "registerNewTeam" -> {
                    if (teams.containsKey((String) a[0])) throw new IllegalArgumentException("team exists");
                    TeamState t = new TeamState(this, (String) a[0]);
                    teams.put(t.name, t);
                    yield t.proxy;
                }
                case "getEntryTeam" -> teams.values().stream().filter(t -> t.entries.contains((String) a[0]))
                        .map(t -> t.proxy).findFirst().orElse(null);
                case "getObjective" -> a[0] instanceof DisplaySlot slot
                        ? (slots.containsKey(slot) ? slots.get(slot).proxy : null)
                        : (objectives.containsKey((String) a[0]) ? objectives.get((String) a[0]).proxy : null);
                case "getObjectives" -> Set.copyOf(objectives.values().stream().map(o -> o.proxy).toList());
                case "registerNewObjective" -> {
                    if (objectives.containsKey((String) a[0])) throw new IllegalArgumentException("objective exists");
                    ObjectiveState o = new ObjectiveState(this, (String) a[0], (Criteria) a[1],
                            (net.kyori.adventure.text.Component) a[2], a.length > 3 ? (RenderType) a[3] : RenderType.INTEGER);
                    objectives.put(o.name, o);
                    yield o.proxy;
                }
                case "clearSlot" -> {
                    slots.remove((DisplaySlot) a[0]);
                    yield null;
                }
                case "getEntries" -> {
                    Set<String> all = new HashSet<>();
                    objectives.values().forEach(o -> all.addAll(o.scores.keySet()));
                    yield all;
                }
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p == a[0];
                case "toString" -> "FakeBoard";
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    private static final class TeamState {
        final BoardState board;
        final String name;
        final Set<String> entries = new LinkedHashSet<>();
        final Map<Team.Option, Team.OptionStatus> options = new HashMap<>();
        net.kyori.adventure.text.Component displayName = net.kyori.adventure.text.Component.empty();
        net.kyori.adventure.text.Component prefix = net.kyori.adventure.text.Component.empty();
        net.kyori.adventure.text.Component suffix = net.kyori.adventure.text.Component.empty();
        net.kyori.adventure.text.format.NamedTextColor color;
        boolean friendlyFire = true;
        boolean seeInvisibles = false;
        final Team proxy;

        TeamState(BoardState board, String name) {
            this.board = board;
            this.name = name;
            proxy = FakeBoards.proxy(Team.class, (p, m, a) -> switch (m.getName()) {
                case "getName" -> name;
                case "getEntries" -> Set.copyOf(entries);
                case "hasEntry" -> entries.contains((String) a[0]);
                case "addEntry" -> {
                    board.teams.values().forEach(t -> t.entries.remove((String) a[0]));
                    entries.add((String) a[0]);
                    yield null;
                }
                case "removeEntry" -> entries.remove((String) a[0]);
                case "unregister" -> {
                    board.teams.remove(name);
                    yield null;
                }
                case "displayName" -> a == null ? displayName : set(() -> displayName = (net.kyori.adventure.text.Component) a[0]);
                case "prefix" -> a == null ? prefix : set(() -> prefix = (net.kyori.adventure.text.Component) a[0]);
                case "suffix" -> a == null ? suffix : set(() -> suffix = (net.kyori.adventure.text.Component) a[0]);
                case "hasColor" -> color != null;
                case "color" -> a == null ? color : set(() -> color = (net.kyori.adventure.text.format.NamedTextColor) a[0]);
                case "allowFriendlyFire" -> friendlyFire;
                case "setAllowFriendlyFire" -> set(() -> friendlyFire = (Boolean) a[0]);
                case "canSeeFriendlyInvisibles" -> seeInvisibles;
                case "setCanSeeFriendlyInvisibles" -> set(() -> seeInvisibles = (Boolean) a[0]);
                case "getOption" -> options.getOrDefault((Team.Option) a[0], Team.OptionStatus.ALWAYS);
                case "setOption" -> set(() -> options.put((Team.Option) a[0], (Team.OptionStatus) a[1]));
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p == a[0];
                case "toString" -> "FakeTeam " + name;
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    private static final class ObjectiveState {
        final BoardState board;
        final String name;
        final Criteria criteria;
        net.kyori.adventure.text.Component displayName;
        RenderType renderType;
        io.papermc.paper.scoreboard.numbers.NumberFormat numberFormat;
        final Map<String, ScoreState> scores = new LinkedHashMap<>();
        final Objective proxy;

        ObjectiveState(BoardState board, String name, Criteria criteria, net.kyori.adventure.text.Component displayName,
                       RenderType renderType) {
            this.board = board;
            this.name = name;
            this.criteria = criteria;
            this.displayName = displayName;
            this.renderType = renderType;
            proxy = FakeBoards.proxy(Objective.class, (p, m, a) -> switch (m.getName()) {
                case "getName" -> name;
                case "getTrackedCriteria" -> criteria;
                case "displayName" -> a == null ? this.displayName : set(() -> this.displayName = (net.kyori.adventure.text.Component) a[0]);
                case "getRenderType" -> this.renderType;
                case "setRenderType" -> set(() -> this.renderType = (RenderType) a[0]);
                case "numberFormat" -> a == null ? numberFormat : set(() -> numberFormat = (io.papermc.paper.scoreboard.numbers.NumberFormat) a[0]);
                case "getDisplaySlot" -> board.slots.entrySet().stream().filter(e -> e.getValue() == this)
                        .map(Map.Entry::getKey).findFirst().orElse(null);
                case "setDisplaySlot" -> {
                    board.slots.values().removeIf(o -> o == this);
                    board.slots.put((DisplaySlot) a[0], this);
                    yield null;
                }
                case "isModifiable" -> true;
                case "getScore" -> scores.computeIfAbsent((String) a[0], e -> new ScoreState(this, e)).proxy;
                case "unregister" -> {
                    board.objectives.remove(name);
                    board.slots.values().removeIf(o -> o == this);
                    yield null;
                }
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p == a[0];
                case "toString" -> "FakeObjective " + name;
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    private static final class ScoreState {
        Integer value;
        net.kyori.adventure.text.Component customName;
        io.papermc.paper.scoreboard.numbers.NumberFormat numberFormat;
        final Score proxy;

        ScoreState(ObjectiveState objective, String entry) {
            proxy = FakeBoards.proxy(Score.class, (p, m, a) -> switch (m.getName()) {
                case "isScoreSet" -> value != null;
                case "getScore" -> value == null ? 0 : value;
                case "setScore" -> set(() -> value = (Integer) a[0]);
                case "resetScore" -> set(() -> {
                    value = null;
                    customName = null;
                });
                case "customName" -> a == null ? customName : set(() -> customName = (net.kyori.adventure.text.Component) a[0]);
                case "numberFormat" -> a == null ? numberFormat : set(() -> numberFormat = (io.papermc.paper.scoreboard.numbers.NumberFormat) a[0]);
                case "getEntry" -> entry;
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p == a[0];
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    private static Object set(Runnable r) {
        r.run();
        return null;
    }
}
