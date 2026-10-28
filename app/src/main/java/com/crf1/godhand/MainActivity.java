package com.crf1.godhand;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Initial Android/ARM64 build check. This is not the game runtime. */
public final class MainActivity extends Activity {
    static {
        System.loadLibrary("godhand_android");
    }

    private native String nativeBuildStatus();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(28, 28, 28, 28);

        TextView title = new TextView(this);
        title.setText("GodHand CRF1 Recomp\n\nAndroid ARM64 bootstrap");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);

        TextView status = new TextView(this);
        status.setText(nativeBuildStatus());
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 24, 0, 0);

        TextView warning = new TextView(this);
        warning.setText("Build de diagnóstico. O jogo ainda não foi integrado.");
        warning.setGravity(Gravity.CENTER);
        warning.setPadding(0, 24, 0, 0);

        layout.addView(title);
        layout.addView(status);
        layout.addView(warning);
        setContentView(layout);
    }
}
