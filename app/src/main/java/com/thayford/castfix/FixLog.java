package com.thayford.castfix;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Run log (files/castfix.log, also logcat tag CastFix) and the last result shown on screen. */
final class FixLog {
    private static final String TAG = "CastFix";
    private static final int KEEP_LINES = 300;
    /** SimpleDateFormat is not thread-safe and a run logs from its worker thread while the screen reads. */
    private final SimpleDateFormat stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private final File file;
    private final SharedPreferences prefs;
    /** Mirrors the file, so a line costs an append rather than a read plus a rewrite of the whole log. */
    private final List<String> lines = new ArrayList<>();
    private boolean loaded = false;

    FixLog(Context context) {
        file = new File(context.getFilesDir(), "castfix.log");
        prefs = context.getSharedPreferences("status", Context.MODE_PRIVATE);
    }

    synchronized void add(String line) {
        Log.i(TAG, line);
        load();
        lines.add(stamp.format(new Date()) + "  " + line);
        while (lines.size() > KEEP_LINES) lines.remove(0);
        // Written through a temp file and renamed: a kill mid-write must not truncate the log the owner needs
        // to diagnose exactly that kind of failure.
        File temp = new File(file.getParentFile(), "castfix.log.tmp");
        try {
            Files.write(temp.toPath(), lines, StandardCharsets.UTF_8);
            try {
                Files.move(temp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Log.w(TAG, "could not write the log", e);
        }
    }

    /** Reads the file once into memory; every later line is an append. */
    private void load() {
        if (loaded) return;
        loaded = true;
        if (!file.exists()) return;
        try {
            lines.addAll(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "could not read the log", e);
        }
    }

    /** Logs the outcome of a run and keeps it for the screen; returns it for chaining. */
    String result(String message) {
        return result(message, Outcome.ATTENTION);
    }

    /**
     * Logs the outcome of a run, records how far this run got so the screen can tell whether casting still
     * works, and returns the message for chaining.
     */
    String result(String message, Outcome outcome) {
        add(message);
        prefs.edit()
                .putString("last", message)
                .putLong("when", System.currentTimeMillis())
                .putString("outcome", outcome.name())
                // Time since boot when the run ended. It resets on a reboot, so a later value smaller than
                // this one means the device restarted and the receiver lost its credentials with it.
                .putLong("elapsedAtResult", SystemClock.elapsedRealtime())
                .apply();
        return message;
    }

    /** How the last run left the device, as far as the run could tell. */
    enum Outcome {
        /** Casting was confirmed working: credentials in memory and the setup wizard off. */
        HEALTHY,
        /** Something needs doing, or the run could not be sure. Either way the owner may run it again. */
        ATTENTION
    }

    /**
     * True when the last run confirmed casting works and the device has not restarted since.
     *
     * <p>This is <em>not</em> what enables the button: a record cannot notice the owner enabling the setup
     * wizard or a receiver dying, so the screen asks the receiver directly instead (see
     * {@code MainActivity.probe()}). This is only used to describe the past, and to warn in the run summary
     * that a reboot has since thrown the credentials away.
     */
    boolean castingConfirmedWorking() {
        return castingConfirmedWorking(prefs.getString("outcome", ""), prefs.getLong("elapsedAtResult", 0),
                SystemClock.elapsedRealtime());
    }

    /**
     * The decision behind {@link #castingConfirmedWorking()}, split out so it can be tested without Android.
     *
     * @param outcome        the recorded outcome name, or ""
     * @param elapsedAtResult uptime when that outcome was recorded, or 0
     * @param now            uptime now
     */
    static boolean castingConfirmedWorking(String outcome, long elapsedAtResult, long now) {
        if (!Outcome.HEALTHY.name().equals(outcome)) return false;
        // now < elapsedAtResult means the uptime counter restarted, i.e. the TV rebooted and the receiver lost
        // its credentials with it.
        return elapsedAtResult > 0 && now >= elapsedAtResult;
    }

    /** True when the recorded outcome predates a reboot of this device. */
    static boolean restartedSince(long elapsedAtResult, long now) {
        return elapsedAtResult > 0 && now < elapsedAtResult;
    }

    /**
     * The last run as one line for the screen: when it ran, what it concluded, and a warning if the device
     * restarted since (a reboot throws the receiver's credentials away).
     */
    String lastRunSummary() {
        long when = prefs.getLong("when", 0);
        if (when == 0) return "No fix has run yet.";
        String stamp = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(new Date(when));
        String note = "";
        long then = prefs.getLong("elapsedAtResult", 0);
        if (restartedSince(then, SystemClock.elapsedRealtime())) {
            note = " The TV has restarted since, so casting needs fixing again.";
        }
        return "Last fix: " + stamp + " — " + prefs.getString("last", "") + note;
    }

    void setPaired() {
        prefs.edit().putBoolean("paired", true).apply();
    }

    boolean isPaired() {
        return prefs.getBoolean("paired", false);
    }

    synchronized List<String> tail(int count) {
        load();
        return new ArrayList<>(lines.subList(Math.max(0, lines.size() - count), lines.size()));
    }
}
