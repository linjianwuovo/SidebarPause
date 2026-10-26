package com.linjianwu.sidebarpause;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static final String NAME = "sidebar_pause";
    static final String KEY_HOLD = "hold_ms";
    static final String KEY_LEFT = "left";
    static final String KEY_RIGHT = "right";
    static final String KEY_HIDE = "hide_bar";
    static final String KEY_AUTO_CLOSE = "auto_close_ms";

    static final int MIN_HOLD = 150;
    static final int MAX_HOLD = 600;
    static final int DEFAULT_HOLD = 200;

    /** 滑条 0=不收，1..16 档每档 500ms；默认第 5 档 = 2500ms。 */
    static final int AUTO_CLOSE_MAX_STEPS = 16;
    static final int AUTO_CLOSE_STEP_MS = 500;
    static final int DEFAULT_AUTO_CLOSE = 2500;

    private Prefs() {
    }

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static int hold(Context context) {
        int value = get(context).getInt(KEY_HOLD, DEFAULT_HOLD);
        return Math.max(MIN_HOLD, Math.min(MAX_HOLD, value));
    }

    static boolean left(Context context) {
        return get(context).getBoolean(KEY_LEFT, true);
    }

    static boolean right(Context context) {
        return get(context).getBoolean(KEY_RIGHT, true);
    }

    static boolean hide(Context context) {
        return get(context).getBoolean(KEY_HIDE, true);
    }

    static int autoCloseMs(Context context) {
        return get(context).getInt(KEY_AUTO_CLOSE, DEFAULT_AUTO_CLOSE);
    }

    static int autoCloseProgress(int ms) {
        return ms <= 0 ? 0 : Math.max(1, Math.min(AUTO_CLOSE_MAX_STEPS,
                Math.round((float) ms / AUTO_CLOSE_STEP_MS)));
    }

    static int autoCloseFromProgress(int progress) {
        return progress <= 0 ? 0
                : Math.min(AUTO_CLOSE_MAX_STEPS, progress) * AUTO_CLOSE_STEP_MS;
    }
}
