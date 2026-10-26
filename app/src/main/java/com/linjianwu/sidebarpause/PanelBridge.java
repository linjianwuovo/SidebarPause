package com.linjianwu.sidebarpause;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import de.robv.android.xposed.XposedBridge;

final class PanelBridge {
    private static final String TAG = "SidebarPause";
    private static final String ACTION = "oplus.intent.action.EDGE_PANEL_UI_SERVICE";
    private static final String PACKAGE = "com.coloros.smartsidebar";
    private static final String DESCRIPTOR =
            "com.oplus.smartsidebar.aidl.ISideBarUiServiceHandler";
    private static final int TRANSACTION_SHOW = 2;

    private static IBinder binder;
    private static boolean binding;
    private static ServiceConnection connection;

    private static Rect pendingRect;
    private static float pendingVelocity;
    private static float pendingDensity = 1f;
    private static boolean pendingShow = true;

    private PanelBridge() {
    }

    static void show(Context context, Rect rect, float velocityX) {
        request(context, rect, velocityX, true);
    }

    static void dismiss(Context context, Rect rect) {
        request(context, rect, 0f, false);
    }

    private static void request(Context context, Rect rect, float velocityX, boolean show) {
        if (context == null || rect == null) {
            return;
        }
        Context app = context.getApplicationContext();
        if (app != null) {
            context = app;
        }
        IBinder current = binder;
        if (current != null && current.isBinderAlive()) {
            send(current, rect, velocityX, show);
            return;
        }
        pendingRect = new Rect(rect);
        pendingVelocity = velocityX;
        pendingDensity = context.getResources().getDisplayMetrics().density;
        pendingShow = show;
        Log.i(TAG, (show ? "panel show" : "panel dismiss") + " queued until service connects");
        bind(context);
    }

    private static synchronized void bind(Context context) {
        if (binding || (binder != null && binder.isBinderAlive())) {
            return;
        }
        binding = true;
        final Context appContext = context;
        connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                synchronized (PanelBridge.class) {
                    binder = service;
                    binding = false;
                    Log.i(TAG, "sidebar UI service connected");
                    if (pendingRect != null) {
                        final IBinder target = service;
                        final Rect rect = pendingRect;
                        final float velocity = pendingVelocity;
                        final boolean show = pendingShow;
                        pendingRect = null;
                        new Handler(Looper.getMainLooper()).postDelayed(
                                () -> send(target, rect, velocity, show), 520L);
                    }
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                synchronized (PanelBridge.class) {
                    binder = null;
                    binding = false;
                }
            }

            @Override
            public void onBindingDied(ComponentName name) {
                synchronized (PanelBridge.class) {
                    binder = null;
                    binding = false;
                }
                try {
                    appContext.unbindService(this);
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void onNullBinding(ComponentName name) {
                synchronized (PanelBridge.class) {
                    binding = false;
                }
            }
        };
        Intent intent = new Intent(ACTION);
        intent.setPackage(PACKAGE);
        try {
            Log.i(TAG, "binding sidebar UI service");
            boolean ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!ok) {
                binding = false;
                XposedBridge.log(TAG + ": sidebar UI service bind returned false");
            }
        } catch (Throwable t) {
            binding = false;
            XposedBridge.log(TAG + ": sidebar UI service bind failed: " + t);
        }
    }

    private static void send(IBinder target, Rect rect, float velocityX, boolean show) {
        Bundle bundle = new Bundle();
        bundle.putParcelable("showRect", new Rect(rect));
        bundle.putFloat("cornerRadius", 24f * Math.max(1f, pendingDensity));
        bundle.putInt("backgroundColor", 0);
        bundle.putInt("strokeColor", 0);
        bundle.putFloat("strokeWidth", 0f);
        bundle.putFloat("velocityX", velocityX);
        bundle.putBoolean("showState", show);
        bundle.putLong("gestureIconDismissTime", 400L);

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeTypedObject(bundle, 0);
            if (!target.transact(TRANSACTION_SHOW, data, reply, 0)) {
                Log.e(TAG, "sidebar UI service transact returned false");
                return;
            }
            reply.readException();
            Log.i(TAG, show ? "sidebar panel transact ok" : "sidebar dismiss transact ok");
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "sidebar panel request failed: " + e);
            synchronized (PanelBridge.class) {
                binder = null;
            }
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
