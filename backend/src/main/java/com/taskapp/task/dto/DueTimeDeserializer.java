package com.taskapp.task.dto;

import java.time.LocalTime;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * {@code dueTime}: exactly {@code HH:mm}, 24-hour (no seconds, no single digits, no {@code 24:00}), as a JSON
 * string; anything else is a malformed body. JSON {@code null} becomes {@link DueTimeUpdate#CLEAR}; an absent
 * property stays Java {@code null} (unchanged).
 */
class DueTimeDeserializer extends StdDeserializer<DueTimeUpdate> {

    private static final Pattern HH_MM = Pattern.compile("([01][0-9]|2[0-3]):([0-5][0-9])");

    DueTimeDeserializer() {
        super(DueTimeUpdate.class);
    }

    @Override
    public DueTimeUpdate deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        if (p.currentToken() != JsonToken.VALUE_STRING) {
            return (DueTimeUpdate) ctxt.handleUnexpectedToken(DueTimeUpdate.class, p);
        }
        String text = p.getString();
        var matcher = HH_MM.matcher(text);
        if (!matcher.matches()) {
            return (DueTimeUpdate) ctxt.handleWeirdStringValue(DueTimeUpdate.class, text, "Expected HH:mm");
        }
        return new DueTimeUpdate(LocalTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
    }

    @Override
    public DueTimeUpdate getNullValue(DeserializationContext ctxt) {
        return DueTimeUpdate.CLEAR;
    }

    @Override
    public Object getAbsentValue(DeserializationContext ctxt) {
        return null;
    }
}
