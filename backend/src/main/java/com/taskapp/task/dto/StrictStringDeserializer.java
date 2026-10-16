package com.taskapp.task.dto;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/**
 * Only accepts JSON strings (nulls are handled by Jackson before this is called). Used for {@code tags} elements so
 * that {@code [1]} or {@code [true]} is a malformed body instead of being coerced to {@code "1"} / {@code "true"}.
 */
class StrictStringDeserializer extends StdDeserializer<String> {

    StrictStringDeserializer() {
        super(String.class);
    }

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() == JsonToken.VALUE_STRING) {
            return p.getText();
        }
        return (String) ctxt.handleUnexpectedToken(String.class, p);
    }
}
