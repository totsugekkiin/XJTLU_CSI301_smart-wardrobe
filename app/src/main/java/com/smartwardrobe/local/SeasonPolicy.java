package com.smartwardrobe.local;

import java.time.LocalDate;

/** Indices follow the existing garment season bitmask: spring, summer, autumn, winter. */
final class SeasonPolicy {
    static final long CACHE_MS = 24L * 60 * 60 * 1000;

    static int monthSeason(LocalDate today) {
        int month = today.getMonthValue();
        if (month >= 3 && month <= 4) return 0;
        if (month >= 5 && month <= 8) return 1;
        if (month >= 9 && month <= 10) return 2;
        return 3;
    }

    static int temperatureSeason(double averageHigh, LocalDate today) {
        if (!Double.isFinite(averageHigh)) return monthSeason(today);
        if (averageHigh < 15) return 3;
        if (averageHigh > 25) return 1;
        return today.getMonthValue() <= 6 ? 0 : 2;
    }

    static boolean fresh(long fetchedAt, long now) {
        long age = now - fetchedAt;
        return fetchedAt > 0 && age >= 0 && age < CACHE_MS;
    }

    static String quarter(LocalDate today) {
        return today.getYear() + "-Q" + ((today.getMonthValue() - 1) / 3 + 1);
    }
}
