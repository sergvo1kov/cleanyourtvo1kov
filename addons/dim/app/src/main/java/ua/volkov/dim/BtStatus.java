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
                }
            }
        } catch (RuntimeException ignored) {
            // no Bluetooth permission or service: show nothing
        }
        return i;
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
