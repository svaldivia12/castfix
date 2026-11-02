package com.thayford.castfix;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

/** Runs CastFixer off the main thread; a foreground service so a run started at boot is not cut short. */
public class CastFixService extends Service {
    static final String EXTRA_TRIGGER = "trigger";
    static final String ACTION_DONE = "com.thayford.castfix.DONE";
    private static final String CHANNEL = "castfix";
    /**
     * Held for the length of a run. Claimed before startForeground so a second startForegroundService cannot
     * post a second notification while doing no work.
     */
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    /** Wait before the single boot-run retry, so it is not racing the same failing conditions. */
    private static final long RETRY_AFTER_MS = 60_000;

    static boolean isRunning() {
        return RUNNING.get();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!RUNNING.compareAndSet(false, true)) {
            // A run is already in progress: the notification that is up belongs to it, so this start must
            // not touch it. Release nothing, because the running thread owns the flag.
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Cast Fix", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_cast_fix)
                .setContentTitle("Cast Fix")
                .setContentText("Getting casting ready")
                .setOngoing(true)
                .build();
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        String trigger = intent == null ? null : intent.getStringExtra(EXTRA_TRIGGER);
        new Thread(() -> {
            String result;
            try {
                result = new CastFixer(this).run(trigger == null ? "unknown" : trigger);
                // A boot run can lose to the network. If the credentials are still not there, one retry a
                // minute later is far cheaper than leaving the TV broken until someone opens the app.
                if (trigger != null && trigger.equals("restart") && needsRetry(result)) {
                    result = retryOnce();
                }
            } catch (Throwable t) {
                // run() reports its own failures, so anything reaching here is a bug. Still end the run
                // properly: leaving the foreground notification up forever is worse than a vague message.
                result = "The fix stopped unexpectedly: " + t;
            } finally {
                RUNNING.set(false);
            }
            String shown = result;
            new Handler(Looper.getMainLooper()).post(() -> finish(shown));
        }, "castfix").start();
        return START_NOT_STICKY;
    }

    /** True when the first attempt failed in a way a later attempt could plausibly fix. */
    private static boolean needsRetry(String result) {
        return result != null && (result.contains("no Wi-Fi") || result.contains("Wireless debugging")
                || result.contains("did not load"));
    }

    /** One more attempt after a pause, for the case where the boot run raced the network. */
    private String retryOnce() {
        try {
            Thread.sleep(RETRY_AFTER_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted before retrying.";
        }
        return new CastFixer(this).run("restart-retry");
    }

    /** Takes the notification down and tells the screen, whatever the run did. */
    private void finish(String result) {
        Toast.makeText(this, "Cast Fix: " + result, Toast.LENGTH_LONG).show();
        sendBroadcast(new Intent(ACTION_DONE).setPackage(getPackageName()));
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
