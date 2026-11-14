"""Tests for check_log.py (run: python3 -m unittest discover -s tools/paper-boot -p 'test_*.py')."""
import unittest

from check_log import check, parse_entries

API = "127.0.0.1:5294"

GOOD_BOOT = """\
[16:30:39] [ServerMain/INFO]: [PluginInitializerManager] Bukkit plugins (3):
 - KnightsAndKings (0.1.0+bd7b78a8), WorldEdit (7.4.6+7648-00664b0), WorldGuard (7.0.20+2410-0b6c3f1)
[16:30:41] [Worker-Main-1/ERROR]: No key layers in MapLike[{}]
[16:30:44] [Server thread/INFO]: [WorldEdit] Enabling WorldEdit v7.4.6+7648-00664b0
[16:30:44] [Server thread/INFO]: Got request to register class com.sk89q.worldedit.bukkit.BukkitServerInterface with WorldEdit
[16:30:46] [Server thread/INFO]: [WorldGuard] Enabling WorldGuard v7.0.20+2410-0b6c3f1
[16:30:47] [Server thread/INFO]: [KnightsAndKings] Enabling KnightsAndKings v0.1.0+bd7b78a8
[16:30:47] [Server thread/INFO]: [KnightsAndKings] KnightsAndKings 0.1.0+bd7b78a8 (commit bd7b78a8)
[16:30:47] [knk-api-client/WARN]: [KnightsAndKings] Failed to load gates from API: ApiException: API error [0]: IO error
[16:30:48] [Server thread/ERROR]: [KnightsAndKings] InventoryMenu startup validation: failed to list menu templates (API down?)
java.util.concurrent.CompletionException: net.knightsandkings.knk.core.exception.ApiException: API error [0]: IO error
\tat java.base/java.util.concurrent.CompletableFuture.wrapInCompletionException(CompletableFuture.java:323) ~[?:?]
Caused by: java.net.ConnectException: Failed to connect to localhost/127.0.0.1:5294
\tat KnightsAndKings.jar//net.knightsandkings.knk.libs.okhttp3.internal.connection.RealConnection.connectSocket(RealConnection.kt:297) ~[?:?]
\t... 21 more
[16:30:48] [Server thread/INFO]: [KnightsAndKings] KnightsAndKings Plugin Enabled!
[16:30:49] [Server thread/INFO]: Done (13.942s)! For help, type "help"
[16:30:54] [knk-api-client/ERROR]: [net.knightsandkings.knk.api.impl.WorldTasksApiImpl] Failed to list tasks by status: Failed to connect to localhost/127.0.0.1:5294
[16:31:15] [Server thread/INFO]: [KnightsAndKings] Disabling KnightsAndKings v0.1.0+bd7b78a8
[16:31:15] [Server thread/INFO]: [KnightsAndKings] KnightsAndKings Plugin Disabled!
"""

# The shaded jar was not the one copied (knk-core missing): Paper refuses to load the plugin.
PLUGIN_NOT_LOADED = """\
[16:29:39] [Server thread/ERROR]: [ModernPluginLoadingStrategy] Could not load plugin 'KnightsAndKings.jar' in folder 'plugins'
org.bukkit.plugin.InvalidPluginException: java.lang.NoClassDefFoundError: net/knightsandkings/knk/core/dataaccess/UsersDataAccess$UserAnswerListener
\tat io.papermc.paper.plugin.provider.type.spigot.SpigotPluginProvider.createInstance(SpigotPluginProvider.java:129) ~[paper-26.3.jar:26.3-205-a90108f]
[16:29:39] [Server thread/INFO]: [WorldEdit] Enabling WorldEdit v7.4.6+7648-00664b0
[16:29:42] [Server thread/INFO]: [WorldGuard] Enabling WorldGuard v7.0.20+2410-0b6c3f1
[16:29:43] [Server thread/INFO]: Done (13.942s)! For help, type "help"
"""


