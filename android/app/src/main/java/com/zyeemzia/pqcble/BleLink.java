package com.zyeemzia.pqcble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Blocking wrapper around one BluetoothGatt connection to the ESP32.
 * Android delivers GATT results as callbacks on a binder thread; each operation
 * here issues one request and waits for its callback, so the experiment thread
 * can run the handshake as straight-line code. Android allows one outstanding
 * GATT operation at a time, which this enforces naturally.
 */
@SuppressLint("MissingPermission")
final class BleLink {
    static final UUID SVC = UUID.fromString("7a1e0001-5c2b-4c7e-9f3a-1d2e3f405a01");
    static final UUID CTRL = UUID.fromString("7a1e0002-5c2b-4c7e-9f3a-1d2e3f405a01");
    static final UUID PK = UUID.fromString("7a1e0003-5c2b-4c7e-9f3a-1d2e3f405a01");
    static final UUID CT = UUID.fromString("7a1e0004-5c2b-4c7e-9f3a-1d2e3f405a01");
    static final UUID RES = UUID.fromString("7a1e0005-5c2b-4c7e-9f3a-1d2e3f405a01");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** One GATT callback result: status plus an optional int value (e.g. MTU). */
    private static final class Ev {
        final String kind; final int status; final int value;
        Ev(String k, int s, int v) { kind = k; status = s; value = v; }
    }

    private final LinkedBlockingQueue<Ev> events = new LinkedBlockingQueue<>();
    final LinkedBlockingQueue<byte[]> pkChunks = new LinkedBlockingQueue<>();
    final LinkedBlockingQueue<byte[]> resValues = new LinkedBlockingQueue<>();
    volatile boolean connected = false;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic ctrl, ct;
    int mtu = 23;

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            connected = state == BluetoothProfile.STATE_CONNECTED;
            events.add(new Ev(connected ? "conn" : "disc", status, state));
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) { events.add(new Ev("svc", status, 0)); }
        @Override public void onMtuChanged(BluetoothGatt g, int m, int status) { events.add(new Ev("mtu", status, m)); }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) { events.add(new Ev("desc", status, 0)); }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) { events.add(new Ev("write", status, 0)); }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {
            if (PK.equals(c.getUuid())) pkChunks.add(v.clone());
            else if (RES.equals(c.getUuid())) resValues.add(v.clone());
        }
    };

    private Ev await(String kind, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) throw new Exception("timeout waiting for " + kind);
            Ev e = events.poll(left, TimeUnit.MILLISECONDS);
            if (e == null) throw new Exception("timeout waiting for " + kind);
            if (e.kind.equals("disc")) { connected = false; throw new Exception("disconnected (status " + e.status + ") while waiting for " + kind); }
            if (e.kind.equals(kind)) {
                if (e.status != BluetoothGatt.GATT_SUCCESS) throw new Exception(kind + " failed, status " + e.status);
                return e;
            }
        }
    }

    /** Connect, discover, set MTU (requestMtu<=23 means leave the default), subscribe. */
    void open(Context ctx, BluetoothDevice dev, int requestMtu) throws Exception {
        events.clear(); pkChunks.clear(); resValues.clear();
        gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
        await("conn", 20000);
        Thread.sleep(300);
        if (!gatt.discoverServices()) throw new Exception("discoverServices refused");
        await("svc", 20000);
        mtu = 23;
        if (requestMtu > 23) {
            if (!gatt.requestMtu(requestMtu)) throw new Exception("requestMtu refused");
            mtu = await("mtu", 10000).value;
        }
        BluetoothGattService s = gatt.getService(SVC);
        if (s == null) throw new Exception("PQC service not found");
        ctrl = s.getCharacteristic(CTRL);
        ct = s.getCharacteristic(CT);
        subscribe(s.getCharacteristic(PK));
        subscribe(s.getCharacteristic(RES));
    }

    private void subscribe(BluetoothGattCharacteristic c) throws Exception {
        if (!gatt.setCharacteristicNotification(c, true)) throw new Exception("setNotification refused");
        BluetoothGattDescriptor d = c.getDescriptor(CCCD);
        int r = gatt.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        if (r != BluetoothGatt.GATT_SUCCESS) throw new Exception("writeDescriptor refused " + r);
        await("desc", 10000);
    }

    boolean priority(int p) { return gatt.requestConnectionPriority(p); }

    /** Write one value and wait for the stack's callback (for NO_RESPONSE this is local flow control). */
    void write(boolean toCtrl, byte[] v, boolean withResponse) throws Exception {
        BluetoothGattCharacteristic c = toCtrl ? ctrl : ct;
        int type = withResponse ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        for (int tries = 0; ; tries++) {
            int r = gatt.writeCharacteristic(c, v, type);
            if (r == BluetoothGatt.GATT_SUCCESS) break;
            // ERROR_GATT_WRITE_REQUEST_BUSY (201): stack queue full, retry briefly.
            if (tries > 200 || !connected) throw new Exception("writeCharacteristic refused " + r);
            Thread.sleep(2);
        }
        await("write", 10000);
    }

    void close() {
        if (gatt != null) {
            try { gatt.disconnect(); Thread.sleep(500); } catch (Exception ignored) { }
            gatt.close();
            gatt = null;
        }
        connected = false;
    }
}
