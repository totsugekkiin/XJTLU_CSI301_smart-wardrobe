package com.smartwardrobe.local;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground coarse-location lookup followed by a bounded, silent weather request. */
final class SeasonWeather implements AutoCloseable {
    record Advice(int season, Double averageHigh) {
        boolean weatherBased() { return averageHigh != null; }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Runnable changed;
    private LocationListener listener;
    private Runnable locationTimeout;
    private boolean busy;
    private volatile boolean closed;
    private volatile HttpURLConnection connection;

    SeasonWeather(Context context, Runnable changed) {
        this.context = context;
        this.changed = changed;
        prefs = context.getSharedPreferences("season_weather", Context.MODE_PRIVATE);
    }

    boolean hasPermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    boolean permissionAsked() { return prefs.getBoolean("permission_asked", false); }
    void markPermissionAsked() { prefs.edit().putBoolean("permission_asked", true).apply(); }

    private boolean online() {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
            return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (RuntimeException ignored) { return false; }
    }

    Advice advice() {
        LocalDate today = LocalDate.now();
        if (hasPermission() && online() && SeasonPolicy.fresh(prefs.getLong("fetched_at", 0), System.currentTimeMillis())) {
            try {
                double average = averageHigh(new JSONObject(prefs.getString("forecast", "")));
                return new Advice(SeasonPolicy.temperatureSeason(average, today), average);
            } catch (Exception ignored) { /* Fall through without surfacing a network or cache error. */ }
        }
        return new Advice(SeasonPolicy.monthSeason(today), null);
    }

    boolean reminderDismissed() { return SeasonPolicy.quarter(LocalDate.now()).equals(prefs.getString("dismissed_quarter", "")); }
    void dismissReminder() { prefs.edit().putString("dismissed_quarter", SeasonPolicy.quarter(LocalDate.now())).apply(); }

    void refreshIfDue() {
        refresh(false);
    }

    void refreshNow() {
        refresh(true);
    }

    private void refresh(boolean force) {
        if (closed || busy || !hasPermission() || !online()) return;
        String today = LocalDate.now().toString();
        if (!force && today.equals(prefs.getString("last_attempt_day", ""))) return;
        // A same-day cached success needs no second request after an activity restart.
        if (!force && SeasonPolicy.fresh(prefs.getLong("fetched_at", 0), System.currentTimeMillis())
                && today.equals(prefs.getString("fetched_day", ""))) return;
        busy = true;
        try {
            LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (!manager.isLocationEnabled()) { busy = false; return; }
            Location cached = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            long age = cached == null ? Long.MAX_VALUE : System.currentTimeMillis() - cached.getTime();
            if (cached != null && age >= 0 && age < SeasonPolicy.CACHE_MS) { fetch(cached, force); return; }
            if (!manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) { busy = false; return; }
            listener = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    if (listener != this) return;
                    stopLocation();
                    fetch(location, force);
                }
                @Override public void onProviderDisabled(String provider) { stopLocation(); busy = false; }
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            };
            locationTimeout = () -> { stopLocation(); busy = false; };
            manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, listener, Looper.getMainLooper());
            main.postDelayed(locationTimeout, 10_000);
        } catch (SecurityException ignored) { stopLocation(); busy = false; }
        catch (RuntimeException ignored) { stopLocation(); busy = false; }
    }

    private void fetch(Location location, boolean force) {
        if (closed || !hasPermission()) { busy = false; return; }
        if (!Double.isFinite(location.getLatitude()) || !Double.isFinite(location.getLongitude())
                || Math.abs(location.getLatitude()) > 90 || Math.abs(location.getLongitude()) > 180) { busy = false; return; }
        String today = LocalDate.now().toString();
        if (!force && today.equals(prefs.getString("last_attempt_day", ""))) { busy = false; return; }
        prefs.edit().putString("last_attempt_day", today).putLong("last_attempt_at", System.currentTimeMillis()).apply();
        // Discard coordinates after this request. No precise provider or stored location history.
        double latitude = Math.round(location.getLatitude() * 100) / 100d;
        double longitude = Math.round(location.getLongitude() * 100) / 100d;
        network.execute(() -> {
            try {
                if (closed) return;
                URL url = new URL(String.format(Locale.ROOT,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=3",
                        latitude, longitude));
                HttpURLConnection request = (HttpURLConnection) url.openConnection();
                connection = request;
                request.setConnectTimeout(5_000);
                request.setReadTimeout(5_000);
                request.setRequestProperty("Accept", "application/json");
                if (force) {
                    request.setUseCaches(false);
                    request.setRequestProperty("Cache-Control", "no-cache");
                }
                if (request.getResponseCode() != 200) return;
                byte[] bytes;
                try (InputStream stream = request.getInputStream(); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int size;
                    while ((size = stream.read(buffer)) != -1) {
                        output.write(buffer, 0, size);
                        if (output.size() > 65_536) return;
                    }
                    bytes = output.toByteArray();
                }
                if (bytes.length > 65_536) return;
                JSONObject data = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                averageHigh(data); // Validate all three days, including lows, before replacing the cache.
                if (!closed && hasPermission()) prefs.edit().putString("forecast", data.toString())
                        .putLong("fetched_at", System.currentTimeMillis()).putString("fetched_day", LocalDate.now().toString()).apply();
            } catch (Exception ignored) {
                // Permission refusal, no fix, offline, timeout and malformed responses share the month fallback.
            } finally {
                HttpURLConnection request = connection;
                if (request != null) request.disconnect();
                connection = null;
                main.post(() -> { busy = false; if (!closed) changed.run(); });
            }
        });
    }

    static double averageHigh(JSONObject data) throws Exception {
        JSONObject daily = data.getJSONObject("daily");
        JSONArray days = daily.getJSONArray("time");
        JSONArray highs = daily.getJSONArray("temperature_2m_max");
        JSONArray lows = daily.getJSONArray("temperature_2m_min");
        if (days.length() < 3 || highs.length() < 3 || lows.length() < 3) throw new IllegalArgumentException("Missing forecast days");
        double sum = 0;
        LocalDate first = LocalDate.parse(days.getString(0));
        LocalDate today = LocalDate.now();
        if (first.isBefore(today.minusDays(1)) || first.isAfter(today.plusDays(1)))
            throw new IllegalArgumentException("Forecast dates are no longer current");
        for (int i = 0; i < 3; i++) {
            double high = highs.getDouble(i), low = lows.getDouble(i);
            if (!LocalDate.parse(days.getString(i)).equals(first.plusDays(i))
                    || !Double.isFinite(high) || !Double.isFinite(low) || high < low || high > 80 || low < -100)
                throw new IllegalArgumentException("Invalid forecast");
            sum += high;
        }
        return sum / 3;
    }

    private void stopLocation() {
        if (locationTimeout != null) main.removeCallbacks(locationTimeout);
        locationTimeout = null;
        if (listener != null) {
            try { ((LocationManager) context.getSystemService(Context.LOCATION_SERVICE)).removeUpdates(listener); }
            catch (RuntimeException ignored) {}
            listener = null;
        }
    }

    void stopLocationWhenHidden() { if (listener != null) { stopLocation(); busy = false; } }

    @Override public void close() {
        closed = true;
        stopLocation();
        HttpURLConnection request = connection;
        if (request != null) request.disconnect();
        network.shutdownNow();
    }
}
