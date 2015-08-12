package com.smartshoe.connect.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseExpandableListAdapter;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.smartshoe.connect.R;
import com.smartshoe.connect.app.settings.ConnectedSettingsActivity;
import com.smartshoe.connect.ble.BleManager;
import com.smartshoe.connect.ui.utils.ExpandableHeightExpandableListView;
import com.smartshoe.connect.ui.utils.ExpandableHeightListView;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesNotAvailableException;
import com.google.android.gms.common.GooglePlayServicesRepairableException;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.places.ui.PlacePicker;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Calendar;

import android.content.BroadcastReceiver;

public class ControllerActivity extends UartInterfaceActivity implements BleManager.BleManagerListener, SensorEventListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, LocationListener {
    // Log
    private final static String TAG = ControllerActivity.class.getSimpleName();

    // Activity request codes (used for onActivityResult)
    private static final int kActivityRequestCode_ConnectedSettingsActivity = 0;

    // Constants
    private final static String kPreferences = "ControllerActivity_prefs";
    private final static String kPreferences_uartToolTip = "uarttooltip";

    // Constants
    private final static int kSendDataInterval = 500;   // milliseconds

    // Sensor Types
    private static final int kSensorType_Quaternion = 3;
    private static final int kSensorType_Accelerometer = 4;
    private static final int kSensorType_Gyroscope = 5;
    private static final int kSensorType_Magnetometer = 6;
    private static final int kSensorType_Location = 0;
    private static final int kSensorType_Time = 1;
    private static final int kSensorType_Notify = 2;
    private static final int kNumSensorTypes = 3;

    // UI
    private ExpandableHeightExpandableListView mControllerListView;
    private ExpandableListAdapter mControllerListAdapter;

    private ExpandableHeightListView mInterfaceListView;
    private ArrayAdapter<String> mInterfaceListAdapter;
    private ViewGroup mUartTooltipViewGroup;

    // Data
    private Handler sendDataHandler = new Handler();
    private GoogleApiClient mGoogleApiClient;
    private SensorManager mSensorManager;
    private SensorData[] mSensorData;
    private Sensor mAccelerometer;
    private Sensor mGyroscope;
    private Sensor mMagnetometer;
    private Sensor mTime;

    private float[] mRotation = new float[9];
    private float[] mOrientation = new float[3];
    private float[] mQuaternion = new float[4];

