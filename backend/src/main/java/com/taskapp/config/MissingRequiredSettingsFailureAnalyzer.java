package com.taskapp.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns {@link MissingRequiredSettingsException} into a short, readable startup failure report. */
public class MissingRequiredSettingsFailureAnalyzer extends AbstractFailureAnalyzer<MissingRequiredSettingsException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, MissingRequiredSettingsException cause) {
        String names = String.join(", ", cause.getMissing());
        String description = cause.getMessage()
                + " Without an active profile the app uses production settings, which have no default database credentials.";
        String action = "Set " + names
                + ", or run with SPRING_PROFILES_ACTIVE=dev for local development.";
        return new FailureAnalysis(description, action, cause);
    }
}
