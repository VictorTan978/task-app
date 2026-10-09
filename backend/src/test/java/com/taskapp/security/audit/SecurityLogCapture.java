package com.taskapp.security.audit;

import java.util.List;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** Collects what {@link SecurityEventLogger} writes. Call {@link #start()} before and {@link #stop()} after a test. */
public final class SecurityLogCapture {

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
        appender.list.clear();
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    public List<String> lines() {
        return events().stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
