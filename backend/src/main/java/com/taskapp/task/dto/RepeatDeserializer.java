package com.taskapp.task.dto;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * {@code repeat}: a JSON string (kept raw; only {@code "NEVER"} is valid) or an object whose {@code frequency} and
 * {@code weekdays} are kept as raw strings, so unknown values are field errors ({@code INVALID_REPEAT}), not a
 * malformed body. Any other JSON type (number, boolean, array), a non-string frequency or a non-array / non-string
 * weekdays is a malformed body. JSON null stays Java null (unchanged).
 */
class RepeatDeserializer extends StdDeserializer<RepeatInput> {

    RepeatDeserializer() {
        super(RepeatInput.class);
    }

    @Override
    public RepeatInput deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_STRING) {
            return RepeatInput.ofText(p.getString());
        }
        if (token != JsonToken.START_OBJECT) {
            return (RepeatInput) ctxt.handleUnexpectedToken(RepeatInput.class, p);
        }
        String frequency = null;
        List<String> weekdays = null;
        while (p.nextToken() == JsonToken.PROPERTY_NAME) {
            String name = p.currentName();
            JsonToken value = p.nextToken();
            if ("frequency".equals(name)) {
                if (value == JsonToken.VALUE_STRING) {
                    frequency = p.getString();
                } else if (value != JsonToken.VALUE_NULL) {
                    return (RepeatInput) ctxt.handleUnexpectedToken(RepeatInput.class, p);
                }
            } else if ("weekdays".equals(name)) {
                if (value == JsonToken.START_ARRAY) {
                    weekdays = new ArrayList<>();
                    while (p.nextToken() != JsonToken.END_ARRAY) {
                        if (p.currentToken() != JsonToken.VALUE_STRING) {
                            return (RepeatInput) ctxt.handleUnexpectedToken(RepeatInput.class, p);
                        }
                        weekdays.add(p.getString());
                    }
                } else if (value != JsonToken.VALUE_NULL) {
                    return (RepeatInput) ctxt.handleUnexpectedToken(RepeatInput.class, p);
                }
            } else {
                p.skipChildren();
            }
        }
        return RepeatInput.ofObject(frequency, weekdays);
    }
}
