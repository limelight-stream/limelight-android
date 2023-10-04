package com.limelight.utils;

import android.util.Log;

public class Loggatore {

    private static final String TAG_PREFIX = "PORCODIOOOOOOO";

    public static void d(String tag, String message) {
        Log.d(TAG_PREFIX + tag, message);
    }

    public static void e(String tag, String message) {
        Log.e(TAG_PREFIX + tag, message);
    }

    // Similarly, add methods for other log levels (i, w, etc.) as needed
}
