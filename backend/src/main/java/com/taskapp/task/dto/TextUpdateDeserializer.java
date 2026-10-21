package com.taskapp.task.dto;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/** JSON string, or JSON null = {@link TextUpdate#CLEAR}; an absent property stays Java {@code null}. */
class TextUpdateDeserializer extends StdDeserializer<TextUpdate> {

    TextUpdateDeserializer() {
        super(TextUpdate.class);
    }

    @Override
    public TextUpdate deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        if (p.currentToken() != JsonToken.VALUE_STRING) {
            return (TextUpdate) ctxt.handleUnexpectedToken(TextUpdate.class, p);
        }
        return new TextUpdate(p.getString());
    }

    @Override
    public TextUpdate getNullValue(DeserializationContext ctxt) {
        return TextUpdate.CLEAR;
    }

    @Override
    public Object getAbsentValue(DeserializationContext ctxt) {
        return null;
    }
}
