package net.knightsandkings.knk.paper.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** PandiO/knk-workspace#184: an erased account's vault file goes without being restored. */
class SiegePlayerVaultDiscardTest {

    @TempDir
    Path dir;

    @Test
    void discard_DeletesTheFile_AndIsIdempotent() throws Exception {
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Path file = Files.writeString(dir.resolve(player + ".yml"), "lobbyId: 1\n");
        Path otherFile = Files.writeString(dir.resolve(other + ".yml"), "lobbyId: 1\n");
        SiegePlayerVault vault = new SiegePlayerVault(dir.toFile(), Logger.getAnonymousLogger());

        assertEquals(1, vault.discard(player));

        assertFalse(Files.exists(file));
        assertTrue(Files.exists(otherFile));
        assertEquals(0, vault.discard(player));
        assertEquals(0, vault.discard(null));
    }
}
