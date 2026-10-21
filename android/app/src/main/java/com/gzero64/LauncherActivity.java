package com.gzero64;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Play / Settings / Change folder. Copies the player's dumps into the app folder and starts the game. */
public final class LauncherActivity extends Activity {
    /** Set by the quick menu's restart: start again as soon as the files check out. */
    static final String EXTRA_RESTART = "restart";
    private static final int PICK_FOLDER = 1;
    private static final long RESTART_DELAY_MS = 500;
    private static final long AUTO_START_DELAY_MS = 1500;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoStart = new Runnable() {
        @Override public void run() { startGame(); }
    };
    private TextView status;
    private Button chooseButton;
    private boolean ready;
    private boolean restartRequested;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restartRequested = savedInstanceState == null && getIntent().getBooleanExtra(EXTRA_RESTART, false);
        showMenu("");
        if (GameFiles.installed(RuntimeFiles.appDir(this))) {
            showReady(true);
        }
    }

    private void showMenu(String message) {
        handler.removeCallbacks(autoStart);
        ready = false;
        setContentView(R.layout.activity_launcher);
        View panel = findViewById(R.id.launcher_panel);
        int width = Math.min(getResources().getConfiguration().screenWidthDp - 48, 560);
        panel.getLayoutParams().width = (int) (width * getResources().getDisplayMetrics().density);
        TextView credits = findViewById(R.id.launcher_credits);
        credits.setText(Html.fromHtml(getString(R.string.launcher_credits,
            BuildConfig.VERSION_NAME, BuildConfig.BUILD_COMMIT_FULL), Html.FROM_HTML_MODE_LEGACY));
        credits.setMovementMethod(LinkMovementMethod.getInstance());
        status = findViewById(R.id.launcher_status);
        status.setText(message);
        status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        chooseButton = findViewById(R.id.choose_game);
        chooseButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                handler.removeCallbacks(autoStart);
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(intent, PICK_FOLDER);
            }
        });
        findViewById(R.id.open_settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                handler.removeCallbacks(autoStart);
                if (ready) status.setText(R.string.launcher_ready_status);
                startActivity(new Intent(LauncherActivity.this, SettingsActivity.class));
            }
        });
        findViewById(R.id.play_game).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { startGame(); }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FOLDER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        importFolder(data.getData());
    }

    private void importFolder(final Uri tree) {
        chooseButton.setEnabled(false);
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.launcher_checking);
        worker.execute(new Runnable() {
            @Override public void run() {
                String message;
                try {
                    File dir = RuntimeFiles.appDir(LauncherActivity.this);
                    GameFiles.Scan scan = GameFiles.importFrom(getContentResolver(), tree, dir);
                    message = describe(scan, GameFiles.installed(dir));
                } catch (IOException | RuntimeException exception) {
                    Log.w("GZero64", "Folder import failed", exception);
                    message = getString(R.string.launcher_unreadable);
                }
                final String result = message;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (GameFiles.installed(RuntimeFiles.appDir(LauncherActivity.this))) {
                            // Never start on its own after choosing a folder; Play does.
                            showReady(false);
                            if (!result.isEmpty()) status.setText(result);
                        } else {
                            showMenu(result);
                        }
                    }
                });
            }
        });
    }

    /** Missing files are named; a complete install says nothing extra. */
    private String describe(GameFiles.Scan scan, boolean complete) {
        if (complete) return "";
        List<String> missing = new ArrayList<>();
        if (scan.rom == null) missing.add(getString(scan.japaneseRom ? R.string.file_rom_japanese : R.string.file_rom));
        if (scan.disk == null) missing.add(getString(scan.japaneseDisk ? R.string.file_disk_japanese : R.string.file_disk));
        if (scan.ipl == null) missing.add(getString(R.string.file_ipl));
        return getString(R.string.launcher_missing, String.join("\n", missing));
    }

    /** Everything is in place: offer Play, or start after a short, cancellable delay. */
    private void showReady(boolean allowAutoStart) {
        ready = true;
        ((TextView) findViewById(R.id.launcher_heading)).setText(R.string.launcher_ready_title);
        ((TextView) findViewById(R.id.launcher_description)).setText(R.string.launcher_ready_description);
        chooseButton.setEnabled(true);
        chooseButton.setText(R.string.launcher_change_folder);
        Button play = findViewById(R.id.play_game);
        play.setVisibility(View.VISIBLE);
        play.requestFocus();
        status.setVisibility(View.VISIBLE);
        if (restartRequested) {
            restartRequested = false;
            status.setText(R.string.launcher_restarting);
            handler.postDelayed(autoStart, RESTART_DELAY_MS);
        } else if (allowAutoStart && LauncherSettings.autoStart(this)) {
            status.setText(R.string.launcher_auto_starting);
            handler.postDelayed(autoStart, AUTO_START_DELAY_MS);
        } else {
            status.setText(R.string.launcher_ready_status);
        }
    }

    private void startGame() {
        handler.removeCallbacks(autoStart);
        if (isFinishing() || isDestroyed()) return;
        findViewById(R.id.play_game).setEnabled(false);
        worker.execute(new Runnable() {
            @Override public void run() {
                boolean installed;
                try {
                    RuntimeFiles.install(LauncherActivity.this);
                    installed = true;
                } catch (IOException exception) {
                    Log.e("GZero64", "Cannot install runtime files", exception);
                    installed = false;
                }
                final boolean ok = installed;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (!ok) {
                            findViewById(R.id.play_game).setEnabled(true);
                            status.setText(R.string.launcher_runtime_failed);
                            return;
                        }
                        Intent game = new Intent(LauncherActivity.this, GameActivity.class);
                        game.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(game);
                        finish();
                    }
                });
            }
        });
    }

    /** A touch, key or controller button during the auto-start countdown only cancels it. */
    private boolean cancelAutoStart() {
        if (!handler.hasCallbacks(autoStart)) return false;
        handler.removeCallbacks(autoStart);
        if (status != null) status.setText(R.string.launcher_ready_status);
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && cancelAutoStart()) return true;
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && cancelAutoStart()) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        // Sticks and triggers: only a clear push, not stick drift.
        if (event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)
                && (Math.abs(event.getAxisValue(android.view.MotionEvent.AXIS_X)) > 0.5f
                    || Math.abs(event.getAxisValue(android.view.MotionEvent.AXIS_Y)) > 0.5f
                    || Math.abs(event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X)) > 0.5f
                    || Math.abs(event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y)) > 0.5f)
                && cancelAutoStart()) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    protected void onPause() {
        // Leaving the launcher (settings, another app) cancels a pending auto-start.
        cancelAutoStart();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(autoStart);
        worker.shutdown();
        super.onDestroy();
    }
}
