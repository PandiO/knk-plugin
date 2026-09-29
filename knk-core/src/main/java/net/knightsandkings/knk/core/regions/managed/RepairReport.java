package net.knightsandkings.knk.core.regions.managed;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** What one reconcile run did: a count per outcome plus the detail behind it. */
public final class RepairReport {

    public enum Outcome { UNCHANGED, CHANGED, SKIPPED, FAILED }

    public record Entry(String regionId, Outcome outcome, String detail) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> warnings = new LinkedHashSet<>();
    private boolean persistFailed;

    void add(String regionId, Outcome outcome, String detail) {
        entries.add(new Entry(regionId, outcome, detail));
    }

    void warn(String warning) {
        warnings.add(warning);
    }

    void persistFailed(String why) {
        persistFailed = true;
        warnings.add("saving WorldGuard regions failed: " + why);
    }

    /** Records a domain that could not even become a spec (no region id, stale reference). */
    public void addSkipped(String label, String reason) {
        add(label, Outcome.SKIPPED, reason);
    }

    public void addWarning(String warning) {
        warn(warning);
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public boolean persistFailed() {
        return persistFailed;
    }

    public int checked() {
        return entries.size();
    }

    public int count(Outcome outcome) {
        return (int) entries.stream().filter(entry -> entry.outcome() == outcome).count();
    }

    public int changed() {
        return count(Outcome.CHANGED);
    }

    public int skipped() {
        return count(Outcome.SKIPPED);
    }

    public int failed() {
        return count(Outcome.FAILED);
    }

    public int unchanged() {
        return count(Outcome.UNCHANGED);
    }

    /** The one-line log summary. */
    public String summary() {
        return "checked=" + checked() + " changed=" + changed() + " skipped=" + skipped() + " failed=" + failed()
                + " unchanged=" + unchanged() + (persistFailed ? " save=FAILED" : "");
    }
}
