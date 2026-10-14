package com.taskapp.security.audit;

import java.util.List;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Collects what {@link SecurityEventLogger} writes. Call {@link #start()} before and {@link #stop()} after a test.
 * <p>
 * {@link #events()} and {@link #lines()} leave out the lines written by the background email jobs (threads
 * {@value #EMAIL_THREAD_PREFIX}*, e.g. {@code EMAIL_SEND_FAILED} after a register): they arrive at an unpredictable
 * moment, so tests of other features can't list them in order. Email tests use {@link #allLines()}.
 */
public final class SecurityLogCapture {

    public static final String EMAIL_THREAD_PREFIX = "email-";

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(SecurityEventLogger.LOGGER_NAME);

    public SecurityLogCapture start() {
        appender.start();
        logger.addAppender(appender);
        return this;
    }

    public void stop() {
        logger.detachAppender(appender);
        appender.stop();
    }

    public void clear() {
        synchronized (appender.list) {
            appender.list.clear();
        }
    }

    /** Lines written on request threads, in order. */
    public List<ILoggingEvent> events() {
        return allEvents().stream().filter(e -> !e.getThreadName().startsWith(EMAIL_THREAD_PREFIX)).toList();
    }

    public List<String> lines() {
        return events().stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Every line, including the background email jobs'. */
    public List<ILoggingEvent> allEvents() {
        synchronized (appender.list) {
            return List.copyOf(appender.list);
        }
    }

    public List<String> allLines() {
        return allEvents().stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
