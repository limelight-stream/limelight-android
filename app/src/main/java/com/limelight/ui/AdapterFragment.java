package com.limelight.ui;


import android.app.Activity;
import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;

import com.limelight.R;

public class AdapterFragment extends Fragment {
    private AdapterFragmentCallbacks callbacks;

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        callbacks = (AdapterFragmentCallbacks) activity;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(callbacks.getAdapterFragmentLayoutId(), container, false);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        callbacks.receiveAbsListView(getView().findViewById(R.id.fragmentView));
    }
}
