package com.linjianwu.sidebarpause;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final String LSPOSED_PACKAGE = "org.lsposed.manager";
    private static final String SYSTEMUI_LOADED_KEY = "sidebarpause_systemui_loaded";
    private static final String SIDEBAR_LOADED_KEY = "sidebarpause_sidebar_loaded";

    private SharedPreferences prefs;
    private TextView holdValue;
    private TextView hookStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = Prefs.get(this);

        holdValue = findViewById(R.id.hold_value);
        hookStatus = findViewById(R.id.hook_status);
        SeekBar hold = findViewById(R.id.hold);
        hold.setMax(Prefs.MAX_HOLD - Prefs.MIN_HOLD);
        hold.setProgress(Prefs.hold(this) - Prefs.MIN_HOLD);
        updateHoldLabel(Prefs.hold(this));

        hold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = Prefs.MIN_HOLD + progress;
                updateHoldLabel(value);
                if (fromUser) {
                    prefs.edit().putInt(Prefs.KEY_HOLD, value).apply();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        CheckBox left = findViewById(R.id.edge_left);
        CheckBox right = findViewById(R.id.edge_right);
        left.setChecked(Prefs.left(this));
        right.setChecked(Prefs.right(this));
        left.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(Prefs.KEY_LEFT, isChecked).apply());
        right.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(Prefs.KEY_RIGHT, isChecked).apply());

        CheckBox hideBar = findViewById(R.id.hide_bar);
        hideBar.setChecked(Prefs.hide(this));
        hideBar.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(Prefs.KEY_HIDE, isChecked).apply());

        Button restart = findViewById(R.id.restart_systemui);
        restart.setOnClickListener(v -> restartSystemUi());

        Button openLsposed = findViewById(R.id.open_lsposed);
        openLsposed.setOnClickListener(v -> openLsposed());
    }

    private void updateHoldLabel(int value) {
        holdValue.setText(getString(R.string.hold_value, value));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateHookStatus();
    }

    private void updateHookStatus() {
        int systemUi = 0;
        int sidebar = 0;
        try {
            systemUi = Settings.Secure.getInt(getContentResolver(), SYSTEMUI_LOADED_KEY, 0);
            sidebar = Settings.Secure.getInt(getContentResolver(), SIDEBAR_LOADED_KEY, 0);
        } catch (Throwable ignored) {
        }
        String text;
        if (systemUi == 0 && sidebar == 0) {
            text = "当前加载：系统界面 ✗ / 侧边栏 ✗（模块可能没启用或没重启作用域）";
        } else if (sidebar == 0) {
            text = "当前加载：系统界面 ✓ / 侧边栏 ✗（去 LSPosed 勾选「OPPO 侧边栏」）";
        } else {
            text = "当前加载：系统界面 ✓ / 侧边栏 ✓";
        }
        hookStatus.setText(text);
    }

    private void restartSystemUi() {
        Toast.makeText(this, R.string.restarting, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String[] suPaths = {"su", "/system/bin/su", "/debug_ramdisk/su", "/data/adb/ksu/bin/su"};
            String command = "pkill -9 -f '[c]om.android.systemui'; "
                    + "pkill -9 -f '[c]om.coloros.smartsidebar'";
            boolean success = false;
            String error = "not found";
            for (String su : suPaths) {
                try {
                    Process process = new ProcessBuilder(su, "-c", command)
                            .redirectErrorStream(true)
                            .start();
                    if (!process.waitFor(20, TimeUnit.SECONDS)) {
                        process.destroy();
                        error = "timeout";
                        continue;
                    }
                    if (process.exitValue() == 0) {
                        success = true;
                        break;
                    }
                    error = "exit " + process.exitValue();
                } catch (Throwable e) {
                    error = e.toString();
                }
            }
            final boolean ok = success;
            final String lastError = error;
            runOnUiThread(() -> Toast.makeText(this,
                    ok ? R.string.restart_done : R.string.restart_failed,
                    Toast.LENGTH_SHORT).show());
            android.util.Log.i("SidebarPause", "restart scope ok=" + ok + " err=" + lastError);
        }).start();
    }

    private void openLsposed() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(LSPOSED_PACKAGE);
        if (intent == null) {
            Toast.makeText(this, R.string.not_found, Toast.LENGTH_SHORT).show();
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Throwable e) {
            Toast.makeText(this, R.string.not_found, Toast.LENGTH_SHORT).show();
        }
    }
}
