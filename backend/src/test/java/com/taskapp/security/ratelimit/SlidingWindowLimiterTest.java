package com.taskapp.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class SlidingWindowLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-09T10:00:00Z"));

    private SlidingWindowLimiter limiter(int limit, Duration window, int maxKeys) {
        return new SlidingWindowLimiter(limit, window, maxKeys, clock);
    }

    @Test
    void allowsUpToLimitThenBlocksWithTimeUntilOldestHitExpires() {
        SlidingWindowLimiter limiter = limiter(3, Duration.ofMinutes(1), 100);
        assertThat(limiter.tryAcquire("a")).isEmpty();
        clock.advance(Duration.ofSeconds(10));
        assertThat(limiter.tryAcquire("a")).isEmpty();
        assertThat(limiter.tryAcquire("a")).isEmpty();

        assertThat(limiter.tryAcquire("a")).contains(Duration.ofSeconds(50));
        assertThat(limiter.tryAcquire("b")).isEmpty();
    }

    @Test
    void windowSlidesHitByHit() {
        SlidingWindowLimiter limiter = limiter(2, Duration.ofMinutes(1), 100);
        limiter.tryAcquire("a");
        clock.advance(Duration.ofSeconds(30));
        limiter.tryAcquire("a");
        assertThat(limiter.tryAcquire("a")).contains(Duration.ofSeconds(30));

        clock.advance(Duration.ofSeconds(30)); // first hit is now exactly one window old
        assertThat(limiter.tryAcquire("a")).isEmpty();
        assertThat(limiter.tryAcquire("a")).contains(Duration.ofSeconds(30));
    }

    @Test
    void blockedAttemptsAreNotCounted() {
        SlidingWindowLimiter limiter = limiter(1, Duration.ofMinutes(1), 100);
        limiter.tryAcquire("a");
        clock.advance(Duration.ofSeconds(59));
        assertThat(limiter.tryAcquire("a")).contains(Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("a")).isEmpty();
    }

    @Test
    void checkDoesNotRecordAndRecordAndResetWork() {
        SlidingWindowLimiter limiter = limiter(2, Duration.ofMinutes(15), 100);
        assertThat(limiter.check("a")).isEmpty();
        assertThat(limiter.trackedKeys()).isZero();

        limiter.record("a");
        limiter.record("a");
        assertThat(limiter.check("a")).contains(Duration.ofMinutes(15));
        assertThat(limiter.check("b")).isEmpty();

        limiter.reset("a");
        assertThat(limiter.check("a")).isEmpty();
    }

    @Test
    void recordKeepsOnlyNewestHits() {
        SlidingWindowLimiter limiter = limiter(2, Duration.ofMinutes(15), 100);
        limiter.record("a");
        clock.advance(Duration.ofMinutes(1));
        limiter.record("a");
        clock.advance(Duration.ofMinutes(1));
        limiter.record("a"); // drops the first one

        assertThat(limiter.check("a")).contains(Duration.ofMinutes(14));
    }

    @Test
    void checkExpiresOldHits() {
        SlidingWindowLimiter limiter = limiter(2, Duration.ofMinutes(15), 100);
        limiter.record("a");
        limiter.record("a");
        clock.advance(Duration.ofMinutes(15));

        assertThat(limiter.check("a")).isEmpty();
        assertThat(limiter.trackedKeys()).isZero();
    }

    @Test
    void periodicSweepRemovesIdleKeys() {
        SlidingWindowLimiter limiter = limiter(5, Duration.ofMinutes(1), 100);
        for (int i = 0; i < 50; i++) {
            limiter.tryAcquire("ip-" + i);
        }
        assertThat(limiter.trackedKeys()).isEqualTo(50);

        clock.advance(SlidingWindowLimiter.SWEEP_INTERVAL.plusSeconds(1));
        limiter.tryAcquire("fresh");

        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    @Test
    void sizeCapEvictsLeastRecentlyHitKeys() {
        SlidingWindowLimiter limiter = limiter(5, Duration.ofHours(1), 10);
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("ip-" + i);
            clock.advance(Duration.ofSeconds(1));
        }
        limiter.tryAcquire("ip-0"); // ip-0 is now the most recent
        limiter.tryAcquire("ip-10"); // 11 keys
        limiter.tryAcquire("ip-11"); // over the cap: sweep, nothing expired, evict down to 9

        assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(10);
        // Oldest untouched keys went first; recently hit ones stay counted.
        limiter.tryAcquire("ip-0");
        limiter.tryAcquire("ip-0");
        limiter.tryAcquire("ip-0");
        assertThat(limiter.tryAcquire("ip-0")).isPresent();
    }

    @Test
    void isThreadSafe() throws Exception {
        SlidingWindowLimiter limiter = limiter(100, Duration.ofMinutes(1), 1000);
        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                futures.add(pool.submit(() -> {
                    if (limiter.tryAcquire("same").isEmpty()) {
                        allowed.incrementAndGet();
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(allowed.get()).isEqualTo(100);
    }
}
