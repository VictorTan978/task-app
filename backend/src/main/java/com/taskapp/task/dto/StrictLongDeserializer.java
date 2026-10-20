package com.taskapp.task.dto;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * Only accepts a JSON integer that fits a {@code long} (nulls are handled by Jackson before this is called). Used for
 * {@code listId} so that {@code 1.5} (Jackson would truncate it to 1) or {@code "8"} is a malformed body.
 */
class StrictLongDeserializer extends StdDeserializer<Long> {

    StrictLongDeserializer() {
        super(Long.class);
    }

    @Override
    public Long deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        if (p.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            // Throws (a malformed body) when the value does not fit a long.
            return p.getLongValue();
        }
        return (Long) ctxt.handleUnexpectedToken(Long.class, p);
    }
}
