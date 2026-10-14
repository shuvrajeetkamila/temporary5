package ai.arena.qatar.android;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;

import ai.arena.qatar.QatarGame;
import ai.arena.qatar.boot.CrashJournal;

/**
 * Android entry point.
 *
 * <p>Deliberately minimal about libGDX -- it owns the GL surface and the lifecycle, and the game
 * configures itself -- but no longer minimal about failure. The third install of this game closed
 * immediately and said nothing: the exception that killed it was on the GL thread, and the crash
 * recorder that was supposed to catch it lived inside {@code QatarGame.create()}, which is one of the
 * things that can fail. A recorder is worthless if it shares a fate with the failure it records, so
 * the ordering here matters:
 *
 * <ol>
 *   <li>Install the journal's crash handler <b>before</b> libGDX is created, in {@code onCreate}.</li>
 *   <li>If the last launch left a record, <b>show it first</b> and start the game only when a person
 *       has read it. The record is not cleared by reading it, and not cleared by the game running
 *       either: only the "clear" button clears it. A repeating crash therefore stays on the screen for
 *       as long as it takes to photograph, which the previous design did not allow -- it printed the
 *       trace into the diagnostics panel and deleted it, and the panel dies with the process.</li>
 * </ol>
 *
 * <p>The dialog is a plain platform {@link AlertDialog} with selectable, scrollable text, so the trace
 * can even be copied out of the phone and pasted into a message.
 */
public final class AndroidLauncher extends AndroidApplication {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Before anything can throw: where to write, and who to call when it does.
        CrashJournal.install(getFilesDir());
        CrashJournal.installHandler();
        CrashJournal.note("AndroidLauncher.onCreate");

        if (CrashJournal.hasCrash()) {
            showPreviousCrash();
        } else {
            startGame();
        }
    }

    private void startGame() {
        CrashJournal.note("starting the GL surface");
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useGL30 = true;
        config.useAccelerometer = false;
        config.useCompass = false;
        config.useGyroscope = false;
        config.useImmersiveMode = true;
        config.numSamples = 0;          // FXAA in-shader; MSAA is opt-in per profile.
        config.depth = 16;
        config.stencil = 0;
        config.r = 8;
        config.g = 8;
        config.b = 8;
        config.a = 8;
        // Screen timeout stays under system control: the game never forces unattended GPU use.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        View view = initializeForView(new QatarGame(), config);
        setContentView(view);
    }

    /**
     * The last launch's failure, on the screen, before the game starts.
     *
     * <p>Shown as a dialog rather than as a line in the game's own console because a crash on the first
     * frame takes the console with it: whatever a person needs to read has to be outside the thing that
     * might die.
     */
    private void showPreviousCrash() {
        String crash = CrashJournal.readCrash();
        String trace = CrashJournal.readTrace();

        final StringBuilder text = new StringBuilder();
        text.append("The previous launch stopped. Nothing was deleted: this record stays until you "
                + "clear it.\n\n");
        text.append(crash == null ? "(no details were recorded)\n" : crash);
        if (trace != null && !trace.isEmpty()) {
            String[] lines = trace.split("\n");
            int from = Math.max(0, lines.length - 12);
            text.append("\nWhere it had got to (last breadcrumbs):\n");
            for (int i = from; i < lines.length; i++) {
                text.append("  ").append(lines[i]).append('\n');
            }
        }
        text.append("\nBreadcrumbs are written by the game itself before every launch stage, and the "
                + "crash record by Android's own machinery, so both survive a crash.\n");

        TextView body = new TextView(this);
        body.setText(text.toString());
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        body.setTextIsSelectable(true);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setPadding(dp(16), dp(8), dp(16), dp(8));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("Qatar: The Peninsula \u2014 what happened last time")
                .setView(scroll)
                .setCancelable(false)
                .setPositiveButton("Start anyway", (dialog, which) -> startGame())
                .setNeutralButton("Copy", (dialog, which) -> {
                    copy(text.toString());
                    // Copying must not also start the game: the dialog stays up so the person can see
                    // that the copy happened and then choose.
                    showPreviousCrash();
                })
                .setNegativeButton("Clear record and start", (dialog, which) -> {
                    CrashJournal.clearCrash();
                    startGame();
                })
                .show();
    }

    private void copy(String text) {
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Qatar boot journal", text));
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
            // Copying is a convenience; failing to copy must never be an event of its own.
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Rendering, physics scheduling and streaming all suspend with the activity; see QatarGame.
    }
}
