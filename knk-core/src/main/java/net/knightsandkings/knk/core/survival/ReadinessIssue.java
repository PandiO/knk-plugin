package net.knightsandkings.knk.core.survival;

/**
 * A readiness finding (DESIGN §10 "Readiness validates…"): an {@link Severity#ERROR} blocks the match start, a
 * {@link Severity#WARNING} is shown to the admin (for example "wipe-only", §3a).
 *
 * @param severity error or warning
 * @param code     a stable machine code, e.g. {@code end.wipe-only}
 * @param message  a human-readable explanation
 */
public record ReadinessIssue(Severity severity, String code, String message) {

    public enum Severity { ERROR, WARNING }

    public static ReadinessIssue error(String code, String message) {
        return new ReadinessIssue(Severity.ERROR, code, message);
    }

    public static ReadinessIssue warning(String code, String message) {
        return new ReadinessIssue(Severity.WARNING, code, message);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }
}
