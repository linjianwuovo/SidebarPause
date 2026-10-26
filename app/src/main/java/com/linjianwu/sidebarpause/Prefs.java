package com.linjianwu.sidebarpause;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static final String NAME = "sidebar_pause";
    static final String KEY_HOLD = "hold_ms";
    static final String KEY_LEFT = "left";
    static final String KEY_RIGHT = "right";
    static final String KEY_HIDE = "hide_bar";

    static final int MIN_HOLD = 150;
    static final int MAX_HOLD = 600;
    static final int DEFAULT_HOLD = 200;

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
}
