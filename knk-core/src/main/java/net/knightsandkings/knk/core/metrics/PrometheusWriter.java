package net.knightsandkings.knk.core.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a Prometheus text exposition (version 0.0.4) document. Samples are grouped per metric family
 * however they are added, so collectors may emit in any order; HELP and TYPE appear once per family.
 * Not thread-safe: one writer per scrape.
 */
public final class PrometheusWriter {

    /** The exact {@code Content-Type} a scraper expects for this format. */
    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private record Sample(String suffix, String labels, double value) {
    }

    private static final class Family {
        final String type;
        final String help;
        final List<Sample> samples = new ArrayList<>();

        Family(String type, String help) {
            this.type = type;
            this.help = help;
        }
    }

    private final Map<String, Family> families = new LinkedHashMap<>();

    /** A gauge sample; {@code labels} alternate name, value. */
    public PrometheusWriter gauge(String name, String help, double value, String... labels) {
        return add(name, "gauge", help, "", labels, value);
    }

    /** A counter sample; the name should end in {@code _total} (or {@code _seconds_total} etc.). */
    public PrometheusWriter counter(String name, String help, double value, String... labels) {
        return add(name, "counter", help, "", labels, value);
    }

    /**
     * One histogram series: cumulative {@code _bucket} lines for each upper bound plus {@code +Inf}, then
     * {@code _sum} and {@code _count}. {@code cumulative} has one entry per bound plus the total.
     */
    public PrometheusWriter histogram(String name, String help, double[] bounds, long[] cumulative, double sum,
                                      String... labels) {
        Family family = families.computeIfAbsent(name, n -> new Family("histogram", help));
        for (int i = 0; i < bounds.length; i++) {
            family.samples.add(new Sample("_bucket", labelString(labels, "le", format(bounds[i])), cumulative[i]));
        }
        family.samples.add(new Sample("_bucket", labelString(labels, "le", "+Inf"), cumulative[bounds.length]));
        family.samples.add(new Sample("_sum", labelString(labels), sum));
        family.samples.add(new Sample("_count", labelString(labels), cumulative[bounds.length]));
        return this;
    }

    private PrometheusWriter add(String name, String type, String help, String suffix, String[] labels, double value) {
        families.computeIfAbsent(name, n -> new Family(type, help)).samples.add(new Sample(suffix, labelString(labels), value));
        return this;
    }

    public String render() {
        StringBuilder out = new StringBuilder(4096);
        for (Map.Entry<String, Family> entry : families.entrySet()) {
            String name = entry.getKey();
            Family family = entry.getValue();
            out.append("# HELP ").append(name).append(' ').append(escapeHelp(family.help)).append('\n');
            out.append("# TYPE ").append(name).append(' ').append(family.type).append('\n');
            for (Sample sample : family.samples) {
                out.append(name).append(sample.suffix).append(sample.labels).append(' ').append(format(sample.value)).append('\n');
            }
        }
        return out.toString();
    }

    private static String labelString(String[] labels, String... extra) {
        int count = labels.length + extra.length;
        if (count == 0) {
            return "";
        }
        if (labels.length % 2 != 0 || extra.length % 2 != 0) {
            throw new IllegalArgumentException("labels are name/value pairs");
        }
        StringBuilder out = new StringBuilder("{");
        appendPairs(out, labels);
        if (labels.length > 0 && extra.length > 0) {
            out.append(',');
        }
        appendPairs(out, extra);
        return out.append('}').toString();
    }

    private static void appendPairs(StringBuilder out, String[] pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                out.append(',');
            }
            out.append(pairs[i]).append("=\"").append(escapeLabel(pairs[i + 1])).append('"');
        }
    }

    static String escapeLabel(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String escapeHelp(String help) {
        return help.replace("\\", "\\\\").replace("\n", "\\n");
    }

    static String format(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "+Inf" : "-Inf";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