def lines(text):
    return text.splitlines(keepends=True)


class CheckLogTest(unittest.TestCase):
    def test_good_boot_passes_when_api_errors_are_excused(self):
        errors, notes = check(lines(GOOD_BOOT), expect_commit="bd7b78a8", unreachable_api=API, expect_shutdown=True)
        self.assertEqual([], errors)
        self.assertIn("2 error/stack-trace entries excused", notes[-1])

    def test_api_errors_fail_without_the_unreachable_api_excuse(self):
        errors, _ = check(lines(GOOD_BOOT))
        self.assertEqual(2, len(errors))
        self.assertTrue(all("ERROR" in e for e in errors))

    def test_unrelated_server_error_is_ignored(self):
        errors, _ = check(lines(GOOD_BOOT), unreachable_api=API)
        self.assertFalse(any("No key layers" in e for e in errors))

    def test_plugin_that_does_not_load_fails(self):
        errors, _ = check(lines(PLUGIN_NOT_LOADED), unreachable_api=API)
        joined = "\n".join(errors)
        self.assertIn("version-stamp startup line not found", joined)
        self.assertIn("Plugin Enabled!", joined)
        self.assertIn("Could not load plugin 'KnightsAndKings.jar'", joined)

    def test_wrong_commit_fails(self):
        errors, _ = check(lines(GOOD_BOOT), expect_commit="deadbeef", unreachable_api=API)
        self.assertEqual(["KnK reports commit bd7b78a8, expected deadbeef"], errors)

    def test_knk_error_not_caused_by_the_api_fails(self):
        log = GOOD_BOOT + (
            "[16:30:50] [Server thread/ERROR]: [KnightsAndKings] Failed to initialize plugin: boom\n"
        )
        errors, _ = check(lines(log), unreachable_api=API)
        self.assertEqual(1, len(errors))
        self.assertIn("Failed to initialize plugin", errors[0])

    def test_worldguard_warning_with_stack_trace_fails(self):
        log = GOOD_BOOT + (
            "[16:30:50] [Server thread/WARN]: [WorldGuard] Failed to load regions\n"
            "java.lang.IllegalStateException: bad region file\n"
            "\tat com.sk89q.worldguard.protection.managers.storage.file.YamlRegionFile.loadAll(YamlRegionFile.java:1)\n"
        )
        errors, _ = check(lines(log), unreachable_api=API)
        self.assertEqual(1, len(errors))
        self.assertIn("WARN with a stack trace", errors[0])

    def test_single_line_knk_warning_passes(self):
        errors, _ = check(lines(GOOD_BOOT), unreachable_api=API)
        self.assertFalse(any("Failed to load gates" in e for e in errors))

    def test_missing_shutdown_line_fails_only_when_expected(self):
        log = GOOD_BOOT.replace("[16:31:15] [Server thread/INFO]: [KnightsAndKings] KnightsAndKings Plugin Disabled!\n", "")
        self.assertEqual([], check(lines(log), unreachable_api=API)[0])
        errors, _ = check(lines(log), unreachable_api=API, expect_shutdown=True)
        self.assertEqual(1, len(errors))
        self.assertIn("Plugin Disabled!", errors[0])

    def test_server_that_never_finished_starting_fails(self):
        log = GOOD_BOOT.replace('Done (13.942s)! For help, type "help"', "Stopping server")
        errors, _ = check(lines(log), unreachable_api=API)
        self.assertEqual(1, len(errors))
        self.assertIn("did not finish starting", errors[0])

    def test_stack_trace_lines_belong_to_the_entry_above(self):
        entries = parse_entries(lines(GOOD_BOOT))
        menu = next(e for e in entries if "InventoryMenu" in e.lines[0])
        self.assertTrue(menu.has_stack_trace)
        self.assertEqual(6, len(menu.lines))


if __name__ == "__main__":
    unittest.main()
