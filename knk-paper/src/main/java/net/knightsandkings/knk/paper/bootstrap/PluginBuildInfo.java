package net.knightsandkings.knk.paper.bootstrap;

/**
 * The build a server runs, read from plugin.yml's version (PandiO/knk-workspace#136). knk-paper/build.gradle.kts
 * expands that version at build time to {@code <base>+<git short sha>} (for example {@code 0.1.0+c8232607}), or to
 * {@code dev} when the build had neither the {@code gitCommit} Gradle property nor git.
 */
public record PluginBuildInfo(String version, String commit) {

    /** The commit part of {@code version}, or {@code null} for a build without a commit ({@code dev}). */
    public static PluginBuildInfo fromVersion(String version) {
        String v = version == null ? "" : version.trim();
        int plus = v.indexOf('+');
        String commit = plus >= 0 && plus < v.length() - 1 ? v.substring(plus + 1) : null;
        return new PluginBuildInfo(v.isEmpty() ? "dev" : v, commit);
    }

    /** The startup log line, for example {@code KnightsAndKings 0.1.0+c8232607 (commit c8232607)}. */
    public String startupLine(String pluginName) {
        return pluginName + " " + version + " (commit " + (commit != null ? commit : "unknown: built without git") + ")";
    }
}
