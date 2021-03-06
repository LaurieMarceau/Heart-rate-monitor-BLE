package lauriemarceau.heart_rate_monitor_ble;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import static lauriemarceau.heart_rate_monitor_ble.GattAttributes.BATTERY_LEVEL_UUID;
import static lauriemarceau.heart_rate_monitor_ble.GattAttributes.BATTERY_SERVICE_UUID;
import static lauriemarceau.heart_rate_monitor_ble.GattAttributes.CLIENT_CHARACTERISTIC_CONFIG_UUID;
import static lauriemarceau.heart_rate_monitor_ble.GattAttributes.HEART_RATE_CONTROL_POINT_CHAR_UUID;
import static lauriemarceau.heart_rate_monitor_ble.GattAttributes.HEART_RATE_MEASUREMENT_CHAR_UUID;
import static lauriemarceau.heart_rate_monitor_ble.GattAttributes.HEART_RATE_SERVICE_UUID;

/**
 * BluetoothLeService, everything related to BLE is handled here
 * Extends service : Android application component without a UI that runs on the main thread
 */
public class BluetoothLeService extends Service {

    public final static String ACTION_CONNECTED =
            "lauriemarceau.heart_rate_monitor_ble.ACTION_GATT_CONNECTED";
    public final static String ACTION_DISCONNECTED =
            "lauriemarceau.heart_rate_monitor_ble.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_DATA_AVAILABLE =
            "lauriemarceau.heart_rate_monitor_ble.ACTION_DATA_AVAILABLE";
    public final static String EXTRA_DATA_HEART_RATE =
            "lauriemarceau.heart_rate_monitor_ble.EXTRA_DATA_HEART_RATE";
    public final static String EXTRA_DATA_BATTERY =
            "lauriemarceau.heart_rate_monitor_ble.EXTRA_DATA_BATTERY";

    private final static String TAG = BluetoothLeService.class.getSimpleName();
    private final IBinder mBinder = new LocalBinder();

