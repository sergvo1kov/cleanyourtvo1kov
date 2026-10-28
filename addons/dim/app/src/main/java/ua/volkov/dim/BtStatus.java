package ua.volkov.dim;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;

/**
 * The connected remote and Bluetooth headphones, with their battery where the device reports it.
 * Android keeps BluetoothDevice.isConnected() and getBatteryLevel() hidden on Android 11, so they are
 * read by reflection; anything that fails just means "unknown".
 */
final class BtStatus {
    static final class Info {
        boolean remote;
        String remoteName = "";
        int remoteBattery = -1;
        BluetoothDevice remoteDevice;
        boolean phones;
        String phonesName = "";
        int phonesBattery = -1;
    }

    private BtStatus() {}

    @SuppressWarnings("MissingPermission")
    static Info read(Context ctx) {
        Info i = new Info();
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null || !a.isEnabled()) return i;
            Set<BluetoothDevice> bonded = a.getBondedDevices();
            if (bonded == null) return i;
            for (BluetoothDevice d : bonded) {
                if (!call(d, "isConnected", false)) continue;
                String name = d.getName() == null ? "" : d.getName();
                int battery = call(d, "getBatteryLevel", -1);
                BluetoothClass cls = d.getBluetoothClass();
                int major = cls == null ? 0 : cls.getMajorDeviceClass();
                String low = name.toLowerCase(Locale.ROOT);
                boolean audio = major == BluetoothClass.Device.Major.AUDIO_VIDEO
                        && !low.contains("remote") && !low.contains("rc");
                if (audio && !i.phones) {
                    i.phones = true;
                    i.phonesName = name;
                    i.phonesBattery = battery;
                } else if (!audio && !i.remote) {
                    i.remote = true;
                    i.remoteName = name;
                    i.remoteBattery = battery;
                    i.remoteDevice = d;
                }
            }
        } catch (RuntimeException ignored) {
            // no Bluetooth permission or service: show nothing
        }
        if (i.remote && i.remoteBattery < 0 && gattBattery >= 0) i.remoteBattery = gattBattery;
        return i;
    }

    // ---- remote battery straight from the remote: the standard GATT Battery Service (0x180F / 0x2A19)
    private static final java.util.UUID BATTERY_SERVICE = java.util.UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final java.util.UUID BATTERY_LEVEL = java.util.UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    static volatile int gattBattery = -1;
    private static volatile long gattAt;
    private static volatile boolean gattBusy;

    /** Asks the remote for its battery at most every 10 minutes; onDone runs with the new value. */
    @SuppressWarnings("MissingPermission")
    static void readRemoteBattery(Context ctx, BluetoothDevice d, Runnable onDone) {
        long now = System.currentTimeMillis();
        if (d == null || (gattBusy && now - gattAt < 60_000L)) return;   // a stuck attempt is given up after a minute
        if (!gattBusy && gattBattery >= 0 && now - gattAt < 10 * 60_000L) return;
        if (!gattBusy && gattBattery < 0 && now - gattAt < 2 * 60_000L && gattAt > 0) return;
        gattBusy = true;
        gattAt = System.currentTimeMillis();
        try {
            d.connectGatt(ctx.getApplicationContext(), false, new android.bluetooth.BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(android.bluetooth.BluetoothGatt g, int status, int state) {
                    if (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED) g.discoverServices();
                    else if (state == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) done(g);
                }

                @Override
                public void onServicesDiscovered(android.bluetooth.BluetoothGatt g, int status) {
                    android.bluetooth.BluetoothGattService sv = g.getService(BATTERY_SERVICE);
                    android.bluetooth.BluetoothGattCharacteristic ch = sv == null ? null : sv.getCharacteristic(BATTERY_LEVEL);
                    if (ch == null || !g.readCharacteristic(ch)) done(g);
                }

                @Override
                public void onCharacteristicRead(android.bluetooth.BluetoothGatt g,
                                                 android.bluetooth.BluetoothGattCharacteristic ch, int status) {
                    if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS && ch.getValue() != null && ch.getValue().length > 0) {
                        int v = ch.getValue()[0] & 0xFF;
                        if (v <= 100) gattBattery = v;
                    }
                    done(g);
                    if (onDone != null) onDone.run();
                }

                private void done(android.bluetooth.BluetoothGatt g) {
                    gattBusy = false;
                    try {
                        g.close();
                    } catch (RuntimeException ignored) {
                        // already closed
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE);
        } catch (RuntimeException e) {
            gattBusy = false;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T call(BluetoothDevice d, String method, T fallback) {
        try {
            Method m = BluetoothDevice.class.getMethod(method);
            Object v = m.invoke(d);
            return v == null ? fallback : (T) v;
        } catch (Exception e) {
            return fallback;
        }
    }
}
