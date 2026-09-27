package net.knightsandkings.knk.paper.chat;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

import net.knightsandkings.knk.core.messaging.PrivateMessageCommandLog;

/**
 * Keeps private messages out of Paper's command log (docs/specs/private-messages/DESIGN.md §5 Q3,
 * developer decision 2026-09-26): drops the {@code "<player> issued server command: /msg …"} lines
 * (and /tell, /w, /r, … - see {@link PrivateMessageCommandLog}) from the root Log4j logger, so
 * {@code logs/latest.log} and its never-expiring gzipped archives don't keep a copy of every PM. The
 * moderation record is the PM log (local files + knk-web-api, 30-day retention).
 * <p>
 * Installed on enable and removed on disable, so a /reload doesn't stack filters.
 */
public final class PrivateMessageCommandLogFilter extends AbstractFilter {

    private static final Logger LOGGER = Logger.getLogger(PrivateMessageCommandLogFilter.class.getName());

    private LoggerConfig installedOn;

    public PrivateMessageCommandLogFilter() {
        super(Result.DENY, Result.NEUTRAL);
    }

    @Override
    public Result filter(LogEvent event) {
        return event == null ? Result.NEUTRAL : decide(event.getMessage());
    }

    Result decide(Message message) {
        if (message == null) {
            return Result.NEUTRAL;
        }
        return PrivateMessageCommandLog.isPrivateMessageCommandLine(message.getFormattedMessage()) ? onMatch : onMismatch;
    }

    /** Adds this filter to the root logger's config; false (with a WARN) if the server's logging isn't Log4j core. */
    public boolean install() {
        try {
            org.apache.logging.log4j.core.Logger root = (org.apache.logging.log4j.core.Logger) LogManager.getRootLogger();
            LoggerConfig config = root.getContext().getConfiguration().getRootLogger();
            start();
            config.addFilter(this);
            installedOn = config;
            return true;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.log(Level.WARNING, "Could not filter private messages out of the server command log", e);
            return false;
        }
    }

    public void uninstall() {
        LoggerConfig config = installedOn;
        installedOn = null;
        if (config == null) {
            return;
        }
        try {
            config.removeFilter(this);
            stop();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.log(Level.WARNING, "Could not remove the private message command log filter", e);
        }
    }
}
