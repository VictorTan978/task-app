package com.taskapp.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns {@link InvalidSettingException} into a short, readable startup failure report. */
public class InvalidSettingFailureAnalyzer extends AbstractFailureAnalyzer<InvalidSettingException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InvalidSettingException cause) {
        return new FailureAnalysis(cause.getMessage(), "Fix " + cause.getSetting()
                + ", or run with SPRING_PROFILES_ACTIVE=dev for local development.", cause);
    }
}
