package com.taskapp.task.dto;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * Only accepts JSON strings (nulls are handled by Jackson before this is called). Used for {@code tags} elements so
 * that {@code [1]} or {@code [true]} is a malformed body instead of being coerced to {@code "1"} / {@code "true"}.
 */
class StrictStringDeserializer extends StdDeserializer<String> {

    StrictStringDeserializer() {
        super(String.class);
    }

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        if (p.currentToken() == JsonToken.VALUE_STRING) {
            return p.getString();
        }
        return (String) ctxt.handleUnexpectedToken(String.class, p);
    }
}
