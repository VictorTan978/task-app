package com.taskapp.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** AC8, AC10, AC23: fire times in the owner's zone, with the agreed daylight-saving rules. */
class FireTimesTest {

    private static final ZoneId BUCHAREST = ZoneId.of("Europe/Bucharest");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 21);

    private static Instant at(LocalDate date, LocalTime time, ReminderOffset offset, ZoneId zone) {
        return FireTimes.fireAt(date, time, offset, zone);
    }

    @Test
    void timedOffsetsSubtractFromTheDueInstantInTheOwnersZone() {
        LocalTime nine30 = LocalTime.of(9, 30);
        // 09:30 in Bucharest (UTC+3 in October 2026, DST ends on the 25th) is 06:30Z.
        assertThat(at(DAY, nine30, ReminderOffset.AT_DUE, BUCHAREST)).isEqualTo(Instant.parse("2026-10-21T06:30:00Z"));
        assertThat(at(DAY, nine30, ReminderOffset.TEN_MINUTES, BUCHAREST))
                .isEqualTo(Instant.parse("2026-10-21T06:20:00Z"));
        assertThat(at(DAY, nine30, ReminderOffset.ONE_HOUR, BUCHAREST)).isEqualTo(Instant.parse("2026-10-21T05:30:00Z"));
        assertThat(at(DAY, nine30, ReminderOffset.ONE_DAY, BUCHAREST)).isEqualTo(Instant.parse("2026-10-20T06:30:00Z"));
        // The same wall-clock value is a different instant in another member's zone.
        assertThat(at(DAY, nine30, ReminderOffset.AT_DUE, NEW_YORK)).isEqualTo(Instant.parse("2026-10-21T13:30:00Z"));
        assertThat(at(DAY, nine30, ReminderOffset.AT_DUE, ZoneOffset.UTC)).isEqualTo(Instant.parse("2026-10-21T09:30:00Z"));
    }

    @Test
    void dateOnlyRemindersAreAtNineOnTheDayOrTheDayBefore() {
        assertThat(at(DAY, null, ReminderOffset.ON_THE_DAY, BUCHAREST)).isEqualTo(Instant.parse("2026-10-21T06:00:00Z"));
        assertThat(at(DAY, null, ReminderOffset.ONE_DAY, BUCHAREST)).isEqualTo(Instant.parse("2026-10-20T06:00:00Z"));
        assertThat(at(DAY, null, ReminderOffset.ON_THE_DAY, NEW_YORK)).isEqualTo(Instant.parse("2026-10-21T13:00:00Z"));
    }

    @Test
    void aTimeInASpringForwardGapIsTheMomentTheClocksJump() {
        // Bucharest: 2026-03-29 03:00 -> 04:00. 03:30 does not exist; the clocks jump at 01:00Z.
        LocalDate day = LocalDate.of(2026, 3, 29);
        assertThat(at(day, LocalTime.of(3, 30), ReminderOffset.AT_DUE, BUCHAREST))
                .isEqualTo(Instant.parse("2026-03-29T01:00:00Z"));
        // 10 minutes before a gap time is counted from that instant.
        assertThat(at(day, LocalTime.of(3, 30), ReminderOffset.TEN_MINUTES, BUCHAREST))
                .isEqualTo(Instant.parse("2026-03-29T00:50:00Z"));
        // New York: 2026-03-08 02:00 -> 03:00 (07:00Z).
        assertThat(at(LocalDate.of(2026, 3, 8), LocalTime.of(2, 15), ReminderOffset.AT_DUE, NEW_YORK))
                .isEqualTo(Instant.parse("2026-03-08T07:00:00Z"));
        // A date-only reminder never lands in a gap at 09:00, but ONE_DAY of a gap time does: the day before
        // 2026-03-30 03:30 is the gap day, 03:30 local.
        assertThat(at(LocalDate.of(2026, 3, 30), LocalTime.of(3, 30), ReminderOffset.ONE_DAY, BUCHAREST))
                .isEqualTo(Instant.parse("2026-03-29T01:00:00Z"));
    }

    @Test
    void aTimeInAFallBackOverlapUsesTheEarlierOffset() {
        // Bucharest: 2026-10-25 04:00 (+3) -> 03:00 (+2). 03:30 happens twice; the first is 00:30Z.
        assertThat(at(LocalDate.of(2026, 10, 25), LocalTime.of(3, 30), ReminderOffset.AT_DUE, BUCHAREST))
                .isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
        // New York: 2026-11-01 01:30 happens twice; the first is EDT (-4) = 05:30Z.
        assertThat(at(LocalDate.of(2026, 11, 1), LocalTime.of(1, 30), ReminderOffset.AT_DUE, NEW_YORK))
                .isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
    }

    @Test
    void oneDayBeforeIsTheSameClockTimeOnThePreviousCalendarDay() {
        // The 24 hours across the Bucharest fall-back day are 25 hours long: "1 day before" is not minus 24 h.
        assertThat(at(LocalDate.of(2026, 10, 26), LocalTime.of(9, 0), ReminderOffset.ONE_DAY, BUCHAREST))
                .isEqualTo(Instant.parse("2026-10-25T07:00:00Z")); // 09:00 on the 25th is +2
        assertThat(at(LocalDate.of(2026, 10, 26), LocalTime.of(9, 0), ReminderOffset.AT_DUE, BUCHAREST))
                .isEqualTo(Instant.parse("2026-10-26T07:00:00Z"));
    }

    @Test
    void aMissingOrUnusableZoneMeansUtc() {
        assertThat(FireTimes.zoneOrUtc(null)).isEqualTo(ZoneOffset.UTC);
        assertThat(FireTimes.zoneOrUtc("")).isEqualTo(ZoneOffset.UTC);
        assertThat(FireTimes.zoneOrUtc("+02:00")).isEqualTo(ZoneOffset.UTC);
        assertThat(FireTimes.zoneOrUtc("Europe/Bucharest")).isEqualTo(BUCHAREST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Europe/Bucharest", "America/New_York", "UTC", "Asia/Kolkata", "Etc/GMT+1"})
    void regionIdsAreAccepted(String id) {
        assertThat(FireTimes.isRegionId(id)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"+02:00", "UTC+2", "GMT+2", "europe/bucharest", "Europe/Nowhere", " UTC", "", "EST5EDT x",
            "Z"})
    void offsetsAbbreviationsAndUnknownIdsAreRejected(String id) {
        assertThat(FireTimes.isRegionId(id)).isFalse();
    }

    @Test
    void anIdOverSixtyFourCharactersIsRejected() {
        assertThat(FireTimes.isRegionId("Europe/" + "x".repeat(60))).isFalse();
        assertThat(FireTimes.isRegionId(null)).isFalse();
    }

    @Test
    void offsetsFitTimedOrDateOnlyTasks() {
        assertThat(ReminderOffset.AT_DUE.fits(true)).isTrue();
        assertThat(ReminderOffset.AT_DUE.fits(false)).isFalse();
        assertThat(ReminderOffset.TEN_MINUTES.fits(false)).isFalse();
        assertThat(ReminderOffset.ONE_HOUR.fits(false)).isFalse();
        assertThat(ReminderOffset.ON_THE_DAY.fits(true)).isFalse();
        assertThat(ReminderOffset.ON_THE_DAY.fits(false)).isTrue();
        assertThat(ReminderOffset.ONE_DAY.fits(true)).isTrue();
        assertThat(ReminderOffset.ONE_DAY.fits(false)).isTrue();
    }

    @Test
    void requestValuesAreCaseSensitiveAndNoneIsRequestOnly() {
        assertThat(ReminderOffset.isRequestValue("NONE")).isTrue();
        assertThat(ReminderOffset.isRequestValue("ONE_HOUR")).isTrue();
        assertThat(ReminderOffset.isRequestValue("one_hour")).isFalse();
        assertThat(ReminderOffset.isRequestValue("")).isFalse();
        assertThat(ReminderOffset.parse("NONE")).isEmpty();
        assertThat(ReminderOffset.parse(null)).isEmpty();
    }
}