    private DataFragment mRetainedDataFragment;
    private NotificationReceiver nReceiver;
    private boolean NotificationReceived = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_controller);

        mBleManager = BleManager.getInstance(this);
        restoreRetainedDataFragment(); //THIS IS KEY! INITS THE SENSOR ARRAY.

        //Init notification things
        nReceiver = new NotificationReceiver();
        IntentFilter filter = new IntentFilter("com.smartshoe.connect.app.NOTIFICATION_LISTENER_EXAMPLE");
        //filter.addAction("com.smartshoe.connect.app.NOTIFICATION_LISTENER_EXAMPLE");
        registerReceiver(nReceiver,filter);

        // UI
        mControllerListView = (ExpandableHeightExpandableListView) findViewById(R.id.controllerListView);
        mControllerListAdapter = new ExpandableListAdapter(this, mSensorData);
        mControllerListView.setAdapter(mControllerListAdapter);
        mControllerListView.setExpanded(true);

        mInterfaceListView = (ExpandableHeightListView) findViewById(R.id.interfaceListView);
        mInterfaceListAdapter = new ArrayAdapter<>(this, R.layout.layout_controller_interface_title, R.id.titleTextView, getResources().getStringArray(R.array.controller_interface_items));
        mInterfaceListView.setAdapter(mInterfaceListAdapter);
        mInterfaceListView.setExpanded(true);
        //Link to colour picker and gamepad.
        mInterfaceListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {

                    Intent i = new Intent("com.smartshoe.connect.app.NOTIFICATION_LISTENER_SERVICE_EXAMPLE");
                    i.putExtra("command","list");
                    sendBroadcast(i);
                    Log.d(TAG, "Sent list command");
                    /*Intent intent = new Intent(ControllerActivity.this, ColorPickerActivity.class);
                    startActivityForResult(intent, 0);*/
                } else { //Destination picker
                    int PLACE_PICKER_REQUEST = 1;
                    PlacePicker.IntentBuilder builder = new PlacePicker.IntentBuilder();

                    Context context = getApplicationContext();
                    try {
                        startActivityForResult(builder.build(context), PLACE_PICKER_REQUEST);
                    } catch (GooglePlayServicesRepairableException e) {
                        Log.d(TAG, "Play repairable");
                    } catch (GooglePlayServicesNotAvailableException e) {
                        Log.d(TAG, "Play not available");
                    }
                }
            }
        });

        mUartTooltipViewGroup = (ViewGroup) findViewById(R.id.uartTooltipViewGroup);
        SharedPreferences preferences = getSharedPreferences(kPreferences, Context.MODE_PRIVATE);
        final boolean showUartTooltip = preferences.getBoolean(kPreferences_uartToolTip, true);
        mUartTooltipViewGroup.setVisibility(showUartTooltip ? View.VISIBLE : View.GONE);

        // Sensors
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mGyroscope = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        mMagnetometer = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        // Google Play Services (used for location updates)
        buildGoogleApiClient();

        // Start services
        onServicesDiscovered();
    }

    @Override
    protected void onStart() {
        super.onStart();

        mGoogleApiClient.connect();
    }

    @Override
    protected void onStop() {
        mGoogleApiClient.disconnect();
        super.onStop();
    }

    @Override
    public void onResume() { //This is called every time app starts.
        super.onResume();

        int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
        if (resultCode == ConnectionResult.SERVICE_MISSING ||
                resultCode == ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED ||
                resultCode == ConnectionResult.SERVICE_DISABLED) {

            Dialog googlePlayErrorDialog = GooglePlayServicesUtil.getErrorDialog(resultCode, this, 0);
            if (googlePlayErrorDialog != null) {
                googlePlayErrorDialog.show();
            }
        }

        // Setup listeners
        mBleManager.setBleListener(this);

        //mMqttManager.setListener(this);
        //updateMqttStatus();

        registerEnabledSensorListeners(true);

        // Setup send data task
        sendDataHandler.postDelayed(mPeriodicallySendData, kSendDataInterval);
    }

    @Override
    protected void onPause() {
        super.onPause();
        registerEnabledSensorListeners(false);

        // Remove send data task
        sendDataHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onDestroy() {
        // Retain data
        saveRetainedDataFragment();

        super.onDestroy();
    }

    private Runnable mPeriodicallySendData = new Runnable() {
        @Override
        public void run() { //Runs in a seperate thread, sends data.
            final String[] prefixes = {"!L", "!T", "!N","!Q", "!A", "!G", "!M"};     // same order that kSensorType

            //Update time
            Calendar mCal = Calendar.getInstance();
            mSensorData[kSensorType_Time].values = new float[]{mCal.get(Calendar.HOUR), mCal.get(Calendar.MINUTE)};

            //Update notifications
            mSensorData[kSensorType_Notify].values = NotificationReceived ? new float[]{ 1 } : null; //Only send on new notification.

            for (int i = 0; i < mSensorData.length; i++) {
                SensorData sensorData = mSensorData[i];

                if (sensorData.enabled && sensorData.values != null) {
                    ByteBuffer buffer = ByteBuffer.allocate(2 + sensorData.values.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);

                    // prefix
                    String prefix = prefixes[sensorData.sensorType];
                    buffer.put(prefix.getBytes());

                    // values
                    for (int j = 0; j < sensorData.values.length; j++) {
                        buffer.putFloat(sensorData.values[j]);
                    }

                    byte[] result = buffer.array();
                    Log.d(TAG, "Send data for sensor: " + i);
                    sendDataWithCRC(result);
                }
            }

            sendDataHandler.postDelayed(this, kSendDataInterval);
        }
    };

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_controller, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_help) {
            startHelp();
            return true;
        } else if (id == R.id.action_connected_settings) {
            startConnectedSettings();
            return true;
        } else if (id == R.id.action_refreshcache) {
            if (mBleManager != null) {
                mBleManager.refreshDeviceCache();
            }
        }

        return super.onOptionsItemSelected(item);
    }

    private void startConnectedSettings() {
        // Launch connected settings activity
        Intent intent = new Intent(this, ConnectedSettingsActivity.class);
        startActivityForResult(intent, kActivityRequestCode_ConnectedSettingsActivity);
    }

    private void startHelp() {
        // Launch app help activity
        Intent intent = new Intent(this, CommonHelpActivity.class);
        intent.putExtra("title", getString(R.string.controller_help_title));
        intent.putExtra("help", "controller_help.html");
        startActivity(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 0) {
            if (resultCode < 0) {       // Unexpected disconnect
                setResult(resultCode);
                finish();
            }
        }
    }

    protected synchronized void buildGoogleApiClient() {
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();
    }

    private boolean isLocationEnabled() {
        int locationMode;
        try {
            locationMode = Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE);

        } catch (Settings.SettingNotFoundException e) {
            locationMode = Settings.Secure.LOCATION_MODE_OFF;
        }

        return locationMode != Settings.Secure.LOCATION_MODE_OFF;

    }

    private void registerEnabledSensorListeners(boolean register) {
        /* Not using these - causes out of bounds errors.
        // Accelerometer
        if (register && (mSensorData[kSensorType_Accelerometer].enabled || mSensorData[kSensorType_Quaternion].enabled)) {
            mSensorManager.registerListener(this, mAccelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            mSensorManager.unregisterListener(this, mAccelerometer);
        }

        // Gyroscope
        if (register && mSensorData[kSensorType_Gyroscope].enabled) {
            mSensorManager.registerListener(this, mGyroscope, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            mSensorManager.unregisterListener(this, mGyroscope);
        }

        // Magnetometer
        if (register && (mSensorData[kSensorType_Magnetometer].enabled || mSensorData[kSensorType_Quaternion].enabled)) {
            mSensorManager.registerListener(this, mMagnetometer, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            mSensorManager.unregisterListener(this, mMagnetometer);
        }*/


        // Location
        if (mGoogleApiClient.isConnected()) {
            if (register && mSensorData[kSensorType_Location].enabled) {
                LocationRequest locationRequest = new LocationRequest();
                locationRequest.setInterval(2000);
                locationRequest.setFastestInterval(500);
                locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

                LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, locationRequest, this);
            } else {
                LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient, this);
            }
        }
    }

    public void onClickCloseTooltip(View view) {
        SharedPreferences settings = getSharedPreferences(kPreferences, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = settings.edit();
        editor.putBoolean(kPreferences_uartToolTip, false);
        editor.commit();

        mUartTooltipViewGroup.setVisibility(View.GONE);

    }

    public void onClickToggle(View view) {
        boolean enabled = ((ToggleButton) view).isChecked();
        int groupPosition = (Integer) view.getTag();

        // Special check for location data
        if (groupPosition == kSensorType_Location) {
            // Detect if location is enabled or warn user
            final boolean isLocationEnabled = isLocationEnabled();
            if (!isLocationEnabled) {
                new AlertDialog.Builder(this)
                        .setMessage(getString(R.string.controller_location_disabled))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        }

        // Enable sensor
        mSensorData[groupPosition].enabled = enabled;
        registerEnabledSensorListeners(true);

        // Expand / Collapse
        if (enabled) {
            mControllerListView.expandGroup(groupPosition, true);
        } else {
            mControllerListView.collapseGroup(groupPosition);
        }
    }

    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Do something here if sensor accuracy changes.
    }

    @Override
    public final void onSensorChanged(SensorEvent event) {
        /* Not using these - and they cause out of bounds errors.
        int sensorType = event.sensor.getType();
        if (sensorType == Sensor.TYPE_ACCELEROMETER) {
            //       Log.d(TAG, "Received data for accelerometer / quaternion");
            mSensorData[kSensorType_Accelerometer].values = event.values;

            updateOrientation();            // orientation depends on Accelerometer and Magnetometer
            mControllerListAdapter.notifyDataSetChanged();
        } else if (sensorType == Sensor.TYPE_GYROSCOPE) {
            //       Log.d(TAG, "Received data for gyroscope");
            mSensorData[kSensorType_Gyroscope].values = event.values;

            mControllerListAdapter.notifyDataSetChanged();
        } else if (sensorType == Sensor.TYPE_MAGNETIC_FIELD) {
//            Log.d(TAG, "Received data for magnetometer / quaternion");
            mSensorData[kSensorType_Magnetometer].values = event.values;

            updateOrientation();            // orientation depends on Accelerometer and Magnetometer
            mControllerListAdapter.notifyDataSetChanged();
        }
        */
    }

    private void updateOrientation() {
        float[] lastAccelerometer = mSensorData[kSensorType_Accelerometer].values;
        float[] lastMagnetometer = mSensorData[kSensorType_Magnetometer].values;
        if (lastAccelerometer != null && lastMagnetometer != null) {
            SensorManager.getRotationMatrix(mRotation, null, lastAccelerometer, lastMagnetometer);
            SensorManager.getOrientation(mRotation, mOrientation);

            final boolean kUse4Components = true;
            if (kUse4Components) {
                SensorManager.getQuaternionFromVector(mQuaternion, mOrientation);
                // Quaternions in Android are stored as [w, x, y, z], so we change it to [x, y, z, w]
                float w = mQuaternion[0];
                mQuaternion[0] = mQuaternion[1];
                mQuaternion[1] = mQuaternion[2];
                mQuaternion[2] = mQuaternion[3];
                mQuaternion[3] = w;

                mSensorData[kSensorType_Quaternion].values = mQuaternion;
            } else {
                mSensorData[kSensorType_Quaternion].values = mOrientation;
            }
        }
    }

    // region BleManagerListener
    @Override
    public void onConnected() {
    }

    @Override
    public void onConnecting() {

    }

    @Override
    public void onDisconnected() {
        Log.d(TAG, "Disconnected. Back to previous activity");
        setResult(-1);      // Unexpected Disconnect
        finish();
    }

    @Override
    public void onServicesDiscovered() {
        mUartService = mBleManager.getGattService(UUID_SERVICE);

        mBleManager.enableNotification(mUartService, UUID_RX, true);
    }
    /*public void onServicesDiscovered() {
        //mUartService = mBleManager.getGattService(UUID_SERVICE);
    }*/

    /*@Override
    public void onDataAvailable(BluetoothGattCharacteristic characteristic) {

    }

    @Override
    public void onDataAvailable(BluetoothGattDescriptor descriptor) {

    }*/

    @Override
    public void onReadRemoteRssi(int rssi) {

    }
    // endregion

    // region Google API Callbacks
    @Override
    public void onConnected(Bundle bundle) {
        Log.d(TAG, "Google Play Services connected");

        setLastLocation(LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient));
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.d(TAG, "Google Play Services suspended");

    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.d(TAG, "Google Play Services connection failed");


    }
    // endregion

    // region LocationListener
    @Override
    public void onLocationChanged(Location location) {
        setLastLocation(location);

    }

    // endregion

    private void setLastLocation(Location location) {
        if (location != null) {
            SensorData sensorData = mSensorData[kSensorType_Location];

            float[] values = new float[3];
            values[0] = (float) location.getLatitude();
            values[1] = (float) location.getLongitude();
            values[2] = (float) location.getAltitude();
            sensorData.values = values;
        }
        mControllerListAdapter.notifyDataSetChanged();
    }


    // region ExpandableListAdapter
    private class SensorData { //This is the data type for SensorData.
        public int sensorType;
        public float[] values;
        public boolean enabled;
    }

    private class ExpandableListAdapter extends BaseExpandableListAdapter {
        private Activity mActivity;
        private SensorData[] mSensorData;

        public ExpandableListAdapter(Activity activity, SensorData[] sensorData) {
            mActivity = activity;
            mSensorData = sensorData;
        }

        @Override
        public int getGroupCount() {
            return kNumSensorTypes;
        }

        @Override
        public int getChildrenCount(int groupPosition) {
            switch (groupPosition) {
                case kSensorType_Quaternion:
                    return 4;       // Quaternion (x, y, z, w)
                case kSensorType_Location: {
                    SensorData sensorData = mSensorData[groupPosition];
                    return sensorData.values == null ? 1 : 3;
                }
                default:
                    return 3;
            }
        }

        @Override
        public Object getGroup(int groupPosition) {
            return null;
        }

        @Override
        public Object getChild(int groupPosition, int childPosition) {
            return null;
        }

        @Override
        public long getGroupId(int groupPosition) {
            return groupPosition;
        }

        @Override
        public long getChildId(int groupPosition, int childPosition) {
            return 0;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public View getGroupView(int groupPosition, boolean isExpanded, View convertView, ViewGroup parent) {
            //Populates the menu
            if (convertView == null) {
                convertView = mActivity.getLayoutInflater().inflate(R.layout.layout_controller_streamitem_title, parent, false);
            }

            // Tag
            convertView.setTag(groupPosition);

            // UI
            TextView nameTextView = (TextView) convertView.findViewById(R.id.nameTextView);
            String[] names = getResources().getStringArray(R.array.controller_stream_items);
            nameTextView.setText(names[groupPosition]);

            ToggleButton enableToggleButton = (ToggleButton) convertView.findViewById(R.id.enableToggleButton);
            enableToggleButton.setTag(groupPosition);
            enableToggleButton.setChecked(mSensorData[groupPosition].enabled);
            enableToggleButton.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View view, MotionEvent event) {
                    // Set onclick to action_down to avoid losing state because the button is recreated when notifiydatasetchanged is called and it could be really fast (before the user has time to generate a ACTION_UP event)
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        ToggleButton button = (ToggleButton) view;
                        button.setChecked(!button.isChecked());
                        onClickToggle(view);
                        return true;
                    }
                    return false;
                }
            });

            return convertView;
        }

        @Override
        public View getChildView(int groupPosition, int childPosition, boolean isLastChild, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = mActivity.getLayoutInflater().inflate(R.layout.layout_controller_streamitem_child, parent, false);
            }

            // Value
            TextView valueTextView = (TextView) convertView.findViewById(R.id.valueTextView);

            String valueString = null;
            SensorData sensorData = mSensorData[groupPosition];
            if (sensorData.values != null && sensorData.values.length > childPosition) {
                if (sensorData.sensorType == kSensorType_Location) {
                    final String[] prefix = {"lat:", "long:", "alt:"};
                    valueString = prefix[childPosition] + " " + sensorData.values[childPosition];
                } else {
                    final String[] prefix = {"x:", "y:", "z:", "w:"};
                    valueString = prefix[childPosition] + " " + sensorData.values[childPosition];
                }
            } else {        // Invalid values
                if (sensorData.sensorType == kSensorType_Location) {
                    if (sensorData.values == null) {
                        valueString = getString(R.string.controller_location_unknown);
                    }
                }
            }
            valueTextView.setText(valueString);

            return convertView;
        }

        @Override
        public boolean isChildSelectable(int groupPosition, int childPosition) {
            return false;
        }
    }

    // endregion

    // region DataFragment
    public static class DataFragment extends Fragment {
        private SensorData[] mSensorData;
        private GoogleApiClient mGoogleApiClient;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setRetainInstance(true);
        }
    }

    private void restoreRetainedDataFragment() {
        // find the retained fragment
        FragmentManager fm = getFragmentManager();
        mRetainedDataFragment = (DataFragment) fm.findFragmentByTag(TAG);

        if (mRetainedDataFragment == null) {
            // Create
            mRetainedDataFragment = new DataFragment();
            fm.beginTransaction().add(mRetainedDataFragment, TAG).commit();

            // Init
            mSensorData = new SensorData[kNumSensorTypes];
            for (int i = 0; i < kNumSensorTypes; i++) {
                SensorData sensorData = new SensorData();
                sensorData.sensorType = i;
                sensorData.enabled = true; //Sensors on by default.
                mSensorData[i] = sensorData;
            }
            /*mSensorData[kSensorType_Location].enabled = true; //Enable location by default.
            mSensorData[kSen].enabled = true; //Enable clock by default.
            mSensorData[6].enabled = true; //Enable notifications by default.*/

        } else {
            // Restore status
            mSensorData = mRetainedDataFragment.mSensorData;
        }
    }

    private void saveRetainedDataFragment() {
        mRetainedDataFragment.mSensorData = mSensorData;
    }
    // endregion

    //Notification reciever.
    class NotificationReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Received notificationEvent");
            TextView txtView = (TextView) findViewById(R.id.textView);
            String temp = intent.getStringExtra("notification_event") + "\n";
            Log.d(TAG, temp);
            txtView.setText(temp);

            if(temp.indexOf("onNotificationPosted :") == 0) { //New notification
                NotificationReceived = true;
            }
        }
    }

    @Override
    public void onDataAvailable(BluetoothGattCharacteristic characteristic) { //Receives over BLE. Requires extra work in onServicesDiscovered();
        // UART RX
        Log.d(TAG, "Received Data");
        if (characteristic.getService().getUuid().toString().equalsIgnoreCase(UUID_SERVICE)) {
            if (characteristic.getUuid().toString().equalsIgnoreCase(UUID_RX)) {
                final String data = new String(characteristic.getValue(), Charset.forName("UTF-8"));

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Log.d(TAG, "Running in UI thread: '"+data+"'");
                        if (data.trim().equalsIgnoreCase("!Nack")) {
                            //Notification was received - stop sending it.
                            NotificationReceived = false;
                            Log.d(TAG, "Cleared notification");
                        }
                    }
                });
            }
        }
    }

    @Override
    public void onDataAvailable(BluetoothGattDescriptor descriptor) {

    }
}