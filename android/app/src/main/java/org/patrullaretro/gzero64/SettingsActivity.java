package org.patrullaretro.gzero64;

import android.app.Activity;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

/** Launcher settings. Game options live in the in-game quick menu. */
public final class SettingsActivity extends Activity {
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
        findViewById(R.id.settings_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { finish(); }
        });
    }
}
