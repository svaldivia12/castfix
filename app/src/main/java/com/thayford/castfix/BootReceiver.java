package com.thayford.castfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

    /** Runs the fix after every restart; BOOT_COMPLETED is one of the few moments a background app may start a service. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        // BOOT_COMPLETED arrives before the network is necessarily up, and this app cannot do anything
        // without it. The launcher's screen-on resume is the practical second chance, and MainActivity also
        // retries from there; this listener just gets the run started as early as it can.
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        context.startForegroundService(new Intent(context, CastFixService.class)
                .putExtra(CastFixService.EXTRA_TRIGGER, "restart"));
    }
}
