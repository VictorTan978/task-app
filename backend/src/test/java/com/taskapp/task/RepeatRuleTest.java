package com.taskapp.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The next-date math of contract 0.15.0: pure dates, no zone (DST cannot matter). */
class RepeatRuleTest {

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    private static RepeatRule rule(RepeatFrequency f, DayOfWeek... days) {
        return RepeatRule.of(f, days.length == 0 ? Set.of() : EnumSet.copyOf(Set.of(days)));
    }

    @Test
    void dailyIsTheNextDay() {
        RepeatRule daily = rule(RepeatFrequency.DAILY);
        assertThat(daily.next(d("2026-10-10"), d("2026-10-10"), d("2026-10-10"))).isEqualTo(d("2026-10-11"));
        assertThat(daily.next(d("2026-12-31"), d("2026-12-31"), d("2026-12-01"))).isEqualTo(d("2027-01-01"));
    }

    @Test
    void completedEarlyStillGoesToTheOccurrenceAfterTheOldDate() {
        RepeatRule daily = rule(RepeatFrequency.DAILY);
        assertThat(daily.next(d("2026-10-20"), d("2026-10-20"), d("2026-10-01"))).isEqualTo(d("2026-10-21"));
    }

    @Test
    void lateCompletionSkipsToTodayOrLater() {
        assertThat(rule(RepeatFrequency.DAILY).next(d("2026-10-10"), d("2026-10-10"), d("2026-10-20")))
                .isEqualTo(d("2026-10-20"));
        // Weekly Mon+Thu, due Mon Oct 5, completed Wed Oct 21: the first Mon/Thu on or after today is Thu Oct 22.
        assertThat(rule(RepeatFrequency.WEEKLY, DayOfWeek.MONDAY, DayOfWeek.THURSDAY)
                .next(d("2026-10-05"), d("2026-10-05"), d("2026-10-21"))).isEqualTo(d("2026-10-22"));
    }

    @Test
    void weeklyIsTheNextSelectedWeekdayStrictlyAfter() {
        RepeatRule monThu = rule(RepeatFrequency.WEEKLY, DayOfWeek.MONDAY, DayOfWeek.THURSDAY);
        assertThat(monThu.next(d("2026-10-19"), d("2026-10-19"), d("2026-10-19"))).isEqualTo(d("2026-10-22"));
        assertThat(monThu.next(d("2026-10-22"), d("2026-10-22"), d("2026-10-22"))).isEqualTo(d("2026-10-26"));
        // A due date that is not a selected weekday still moves to the next selected one.
        assertThat(monThu.next(d("2026-10-21"), d("2026-10-21"), d("2026-10-21"))).isEqualTo(d("2026-10-22"));
        // One weekday: the same weekday a week later, not the same day.
        assertThat(rule(RepeatFrequency.WEEKLY, DayOfWeek.FRIDAY).next(d("2026-10-23"), d("2026-10-23"),
                d("2026-10-23"))).isEqualTo(d("2026-10-30"));
        assertThat(rule(RepeatFrequency.WEEKLY, DayOfWeek.SUNDAY).next(d("2026-10-20"), d("2026-10-20"),
                d("2026-10-20"))).isEqualTo(d("2026-10-25"));
    }

    @Test
    void monthlyJan31ChainDoesNotDrift() {
        RepeatRule monthly = rule(RepeatFrequency.MONTHLY);
        LocalDate anchor = d("2026-01-31");
        LocalDate due = anchor;
        LocalDate today = d("2026-01-31");
        due = monthly.next(anchor, due, today);
        assertThat(due).isEqualTo(d("2026-02-28"));
        due = monthly.next(anchor, due, due);
        assertThat(due).isEqualTo(d("2026-03-31"));
        due = monthly.next(anchor, due, due);
        assertThat(due).isEqualTo(d("2026-04-30"));
        due = monthly.next(anchor, due, due);
        assertThat(due).isEqualTo(d("2026-05-31"));
        // Leap-year February.
        assertThat(monthly.next(d("2028-01-31"), d("2028-01-31"), d("2028-01-31"))).isEqualTo(d("2028-02-29"));
    }

    @Test
    void monthlyAcrossTheYearEnd() {
        assertThat(rule(RepeatFrequency.MONTHLY).next(d("2026-12-15"), d("2026-12-15"), d("2026-12-15")))
                .isEqualTo(d("2027-01-15"));
    }

    @Test
    void monthlyLateCompletionPicksTheFirstOccurrenceNotBeforeToday() {
        RepeatRule monthly = rule(RepeatFrequency.MONTHLY);
        // Day 15, today is the 20th: this month's 15th has passed.
        assertThat(monthly.next(d("2026-05-15"), d("2026-05-15"), d("2026-10-20"))).isEqualTo(d("2026-11-15"));
        // Today is the 10th: this month's 15th still counts.
        assertThat(monthly.next(d("2026-05-15"), d("2026-05-15"), d("2026-10-10"))).isEqualTo(d("2026-10-15"));
        // Exactly today counts.
        assertThat(monthly.next(d("2026-05-15"), d("2026-05-15"), d("2026-10-15"))).isEqualTo(d("2026-10-15"));
        // Day 31 anchor, today Nov 5: November has 30 days, so Nov 30.
        assertThat(monthly.next(d("2026-01-31"), d("2026-01-31"), d("2026-11-05"))).isEqualTo(d("2026-11-30"));
    }

    @Test
    void yearlyFeb29ReturnsInLeapYears() {
        RepeatRule yearly = rule(RepeatFrequency.YEARLY);
        LocalDate anchor = d("2028-02-29");
        LocalDate due = yearly.next(anchor, anchor, anchor);
        assertThat(due).isEqualTo(d("2029-02-28"));
        due = yearly.next(anchor, due, due);
        assertThat(due).isEqualTo(d("2030-02-28"));
        due = yearly.next(anchor, due, due);
        assertThat(due).isEqualTo(d("2031-02-28"));
        due = yearly.next(anchor, due, due);
        assertThat(due).isEqualTo(d("2032-02-29"));
    }

    @Test
    void yearlyLateCompletion() {
        RepeatRule yearly = rule(RepeatFrequency.YEARLY);
        assertThat(yearly.next(d("2020-03-10"), d("2020-03-10"), d("2026-10-20"))).isEqualTo(d("2027-03-10"));
        assertThat(yearly.next(d("2020-12-10"), d("2020-12-10"), d("2026-10-20"))).isEqualTo(d("2026-12-10"));
    }

    @Test
    void weekdaysAreBitsMondayFirst() {
        RepeatRule rule = rule(RepeatFrequency.WEEKLY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.THURSDAY);
        assertThat(rule.weekdayMask()).isEqualTo(1 + 8 + 64);
        assertThat(rule.weekdayNames()).containsExactly("MON", "THU", "SUN");
        assertThat(rule(RepeatFrequency.DAILY).weekdayNames()).isEmpty();
        assertThat(rule(RepeatFrequency.DAILY).weekdayMask()).isZero();
    }

    @Test
    void aWeeklyRuleNeedsWeekdays() {
        assertThatThrownBy(() -> new RepeatRule(RepeatFrequency.WEEKLY, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RepeatRule(RepeatFrequency.DAILY, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
