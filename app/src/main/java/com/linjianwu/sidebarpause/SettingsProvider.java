package com.linjianwu.sidebarpause;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public final class SettingsProvider extends ContentProvider {
    static final String AUTHORITY = "com.linjianwu.sidebarpause.settings";
    static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/settings");

    private static final int SETTINGS = 1;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(AUTHORITY, "settings", SETTINGS);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (MATCHER.match(uri) != SETTINGS) {
            return null;
        }
        Context context = getContext();
        if (context == null) {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(new String[]{"hold_ms", "left", "right", "hide_bar"}, 1);
        cursor.addRow(new Object[]{
                Prefs.hold(context),
                Prefs.left(context) ? 1 : 0,
                Prefs.right(context) ? 1 : 0,
                Prefs.hide(context) ? 1 : 0
        });
        cursor.setNotificationUri(context.getContentResolver(), uri);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return MATCHER.match(uri) == SETTINGS
                ? "vnd.android.cursor.item/sidebar-pause-settings"
                : null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
