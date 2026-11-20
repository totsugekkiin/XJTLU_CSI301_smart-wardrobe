package com.smartwardrobe.local;

import java.time.LocalDate;

public final class SeasonPolicyTest {
    private static int checks;
    private static void check(boolean result) { checks++; if (!result) throw new AssertionError("Case " + checks); }

    public static void main(String[] args) {
        int[] expected = {3,3,0,0,1,1,1,1,2,2,3,3};
        for (int month = 1; month <= 12; month++) check(SeasonPolicy.monthSeason(LocalDate.of(2026,month,1)) == expected[month-1]);
        LocalDate spring = LocalDate.of(2026,4,1), autumn = LocalDate.of(2026,11,20);
        for (double temp : new double[]{-20,0,14.999}) check(SeasonPolicy.temperatureSeason(temp,spring) == 3);
        for (double temp : new double[]{15,22,23,25}) {
            check(SeasonPolicy.temperatureSeason(temp,spring) == 0);
            check(SeasonPolicy.temperatureSeason(temp,autumn) == 2);
        }
        for (double temp : new double[]{25.001,38}) check(SeasonPolicy.temperatureSeason(temp,autumn) == 1);
        check(SeasonPolicy.temperatureSeason(Double.NaN,autumn) == 3);
        check(SeasonPolicy.temperatureSeason(Double.POSITIVE_INFINITY,spring) == 0);
        check(SeasonPolicy.quarter(LocalDate.of(2026,3,31)).equals("2026-Q1"));
        check(SeasonPolicy.quarter(LocalDate.of(2026,4,1)).equals("2026-Q2"));
        check(SeasonPolicy.quarter(LocalDate.of(2026,12,31)).equals("2026-Q4"));
        check(SeasonPolicy.quarter(LocalDate.of(2027,1,1)).equals("2027-Q1"));
        long stamp = 100_000_000;
        check(SeasonPolicy.fresh(stamp,stamp));
        check(SeasonPolicy.fresh(stamp,stamp + SeasonPolicy.CACHE_MS - 1));
        check(!SeasonPolicy.fresh(stamp,stamp + SeasonPolicy.CACHE_MS));
        check(!SeasonPolicy.fresh(stamp,stamp - 1));
        check(!SeasonPolicy.fresh(0,stamp));
        System.out.println("PASS: " + checks + " policy cases: months, temperature boundaries, transition gap, quarter rollover and 24h cache");
    }
}
