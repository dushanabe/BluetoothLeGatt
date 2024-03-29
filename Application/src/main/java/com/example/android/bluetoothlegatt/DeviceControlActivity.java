/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.bluetoothlegatt;

import android.app.Activity;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.os.Environment;

import com.sccomponents.gauges.gr004.GR004;

import java.util.List;
import java.util.Date;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;

/**
 * For a given BLE device, this Activity provides the user interface to connect, display data,
 * and display GATT services and characteristics supported by the device.  The Activity
 * communicates with {@code BluetoothLeService}, which in turn interacts with the
 * Bluetooth LE API.
 */
public class DeviceControlActivity extends Activity {
    private final static String TAG = DeviceControlActivity.class.getSimpleName();

    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";



    private TextView mConnectionState;
    private TextView mDataField;
    private GR004 mGauge;
    private String mDeviceName;
    private TextView mCoundownText;
    private EditText mTestLength;
    private Button mStartButton;
    private CountDownTimer mCountdownTimer;
    private BufferedWriter file;
    private long mTestDurationInMilliseconds = 120000;
    private long mTimeLeftInMilliseconds = mTestDurationInMilliseconds;
    private long mTimeElapsedInMilliseconds = 0;
    private boolean mTimerRunning;
    private String mDeviceAddress;
    private BluetoothLeService mBluetoothLeService;
    private boolean mConnected = false;
    private BluetoothGattCharacteristic mNotifyCharacteristic;

    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }
            // Automatically connects to the device upon successful start-up initialization.
            mBluetoothLeService.connect(mDeviceAddress);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };

    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read
    //                        or notification operations.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;
                updateConnectionState(R.string.connected);
                invalidateOptionsMenu();
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;
                updateConnectionState(R.string.disconnected);
                invalidateOptionsMenu();
                clearUI();
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // need to find the isoball service and pressure characteristics
                connectToIsoballService(mBluetoothLeService.getSupportedGattServices());

            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                displayData(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
            }
        }
    };

    private void clearUI() {
        mDataField.setText(R.string.no_data);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.gatt_services_characteristics);

        final Intent intent = getIntent();
        mDeviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
        mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);

        // Sets up UI references.
        //((TextView) findViewById(R.id.device_address)).setText(mDeviceAddress);
        //mGattServicesList = findViewById(R.id.gatt_services_list);
        //mGattServicesList.setOnChildClickListener(servicesListClickListner);
        mTestLength = findViewById(R.id.duration_sec);
        mCoundownText = findViewById(R.id.countdown_text);
        mStartButton = findViewById(R.id.start_button);
        mConnectionState = findViewById(R.id.connection_state);
        mDataField = findViewById(R.id.data_value);
        mGauge = findViewById(R.id.gauge);


        getActionBar().setTitle(mDeviceName);
        getActionBar().setDisplayHomeAsUpEnabled(true);
        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

        mStartButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startStop();
            }
        });
        mStartButton.setEnabled(false);


        mTestLength.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                mStartButton.setEnabled(false);
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable editable) {
                mStartButton.setEnabled(true);
            }
        });

    }

    public void startStop(){
        if(mTimerRunning){
            mTestLength.setEnabled(true);
            stopTimer();
            stopRecording();
        } else {
            mTestLength.setEnabled(false);
            String sTextFromTestLength = mTestLength.getText().toString().trim();
            int iTestLength = Integer.valueOf(sTextFromTestLength);
            mTestDurationInMilliseconds = iTestLength * 1000;
            mTimeLeftInMilliseconds = mTestDurationInMilliseconds;
            startTimer();
            startRecording();
        }
    }

    public void startTimer(){
        mCountdownTimer = new CountDownTimer(mTimeLeftInMilliseconds, 500) {
            @Override
            public void onTick(long millisUntilFinished) {
                mTimeLeftInMilliseconds = millisUntilFinished;
                mTimeElapsedInMilliseconds = mTestDurationInMilliseconds - mTimeLeftInMilliseconds;
                updateTimer();
            }

            @Override
            public void onFinish() {
                stopTimer();
                stopRecording();
            }
        }.start();
        mStartButton.setText("Stop");
        mTimerRunning = true;
    }

    public void stopTimer(){
        mCountdownTimer.cancel();
        mStartButton.setText("Start");
        mTimeLeftInMilliseconds = mTestDurationInMilliseconds;
        mTimerRunning = false;
    }

    public void updateTimer(){
        int minutes = (int) mTimeLeftInMilliseconds / 60000;
        int seconds = (int) mTimeLeftInMilliseconds % 60000 / 1000;
        String timeLeftText;

        timeLeftText = "" + minutes;
        timeLeftText += ":";
        if (seconds < 10) timeLeftText += "0";
        timeLeftText += seconds;

        mCoundownText.setText(timeLeftText);
    }


    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        if (mBluetoothLeService != null) {
            final boolean result = mBluetoothLeService.connect(mDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(mServiceConnection);
        mBluetoothLeService = null;
        stopRecording();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gatt_services, menu);
        if (mConnected) {
            menu.findItem(R.id.menu_connect).setVisible(false);
            menu.findItem(R.id.menu_disconnect).setVisible(true);
        } else {
            menu.findItem(R.id.menu_connect).setVisible(true);
            menu.findItem(R.id.menu_disconnect).setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.menu_connect:
                mBluetoothLeService.connect(mDeviceAddress);
                return true;
            case R.id.menu_disconnect:
                mBluetoothLeService.disconnect();
                return true;
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateConnectionState(final int resourceId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mConnectionState.setText(resourceId);
            }
        });
    }

    private void displayData(String data) {
        if (data != null) {
            mDataField.setText(data);
            mGauge.setValue(Double.parseDouble(data));

            int minutes = (int) mTimeElapsedInMilliseconds / 60000;
            float seconds = (float) mTimeElapsedInMilliseconds % 60000 / 1000;
            String timeElapsed;

            timeElapsed = "" + minutes;
            timeElapsed += ":";
            if (seconds < 10) timeElapsed += "0";
            timeElapsed += String.format("%.1f", seconds);
            write(timeElapsed, data);
        }
    }

    // Demonstrates how to iterate through the supported GATT Services/Characteristics.
    // In this sample, we populate the data structure that is bound to the ExpandableListView
    // on the UI.
    private void connectToIsoballService(List<BluetoothGattService> gattServices) {
        if (gattServices == null) return;

        String suuid = null;
        String cuuid = null;
        for (BluetoothGattService gattService : gattServices) {
            suuid = gattService.getUuid().toString();
            if (suuid.equals(SampleGattAttributes.ISOBALL_SERVICES)){
                List<BluetoothGattCharacteristic> gattCharacteristics =
                        gattService.getCharacteristics();

                for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                    cuuid = gattCharacteristic.getUuid().toString();
                    if (cuuid.equals(SampleGattAttributes.ISOBALL_MEASUREMENT)){
                        BluetoothGattCharacteristic characteristic = gattCharacteristic;
                        final int charaProp = characteristic.getProperties();

                        if ((charaProp | BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
                            // If there is an active notification on a characteristic, clear
                            // it first so it doesn't update the data field on the user interface.
                            if (mNotifyCharacteristic != null) {
                                mBluetoothLeService.setCharacteristicNotification(
                                        mNotifyCharacteristic, false);
                                mNotifyCharacteristic = null;
                            }
                            mBluetoothLeService.readCharacteristic(characteristic);
                        }
                        if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                            mNotifyCharacteristic = characteristic;
                            mBluetoothLeService.setCharacteristicNotification(
                                    characteristic, true);
                        }
                    }
                }
            }
        }
    }

    private void startRecording() {
        // Prepare data storage
        File directory = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        SimpleDateFormat sdf = new SimpleDateFormat("MMMddyyyyHHmm");
        Date currentdate = new Date(System.currentTimeMillis());
        String name = "Exercise_" + sdf.format(currentdate) + ".csv";
        File filename = new File(directory, name);
        try {
            file = new BufferedWriter(new FileWriter(filename));
        } catch (IOException e) {
            e.printStackTrace();
        }
        //filenameDisplay.setText(name);
    }

    private void stopRecording() {
        try {
            file.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private void write(String tag, String value) {
        if (file == null) {
            return;
        }

        String line = tag + "," + value + "\n";

        try {
            file.write(line);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        //logDisplay.setText(line);
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }
}
