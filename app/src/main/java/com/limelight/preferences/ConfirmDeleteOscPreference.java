package com.limelight.preferences;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Build;
import android.preference.DialogPreference;
import android.util.AttributeSet;
import android.widget.Toast;

import com.limelight.R;

import static com.limelight.binding.input.virtual_controller.VirtualControllerConfigurationLoader.OSC_PREFERENCE;

public class ConfirmDeleteOscPreference extends DialogPreference {
    public ConfirmDeleteOscPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public ConfirmDeleteOscPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ConfirmDeleteOscPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ConfirmDeleteOscPreference(Context context) {
        super(context);
    }

    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_POSITIVE) {
            getContext().getSharedPreferences(OSC_PREFERENCE, Context.MODE_PRIVATE).edit().clear().apply();
            Toast.makeText(getContext(), R.string.toast_reset_osc_success, Toast.LENGTH_SHORT).show();
        }
    }
}
