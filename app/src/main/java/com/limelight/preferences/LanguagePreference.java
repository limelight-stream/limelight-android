package com.limelight.preferences;

import android.annotation.TargetApi;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.preference.ListPreference;
import android.provider.Settings;
import android.util.AttributeSet;

public class LanguagePreference extends ListPreference {
    public LanguagePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public LanguagePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public LanguagePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public LanguagePreference(Context context) {
        super(context);
    }

    @Override
    protected void onClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                // Launch the Android native app locale settings page
                Intent intent = new Intent(Settings.ACTION_APP_LOCALE_SETTINGS);
                intent.addCategory(Intent.CATEGORY_DEFAULT);
                intent.setData(Uri.parse("package:" + getContext().getPackageName()));
                getContext().startActivity(intent, null);
                return;
            } catch (ActivityNotFoundException e) {
                // App locale settings should be present on all Android 13 devices,
                // but if not, we'll launch the old language chooser.
            }
        }

        // If we don't have native app locale settings, launch the normal dialog
        super.onClick();
    }
}