    private BluetoothGatt mGatt;
    private BluetoothAdapter mBluetoothAdapter;

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        close();
        return super.onUnbind(intent);
    }

    public class LocalBinder extends Binder {
        BluetoothLeService getService() {
            return BluetoothLeService.this;
        }
    }

    /**
     * Initialize the bluetoothAdapter {@link BluetoothAdapter}
     * @return false when bluetooth initialization wasn't successful, true otherwise
     */
    public boolean initBluetooth() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);

        if (bluetoothManager != null) {
            mBluetoothAdapter = bluetoothManager.getAdapter();
            return true;
        } else {
            Log.e(TAG, "Bluetooth adapter init didn't work");
            return false;
        }
    }

    /**
     * Connect to a BLE device
     * @param address MAC address
     * @return true if the connection was successful, false otherwise
     */
    public boolean connectToDevice(final String address) {
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "No Bluetooth adapter or no address");
            return false;
        }
        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);

        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            return false;
        }

        DeviceActivity.runOnUI(() -> {
            mGatt = device.connectGatt(this, true, gattClientCallback);
            Log.d(TAG, "Connecting to selected device");
        });
        return true;
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        if (mGatt != null) {
            mGatt.close();
            mGatt = null;
        }
    }

    /**
     * Disconnect the gatt server when a connection state changed happened and failed
     * i.e. when GATT_FAILED or !GATT_SUCCESS or STATE_DISCONNECTED
     */
    public void disconnectGattServer() {
        if (mGatt != null) {
            mGatt.disconnect();
            mGatt.close();
        }
    }

    /**
     * Gatt client callback: append the text view, show/hide contextual buttons
     * and discover services
     */
    private final BluetoothGattCallback gattClientCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            String intentAction;

            if (status == BluetoothGatt.GATT_FAILURE) {
                DeviceActivity.runOnUI(() -> {
                    Log.d(TAG, "Connection failure, disconnected from server");
                    disconnectGattServer();
                });
                return;
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                DeviceActivity.runOnUI(() -> {
                    Log.d(TAG, "Connection failure, disconnected from server");
                    disconnectGattServer();
                });
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                intentAction = ACTION_CONNECTED;

                broadcastUpdate(intentAction);
                DeviceActivity.runOnUI(() -> {
                    Log.d(TAG, "Success: connecting to gatt and discovering services");
                    Log.i(TAG, "Attempting to start service discovery: " +
                            gatt.discoverServices());
                });
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_DISCONNECTED;

                broadcastUpdate(intentAction);
                DeviceActivity.runOnUI(() -> {
                    Log.d(TAG, "Connection failure, disconnected from server");
                    disconnectGattServer();
                });
            }
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, final int status) {
            super.onServicesDiscovered(gatt, status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Device service discovery unsuccessful, status " + status);
                return;
            }
            gatt.getServices();
            setHeartRateNotification(gatt, true);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                                      int status){
            BluetoothGattCharacteristic characteristic =
                    gatt.getService(HEART_RATE_SERVICE_UUID)
                            .getCharacteristic(HEART_RATE_CONTROL_POINT_CHAR_UUID);

            /* Write to Heart Rate Control Point Characteristic to get data streaming */
            characteristic.setValue(new byte[]{1, 1});
            gatt.writeCharacteristic(characteristic);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
        }
    };

    /**
     * Enables the notification mode, writing the descriptor tells the sensor to
     * start streaming data
     * @param gatt {@link BluetoothGatt}
     * @param enabled set notification on for the heart rate service
     */
    public void setHeartRateNotification(BluetoothGatt gatt,
                                         boolean enabled) {
        BluetoothGattCharacteristic characteristic =
                gatt.getService(HEART_RATE_SERVICE_UUID)
                        .getCharacteristic(HEART_RATE_MEASUREMENT_CHAR_UUID);

        /* Enable notification  on the heart rate measurement characteristic */
        gatt.setCharacteristicNotification(characteristic, enabled);

        BluetoothGattDescriptor descriptor =
                characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID);

        descriptor.setValue(
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);

        gatt.writeDescriptor(descriptor);
    }

    /**
     * Notify DeviceActivity of key events happening in BluetoothLeService
     * @param action ACTION_CONNECTED, ACTION_DISCONNECTED, ACTION_DATA_AVAILABLE
     */
    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        sendBroadcast(intent);
    }

    /**
     * Notify DeviceActivity of key events happening in BluetoothLeService
     * @param action ACTION_CONNECTED, ACTION_DISCONNECTED, ACTION_DATA_AVAILABLE
     * @param characteristic {@link BluetoothGattCharacteristic}
     */
    private void broadcastUpdate(final String action,
                                 final BluetoothGattCharacteristic characteristic) {
        final Intent intent = new Intent(action);

        /* Following heart rate profile specification */
        if (HEART_RATE_MEASUREMENT_CHAR_UUID.equals(characteristic.getUuid())) {
            int flag = characteristic.getProperties();
            int format = -1;

            if ((flag & 0x01) != 0) {
                format = BluetoothGattCharacteristic.FORMAT_UINT16;
                Log.d(TAG, "Heart rate format UINT16.");
            } else {
                format = BluetoothGattCharacteristic.FORMAT_UINT8;
                Log.d(TAG, "Heart rate format UINT8.");
            }
            final int heartRate = characteristic.getIntValue(format, 1);
            Log.d(TAG, String.format("Received heart rate: %d", heartRate));
            intent.putExtra(EXTRA_DATA_HEART_RATE, String.valueOf(heartRate));

        /* Get the battery level */
        } else if (BATTERY_LEVEL_UUID.equals((characteristic.getUuid()))){
            final int batteryLevel = characteristic.getIntValue
                    (BluetoothGattCharacteristic.FORMAT_UINT8, 0);

            if (batteryLevel != 0) {
                Log.d(TAG, String.format("Received battery level: %d", batteryLevel));
                intent.putExtra(EXTRA_DATA_BATTERY, String.valueOf(batteryLevel));
            }
        }
        sendBroadcast(intent);
    }

    /**
     * Get the battery level from the battery service of the heart rate monitor
     */
    public void getBattery() {
        BluetoothGattService batteryService = mGatt.getService(BATTERY_SERVICE_UUID);

        if (batteryService == null) {
            Log.d(TAG, "Battery service not found!");
            return;
        }
        BluetoothGattCharacteristic batteryLevel =
                batteryService.getCharacteristic(BATTERY_LEVEL_UUID);

        if (batteryLevel == null) {
            Log.d(TAG, "Battery level not found!");
            return;
        }
        Log.v(TAG, "batteryLevel = " + mGatt.readCharacteristic(batteryLevel));
        mGatt.readCharacteristic(batteryLevel);
    }
}
