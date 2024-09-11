package com.limelight;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;

import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ShortcutTrampoline extends Activity {
    private String uuidString;
    private NvApp app;
    private ArrayList<Intent> intentStack = new ArrayList<>();

    private int wakeHostTries = 10;
    private ComputerDetails computer;
    private SpinnerDialog blockingLoadSpinner;

    private ComputerManagerService.ComputerManagerBinder managerBinder;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Now make the binder visible
                    managerBinder = localBinder;

                    // Get the computer object
                    computer = managerBinder.getComputer(uuidString);

                    if (computer == null) {
                        Dialog.displayDialog(ShortcutTrampoline.this,
                                getResources().getString(R.string.conn_error_title),
                                getResources().getString(R.string.scut_pc_not_found),
                                true);

                        if (blockingLoadSpinner != null) {
                            blockingLoadSpinner.dismiss();
                            blockingLoadSpinner = null;
                        }

                        if (managerBinder != null) {
                            unbindService(serviceConnection);
                            managerBinder = null;
                        }

                        return;
                    }

                    // Force CMS to repoll this machine
                    managerBinder.invalidateStateForComputer(computer.uuid);

                    // Start polling
                    managerBinder.startPolling(new ComputerManagerListener() {
                        @Override
                        public void notifyComputerUpdated(final ComputerDetails details) {
                            // Don't care about other computers
                            if (!details.uuid.equalsIgnoreCase(uuidString)) {
                                return;
                            }

                            // Try to wake the target PC if it's offline (up to some retry limit)
                            if (details.state == ComputerDetails.State.OFFLINE && details.macAddress != null && --wakeHostTries >= 0) {
                                try {
                                    // Make a best effort attempt to wake the target PC
                                    WakeOnLanSender.sendWolPacket(computer);

                                    // If we sent at least one WoL packet, reset the computer state
                                    // to force ComputerManager to poll it again.
                                    managerBinder.invalidateStateForComputer(computer.uuid);
                                    return;
                                } catch (IOException e) {
                                    // If we got an exception, we couldn't send a single WoL packet,
                                    // so fallthrough into the offline error path.
                                    e.printStackTrace();
                                }
                            }

                            if (details.state != ComputerDetails.State.UNKNOWN) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        // Stop showing the spinner
                                        if (blockingLoadSpinner != null) {
                                            blockingLoadSpinner.dismiss();
                                            blockingLoadSpinner = null;
                                        }

                                        // If the managerBinder was destroyed before this callback,
                                        // just finish the activity.
                                        if (managerBinder == null) {
                                            finish();
                                            return;
                                        }

                                        if (details.state == ComputerDetails.State.ONLINE && details.pairState == PairingManager.PairState.PAIRED) {
                                            
                                            // Launch game if provided app ID, otherwise launch app view
                                            if (app != null) {
                                                if (details.runningGameId == 0 || details.runningGameId == app.getAppId()) {
                                                    intentStack.add(ServerHelper.createStartIntent(ShortcutTrampoline.this, app, details, managerBinder));

                                                    // Close this activity
                                                    finish();

                                                    // Now start the activities
                                                    startActivities(intentStack.toArray(new Intent[]{}));
                                                } else {
                                                    // Create the start intent immediately, so we can safely unbind the managerBinder
                                                    // below before we return.
                                                    final Intent startIntent = ServerHelper.createStartIntent(ShortcutTrampoline.this, app, details, managerBinder);

                                                    UiHelper.displayQuitConfirmationDialog(ShortcutTrampoline.this, new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            intentStack.add(startIntent);

                                                            // Close this activity
                                                            finish();

                                                            // Now start the activities
                                                            startActivities(intentStack.toArray(new Intent[]{}));
                                                        }
                                                    }, new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            // Close this activity
                                                            finish();
                                                        }
                                                    });
                                                }
                                            } else {
                                                // Close this activity
                                                finish();

                                                // Add the PC view at the back (and clear the task)
                                                Intent i;
                                                i = new Intent(ShortcutTrampoline.this, PcView.class);
                                                i.setAction(Intent.ACTION_MAIN);
                                                i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                                                intentStack.add(i);

                                                // Take this intent's data and create an intent to start the app view
                                                i = new Intent(getIntent());
                                                i.setClass(ShortcutTrampoline.this, AppView.class);
                                                intentStack.add(i);

                                                // If a game is running, we'll make the stream the top level activity
                                                if (details.runningGameId != 0) {
                                                    intentStack.add(ServerHelper.createStartIntent(ShortcutTrampoline.this,
                                                            new NvApp(null, details.runningGameId, false), details, managerBinder));
                                                }

                                                // Now start the activities
                                                startActivities(intentStack.toArray(new Intent[]{}));
                                            }
                                            
                                        }
                                        else if (details.state == ComputerDetails.State.OFFLINE) {
                                            // Computer offline - display an error dialog
                                            Dialog.displayDialog(ShortcutTrampoline.this,
                                                    getResources().getString(R.string.conn_error_title),
                                                    getResources().getString(R.string.error_pc_offline),
                                                    true);
                                        } else if (details.pairState != PairingManager.PairState.PAIRED) {
                                            // Computer not paired - display an error dialog
                                            Dialog.displayDialog(ShortcutTrampoline.this,
                                                    getResources().getString(R.string.conn_error_title),
                                                    getResources().getString(R.string.scut_not_paired),
                                                    true);
                                        }

                                        // We don't want any more callbacks from now on, so go ahead
                                        // and unbind from the service
                                        if (managerBinder != null) {
                                            managerBinder.stopPolling();
                                            unbindService(serviceConnection);
                                            managerBinder = null;
                                        }
                                    }
                                });
                            }
                        }
                    });
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    protected boolean validateInput(String uuidString, String appIdString, String nameString) {
        // Validate PC UUID/Name
        if (uuidString == null && nameString == null) {
            Dialog.displayDialog(ShortcutTrampoline.this,
                    getResources().getString(R.string.conn_error_title),
                    getResources().getString(R.string.scut_invalid_uuid),
                    true);
            return false;
        }

        if (uuidString != null && !uuidString.isEmpty()) {
            try {
                UUID.fromString(uuidString);
            } catch (IllegalArgumentException ex) {
                Dialog.displayDialog(ShortcutTrampoline.this,
                        getResources().getString(R.string.conn_error_title),
                        getResources().getString(R.string.scut_invalid_uuid),
                        true);
                return false;
            }
        } else {
            // UUID is null, so fallback to Name
            if (nameString == null || nameString.isEmpty()) {
                Dialog.displayDialog(ShortcutTrampoline.this,
                        getResources().getString(R.string.conn_error_title),
                        getResources().getString(R.string.scut_invalid_uuid),
                        true);
                return false;
            }
        }

        // Validate App ID (if provided)
        if (appIdString != null && !appIdString.isEmpty()) {
            try {
                Integer.parseInt(appIdString);
            } catch (NumberFormatException ex) {
                Dialog.displayDialog(ShortcutTrampoline.this,
                        getResources().getString(R.string.conn_error_title),
                        getResources().getString(R.string.scut_invalid_app_id),
                        true);
                return false;
            }
        }

        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        UiHelper.notifyNewRootView(this);
        ComputerDatabaseManager dbManager = new ComputerDatabaseManager(this);
        ComputerDetails _computer = null;

        // PC arguments, both are optional, but at least one must be provided
        uuidString = getIntent().getStringExtra(AppView.UUID_EXTRA);
        String nameString = getIntent().getStringExtra(AppView.NAME_EXTRA);

        // App arguments, both are optional, but one must be provided in order to start an app
        String appIdString = getIntent().getStringExtra(Game.EXTRA_APP_ID);
        String appNameString = getIntent().getStringExtra(Game.EXTRA_APP_NAME);

        if (!validateInput(uuidString, appIdString, nameString)) {
            // Invalid input, so just return
            return;
        }

        if (uuidString == null || uuidString.isEmpty()) {
            // Use nameString to find the corresponding UUID
            _computer = dbManager.getComputerByName(nameString);

            if (_computer == null) {
                Dialog.displayDialog(ShortcutTrampoline.this,
                        getResources().getString(R.string.conn_error_title),
                        getResources().getString(R.string.scut_pc_not_found),
                        true);
                return;
            }

            uuidString = _computer.uuid;

            // Set the AppView UUID intent, since it wasn't provided
            setIntent(new Intent(getIntent()).putExtra(AppView.UUID_EXTRA, uuidString));
        }

        if (appIdString != null && !appIdString.isEmpty()) {
            app = new NvApp(getIntent().getStringExtra(Game.EXTRA_APP_NAME),
                    Integer.parseInt(appIdString),
                    getIntent().getBooleanExtra(Game.EXTRA_APP_HDR, false));
        }
        else if (appNameString != null && !appNameString.isEmpty()) {
            // Use appNameString to find the corresponding AppId
            try {
                int appId = -1;
                String rawAppList = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuidString));

                if (rawAppList.isEmpty()) {
                    Dialog.displayDialog(ShortcutTrampoline.this,
                            getResources().getString(R.string.conn_error_title),
                            getResources().getString(R.string.scut_invalid_app_id),
                            true);
                    return;
                }
                List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(rawAppList));

                for (NvApp _app : applist) {
                    if (_app.getAppName().equals(appNameString)) {
                        appId = _app.getAppId();
                        break;
                    }
                }
                if (appId < 0) {
                    Dialog.displayDialog(ShortcutTrampoline.this,
                            getResources().getString(R.string.conn_error_title),
                            getResources().getString(R.string.scut_invalid_app_id),
                            true);
                    return;
                }
                setIntent(new Intent(getIntent()).putExtra(Game.EXTRA_APP_ID, appId));
                app = new NvApp(
                        appNameString,
                        appId,
                        getIntent().getBooleanExtra(Game.EXTRA_APP_HDR, false));
            } catch (IOException | XmlPullParserException e) {
                Dialog.displayDialog(ShortcutTrampoline.this,
                        getResources().getString(R.string.conn_error_title),
                        getResources().getString(R.string.scut_invalid_app_id),
                        true);
                return;
            }
        }

        // Bind to the computer manager service
        bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        blockingLoadSpinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.conn_establishing_title),
                getResources().getString(R.string.applist_connect_msg), true);
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (blockingLoadSpinner != null) {
            blockingLoadSpinner.dismiss();
            blockingLoadSpinner = null;
        }

        Dialog.closeDialogs();

        if (managerBinder != null) {
            managerBinder.stopPolling();
            unbindService(serviceConnection);
            managerBinder = null;
        }

        finish();
    }
}
