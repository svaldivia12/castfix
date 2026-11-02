package com.thayford.castfix;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Status, the last runs, and a button to run the fix by hand (e.g. if casting stops without a restart). */
public class MainActivity extends Activity {
    private FixLog log;
    private TextView status;
    private TextView setup;
    private TextView recent;
    private Button run;

    /**
     * What the last signing check found. A cached result is not evidence: the owner can enable the wizard, or
     * the receiver can be replaced, without any run of this app happening - so the screen asks the receiver
     * itself each time it comes to the foreground.
     */
    private ReceiverState.Probe probed = null;
    /** Whether the setup wizard was enabled at the same moment, which the handshake cannot tell. */
    private boolean probedWizardOn = false;
    /** True while the check is in flight, so the screen says so instead of showing a stale verdict. */
    private volatile boolean probing = false;
    /** Set when a probe completes after the activity is gone, so the next resume re-checks. */
    private volatile boolean probeStale = true;
    /**
     * Set between asking for a run and the service reporting that it started. Without it, a refresh landing
     * in that gap sees no run, overwrites "Fixing casting…" with the previous result, and re-probes a device
     * that is about to change under it. Bounded by {@link #START_GRACE_MS} so a service that never starts
     * cannot leave the screen stuck on "Fixing casting…" forever.
     */
    private long startingAt = 0;
    /** How long to treat a requested run as running before deciding it never started. */
    private static final long START_GRACE_MS = 20_000;
    /** Guards the receiver registration, which throws if it happens twice without an unregister. */
    private boolean registered = false;

