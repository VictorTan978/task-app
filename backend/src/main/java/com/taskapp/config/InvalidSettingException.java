package com.taskapp.config;

/** Thrown at startup when a setting (environment variable) has a value the app refuses to run with. */
public class InvalidSettingException extends IllegalStateException {

    private final String setting;

    public InvalidSettingException(String setting, String problem) {
        super(setting + " " + problem + ".");
        this.setting = setting;
    }

    public String getSetting() {
        return setting;
    }
}
