package net.knightsandkings.knk.paper.scenario;

import net.knightsandkings.knk.core.domain.permissions.EffectivePermissionResolver;
import net.knightsandkings.knk.core.domain.permissions.EffectivePermissionSet;
import net.knightsandkings.knk.core.domain.permissions.PermissionResolution;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** The parts of a scenario's scripted player that need no server (PandiO/knk-workspace#540). */
class ScenarioPlayerTest {

    // kept in a field, never inline (knk-plugin CLAUDE.md: a Location holds its world weakly)
    private final World world = mock(World.class);

    private ScenarioScene scene() {
        return new ScenarioScene("player-test", 3, world, 30192, 100, 30000,
            new ScenarioServices(null, () -> null, () -> null, () -> null, () -> null, () -> null, () -> null, () -> null,
                () -> null, () -> null, () -> null, () -> null, () -> null, () -> null));
    }

    @Test
    void aPlayersUuidIsTheOfflineModeUuidOfTheName() {
        // what an offline-mode server (and a bot that logs in with the same name) gives the name
        assertEquals(UUID.fromString("b50ad385-829d-3141-a216-7e7d7539ba7f"), ScenarioPlayer.uuidOf("Notch"));
    }

    @Test
    void theSceneHandsOutOnePlayerPerName() {
        ScenarioScene scene = scene();
        ScenarioPlayer first = scene.player("ScnAlice");
        assertSame(first, scene.player("scnalice"));
        assertNotSame(first, scene.player("ScnBob"));
        assertEquals(ScenarioPlayer.uuidOf("ScnAlice"), first.uuid());
    }

    @Test
    void namesFollowMinecraftsRules() {
        ScenarioScene scene = scene();
        assertThrows(IllegalArgumentException.class, () -> scene.player("ab"));
        assertThrows(IllegalArgumentException.class, () -> scene.player("SeventeenLetterss"));
        assertThrows(IllegalArgumentException.class, () -> scene.player("has space"));
        assertDoesNotThrow(() -> scene.player("Scn_Ok_16_chars_"));
    }

    @Test
    void aFakeAccountNeedsANegativeIdAndThePlayersUuid() {
        ScenarioPlayer player = scene().player("ScnCarol");
        assertThrows(IllegalArgumentException.class, () -> player.account(5));
        assertThrows(IllegalArgumentException.class, () -> player.account(new UserSummary(-1, "ScnCarol",
            UUID.randomUUID(), 0)));
        assertDoesNotThrow(() -> player.account(-421_003_001));
    }

    @Test
    void grantsAndDeniesBecomeTheUsersOwnEntries() {
        Map<String, Boolean> nodes = new LinkedHashMap<>();
        nodes.put("knk.feed", true);
        nodes.put("knk.feed.others", false);
        EffectivePermissionSet set = ScenarioPlayer.permissionSet(-421_003_001, "ScnDave", nodes);
        EffectivePermissionResolver resolver = EffectivePermissionResolver.of(set);

        assertEquals(-421_003_001, set.userId());
        assertEquals(PermissionResolution.GRANTED, resolver.resolve("knk.feed").orElseThrow().result());
        assertEquals(PermissionResolution.DENIED, resolver.resolve("knk.feed.others").orElseThrow().result());
        assertEquals(PermissionResolution.UNDECLARED, resolver.resolve("knk.teleport.staff").orElseThrow().result());
        assertTrue(set.permissions().stream().allMatch(p -> "User".equals(p.source().type())));
    }

    @Test
    void aMessageGivesItsPlainTextAndTheColourOfAPart() {
        Component line = Component.text()
            .append(Component.text("-{", NamedTextColor.GOLD))
            .append(Component.text("Knight", NamedTextColor.AQUA))
            .append(Component.text("}- ", NamedTextColor.GOLD))
            .append(Component.text("ScnSpeaker: ", NamedTextColor.AQUA)
                .append(Component.text("Hello")))
            .build();
        ScenarioPlayer.Message message = new ScenarioPlayer.Message(ScenarioPlayer.Kind.CHAT, line);

        assertEquals("-{Knight}- ScnSpeaker: Hello", message.plain());
        assertEquals(NamedTextColor.GOLD, message.colorOf("-{"));
        assertEquals(NamedTextColor.AQUA, message.colorOf("Knight"));
        assertEquals(NamedTextColor.AQUA, message.colorOf("Hello"), "inherited from its parent");
        assertNull(message.colorOf("not there"));
    }

    @Test
    void theColourLookupReadsAChatTypesTranslationArguments() {
        // a chat line arrives decorated by its chat type: translatable "%s" with the rendered line as its argument
        Component decorated = Component.translatable("chat.type.raw", List.of(
            Component.text("ScnStaffer: ", NamedTextColor.BLUE).append(Component.text("Hi all"))));
        ScenarioPlayer.Message message = new ScenarioPlayer.Message(ScenarioPlayer.Kind.CHAT, decorated);

        assertEquals(NamedTextColor.BLUE, message.colorOf("ScnStaffer"));
        assertEquals(NamedTextColor.BLUE, message.colorOf("Hi all"));
    }
}
