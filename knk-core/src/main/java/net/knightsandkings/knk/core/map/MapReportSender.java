package net.knightsandkings.knk.core.map;

import java.util.Map;

/** Sends one map report to the API (on Paper: a game hub invocation). Must not block; false when it was not sent. */
@FunctionalInterface
public interface MapReportSender {

    boolean send(String hubMethod, Map<String, Object> argument);
}
