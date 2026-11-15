package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioPlayer;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.List;
import java.util.Optional;

/**
 * Public chat as other players see it (simulated players, PandiO/knk-workspace#540): a player whose account carries a
 * title and group chat colours ({@code chatPrimaryColor} {@code &b}, {@code chatSecondaryColor} {@code &6}, KNG-7/KNG-8,
 * PandiO/knk-workspace#260) says something; a second player receives {@code -{Knight}- Name: Message} with the title
 * and name in the primary colour and the brackets in the secondary one, the first letter capitalised. A player holding
 * {@code knk.mode.staff} in-house is shown with the {@code [STAFF]} tag. The line goes through the server's chat path
 * ({@code Player#chat}, the chat event and the plugin's renderer) and is read from what the server sent the listener.
 */
public final class PlayerChatGroupColoursScenario implements Scenario<ScenarioScene> {

    @Override
    public String name() {
        return "player-chat-group-colours";
    }

    @Override
    public String description() {
        return "Chat shows a player's title and group chat colours to others, and the [STAFF] tag for staff";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        String speakerName = "ScnSpeaker";
        ScenarioPlayer speaker = scene.player(speakerName, p -> p.account(new UserSummary(scene.fakeId(1), speakerName,
            ScenarioPlayer.uuidOf(speakerName), null, 0, 0, 0, false, false, null, null, null, "Knight", 0, null, null, null,
            false, null, null, "&b", "&6", null, List.of())));
        ScenarioPlayer listener = scene.player("ScnListener", p -> p.account(scene.fakeId(2)));
        ScenarioPlayer staff = scene.player("ScnStaffer", p -> p.account(scene.fakeId(3)).grant("knk.mode.staff"));
        plan.act("three players join", () -> {
                speaker.join();
                listener.join();
                staff.join();
            })
            .await("all are online", 200, () -> speaker.online() && listener.online() && staff.online())
            .act("forget the join messages", () -> {
                speaker.clearMessages();
                listener.clearMessages();
                staff.clearMessages();
            })
            .act("the speaker says hello there", () -> speaker.chat("hello there"))
            .await("the listener receives the line", 60, () -> listener.received("ScnSpeaker: Hello there"))
            .check("the line reads -{Knight}- ScnSpeaker: Hello there", () -> {
                String line = line(listener, "ScnSpeaker: Hello there").plain();
                return line.equals("-{Knight}- ScnSpeaker: Hello there") ? null : "the line is '" + line + "'";
            })
            .check("title and name are aqua (&b), the brackets gold (&6)", () -> {
                ScenarioPlayer.Message line = line(listener, "ScnSpeaker: Hello there");
                return colourProblem(line, "Knight", NamedTextColor.AQUA)
                    .or(() -> colourProblem(line, "ScnSpeaker", NamedTextColor.AQUA))
                    .or(() -> colourProblem(line, "-{", NamedTextColor.GOLD))
                    .orElse(null);
            })
            .await("the speaker sees their own line too", 20, () -> speaker.received("ScnSpeaker: Hello there"))
            .act("the staff player says hi all", () -> staff.chat("hi all"))
            .await("the listener receives the staff line", 60, () -> listener.received("ScnStaffer: Hi all"))
            .check("it carries the [STAFF] tag", () -> {
                String line = line(listener, "ScnStaffer: Hi all").plain();
                return line.equals("[STAFF] ScnStaffer: Hi all") ? null : "the line is '" + line + "'";
            });
    }

    private static ScenarioPlayer.Message line(ScenarioPlayer player, String text) {
        return player.find(text).orElseThrow();
    }

    private static Optional<String> colourProblem(ScenarioPlayer.Message line, String part, TextColor expected) {
        TextColor actual = line.colorOf(part);
        return expected.equals(actual) ? Optional.empty()
            : Optional.of("'" + part + "' is " + actual + ", expected " + expected);
    }
}
