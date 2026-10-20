package com.taskapp.task;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalTime;
import java.util.Objects;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.usertype.UserType;

/**
 * A {@code TIME} column as a {@link LocalTime}, bound as the driver's own {@code LocalTime} so the wall-clock value
 * round-trips exactly. Hibernate's built-in mapping goes through {@code java.sql.Time} and, because
 * {@code hibernate.jdbc.time_zone} is UTC (needed for the {@code DATETIME(6)} instants), shifts the value by the JVM
 * zone's offset: 09:30 would be stored as 02:00 on a machine in UTC+7:30 at the epoch. A due time is a wall-clock
 * value (contract 0.14.0) and must never move.
 */
public class WallClockTimeType implements UserType<LocalTime> {

    @Override
    public int getSqlType() {
        return Types.TIME;
    }

    @Override
    public Class<LocalTime> returnedClass() {
        return LocalTime.class;
    }

    @Override
    public boolean equals(LocalTime x, LocalTime y) {
        return Objects.equals(x, y);
    }

    @Override
    public int hashCode(LocalTime x) {
        return Objects.hashCode(x);
    }

    @Override
    public LocalTime nullSafeGet(ResultSet rs, int position, WrapperOptions options) throws SQLException {
        return rs.getObject(position, LocalTime.class);
    }

    @Override
    public void nullSafeSet(PreparedStatement st, LocalTime value, int index, WrapperOptions options)
            throws SQLException {
        if (value == null) {
            st.setNull(index, Types.TIME);
        } else {
            st.setObject(index, value);
        }
    }

    @Override
    public LocalTime deepCopy(LocalTime value) {
        return value;
    }

    @Override
    public boolean isMutable() {
        return false;
    }

    @Override
    public Serializable disassemble(LocalTime value) {
        return value;
    }

    @Override
    public LocalTime assemble(Serializable cached, Object owner) {
        return (LocalTime) cached;
    }

    @Override
    public LocalTime replace(LocalTime detached, LocalTime managed, Object owner) {
        return detached;
    }
}
