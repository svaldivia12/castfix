package com.thayford.castfix;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.provider.Settings;

import java.net.Inet4Address;

/**
 * On Chromecast with Google TV the Cast receiver gets its device credentials only from the setup wizard
 * (setupwraith's CastInfoProvider), and the wizard has to stay disabled for a replacement launcher to own
 * Home. The receiver keeps the credentials in memory once loaded, so after each boot: switch the wizard on
 * until the receiver has loaded them, then switch it and the stock launcher off again.
 *
 * <p>On Google TV Streamer (kirkwood, Google TV 14, UTTK.260317.003) leaving the wizard enabled is not a
 * neutral state: its RecoveryActivity claims Home and system_server crash-loops inside Task.moveToBack
 * roughly every 47 s, taking setupwraith and SystemUI down with it. So the wizard is enabled for the
 * shortest possible window and is put back on every exit path, including a killed process (see
 * {@link RunJournal}).
 */
final class CastFixer {
    static final String SETUP = "com.google.android.tungsten.setupwraith";
    static final String STOCK_HOME = "com.google.android.apps.tv.launcherx";
    static final String CAST_RECEIVER = "com.google.android.apps.mediashell";

    private static final String ADB_WIFI = "adb_wifi_enabled";
    private static final String ADB_TIMEOUT = "adb_allowed_connection_time";
    private static final String KEEP_DEBUGGING = "castfix_keep_debugging";
    /**
     * How long to wait, after enabling the wizard, for the receiver to pick the credentials up.
     *
     * <p>The receiver retries at most once a minute, so the retry that can see the provider may be a minute
     * away; on a cold boot measured on kirkwood it was 31 s. The verdict comes from the handshake rather than
     * the log, because at exactly this moment the log lies: a retry that failed one second before the wizard
     * was enabled is still inside the window, and truncating both to whole seconds makes them indistinguishable.
     */
    private static final long CREDS_WAIT_MS = 150_000;
    /** How often the handshake is retried while waiting for the receiver to pick the provider up. */
    private static final long PROBE_EVERY_MS = 5_000;
    /**
     * How long to wait for Wi-Fi before giving up. A run at boot can start before the network is up: measured
     * on kirkwood the radio associated well after BOOT_COMPLETED was delivered.
     */
    private static final long CONNECT_TIMEOUT_MS = 120_000;

    private final Context context;
    private final FixLog log;
    private final RunJournal journal;

    CastFixer(Context context) {
        this.context = context;
        this.log = new FixLog(context);
        this.journal = new RunJournal(context);
    }