    private final BroadcastReceiver done = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // A fix just changed the device, so whatever was probed before is out of date.
            startingAt = 0;
            probeStale = true;
            refresh();
            if (!CastFixService.isRunning()) probe();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log = new FixLog(this);
        int pad = dp(48);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(12, 18, 24));

        root.addView(text("Cast Fix", 34, Color.WHITE));
        root.addView(text("Keeps phones casting on this TV while the setup wizard stays off, so your own "
                + "launcher can own Home. It runs by itself after every restart.",
                16, Color.rgb(170, 180, 190)));
        status = text("", 20, Color.WHITE);
        status.setPadding(0, dp(24), 0, 0);
        root.addView(status);
        setup = text("", 14, Color.rgb(170, 180, 190));
        root.addView(setup);

        // The label tracks the screen's state, and only one of those states is pressable, so a greyed button
        // with "Fixed" on it is the whole answer to "is there anything for me to do?".
        run = new Button(this);
        run.setText(LABEL_ACTION);
        run.setOnClickListener(v -> start("button"));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(20);
        buttonParams.gravity = Gravity.START;
        root.addView(run, buttonParams);

        TextView heading = text("Recent runs", 12, Color.rgb(120, 130, 140));
        heading.setPadding(0, dp(24), 0, 0);
        root.addView(heading);
        recent = text("", 12, Color.rgb(140, 150, 160));
        recent.setTypeface(Typeface.MONOSPACE);
        recent.setPadding(0, dp(6), 0, 0);
        root.addView(recent);
        setContentView(root);
        run.requestFocus();

        // From a computer: am start -n com.thayford.castfix/.MainActivity --ez run true
        if (savedInstanceState == null && getIntent().getBooleanExtra("run", false)) start("adb");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // An intent delivered to an activity that is already visible never reaches onCreate, so this is the
        // only path that makes `am start ... --ez run true` work while the app is on screen - which is exactly
        // when someone debugging would use it.
        if (intent.getBooleanExtra("run", false) && !CastFixService.isRunning()) start("adb");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!registered) {
            registerReceiver(done, new IntentFilter(CastFixService.ACTION_DONE), Context.RECEIVER_NOT_EXPORTED);
            registered = true;
        }
        refresh();
        // Opening the app is the moment the owner is looking, so it is also the moment to put the device back
        // if a run died mid-way. This costs one adb round trip and only happens when a run left a record.
        if (!CastFixService.isRunning() && new CastFixer(this).needsRecovery()) {
            start("recovery");
            return;
        }
        probe();
    }

    /**
     * Asks the receiver whether it can sign, off the main thread. This is the only check that describes the
     * device now rather than what the last run recorded, and it is what decides whether the button is usable.
     */
    private void probe() {
        if (probing || CastFixService.isRunning()) return;
        // A result taken before this resume is only trusted if nothing has changed since, which the flag
        // tracks: set on start, cleared by every completed probe.
        if (probed != null && !probeStale) {
            refresh();
            return;
        }
        probing = true;
        probeStale = false;
        refresh();
        new Thread(() -> {
            ReceiverState.Probe found;
            boolean wizardOn;
            try {
                found = ReceiverState.probe(ReceiverState.CAST_PORT, ReceiverState.PROBE_TIMEOUT_MS);
                // Signing is only half the answer. On this build the setup wizard being enabled is the
                // crash-loop precondition, and it is not visible in the handshake at all, so it has to be
                // asked about separately or the screen would call the device healthy while it is at risk.
                wizardOn = wizardPackageEnabled();
            } finally {
                probing = false;
            }
            final ReceiverState.Probe result = found;
            final boolean wizard = wizardOn;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    // The activity went away while the check ran; mark it stale so the next resume re-checks
                    // instead of showing a verdict the owner never saw applied.
                    probeStale = true;
                    return;
                }
                probed = result;
                probedWizardOn = wizard;
                refresh();
            });
        }, "castfix-probe").start();
    }

    /**
     * Whether the setup wizard package is enabled for this user. Read from the package manager rather than
     * through adb, because the screen must work before the app has ever been paired.
     */
    private boolean wizardPackageEnabled() {
        try {
            android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageInfo(CastFixer.SETUP, 0);
            return info.applicationInfo != null && info.applicationInfo.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false; // no wizard on this device: nothing to be at risk from
        }
    }

    @Override
    protected void onPause() {
        if (registered) {
            unregisterReceiver(done);
            registered = false;
        }
        super.onPause();
    }

    /** True while a run was requested but the service has not visibly taken over yet. */
    private boolean starting() {
        return startingAt > 0 && SystemClock.elapsedRealtime() - startingAt < START_GRACE_MS;
    }

    /** The one enabled state: nothing is running or being checked, and the device is not provably fine. */
    static final String LABEL_ACTION = "Fix casting now";
    /** A check is in flight; a press now would race the answer. */
    static final String LABEL_CHECKING = "Checking…";
    /** A run is in progress. */
    static final String LABEL_FIXING = "Fixing…";
    /** Confirmed working: signing, and the setup wizard off. */
    static final String LABEL_FIXED = "Fixed";

    /**
     * The label the button shows, given the three states the screen already knows.
     *
     * <p>{@code busy} is tested first: a run can begin while a check is still in flight (an adb intent does
     * not wait for the probe), and "Fixing…" describes what is actually happening then. {@code probing} comes
     * next and still outranks {@code healthy}, which is the point of it: while the answer is unknown, the
     * previous verdict must not be shown as if it were current.
     */
    static String labelFor(boolean probing, boolean busy, boolean healthy) {
        if (busy) return LABEL_FIXING;
        if (probing) return LABEL_CHECKING;
        if (healthy) return LABEL_FIXED;
        return LABEL_ACTION;
    }

    /** True only for the state where running the fix would actually do something. */
    static boolean enabledFor(boolean probing, boolean busy, boolean healthy) {
        return !busy && !probing && !healthy;
    }

    /** Sets the button's label and its enabled state together, so the two cannot drift apart. */
    private void showButton(String label, boolean enabled) {
        run.setText(label);
        run.setEnabled(enabled);
    }

    private void start(String trigger) {
        if (CastFixService.isRunning() || starting()) {
            // The service refuses a second concurrent run, so say so instead of claiming to work.
            status.setText("A fix is already running.");
            return;
        }
        startingAt = SystemClock.elapsedRealtime();
        // Claim the button before the service reports back, so a refresh in the gap cannot re-enable it.
        showButton(LABEL_FIXING, false);
        status.setText("Fixing casting… this takes up to three minutes.");
        try {
            startForegroundService(new Intent(this, CastFixService.class)
                    .putExtra(CastFixService.EXTRA_TRIGGER, trigger));
        } catch (Exception e) {
            // Background-start limits can refuse the service. Say so, and put the action label back rather
            // than leaving "Fixing…" on a button whose run never began.
            startingAt = 0;
            status.setText("Could not start the fix. Open the app and try again.");
            showButton(LABEL_ACTION, true);
        }
    }

    private void refresh() {
        boolean busy = starting() || CastFixService.isRunning();
        CastFixer fixer = new CastFixer(this);
        // "Casting works" is now something the app just checked, not something it remembers from its last run.
        // Both halves matter: the receiver must be signing, and the setup wizard must be off, because an
        // enabled wizard is the crash-loop precondition on this build and does not show up in the handshake.
        boolean healthy = !busy && !probing
                && probed == ReceiverState.Probe.SIGNED && !probedWizardOn;
        // The label and the enabled flag are decided together, from the same three booleans, so the button
        // can never say one thing while behaving as another. Only the "needs fixing" state is pressable.
        showButton(labelFor(probing, busy, healthy), enabledFor(probing, busy, healthy));
        String recovery = fixer.recoveryNote();
        if (!recovery.isEmpty()) {
            status.setText(recovery);
        } else if (busy) {
            status.setText("Fixing casting… this takes up to three minutes.");
        } else if (probing) {
            status.setText("Checking casting…");
        } else if (healthy) {
            status.setText("Casting works. Nothing to do.");
        } else if (probedWizardOn) {
            // The most serious case, and the one the handshake cannot see: the receiver may be signing fine
            // while the TV is in the state that crash-loops system_server.
            status.setText("The setup wizard is on. That crashes this TV until it is switched off.");
        } else if (probed == ReceiverState.Probe.UNREACHABLE) {
            status.setText("The Cast receiver is not answering. Restart the TV.");
        } else if (probed == ReceiverState.Probe.UNSIGNED) {
            status.setText("Casting needs fixing.");
        } else {
            // probed is null only before the first check returns.
            status.setText("Checking casting…");
        }
        boolean granted = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        // "Setup" alone reads as the setup wizard this app exists to keep off, so name what is meant.
        String detail = "adb key: " + (log.isPaired() ? "paired" : "NOT paired")
                + " · settings access: " + (granted ? "granted" : "MISSING — grant it from a computer, see README");
        if (!healthy) detail += "\n" + log.lastRunSummary();
        setup.setText(detail);
        recent.setText(String.join("\n", log.tail(14)));
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
