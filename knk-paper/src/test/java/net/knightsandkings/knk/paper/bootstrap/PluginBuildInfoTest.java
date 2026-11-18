package net.knightsandkings.knk.paper.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** PandiO/knk-workspace#136: the startup line names the build's version and commit. */
class PluginBuildInfoTest {

    @Test
    void readsTheCommitAfterThePlus() {
        PluginBuildInfo info = PluginBuildInfo.fromVersion("0.1.0+c8232607");

        assertEquals("0.1.0+c8232607", info.version());
        assertEquals("c8232607", info.commit());
        assertEquals("KnightsAndKings 0.1.0+c8232607 (commit c8232607)", info.startupLine("KnightsAndKings"));
    }

    @Test
    void devBuildHasNoCommit() {
        PluginBuildInfo info = PluginBuildInfo.fromVersion("dev");

        assertNull(info.commit());
        assertEquals("KnightsAndKings dev (commit unknown: built without git)", info.startupLine("KnightsAndKings"));
    }

    @Test
    void blankOrDanglingPlusCountsAsDev() {
        assertEquals("dev", PluginBuildInfo.fromVersion(null).version());
        assertEquals("dev", PluginBuildInfo.fromVersion("  ").version());
        assertNull(PluginBuildInfo.fromVersion("0.1.0+").commit());
    }
}
