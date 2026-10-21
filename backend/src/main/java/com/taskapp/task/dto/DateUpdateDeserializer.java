package com.taskapp.task.dto;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/** {@code YYYY-MM-DD} as a JSON string, or JSON null = {@link DateUpdate#CLEAR}; absent stays Java {@code null}. */
class DateUpdateDeserializer extends StdDeserializer<DateUpdate> {

    DateUpdateDeserializer() {
        super(DateUpdate.class);
    }

    @Override
    public DateUpdate deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        if (p.currentToken() != JsonToken.VALUE_STRING) {
            return (DateUpdate) ctxt.handleUnexpectedToken(DateUpdate.class, p);
        }
        String text = p.getString();
        try {
            return new DateUpdate(LocalDate.parse(text));
        } catch (DateTimeParseException e) {
            return (DateUpdate) ctxt.handleWeirdStringValue(DateUpdate.class, text, "Expected YYYY-MM-DD");
        }
    }

    @Override
    public DateUpdate getNullValue(DeserializationContext ctxt) {
        return DateUpdate.CLEAR;
    }

    @Override
    public Object getAbsentValue(DeserializationContext ctxt) {
        return null;
    }
}
