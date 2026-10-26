package com.linjianwu.sidebarpause;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "SidebarPause";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String DETECTOR_CLASS =
            "com.oplus.systemui.navigationbar.gesture.sidegesture.SideGestureDetector";
    private static final String CALLBACK_CLASS = DETECTOR_CLASS + "$3";
    private static final Uri CONFIG_URI =
            Uri.parse("content://com.linjianwu.sidebarpause.settings/settings");
    private static final String SCENE_REGION_KEY = "sidebar_scene_region";
    private static final String FLOAT_BAR_SIDE_KEY = "key_float_bar_left_right";
    private static final String FLOAT_BAR_ALPHA_KEY = "edge_panel_os_float_bar_alpha";
    private static final String LAST_SHOW_KEY = "sidebarpause_last_show_at";
    private static final String PANEL_CLOSED_KEY = "sidebarpause_panel_closed_at";
    private static final String SYSTEMUI_LOADED_KEY = "sidebarpause_systemui_loaded";
    private static final String SIDEBAR_LOADED_KEY = "sidebarpause_sidebar_loaded";

    private static final long CONFIG_TTL_MS = 1200L;
    private static final long SCENE_TTL_MS = 1000L;
    private static final long SIDE_SWITCH_SCENE_TIMEOUT = 1200L;
    private static final long PANEL_OPEN_WINDOW_MS = 8000L;

    private static final Map<Object, State> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<Object> SUPPRESSED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Map<Class<?>, Method> CANCEL_METHODS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static Config cachedConfig = new Config();
    private static long cachedConfigAt;
    private static boolean hideConfig = true;
    private static long hideConfigAt;
    private static Rect cachedSceneRect;
    private static long cachedSceneAt;
    private static boolean systemUiLoadedMarked;
    private static boolean sidebarLoadedMarked;
    private static long lastPanelShownAt;
    private static int lastPanelSide = -1;
    private static volatile boolean switching;
    private static long switchingUntil;
    private static volatile boolean panelHiddenByUs;
    private static volatile int sourceRectGeneration;
    private static volatile Object[] pendingAutoClose;
    private static int autoCloseConfig;
    private static long autoCloseConfigAt;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if ("com.coloros.smartsidebar".equals(loadPackageParam.packageName)) {
            Log.i(TAG, "module loaded in smartsidebar " + loadPackageParam.processName);
            hookSidebarBar(loadPackageParam.classLoader);
            hookPanelClose(loadPackageParam.classLoader);
            return;
        }
        if (!SYSTEM_UI.equals(loadPackageParam.packageName)) {
            return;
        }
        Log.i(TAG, "module loaded in " + loadPackageParam.processName);
        Class<?> detector = XposedHelpers.findClassIfExists(
                DETECTOR_CLASS, loadPackageParam.classLoader);
        if (detector == null) {
            log("detector class not found");
            return;
        }

        Log.i(TAG, "detector found, hooking");
        log("hooking " + DETECTOR_CLASS);
        XposedHelpers.findAndHookMethod(detector, "onMotionEventImpl",
                MotionEvent.class, Map.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            MotionEvent event = (MotionEvent) param.args[0];
                            if (onMotionEvent(param.thisObject, event)) {
                                param.setResult(null);
                            }
                        } catch (Throwable t) {
                            log("event hook failed: " + t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            MotionEvent event = (MotionEvent) param.args[0];
                            int action = event.getActionMasked();
                            if (action == MotionEvent.ACTION_UP
                                    || action == MotionEvent.ACTION_CANCEL) {
                                Object owner = param.thisObject;
                                SUPPRESSED.remove(owner);
                                State state = STATES.get(owner);
                                if (state != null) {
                                    state.finishGesture();
                                }
                            }
                        } catch (Throwable t) {
                            log("post hook failed: " + t);
                        }
                    }
                });

        Class<?> callback = XposedHelpers.findClassIfExists(
                CALLBACK_CLASS, loadPackageParam.classLoader);
        if (callback != null) {
            XposedHelpers.findAndHookMethod(callback, "triggerBack", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object owner = XposedHelpers.getObjectField(param.thisObject, "this$0");
                        if (owner != null && SUPPRESSED.contains(owner)) {
                            param.setResult(null);
                            log("back suppressed");
                        }
                    } catch (Throwable t) {
                        log("triggerBack hook failed: " + t);
                    }
                }
            });
        } else {
            log("back callback class not found");
        }

        XposedHelpers.findAndHookMethod(detector, "cancelGesture",
                MotionEvent.class, Boolean.class, Boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (shouldKeepBack(param.thisObject,
                                    (MotionEvent) param.args[0], param.args[1], param.args[2])) {
                                param.setResult(null);
                                Log.d(TAG, "native long-press cancel skipped; back kept");
                            }
                        } catch (Throwable t) {
                            log("cancelGesture hook failed: " + t);
                        }
                    }
                });
    }

    private static boolean shouldKeepBack(Object owner, MotionEvent event,
                                          Object flag1, Object flag2) {
        if (!Boolean.FALSE.equals(flag1) || !Boolean.TRUE.equals(flag2)) {
            return false;
        }
        if (event == null || event.getActionMasked() != MotionEvent.ACTION_MOVE
                || event.getPointerCount() != 1) {
            return false;
        }
        if (SUPPRESSED.contains(owner)) {
            return false;
        }
        State state = STATES.get(owner);
        return state != null && state.down && state.armed && state.enabled && !state.triggered;
    }

    private static void hookSidebarBar(ClassLoader loader) {
        Class<?> cls = XposedHelpers.findClassIfExists(
                "com.oplus.smartsidebar.permanent.floatbar.FloatBarMainView", loader);
        if (cls == null) {
            log("FloatBarMainView not found");
            return;
        }
        XposedHelpers.findAndHookMethod(cls, "updateFloatBarVisibility",
                boolean.class, boolean.class, boolean.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (hideFloatBar((android.view.View) param.thisObject)) {
                                param.args[0] = Boolean.FALSE;
                            }
                        } catch (Throwable t) {
                            log("float bar visibility hook failed: " + t);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(cls, "updateFloatBarAlpha",
                float.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (hideFloatBar((android.view.View) param.thisObject)) {
                                param.args[0] = 0f;
                            }
                        } catch (Throwable t) {
                            log("float bar alpha hook failed: " + t);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(cls, "addFloatBarMainViewIfNeeded",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            android.view.View view = (android.view.View) param.thisObject;
                            if (hideFloatBar(view)) {
                                view.setAlpha(0f);
                            }
                        } catch (Throwable t) {
                            log("float bar add hook failed: " + t);
                        }
                    }
                });
        Class<?> barView = XposedHelpers.findClassIfExists(
                "com.oplus.smartsidebar.permanent.floatbar.FloatBarView", loader);
        if (barView != null) {
            try {
                XposedHelpers.findAndHookMethod(barView, "onDraw", Canvas.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    if (hideFloatBar((View) param.thisObject)) {
                                        param.setResult(null);
                                    }
                                } catch (Throwable t) {
                                    log("float bar draw hook failed: " + t);
                                }
                            }
                        });
            } catch (Throwable t) {
                log("float bar onDraw method not found: " + t);
            }
            try {
                XposedHelpers.findAndHookMethod(barView, "updateFloatBarAlpha",
                        boolean.class, float.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    if (hideFloatBar((View) param.thisObject)) {
                                        param.args[0] = Boolean.TRUE;
                                        param.args[1] = 0f;
                                    }
                                } catch (Throwable t) {
                                    log("float bar alpha2 hook failed: " + t);
                                }
                            }
                        });
            } catch (Throwable t) {
                log("float bar alpha2 method not found: " + t);
            }
        } else {
            log("FloatBarView not found");
        }
        hookFloatBarWindow(loader);
        log("float bar hooks installed");
    }

    private static void hookFloatBarWindow(ClassLoader loader) {
        Class<?> util = XposedHelpers.findClassIfExists(
                "com.oplus.smartsidebar.utils.WindowUtil$Companion", loader);
        if (util == null) {
            log("WindowUtil not found");
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(util, "a", View.class, int.class, int.class,
                    WindowManager.LayoutParams.class, boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                View view = (View) param.args[0];
                                WindowManager.LayoutParams params =
                                        (WindowManager.LayoutParams) param.args[3];
                                String title = params == null
                                        ? "" : String.valueOf(params.getTitle());
                                String cls = view == null ? "" : view.getClass().getName();
                                if (view != null && hideFloatBar(view)
                                        && (cls.contains("FloatBar") || title.contains("FloatBar"))) {
                                    if (params != null) {
                                        params.alpha = 0f;
                                        params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                                    }
                                    log("float bar window muted cls=" + cls + " title=" + title);
                                }
                            } catch (Throwable t) {
                                log("float bar window hook failed: " + t);
                            }
                        }
                    });
            log("float bar window hook installed");
        } catch (Throwable t) {
            log("WindowUtil addView hook failed: " + t);
        }
    }

    private static void hookPanelClose(ClassLoader loader) {
        Class<?> panel = XposedHelpers.findClassIfExists(
                "com.oplus.smartsidebar.panelview.edgepanel.PanelMainView", loader);
        if (panel == null) {
            log("PanelMainView not found");
            return;
        }
        Class<?> state = XposedHelpers.findClassIfExists(
                "com.oplus.smartsidebar.panelview.edgepanel.PanelMainView$State", loader);
        if (state != null) {
            XposedHelpers.findAndHookMethod(panel, "changeState", state, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        View view = (View) param.thisObject;
                        String name = stateName(param.args[0]);
                        log("panel state -> " + name + " w=" + view.getWidth()
                                + " h=" + view.getHeight() + " vis=" + view.getVisibility()
                                + " alpha=" + view.getAlpha());
                        if (name.endsWith("FLOAT_BAR_SHOWING")) {
                            startHideWatch(view, "float-bar-state");
                        } else {
                            restorePanel(view, name);
                            resetSourceRectSoon(view, name);
                            if (name.endsWith("EDGE_PANEL_SHOWING")) {
                                armAutoClose(view);
                            }
                        }
                    } catch (Throwable t) {
                        log("panel state hook failed: " + t);
                    }
                }
            });
        } else {
            log("PanelMainView state class not found");
        }
        try {
            XposedHelpers.findAndHookMethod(panel, "panelInvisibleDelayTask$lambda$3",
                    panel, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                View view = (View) param.args[0];
                                log("panel invisible task state="
                                        + stateName(XposedHelpers.getObjectField(view, "mState")));
                                startHideWatch(view, "invisible-task");
                            } catch (Throwable t) {
                                log("panel invisible task hook failed: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("panel invisible task method not found: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(panel, "closeUserPanel",
                    boolean.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                View view = (View) param.thisObject;
                                boolean closed = Boolean.TRUE.equals(param.getResult());
                                log("closeUserPanel result=" + closed + " state="
                                        + stateName(XposedHelpers.getObjectField(view, "mState")));
                                if (closed) {
                                    startHideWatch(view, "close-user-panel");
                                }
                            } catch (Throwable t) {
                                log("closeUserPanel hook failed: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("closeUserPanel hook failed: " + t);
        }
        hookPanelTouch(loader);
        log("panel close hooks installed");
    }

    /** 面板被点过一下就不再自动收回。 */
    private static void hookPanelTouch(ClassLoader loader) {
        Class<?> handler = XposedHelpers.findClassIfExists(
                "com.oplus.smartsidebar.panelview.edgepanel.PanelMainView$PanelMainViewTouchHandler",
                loader);
        if (handler == null) {
            log("PanelMainViewTouchHandler not found");
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(handler, "dispatchTouchEvent", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                MotionEvent event = (MotionEvent) param.args[0];
                                if (event != null && event.getActionMasked()
                                        != MotionEvent.ACTION_UP) {
                                    cancelAutoClose();
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            log("panel touch hook failed: " + t);
        }
    }

    private static void armAutoClose(View view) {
        cancelAutoClose();
        int ms = autoCloseMs(view);
        if (ms <= 0) {
            return;
        }
        long shownAt = 0L;
        try {
            shownAt = Settings.Secure.getLong(view.getContext().getContentResolver(),
                    LAST_SHOW_KEY, 0L);
        } catch (Throwable ignored) {
        }
        if (shownAt > 0L && SystemClock.uptimeMillis() - shownAt > 1500L) {
            return;  // 不是我们呼出的（比如直接点了浮标），别抢 OPPO 的行为
        }
        final View target = view;
        Runnable runnable = () -> {
            pendingAutoClose = null;
            try {
                String state = stateName(XposedHelpers.getObjectField(target, "mState"));
                if (!state.endsWith("EDGE_PANEL_SHOWING")) {
                    return;
                }
                XposedHelpers.callMethod(target, "closeUserPanel", true, false);
                log("panel auto-closed after " + ms + "ms idle");
            } catch (Throwable t) {
                log("panel auto close failed: " + t);
            }
        };
        pendingAutoClose = new Object[]{target, runnable};
        view.postDelayed(runnable, ms);
    }

    private static void cancelAutoClose() {
        Object[] pending = pendingAutoClose;
        if (pending == null) {
            return;
        }
        pendingAutoClose = null;
        try {
            ((View) pending[0]).removeCallbacks((Runnable) pending[1]);
        } catch (Throwable ignored) {
        }
    }

    private static int autoCloseMs(View view) {
        long now = SystemClock.uptimeMillis();
        if (now - autoCloseConfigAt < CONFIG_TTL_MS) {
            return autoCloseConfig;
        }
        int value = 0;
        android.database.Cursor cursor = null;
        try {
            cursor = view.getContext().getContentResolver().query(CONFIG_URI,
                    new String[]{"auto_close_ms"}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                value = cursor.getInt(0);
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        autoCloseConfig = value;
        autoCloseConfigAt = now;
        return value;
    }

    private static String stateName(Object state) {
        if (state instanceof Enum) {
            return ((Enum<?>) state).name();
        }
        return String.valueOf(state);
    }

    private static void startHideWatch(View view, String why) {
        markPanelClosed(view.getContext());
        final long start = SystemClock.uptimeMillis();
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            try {
                if (!hideFloatBar(view)) {
                    return;
                }
                String state = stateName(XposedHelpers.getObjectField(view, "mState"));
                if (!state.endsWith("FLOAT_BAR_SHOWING")) {
                    // 面板又打开了（或者还在打开中），这一轮跳过，别停掉整个看护
                    if (SystemClock.uptimeMillis() - start < 2200L) {
                        view.postDelayed(tick[0], 120L);
                    }
                    return;
                }
                hideClosedPanel(view, why + "/" + state);
                if (SystemClock.uptimeMillis() - start < 2200L) {
                    view.postDelayed(tick[0], 120L);
                } else {
                    log("panel hide watch finished (" + why + ")");
                }
            } catch (Throwable t) {
                log("panel hide watch failed (" + why + "): " + t);
            }
        };
        view.postDelayed(tick[0], 120L);
    }

    /** 面板开到位后，把形变起点还给侧边栏自己算的浮标矩形，这样收起时不会留下灰长条。 */
    private static void resetSourceRectSoon(View view, String state) {
        final int generation = ++sourceRectGeneration;
        view.postDelayed(() -> {
            try {
                if (generation != sourceRectGeneration) {
                    return;
                }
                String now = stateName(XposedHelpers.getObjectField(view, "mState"));
                if (now.endsWith("FLOAT_BAR_SHOWING")) {
                    return;
                }
                Method method = view.getClass().getMethod("updateFloatBarRect",
                        Rect.class, Integer.class, Float.class, Integer.class, Float.class);
                method.invoke(view, new Rect(), null, null, null, null);
                log("morph source rect reset to float bar (opened as " + state + ")");
            } catch (Throwable t) {
                log("morph source rect reset failed: " + t);
            }
        }, 900L);
    }

    private static void hideClosedPanel(View view, String why) {
        try {
            if (!hideFloatBar(view)) {
                return;
            }
            if (view.getVisibility() == View.INVISIBLE && view.getAlpha() == 0f) {
                return;
            }
            String state = stateName(XposedHelpers.getObjectField(view, "mState"));
            view.setAlpha(0f);
            view.setVisibility(View.INVISIBLE);
            panelHiddenByUs = true;
            log("closed panel hidden (" + why + ") state=" + state);
        } catch (Throwable t) {
            log("closed panel hide failed (" + why + "): " + t);
        }
    }

    /** 面板重新打开时，把我们自己藏掉的容器还原，避免打开后面板不可见。 */
    private static void restorePanel(View view, String state) {
        if (!panelHiddenByUs) {
            return;
        }
        try {
            if (view.getVisibility() != View.VISIBLE || view.getAlpha() < 1f) {
                view.setVisibility(View.VISIBLE);
                view.setAlpha(1f);
                log("panel restored for state=" + state);
            }
            panelHiddenByUs = false;
        } catch (Throwable t) {
            log("panel restore failed: " + t);
        }
    }

    private static boolean hideFloatBar(android.view.View view) {
        long now = SystemClock.uptimeMillis();
        if (now - hideConfigAt < CONFIG_TTL_MS) {
            return hideConfig;
        }
        boolean hide = true;
        android.database.Cursor cursor = null;
        markSidebarLoaded(view.getContext());
        try {
            cursor = view.getContext().getContentResolver().query(CONFIG_URI,
                    new String[]{"hide_bar"}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                hide = cursor.getInt(0) != 0;
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        hideConfig = hide;
        hideConfigAt = now;
        return hide;
    }

    private static boolean onMotionEvent(Object owner, MotionEvent event) {
        if (owner == null || event == null) {
            return false;
        }
        Context context = contextOf(owner);
        if (context == null) {
            return false;
        }

        int action = event.getActionMasked();
        State state = STATES.get(owner);
        if (state == null) {
            state = new State();
            STATES.put(owner, state);
        }

        if (state.triggered && action != MotionEvent.ACTION_DOWN) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                hideSideGestureView(owner);
                state.cancelTimer();
                state.armed = false;
                state.down = false;
                state.recycleEvent();
            }
            Log.d(TAG, "skip remaining events after panel trigger, action=" + action);
            return true;
        }

        if (action == MotionEvent.ACTION_DOWN) {
            state.cancelTimer();
            state.recycleEvent();
            SUPPRESSED.remove(owner);
            state.generation++;
            state.handler = handlerOf(owner);
            state.down = true;
            state.armed = false;
            state.triggered = false;
            state.downX = event.getRawX();
            state.downY = event.getRawY();
            state.lastX = state.downX;
            state.lastY = state.downY;
            state.downAt = event.getDownTime();
            state.lastMoveAt = SystemClock.uptimeMillis();
            state.velocityX = 0f;
            state.screenW = displaySize(owner, context).x;
            state.screenH = displaySize(owner, context).y;
            state.isLeft = state.downX < state.screenW / 2f;

            Config config = config(context);
            markSystemUiLoaded(context);
            Log.d(TAG, "down x=" + (int) state.downX + " y=" + (int) state.downY
                    + " side=" + (state.isLeft ? "L" : "R") + " hold=" + config.holdMs
                    + " hide=" + config.hideBar);
            applyBarHidden(context, config);
            state.holdMs = config.holdMs;
            state.hideBar = config.hideBar;
            state.enabled = state.isLeft ? config.left : config.right;
            if (state.enabled && !config.hideBar) {
                Rect tab = sceneRect(context);
                if (tab != null && tab.contains((int) state.downX, (int) state.downY)) {
                    state.enabled = false;
                }
            }
            return false;
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            state.enabled = false;
            state.cancelTimer();
            return false;
        }

        if (!state.down) {
            return false;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (!state.enabled) {
                return false;
            }
            trackMove(owner, state, event, context);
            return false;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            state.cancelTimer();
            state.armed = false;
            state.down = false;
            state.recycleEvent();
        }
        return false;
    }

    private static void trackMove(Object owner, State state, MotionEvent event, Context context) {
        float x = event.getRawX();
        float y = event.getRawY();
        float inward = state.isLeft ? x - state.downX : state.downX - x;
        float dx = Math.abs(x - state.downX);
        float dy = Math.abs(y - state.downY);
        float minInward = dp(context, 14f);
        float maxInward = Math.min(state.screenW * 0.30f, dp(context, 220f));

        if (inward < minInward || inward > maxInward
                || dy > dx * 0.8f + dp(context, 10f)) {
            state.armed = false;
            state.cancelTimer();
            state.lastX = x;
            state.lastY = y;
            return;
        }

        float moved = (float) Math.hypot(x - state.lastX, y - state.lastY);
        if (moved > dp(context, 1.2f)) {
            state.lastMoveAt = SystemClock.uptimeMillis();
            state.cancelTimer();
        }
        state.lastX = x;
        state.lastY = y;

        if (!state.armed) {
            state.armed = true;
            state.cancelTimer();
            Log.d(TAG, "armed inward=" + (int) inward);
        }
        if (state.armed && state.timer == null) {
            scheduleTrigger(owner, state, event, context);
        }
    }

    private static void scheduleTrigger(Object owner, State state, MotionEvent event,
                                        Context context) {
        state.recycleEvent();
        state.timerEvent = MotionEvent.obtain(event);
        final int generation = state.generation;
        final Context appContext = context;
        final MotionEvent timerEvent = state.timerEvent;
        Runnable runnable = () -> {
            try {
                if (state.generation != generation || !state.down || !state.armed
                        || state.triggered || !state.enabled) {
                    return;
                }
                long elapsed = SystemClock.uptimeMillis() - state.lastMoveAt;
                if (elapsed < state.holdMs - 40L) {
                    return;
                }
                trigger(owner, state, appContext, timerEvent);
            } catch (Throwable t) {
                log("trigger failed: " + t);
            }
        };
        state.timer = runnable;
        state.handler.postDelayed(runnable, Math.max(120L, state.holdMs));
    }

    private static void trigger(Object owner, State state, Context context,
                                MotionEvent event) {
        state.triggered = true;
        state.armed = false;
        state.timer = null;
        SUPPRESSED.add(owner);

        cancelSystemBack(owner, event);
        hideSideGestureView(owner);
        scheduleGestureViewHides(owner, state, state.generation);
        haptic(context);

        final int desired = state.isLeft ? 1 : 0;
        final int current = currentPanelSide(context, state.screenW);
        final int downY = (int) state.downY;
        final int screenW = state.screenW;
        final int screenH = state.screenH;
        final float velocity = state.velocityX;
        final long now = SystemClock.uptimeMillis();

        if (desired == current) {
            setPanelSideRaw(context, desired);
            Rect anchor = anchorRect(context, state);
            Log.i(TAG, "trigger x=" + (int) state.downX + " y=" + downY
                    + " hold=" + state.holdMs + " anchor=" + anchor);
            markPanelShown(context);
            PanelBridge.show(context, anchor, velocity);
            lastPanelShownAt = now;
            lastPanelSide = desired;
            log("hold triggered at x=" + (int) state.downX + " y=" + downY
                    + " hold=" + state.holdMs + "ms");
            state.recycleEvent();
            return;
        }

        if (switching && now < switchingUntil) {
            Log.d(TAG, "side switch already in progress; trigger ignored");
            state.recycleEvent();
            return;
        }
        switching = true;
        switchingUntil = now + 2500L;
        final int side = desired;
        final boolean likelyOpen = now - lastPanelShownAt < PANEL_OPEN_WINDOW_MS;
        Runnable switchAndShow = () -> {
            try {
                setPanelSideRaw(context, side);
                waitForSceneSide(context, state.handler, side, screenW,
                        SystemClock.uptimeMillis() + SIDE_SWITCH_SCENE_TIMEOUT, () -> {
                            try {
                                Rect anchor = sideAnchor(context, side, downY, screenW, screenH);
                                Log.i(TAG, "trigger after side switch anchor=" + anchor);
                                markPanelShown(context);
                                PanelBridge.show(context, anchor, velocity);
                                lastPanelShownAt = SystemClock.uptimeMillis();
                                lastPanelSide = side;
                            } catch (Throwable t) {
                                log("delayed panel show failed: " + t);
                            } finally {
                                switching = false;
                            }
                        });
            } catch (Throwable t) {
                log("side switch failed: " + t);
                switching = false;
            }
        };
        Log.i(TAG, "switching panel side " + current + "->" + side + " likelyOpen=" + likelyOpen);
        if (likelyOpen) {
            PanelBridge.dismiss(context, sideAnchor(context, current, downY, screenW, screenH));
            state.handler.postDelayed(switchAndShow, 320L);
        } else {
            switchAndShow.run();
        }
        state.recycleEvent();
    }

    private static void cancelSystemBack(Object detector, MotionEvent event) {
        try {
            Class<?> type = detector.getClass();
            Method cancel = CANCEL_METHODS.get(type);
            if (cancel == null) {
                cancel = type.getDeclaredMethod("cancelGesture",
                        MotionEvent.class, Boolean.class, Boolean.class);
                cancel.setAccessible(true);
                CANCEL_METHODS.put(type, cancel);
            }
            MotionEvent argument = event;
            if (argument == null) {
                State state = STATES.get(detector);
                long now = SystemClock.uptimeMillis();
                argument = MotionEvent.obtain(
                        state == null ? now : state.downAt,
                        now,
                        MotionEvent.ACTION_MOVE,
                        state == null ? 0f : state.lastX,
                        state == null ? 0f : state.lastY,
                        0);
            }
            cancel.invoke(detector, argument, Boolean.FALSE, Boolean.TRUE);
            if (argument != event) {
                argument.recycle();
            }
        } catch (Throwable t) {
            log("cancelGesture failed: " + t);
        }
    }

    private static Rect anchorRect(Context context, State state) {
        Rect scene = sceneRect(context);
        if (scene != null) {
            boolean sceneLeft = scene.centerX() < state.screenW / 2f;
            if (sceneLeft == state.isLeft) {
                return new Rect(scene);
            }
        }
        int width = Math.max(1, (int) dp(context, 18f));
        int height = Math.min(state.screenH, (int) dp(context, 300f));
        int top = (int) state.downY - height / 2;
        top = Math.max(0, Math.min(state.screenH - height, top));
        int left = state.isLeft ? 0 : state.screenW - width;
        return new Rect(left, top, left + width, top + height);
    }

    private static void markPanelShown(Context context) {
        try {
            Settings.Secure.putLong(context.getContentResolver(), LAST_SHOW_KEY,
                    SystemClock.uptimeMillis());
        } catch (Throwable t) {
            log("mark panel shown failed: " + t);
        }
    }

    private static void markSystemUiLoaded(Context context) {
        if (systemUiLoadedMarked) {
            return;
        }
        systemUiLoadedMarked = true;
        try {
            Settings.Secure.putInt(context.getContentResolver(), SYSTEMUI_LOADED_KEY, 1);
        } catch (Throwable ignored) {
        }
    }

    private static void markSidebarLoaded(Context context) {
        if (sidebarLoadedMarked) {
            return;
        }
        sidebarLoadedMarked = true;
        try {
            Settings.Secure.putInt(context.getContentResolver(), SIDEBAR_LOADED_KEY, 1);
        } catch (Throwable ignored) {
        }
    }

    private static void markPanelClosed(Context context) {
        try {
            Settings.Secure.putLong(context.getContentResolver(), PANEL_CLOSED_KEY,
                    SystemClock.uptimeMillis());
        } catch (Throwable t) {
            log("mark panel closed failed: " + t);
        }
    }

    private static void applyBarHidden(Context context, Config config) {
        try {
            int alpha = config.hideBar ? 0 : 40;
            Settings.Secure.putInt(context.getContentResolver(), FLOAT_BAR_ALPHA_KEY, alpha);
        } catch (Throwable t) {
            log("set float bar alpha failed: " + t);
        }
    }

    private static void setPanelSideRaw(Context context, int side) {
        try {
            Settings.Secure.putInt(context.getContentResolver(), FLOAT_BAR_SIDE_KEY, side);
            invalidateSceneCache();
            Log.i(TAG, "panel side set to " + (side == 1 ? "left" : "right"));
        } catch (Throwable t) {
            log("set panel side failed: " + t);
        }
    }

    private static int currentPanelSide(Context context, int screenW) {
        try {
            int side = Settings.Secure.getInt(
                    context.getContentResolver(), FLOAT_BAR_SIDE_KEY, -1);
            if (side == 0 || side == 1) {
                return side;
            }
        } catch (Throwable ignored) {
        }
        Rect scene = sceneRect(context);
        if (scene != null) {
            return scene.centerX() < screenW / 2f ? 1 : 0;
        }
        return 0;
    }

    private static Rect sideAnchor(Context context, int side, int downY,
                                   int screenW, int screenH) {
        Rect scene = sceneRect(context);
        if (scene != null && (scene.centerX() < screenW / 2f ? 1 : 0) == side) {
            return new Rect(scene);
        }
        int width = Math.max(1, (int) dp(context, 18f));
        int height = Math.min(screenH, (int) dp(context, 300f));
        int top = downY - height / 2;
        top = Math.max(0, Math.min(screenH - height, top));
        int left = side == 1 ? 0 : screenW - width;
        return new Rect(left, top, left + width, top + height);
    }

    private static void waitForSceneSide(Context context, Handler handler, int side,
                                         int screenW, long deadline, Runnable ready) {
        invalidateSceneCache();
        Rect scene = sceneRect(context);
        boolean matched = scene != null
                && (scene.centerX() < screenW / 2f ? 1 : 0) == side;
        if (matched || SystemClock.uptimeMillis() >= deadline) {
            ready.run();
            return;
        }
        handler.postDelayed(() -> waitForSceneSide(
                context, handler, side, screenW, deadline, ready), 60L);
    }

    private static void invalidateSceneCache() {
        cachedSceneAt = 0L;
        cachedSceneRect = null;
    }

    /**
     * 手势被我们接管后，SystemUI 的 mAllowGesture 已经是 false，松手时它自己会跳过
     * onUpEvent()，返回手势的那块灰色贝塞尔形状就留在屏幕上。多补几次，直到这轮手势结束。
     */
    private static void scheduleGestureViewHides(Object owner, State state, int generation) {
        Handler handler = state.handler != null ? state.handler : handlerOf(owner);
        long[] delays = {150L, 400L, 900L, 1600L};
        for (final long delay : delays) {
            handler.postDelayed(() -> {
                try {
                    if (state.generation != generation || !state.triggered) {
                        return;
                    }
                    hideSideGestureView(owner);
                } catch (Throwable t) {
                    log("delayed gesture view hide failed: " + t);
                }
            }, delay);
        }
    }

    private static void hideSideGestureView(Object detector) {
        try {
            Object manager = XposedHelpers.getObjectField(detector, "mSideGestureViewManager");
            if (manager == null) {
                return;
            }
            XposedHelpers.callMethod(manager, "onUpEvent", 0);
            XposedHelpers.callMethod(manager, "onUpEvent", 1);
            for (String field : new String[]{"navLeftView", "navRightView"}) {
                try {
                    Object nav = XposedHelpers.getObjectField(manager, field);
                    if (nav != null) {
                        XposedHelpers.callMethod(nav, "resetState");
                    }
                } catch (Throwable ignored) {
                }
            }
            Log.d(TAG, "side gesture views dismissed");
        } catch (Throwable t) {
            log("hide side gesture view failed: " + t);
        }
    }

    private static void haptic(Context context) {
        try {
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(10L, 80));
            }
        } catch (Throwable ignored) {
        }
    }

    private static Config config(Context context) {
        long now = SystemClock.uptimeMillis();
        if (now - cachedConfigAt < CONFIG_TTL_MS) {
            return cachedConfig;
        }
        Config config = new Config();
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(CONFIG_URI,
                    new String[]{"hold_ms", "left", "right", "hide_bar"}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int hold = cursor.getInt(0);
                config.holdMs = Math.max(Prefs.MIN_HOLD, Math.min(Prefs.MAX_HOLD, hold));
                config.left = cursor.getInt(1) != 0;
                config.right = cursor.getInt(2) != 0;
                if (cursor.getColumnCount() > 3) {
                    config.hideBar = cursor.getInt(3) != 0;
                }
            }
        } catch (Throwable t) {
            log("config query failed: " + t);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        cachedConfig = config;
        cachedConfigAt = now;
        return config;
    }

    private static Rect sceneRect(Context context) {
        long now = SystemClock.uptimeMillis();
        if (now - cachedSceneAt < SCENE_TTL_MS) {
            return cachedSceneRect;
        }
        Rect rect = null;
        try {
            String value = Settings.Secure.getString(
                    context.getContentResolver(), SCENE_REGION_KEY);
            if (value != null) {
                String[] parts = value.trim().split("\\s+");
                if (parts.length == 4) {
                    int left = Integer.parseInt(parts[0]);
                    int top = Integer.parseInt(parts[1]);
                    int right = Integer.parseInt(parts[2]);
                    int bottom = Integer.parseInt(parts[3]);
                    if (right > left && bottom > top) {
                        rect = new Rect(left, top, right, bottom);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        cachedSceneRect = rect;
        cachedSceneAt = now;
        return rect;
    }

    private static Point displaySize(Object owner, Context context) {
        try {
            Object value = XposedHelpers.getObjectField(owner, "mDisplaySize");
            if (value instanceof Point) {
                Point point = (Point) value;
                if (point.x > 0 && point.y > 0) {
                    return point;
                }
            }
        } catch (Throwable ignored) {
        }
        return new Point(
                context.getResources().getDisplayMetrics().widthPixels,
                context.getResources().getDisplayMetrics().heightPixels);
    }

    private static Handler handlerOf(Object owner) {
        try {
            Object value = XposedHelpers.getObjectField(owner, "mGestureUIHandler");
            if (value instanceof Handler) {
                return (Handler) value;
            }
        } catch (Throwable ignored) {
        }
        return new Handler(Looper.getMainLooper());
    }

    private static Context contextOf(Object owner) {
        try {
            Object value = XposedHelpers.getObjectField(owner, "mContext");
            if (value instanceof Context) {
                return (Context) value;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static float dp(Context context, float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static final class Config {
        long holdMs = 200L;
        boolean left = true;
        boolean right = true;
        boolean hideBar = true;
    }

    private static final class State {
        boolean down;
        boolean armed;
        boolean triggered;
        boolean enabled;
        boolean hideBar;
        boolean isLeft;
        int generation;
        int screenW;
        int screenH;
        long holdMs = 200L;
        long downAt;
        long lastMoveAt;
        float downX;
        float downY;
        float lastX;
        float lastY;
        float velocityX;
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable timer;
        MotionEvent timerEvent;

        void finishGesture() {
            down = false;
            armed = false;
            cancelTimer();
            recycleEvent();
        }

        void cancelTimer() {
            if (timer != null && handler != null) {
                handler.removeCallbacks(timer);
            }
            timer = null;
        }

        void recycleEvent() {
            MotionEvent event = timerEvent;
            timerEvent = null;
            if (event != null) {
                event.recycle();
            }
        }
    }
}
