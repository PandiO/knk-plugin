package net.knightsandkings.knk.paper.realtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.knightsandkings.knk.api.realtime.GateCommandEvents;
import net.knightsandkings.knk.api.realtime.GateCommandEvents.GateCommand;
import net.knightsandkings.knk.api.realtime.RealtimeEnvelope;
import net.knightsandkings.knk.core.gates.command.GateCommandHandler;
import net.knightsandkings.knk.core.gates.command.GateCommandHandler.Action;
import net.knightsandkings.knk.core.gates.command.GateCommandHandler.DoorResult;
import net.knightsandkings.knk.core.gates.command.GateCommandHandler.Result;
import net.knightsandkings.knk.core.realtime.RecentActionIds;

/**
 * Overview map L7 (PandiO/knk-workspace#741, OVERVIEW_MAP_DESIGN § 6.2.5–6.2.6, § 7.1.3, O17): the game server's handler
 * for {@code GateCommand} v1. Runs the command with {@link GateCommandHandler} on the main thread (the existing gate
 * operations, normal animation) and answers {@code GateCommandResult} over the hub with the outcome and the doors'
 * states. The feature flag is the API's ({@code Map:GateCommands:Enabled}): with it off the API sends no commands, so
 * nothing here runs; the plugin has no own key (spec § 7.3). A command id seen before is skipped (no second answer).
 * Threads: parsing and the duplicate check on the realtime thread, the command and the answer on {@code mainThread}
 * ({@link RealtimeService#send} only queues the message).
 */
public final class GateCommandRealtimeSync {

    private static final String TAG = "[KnK Gates] ";

    private final Executor mainThread;
    private final GateCommandHandler handler;
    private final BiPredicate<String, Object> hubSend;
    private final RecentActionIds handled;
    private final Logger logger;

    /**
     * @param hubSend {@link RealtimeService#send}
     */
    public GateCommandRealtimeSync(Executor mainThread, GateCommandHandler handler, BiPredicate<String, Object> hubSend,
                                   RecentActionIds handled, Logger logger) {
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.hubSend = Objects.requireNonNull(hubSend, "hubSend");
        this.handled = handled == null ? new RecentActionIds() : handled;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Registers the handler (before the client starts: it listens for the registered types). */
    public void register(RealtimeDispatcher dispatcher) {
        dispatcher.register(GateCommandEvents.GATE_COMMAND, 1, Runnable::run, this::onCommand);
    }

    /** Realtime thread. */
    void onCommand(RealtimeEnvelope envelope) {
        GateCommand command = GateCommand.parse(envelope);
        if (command == null) {
            logger.warning(TAG + "GateCommand (seq " + (envelope == null ? "?" : envelope.seq())
                + ") has no usable command id, gate id or action; ignored");
            return;
        }
        if (!handled.firstTime(command.commandId())) {
            logger.fine(TAG + "GateCommand " + command.commandId() + " was handled already; skipped");
            return;
        }
        mainThread.execute(() -> run(command));
    }

    /** Main thread. */
    void run(GateCommand command) {
        Result result;
        try {
            result = handler.handle(command.gateId(), command.doorId(), Action.parse(command.action()));
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, TAG + "Web gate command " + command.commandId() + " failed", e);
            result = new Result(GateCommandHandler.Outcome.REFUSED, "The game server failed to run the command.", List.of());
        }
        logger.info(TAG + "Web gate command " + command.action() + " gate " + command.gateId()
            + (command.doorId() == null ? "" : " door " + command.doorId()) + " by " + staff(command) + ": "
            + result.outcome().wire() + " - " + result.message());
        if (!hubSend.test(GateCommandEvents.GATE_COMMAND_RESULT, body(command.commandId(), result))) {
            logger.warning(TAG + "Result of web gate command " + command.commandId()
                + " not sent (hub not connected); the web app shows a timeout");
        }
    }

    static Map<String, Object> body(String commandId, Result result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandId", commandId);
        body.put("result", result.outcome().wire());
        body.put("message", result.message());
        List<Map<String, Object>> doors = new ArrayList<>();
        for (DoorResult door : result.doors()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("doorId", door.doorId());
            d.put("state", door.state());
            d.put("healthCurrent", door.healthCurrent());
            d.put("healthMax", door.healthMax());
            d.put("destroyed", door.destroyed());
            d.put("outcome", door.outcome().wire());
            d.put("reason", door.reason());
            doors.add(d);
        }
        body.put("doors", doors);
        return body;
    }

    private static String staff(GateCommand command) {
        return command.staffUsername() != null ? command.staffUsername()
            : (command.staffUserId() != null ? "user " + command.staffUserId() : "staff");
    }
}
