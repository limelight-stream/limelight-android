package com.limelight.preferences;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.preference.Preference;
import android.util.AttributeSet;

import com.limelight.utils.HelpLauncher;

public class WebLauncherPreference extends Preference {
    private String url;

    public WebLauncherPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize(attrs);
    }

    public WebLauncherPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize(attrs);
    }

    public WebLauncherPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initialize(attrs);
    }

    private void initialize(AttributeSet attrs) {
        if (attrs == null) {
            throw new IllegalStateException("WebLauncherPreference must have attributes!");
        }

        url = attrs.getAttributeValue(null, "url");
        if (url == null) {
            throw new IllegalStateException("WebLauncherPreference must have 'url' attribute!");
        }
    }

    @Override
    public void onClick() {
        HelpLauncher.launchUrl(getContext(), url);
    }
}
