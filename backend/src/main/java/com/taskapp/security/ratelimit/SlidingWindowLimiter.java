package com.taskapp.security.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, in-memory sliding-window log: at most {@code limit} hits per key within any {@code window}.
 * A hit at time t counts until t + window.
 *
 * <p>Memory is bounded: each key keeps at most {@code limit} timestamps; keys with no hit inside the window
 * are removed by a sweep that runs at most once per {@link #SWEEP_INTERVAL} (on access, no scheduler), or
 * immediately when more than {@code maxKeys} keys are tracked. If a sweep cannot get below
 * {@code maxKeys}, the least recently hit keys are dropped.
 *
 * <p>All per-key reads and writes go through {@link ConcurrentHashMap#compute} and friends, so each key's
 * deque is only touched while its map bin is locked.
 */
final class SlidingWindowLimiter {

    static final Duration SWEEP_INTERVAL = Duration.ofMinutes(1);

    private final int limit;
    private final Duration window;
    private final int maxKeys;
    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private volatile Instant nextSweep;

    SlidingWindowLimiter(int limit, Duration window, int maxKeys, Clock clock) {
        if (limit < 1 || maxKeys < 1 || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("limit, maxKeys and window must be positive");
        }
        this.limit = limit;
        this.window = window;
        this.maxKeys = maxKeys;
        this.clock = clock;
        this.nextSweep = clock.instant().plus(SWEEP_INTERVAL);
    }

    /**
     * Records a hit for {@code key} if it is under the limit.
     *
     * @return empty if allowed (and recorded); otherwise the time until the next hit would be allowed
     */
    Optional<Duration> tryAcquire(String key) {
        Instant now = now();
        Instant[] retryAt = {null};
        hits.compute(key, (k, log) -> {
            Deque<Instant> q = log == null ? new ArrayDeque<>() : log;
            prune(q, now);
            if (q.size() >= limit) {
                retryAt[0] = q.peekFirst().plus(window);
            } else {
                q.addLast(now);
            }
            return q;
        });
        return untilRetry(retryAt[0], now);
    }

    /**
     * Checks {@code key} without recording a hit.
     *
     * @return empty if under the limit; otherwise the time until it will be
     */
    Optional<Duration> check(String key) {
        Instant now = now();
        Instant[] retryAt = {null};
        hits.computeIfPresent(key, (k, q) -> {
            prune(q, now);
            if (q.size() >= limit) {
                retryAt[0] = q.peekFirst().plus(window);
            }
            return q.isEmpty() ? null : q;
        });
        return untilRetry(retryAt[0], now);
    }

    /** Records a hit unconditionally; only the newest {@code limit} hits are kept. */
    void record(String key) {
        Instant now = now();
        hits.compute(key, (k, log) -> {
            Deque<Instant> q = log == null ? new ArrayDeque<>() : log;
            prune(q, now);
            q.addLast(now);
            while (q.size() > limit) {
                q.removeFirst();
            }
            return q;
        });
    }

    void reset(String key) {
        hits.remove(key);
    }

    int trackedKeys() {
        return hits.size();
    }

    private Instant now() {
        Instant now = clock.instant();
        if (hits.size() > maxKeys || !now.isBefore(nextSweep)) {
            sweep(now);
        }
        return now;
    }

    /** Removes keys with no hit inside the window, then drops the least recently hit keys if still over cap. */
    synchronized void sweep(Instant now) {
        if (hits.size() <= maxKeys && now.isBefore(nextSweep)) {
            return; // another thread just swept
        }
        nextSweep = now.plus(SWEEP_INTERVAL);
        for (String key : hits.keySet()) {
            hits.computeIfPresent(key, (k, q) -> {
                prune(q, now);
                return q.isEmpty() ? null : q;
            });
        }
        if (hits.size() > maxKeys) {
            evictLeastRecent();
        }
    }

    /** Evicts down to 90% of the cap, so a key flood doesn't trigger a full sweep on every request. */
    private void evictLeastRecent() {
        List<Map.Entry<String, Instant>> lastHits = new ArrayList<>();
        for (String key : hits.keySet()) {
            hits.computeIfPresent(key, (k, q) -> {
                lastHits.add(Map.entry(k, q.peekLast()));
                return q;
            });
        }
        lastHits.sort(Map.Entry.comparingByValue(Comparator.naturalOrder()));
        int toRemove = hits.size() - (int) (maxKeys * 0.9);
        for (int i = 0; i < toRemove && i < lastHits.size(); i++) {
            Map.Entry<String, Instant> e = lastHits.get(i);
            // Keep the key if it was hit again meanwhile.
            hits.computeIfPresent(e.getKey(), (k, q) -> e.getValue().equals(q.peekLast()) ? null : q);
        }
    }

    private void prune(Deque<Instant> q, Instant now) {
        Instant cutoff = now.minus(window);
        while (!q.isEmpty() && !q.peekFirst().isAfter(cutoff)) {
            q.removeFirst();
        }
    }

    private static Optional<Duration> untilRetry(Instant retryAt, Instant now) {
        return retryAt == null ? Optional.empty() : Optional.of(Duration.between(now, retryAt));
    }
}
