package com.gzero64;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Launcher settings. Game options live in the in-game quick menu. */
public final class SettingsActivity extends Activity {
    private static final int SAVE_LOG = 1;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SAVE_LOG || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri target = data.getData();
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = DiagnosticLog.save(SettingsActivity.this, target);
                final int message = ok ? R.string.settings_log_saved : R.string.settings_log_failed;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Switch autoStart = findViewById(R.id.setting_auto_start);
        autoStart.setChecked(LauncherSettings.autoStart(this));
        autoStart.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                LauncherSettings.setAutoStart(SettingsActivity.this, checked);
            }
        });
        TextView credits = findViewById(R.id.settings_credits);
        credits.setText(Html.fromHtml(getString(R.string.port_credits_body,
            BuildConfig.VERSION_NAME, BuildConfig.BUILD_COMMIT_FULL), Html.FROM_HTML_MODE_LEGACY));
        credits.setMovementMethod(LinkMovementMethod.getInstance());
        findViewById(R.id.settings_save_log).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/zip");
                intent.putExtra(Intent.EXTRA_TITLE, "g-zero-64-registro.zip");
                startActivityForResult(intent, SAVE_LOG);
            }
        });
        findViewById(R.id.settings_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { finish(); }
        });
    }
}
