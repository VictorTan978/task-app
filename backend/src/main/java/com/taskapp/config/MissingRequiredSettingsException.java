package com.taskapp.config;

import java.util.List;

/** Thrown at startup when required settings (environment variables) are not set. */
public class MissingRequiredSettingsException extends IllegalStateException {

    private final List<String> missing;

    public MissingRequiredSettingsException(List<String> missing) {
        super(String.join(", ", missing) + (missing.size() == 1 ? " is" : " are") + " not set.");
        this.missing = List.copyOf(missing);
    }

    public List<String> getMissing() {
        return missing;
    }
}