    /** Runs the whole fix and returns a one-line result for the screen. */
    String run(String trigger) {
        log.add("--- run (" + trigger + ")");
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            return log.result("Cast Fix is missing the settings permission. Grant it once from a computer; "
                    + "see Setup in the README.");
        }
        // A run started at boot can find the network still coming up. Waiting is cheaper than failing and
        // leaving the TV without credentials until someone opens the app by hand.
        String ip = waitForWifi(CONNECT_TIMEOUT_MS);
        if (ip == null) return log.result("This TV has no Wi-Fi, so it cannot be reached. Nothing was changed.");
        ContentResolver resolver = context.getContentResolver();
        boolean debuggingWasOn = Settings.Global.getInt(resolver, ADB_WIFI, 0) == 1;
        Settings.Global.putInt(resolver, ADB_WIFI, 1);
        // Only recorded as ours when it was not already 0, so the uninstall path can put it back instead of
        // silently converting the owner's (and every other paired key's) expiry to "never".
        boolean timeoutWasSet = setAdbTimeoutOnce(resolver);
        try (Shell shell = new Shell(context, ip, log)) {
            if (!shell.connect()) return log.result("Could not reach Wireless debugging. Turn it on and fix again.");
            // Recovery before any new work: putting the device back matters more than fixing it again, and a
            // wizard left on by a dead run is the one state that must not survive another run. It also hands
            // Home back, so what is read afterwards is the launcher the owner actually chose.
            recover(shell);
            String homeBefore = HomeState.read(shell).component;
            journal.begin(trigger, debuggingWasOn, timeoutWasSet, homeBefore);
            String result;
            FixLog.Outcome outcome = FixLog.Outcome.ATTENTION;
            boolean finished = false;
            try {
                Outcome fixResult = fix(shell, homeBefore);
                result = fixResult.message;
                outcome = fixResult.outcome;
                // Only now is the wizard provably off and Home back, so only now is the record safe to clear.
                journal.finish();
                finished = true;
            } finally {
                // Any exit from here - including an exception - must not leave the wizard on. The guard runs
                // only when the run did not complete, so a finished run does not repeat its own work, and a
                // failed one keeps the record so the next start recovers.
                if (!finished) {
                    if (journal.setupEnabledByUs() || !hasPackage(
                            shell.run("pm list packages -d --user 0"), SETUP)) {
                        disableWizard(shell);
                    }
                }
            }
            return log.result(result, outcome);
        } catch (Exception e) {
            // The journal stays active on purpose: the next start recovers.
            log.add("error: " + e);
            return log.result("The fix stopped with an error: " + e.getMessage());
        } finally {
            // Troubleshooting: `settings put global castfix_keep_debugging 1` keeps it on to read the logs.
            if (!debuggingWasOn && Settings.Global.getInt(resolver, KEEP_DEBUGGING, 0) != 1) {
                Settings.Global.putInt(resolver, ADB_WIFI, 0);
                log.add("Wireless debugging switched off again");
            }
        }
    }

    /** The fix proper: a one-line result for the screen, plus how the device was left. */
    private static final class Outcome {
        final String message;
        final FixLog.Outcome outcome;

        Outcome(String message, FixLog.Outcome outcome) {
            this.message = message;
            this.outcome = outcome;
        }

        static Outcome working(String message) {
            return new Outcome(message, FixLog.Outcome.HEALTHY);
        }

        static Outcome attention(String message) {
            return new Outcome(message, FixLog.Outcome.ATTENTION);
        }
    }

    /** The fix proper. Never logs the result, so the caller can record it once. */
    private Outcome fix(Shell shell, String homeBefore) throws Exception {
        String disabled = shell.run("pm list packages -d --user 0");
        if (!packageExists(shell, SETUP)) {
            return Outcome.attention("This TV has no setup wizard package, so there is nothing to fix.");
        }
        if (!packageExists(shell, CAST_RECEIVER)) {
            return Outcome.attention("This TV has no Cast receiver, so it is not a Google TV device.");
        }
        boolean setupOn = !hasPackage(disabled, SETUP);
        Outcome fine = alreadyFine(shell, setupOn);
        if (fine != null) return fine;

        if (setupOn) {
            // Somebody else left it on: it still has to come off when this run ends.
            log.add("setup wizard was already on and the receiver cannot sign yet; waiting for the receiver");
            journal.setupEnabled();
        } else {
            boolean stockHomeWasOff = hasPackage(disabled, STOCK_HOME) && packageExists(shell, STOCK_HOME);
            // Stock launcher first. Measured on kirkwood the resolver ranks launcherx (priority 2) above
            // setupwraith/.RecoveryActivity (priority 1), so with nothing else pinned this keeps a Home press
            // away from the wizard. Belt-and-braces, not a guarantee: where setupwraith's MainActivity is
            // enabled it outranks both at priority 4. The protection that holds is the wizard going off again
            // before this run ends.
            if (stockHomeWasOff) {
                journal.markStockHomeWasOff();
                if (enablePackage(shell, STOCK_HOME)) {
                    log.add("stock launcher on");
                } else {
                    log.add("could not enable the stock launcher; carrying on with the wizard alone");
                }
            }
            if (!enablePackage(shell, SETUP)) {
                restoreHome(shell, homeBefore);
                return Outcome.attention("Could not switch the setup wizard on. Nothing was changed.");
            }
            log.add("setup wizard on, waiting for the Cast receiver");
            journal.setupEnabled();
        }

        long outcome = awaitSigner(shell);
        // Close the crash-loop window as early as possible, not after the rest of the run.
        boolean wizardOff = disableWizard(shell);
        restoreHome(shell, homeBefore);
        if (outcome == LOADED) {
            log.add("credentials loaded");
            // A wizard left on would make this the crash-loop state, whatever the credentials say.
            return wizardOff
                    ? Outcome.working("Credentials loaded and the setup wizard is off.")
                    : Outcome.attention("Credentials loaded, but the setup wizard is still on. Try again.");
        }
        return Outcome.attention("The receiver did not load credentials in time. Try again.");
    }

    /**
     * Answers "is there anything to do?" from the handshake, before any waiting.
     *
     * <p>A signed reply is proof the receiver holds credentials, so a wizard left on can simply be switched
     * off and a healthy device is recognised in seconds instead of the two minutes a log window costs. This
     * is the same question the old code asked with {@code ReceiverState.watch}, whose window existed only
     * because the log cannot be trusted - the handshake makes that whole wait unnecessary on the happy path.
     *
     * @param setupOn whether the wizard is currently enabled
     * @return the result to report, or null when the device does need the wizard
     */
    private Outcome alreadyFine(Shell shell, boolean setupOn) throws Exception {
        ReceiverState.Probe probe = ReceiverState.probe(shell, log);
        if (probe != ReceiverState.Probe.SIGNED) {
            // Unsigned means the credentials are genuinely missing. Unreachable means the receiver is not
            // serving at all, which the wizard cannot fix either: say so rather than reopening it.
            if (probe == ReceiverState.Probe.UNREACHABLE) {
                log.add("device-auth port not answering and the wizard is "
                        + (setupOn ? "on" : "off"));
            }
            return null;
        }
        if (setupOn) {
            // Not "already fine": on kirkwood an enabled wizard is the crash-loop precondition. This is a
            // repair, and the message must read as one.
            log.add("receiver signs, but the setup wizard was on; switching it off");
            boolean wizardOff = disableWizard(shell);
            return wizardOff
                    ? Outcome.working("The setup wizard was on and is now off. Credentials were already loaded.")
                    : Outcome.attention("The setup wizard is still on. Try again, or turn Developer options on.");
        }
        log.add("receiver already signs; nothing to do");
        return Outcome.working("Credentials were already loaded.");
    }

    /** Waits for the receiver to pick credentials up after the wizard came on, and reports whether it did.
     *
     * <p>The verdict comes from the device-auth handshake, not from the log. The log cannot be trusted here:
     * on a cold boot the retry that failed one second before the wizard was enabled is still inside the
     * window, and because both timestamps are truncated to whole seconds it is indistinguishable from a
     * fresh failure. That is exactly what made a boot run give up 2 s after enabling the wizard and report
     * "did not load" while the receiver was still 29 s from its next attempt.
     *
     * <p>The handshake also answers the other question the log never could: a receiver that already holds its
     * credentials signs immediately, without logging anything at all.
     *
     * @return {@link #LOADED} once a signed reply comes back, or {@link #FAILED} if none does in time
     */
    private long awaitSigner(Shell shell) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + CREDS_WAIT_MS;
        int attempts = 0;
        while (SystemClock.elapsedRealtime() < deadline) {
            ReceiverState.Probe probe = ReceiverState.probe(shell, log);
            attempts++;
            if (probe == ReceiverState.Probe.SIGNED) {
                log.add("receiver signed on attempt " + attempts + " after enabling the wizard");
                return LOADED;
            }
            if (probe == ReceiverState.Probe.UNREACHABLE) {
                // The port being closed is not the same as "no credentials"; keep trying, the receiver may
                // still be starting on a device that just booted.
                log.add("device-auth port not answering yet (attempt " + attempts + ")");
            }
            // Also record what the log says, for diagnosis only: it must not decide the outcome.
            log.add("attempt " + attempts + ": " + ReceiverState.describe(shell));
            Thread.sleep(PROBE_EVERY_MS);
        }
        log.add("receiver still not signing after " + (CREDS_WAIT_MS / 1000) + " s");
        return FAILED;
    }

    /** Credentials confirmed in memory. */
    private static final long LOADED = 1;
    /** The receiver never signed within the wait. */
    private static final long FAILED = 0;

    /**
     * Puts the device back after a run that did not finish. Order is not negotiable: the wizard goes off
     * first, because leaving it on with the stock launcher on is the kirkwood crash-loop state.
     */
    private void recover(Shell shell) throws Exception {
        if (!journal.isActive()) return;
        log.add("previous run (" + journal.trigger() + ") did not finish; putting things back first");
        // Unconditional, and the first thing done: a wizard left on by any means is the crash-loop state,
        // whatever the journal managed to write before the process died.
        disableWizard(shell);
        if (journal.stockHomeWasOff() && packageExists(shell, STOCK_HOME)) {
            if (!enablePackage(shell, STOCK_HOME)) log.add("could not re-enable the stock launcher");
        }
        String homeBefore = journal.homeBefore();
        if (!homeBefore.isEmpty() && new HomeState(homeBefore).ownedByReplacement()) {
            HomeState now = HomeState.read(shell);
            boolean homeIsWrong = !now.component.equals(homeBefore);
            // Restore the owner's launcher whenever Home is not already there, including when the stock
            // launcher got it: a run killed between enabling the stock launcher and restoring Home leaves
            // exactly that, and it is not the state to return to.
            if (homeIsWrong) {
                shell.run("am start -n " + homeBefore);
                shell.run("cmd package set-home-activity --user 0 " + homeBefore);
                HomeState after = HomeState.read(shell);
                if (after.component.equals(homeBefore)) {
                    log.add("Home restored to " + homeBefore);
                    // The stock launcher was only on for the fix; put it back as the owner had it.
                    if (journal.stockHomeWasOff()) {
                        shell.run("pm disable-user --user 0 " + STOCK_HOME);
                        log.add("stock launcher off again");
                    }
                } else {
                    log.add("Home went to " + (after.found() ? after.component : "(none)")
                            + "; leaving the stock launcher on");
                }
            }
        }
        if (journal.adbTimeoutWasSet()) {
            // Left set on purpose: the next run still needs it, and the uninstall path restores it.
            log.add("adb_allowed_connection_time was set by the unfinished run; uninstall restores it");
        }
        journal.finish();
        log.add("recovered");
    }

    /** Disables the wizard and confirms it; a silent failure here is what the crash loop grows out of. */
    private boolean disableWizard(Shell shell) throws Exception {
        Shell.Result result = shell.runChecked("pm disable-user --user 0 " + SETUP);
        // Confirmed from the package manager, not from the exit status: adb can return before the command's
        // status does, and what matters is the state the device ended up in.
        if (hasPackage(shell.run("pm list packages -d --user 0"), SETUP)) {
            log.add("setup wizard off");
            return true;
        }
        log.add("could not disable the setup wizard"
                + (result.ok() ? "" : ": " + result.problem()));
        return false;
    }

    private boolean enablePackage(Shell shell, String pkg) throws Exception {
        if (!packageExists(shell, pkg)) {
            log.add(pkg + " is not installed on this TV");
            return false;
        }
        Shell.Result result = shell.runChecked("pm enable --user 0 " + pkg);
        // Same reasoning as disableWizard: the state is the evidence, the exit status is a hint.
        if (!hasPackage(shell.run("pm list packages -d --user 0"), pkg)) return true;
        log.add("pm enable " + pkg + " did not take effect"
                + (result.ok() ? "" : ": " + result.problem()));
        return false;
    }

    /**
     * Puts the replacement launcher back as Home, or leaves the stock one on if that fails. Verified by asking
     * the resolver afterwards, not by reading window focus: on kirkwood `dumpsys window` prints
     * `mCurrentFocus=null`, so the old check never fired and the launcher was disabled without being brought
     * forward first.
     */
    private void restoreHome(Shell shell, String homeBefore) throws Exception {
        HomeState before = new HomeState(homeBefore);
        if (!before.ownedByReplacement()) {
            log.add("no replacement launcher owned Home; leaving the stock launcher on");
            return;
        }
        // Bring it up before disabling the stock one, so a Home press during the switch has somewhere to go.
        HomeState now = HomeState.read(shell);
        if (!now.component.equals(before.component)) shell.run("am start -n " + before.component);
        shell.run("pm disable-user --user 0 " + STOCK_HOME);
        shell.run("cmd package set-home-activity --user 0 " + before.component);
        HomeState after = HomeState.read(shell);
        if (after.component.equals(before.component)) {
            log.add("Home is " + before.component + " again");
            return;
        }
        log.add("Home went to " + (after.found() ? after.component : "(none)")
                + "; stock launcher switched back on");
        if (packageExists(shell, STOCK_HOME)) enablePackage(shell, STOCK_HOME);
        // "Not the replacement launcher" is not the only bad outcome: Home on the wizard is the crash loop.
        if (after.ownedByWizard()) disableWizard(shell);
    }

    /**
     * Turns off the 7-day adb key expiry, but only when it is not already off, so the uninstall path can tell
     * whether this app is the reason. Returns true if this run set it.
     */
    private boolean setAdbTimeoutOnce(ContentResolver resolver) {
        long current = Settings.Global.getLong(resolver, ADB_TIMEOUT, -1L);
        if (current == 0) return false;
        Settings.Global.putLong(resolver, ADB_TIMEOUT, 0L);
        return true;
    }

    private boolean packageExists(Shell shell, String pkg) throws Exception {
        return hasPackage(shell.run("pm list packages --user 0"), pkg);
    }

    /** The Wi-Fi IPv4 address once Wi-Fi is up, or null on timeout. */
    private String waitForWifi(long timeoutMs) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            Network network = cm.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
            LinkProperties link = network == null ? null : cm.getLinkProperties(network);
            if (caps != null && link != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                for (LinkAddress address : link.getLinkAddresses()) {
                    if (address.getAddress() instanceof Inet4Address) return address.getAddress().getHostAddress();
                }
            }
            SystemClock.sleep(1_000);
        }
        return null;
    }

    private static boolean hasPackage(String packageList, String pkg) {
        for (String line : packageList.split("\n")) {
            if (line.trim().equals("package:" + pkg)) return true;
        }
        return false;
    }

    /** True when a run left the device in a state it did not finish undoing. */
    boolean needsRecovery() {
        return journal.isActive();
    }

    /** A one-line description of the unfinished run, for the status screen. */
    String recoveryNote() {
        if (!journal.isActive()) return "";
        // Headline form: the screen shows this instead of the normal status, so it has to read as a state.
        return "A fix was interrupted. The setup wizard may still be on until the next fix runs.";
    }
}
