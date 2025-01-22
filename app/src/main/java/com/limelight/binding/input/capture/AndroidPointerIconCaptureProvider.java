package com.limelight.binding.input.capture;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.PointerIcon;
import android.view.View;

@TargetApi(Build.VERSION_CODES.N)
public class AndroidPointerIconCaptureProvider extends InputCaptureProvider {
    private final View targetView;
    private final Context context;

    public AndroidPointerIconCaptureProvider(Activity activity, View targetView) {
        this.context = activity;
        this.targetView = targetView;
    }

    public static boolean isCaptureProviderSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    @Override
    public void hideCursor() {
        super.hideCursor();
        targetView.setPointerIcon(PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL));
    }

    @Override
    public void showCursor() {
        super.showCursor();
        targetView.setPointerIcon(null);
    }
}
