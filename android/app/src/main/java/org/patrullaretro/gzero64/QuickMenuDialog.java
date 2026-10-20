package org.patrullaretro.gzero64;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;

/** In-game quick menu: Continue (green, left) and Exit (red, right). */
final class QuickMenuDialog {
    private QuickMenuDialog() { }

    static Dialog show(final GameActivity activity) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_quick_menu);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // Keep the game's immersive mode while the dialog is up.
            window.getDecorView().setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
        }
        ((TextView) dialog.findViewById(R.id.quick_version)).setText(activity.getString(
            R.string.quick_version, BuildConfig.VERSION_NAME));
        Button resume = dialog.findViewById(R.id.quick_resume);
        resume.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { dialog.dismiss(); }
        });
        dialog.findViewById(R.id.quick_options).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                dialog.dismiss();
                activity.openPortMenu();
            }
        });
        dialog.findViewById(R.id.quick_exit).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                dialog.dismiss();
                activity.exitGame();
            }
        });
        dialog.show();
        resume.requestFocus();
        return dialog;
    }
}
