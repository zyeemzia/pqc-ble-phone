package com.zyeemzia.pqcble;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.crypto.digests.SHA3Digest;
import org.bouncycastle.crypto.kems.MLKEMGenerator;
import org.bouncycastle.crypto.params.MLKEMParameters;
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Phone side of the ML-KEM-768 over BLE study. The phone is the BLE central:
 * it asks the ESP32 for a handshake, reassembles the 1184 B public key from
 * notifications, encapsulates (BouncyCastle ML-KEM-768), writes the 1088 B
 * ciphertext in MTU-sized chunks and checks the shared-secret tag the ESP32
 * returns. Every handshake is logged, including failures.
 *
 * Sweep: ATT MTU x Android connection priority x ESP32 CPU clock.
 * Phone energy: BatteryManager CURRENT_NOW sampled in the background into
 * samples.csv, tagged with the current phase, so the analysis can compare the
 * handshake phases of each cell with that cell's idle baseline. The phone must
 * be on battery (USB unplugged) for those samples to mean anything; the
 * plugged state is recorded in every sample.
 *
 * Output: Android/data/com.zyeemzia.pqcble/files/run_<time>/
 */
@SuppressLint({"MissingPermission", "SetTextI18n"})
public class MainActivity extends Activity {
    static final int PK_BYTES = 1184, CT_BYTES = 1088, RES_BYTES = 20;
    static final int[] MTUS = {23, 185, 247, 517};
    static final int[] PRIOS = {BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER,
            BluetoothGatt.CONNECTION_PRIORITY_BALANCED, BluetoothGatt.CONNECTION_PRIORITY_HIGH};
    static final String[] PRIO_NAMES = {"LOW_POWER", "BALANCED", "HIGH"};
    static final int[] MHZ = {80, 160, 240};
    static final long IDLE_BASELINE_MS = 10000, GAP_MS = 200;

    private TextView log;
    private EditText nField;
    private Button quick, full, stop;
    private volatile boolean stopReq = false;
    private volatile String phase = "setup";
    private Thread worker, sampler;
    private BatteryManager bm;
    private boolean lastTagOk = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // fixed screen state
        bm = (BatteryManager) getSystemService(BATTERY_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);
        TextView nLabel = new TextView(this);
        nLabel.setText("Handshakes per cell:");
        nField = new EditText(this);
        nField.setInputType(InputType.TYPE_CLASS_NUMBER);
        nField.setText("100");
        quick = new Button(this); quick.setText("Quick test (10 handshakes, MTU 517)");
        full = new Button(this); full.setText("Full sweep (4 MTU x 3 priority x 3 clock)");
        stop = new Button(this); stop.setText("Stop"); stop.setEnabled(false);
        log = new TextView(this);
        log.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(log);
        root.addView(nLabel); root.addView(nField); root.addView(quick); root.addView(full); root.addView(stop);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        quick.setOnClickListener(v -> start(true));
        full.setOnClickListener(v -> start(false));
        stop.setOnClickListener(v -> { stopReq = true; say("stop requested"); });

        String[] perms = {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        for (String p : perms) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) { requestPermissions(perms, 1); break; }
        say("Ready. Unplug USB before a real run (phone energy needs battery power).");
    }

    void say(String s) {
        runOnUiThread(() -> { log.append(s + "\n"); });
    }

    void start(boolean quickRun) {
        if (worker != null && worker.isAlive()) return;
        int n;
        try { n = Integer.parseInt(nField.getText().toString().trim()); } catch (Exception e) { n = 100; }
        final int perCell = quickRun ? 10 : Math.max(1, n);
        stopReq = false;
        quick.setEnabled(false); full.setEnabled(false); stop.setEnabled(true);
        worker = new Thread(() -> {
            try { run(quickRun, perCell); }
            catch (Exception e) { say("RUN FAILED: " + e); }
            finally {
                phase = "done";
                runOnUiThread(() -> { quick.setEnabled(true); full.setEnabled(true); stop.setEnabled(false); });
            }
        });
        worker.start();
    }

    BluetoothDevice scan() throws Exception {
        BluetoothManager m = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothLeScanner sc = m.getAdapter().getBluetoothLeScanner();
        if (sc == null) throw new Exception("Bluetooth is off");
        LinkedBlockingQueue<BluetoothDevice> q = new LinkedBlockingQueue<>();
        ScanCallback cb = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) { q.offer(r.getDevice()); }
        };
        ScanFilter f = new ScanFilter.Builder().setDeviceName("PQC-ESP32").build();
        sc.startScan(Collections.singletonList(f),
                new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb);
        BluetoothDevice d = q.poll(20, TimeUnit.SECONDS);
        sc.stopScan(cb);
        if (d == null) throw new Exception("PQC-ESP32 not found (powered? another device connected to it?)");
        return d;
    }

    void run(boolean quickRun, int perCell) throws Exception {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = new File(getExternalFilesDir(null), "run_" + stamp + (quickRun ? "_quick" : ""));
        if (!dir.mkdirs()) throw new Exception("cannot create " + dir);
        say("Output: " + dir);

        try (PrintWriter meta = new PrintWriter(new FileWriter(new File(dir, "meta.txt")))) {
            meta.println("model=" + Build.MANUFACTURER + " " + Build.MODEL);
            meta.println("android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT);
            meta.println("soc=" + Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL);
            meta.println("crypto=BouncyCastle bcprov-jdk18on 1.87 ML-KEM-768");
            meta.println("per_cell=" + perCell + " quick=" + quickRun);
            meta.println("idle_baseline_ms=" + IDLE_BASELINE_MS + " gap_ms=" + GAP_MS);
            meta.println("screen=on (FLAG_KEEP_SCREEN_ON)");
            meta.println("current_sign_convention=raw BatteryManager.CURRENT_NOW microamps (vendor-specific sign)");
        }

        PrintWriter hs = new PrintWriter(new FileWriter(new File(dir, "handshakes.csv")));
        hs.println("cell,mtu_req,mtu,priority,mhz_req,i,t_start_ns,total_ms,pk_rx_ms,encaps_ms,ct_tx_ms,res_wait_ms,"
                + "pk_notifs,ct_writes,esp_status,esp_cpu_mhz,esp_keygen_us,esp_pk_send_us,esp_ct_rx_us,esp_decaps_us,tag_ok,error");
        startSampler(new File(dir, "samples.csv"));

        BluetoothDevice dev = scan();
        say("Found " + dev.getAddress());
        int[] mtus = quickRun ? new int[]{517} : MTUS;
        int[] prioIdx = quickRun ? new int[]{2} : new int[]{0, 1, 2};
        int[] mhzs = quickRun ? new int[]{240} : MHZ;
        int cell = 0, ok = 0, fail = 0;
        SecureRandom rng = new SecureRandom();

        try {
            for (int mtuReq : mtus) {
                BleLink link = new BleLink();
                link.open(this, dev, mtuReq);
                say("Connected, MTU requested " + mtuReq + " got " + link.mtu);
                try {
                    for (int pi : prioIdx) {
                        link.priority(PRIOS[pi]);
                        Thread.sleep(2000); // let the connection update settle
                        for (int mhz : mhzs) {
                            if (stopReq) return;
                            cell++;
                            String cellId = "m" + mtuReq + "_" + PRIO_NAMES[pi] + "_f" + mhz;
                            link.write(true, new byte[]{'F', (byte) (mhz & 0xff), (byte) (mhz >> 8)}, true);
                            Thread.sleep(500);
                            phase = "idle:" + cellId;
                            Thread.sleep(IDLE_BASELINE_MS);
                            say("Cell " + cell + " " + cellId);
                            for (int i = 1; i <= perCell && !stopReq; i++) {
                                phase = "hs:" + cellId;
                                String row = handshake(link, rng, cellId, mtuReq, PRIO_NAMES[pi], mhz, i);
                                phase = "gap:" + cellId;
                                hs.println(row);
                                if (lastTagOk) ok++; else fail++;
                                if (!link.connected) throw new Exception("link lost");
                                Thread.sleep(GAP_MS);
                            }
                            hs.flush();
                            say("  ok=" + ok + " fail=" + fail);
                        }
                    }
                } finally {
                    link.close();
                    Thread.sleep(1500);
                }
            }
        } finally {
            hs.close();
            phase = "done";
            say("Finished. ok=" + ok + " fail=" + fail + "\nFiles in " + dir);
        }
    }

    /** One handshake; returns a CSV row. tag_ok is the single 0/1 column right before error. */
    String handshake(BleLink link, SecureRandom rng, String cellId, int mtuReq, String prio, int mhz, int i) {
        link.pkChunks.clear(); link.resValues.clear();
        long t0 = SystemClock.elapsedRealtimeNanos(), t1 = 0, t2 = 0, t3 = 0, t4 = 0;
        int notifs = 0, writes = 0;
        String err = "";
        byte[] res = null;
        boolean tagOk = false;
        try {
            link.write(true, new byte[]{'K'}, true);
            byte[] pk = new byte[PK_BYTES];
            int got = 0;
            StringBuilder lens = new StringBuilder();
            while (got < PK_BYTES) {
                byte[] c = link.pkChunks.poll(10, TimeUnit.SECONDS);
                if (c == null) throw new Exception("pk timeout at " + got + " B; chunks " + lens);
                int n = Math.min(c.length, PK_BYTES - got);
                System.arraycopy(c, 0, pk, got, n);
                got += n; notifs++; lens.append(c.length).append('/');
            }
            t1 = SystemClock.elapsedRealtimeNanos();
            MLKEMPublicKeyParameters pub = new MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, pk);
            SecretWithEncapsulation sw = new MLKEMGenerator(rng).generateEncapsulated(pub);
            byte[] ct = sw.getEncapsulation();
            byte[] ss = sw.getSecret();
            t2 = SystemClock.elapsedRealtimeNanos();
            // ATT attribute values are capped at 512 B, so a 517 MTU still writes 512 B chunks.
            int chunk = Math.min(512, Math.max(20, link.mtu - 3));
            for (int off = 0; off < CT_BYTES; off += chunk) {
                int n = Math.min(chunk, CT_BYTES - off);
                byte[] part = new byte[n];
                System.arraycopy(ct, off, part, 0, n);
                link.write(false, part, false);
                writes++;
            }
            t3 = SystemClock.elapsedRealtimeNanos();
            res = link.resValues.poll(15, TimeUnit.SECONDS);
            t4 = SystemClock.elapsedRealtimeNanos();
            if (res == null) throw new Exception("result timeout");
            if (res.length < RES_BYTES) throw new Exception("short result " + res.length);
            SHA3Digest d = new SHA3Digest(256);
            d.update(ss, 0, ss.length);
            byte[] h = new byte[32];
            d.doFinal(h, 0);
            tagOk = res[0] == 'O' && res[19] == h[0];
            if (!tagOk) err = res[0] == 'O' ? "tag mismatch" : "esp error";
        } catch (Exception e) {
            err = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        StringBuilder sb = new StringBuilder();
        sb.append(cellId).append(',').append(mtuReq).append(',').append(link.mtu).append(',').append(prio).append(',')
          .append(mhz).append(',').append(i).append(',').append(t0).append(',');
        sb.append(ms(t0, t4)).append(',').append(ms(t0, t1)).append(',').append(ms(t1, t2)).append(',')
          .append(ms(t2, t3)).append(',').append(ms(t3, t4)).append(',');
        sb.append(notifs).append(',').append(writes).append(',');
        if (res != null && res.length >= RES_BYTES) {
            sb.append((char) res[0]).append(',').append(u16(res, 17)).append(',').append(u32(res, 1)).append(',')
              .append(u32(res, 5)).append(',').append(u32(res, 9)).append(',').append(u32(res, 13)).append(',');
        } else sb.append(",,,,,,");
        lastTagOk = tagOk;
        sb.append(tagOk ? 1 : 0).append(',').append(err.replace(',', ';'));
        return sb.toString();
    }

    static String ms(long a, long b) { return (a == 0 || b == 0) ? "" : String.format(Locale.US, "%.3f", (b - a) / 1e6); }
    static long u32(byte[] r, int o) { return (r[o] & 0xffL) | (r[o + 1] & 0xffL) << 8 | (r[o + 2] & 0xffL) << 16 | (r[o + 3] & 0xffL) << 24; }
    static int u16(byte[] r, int o) { return (r[o] & 0xff) | (r[o + 1] & 0xff) << 8; }

    /** Background sampler of the phone's battery current, tagged with the experiment phase. */
    void startSampler(File f) {
        if (sampler != null && sampler.isAlive()) return;
        sampler = new Thread(() -> {
            try (PrintWriter w = new PrintWriter(new FileWriter(f))) {
                w.println("t_ns,current_ua,voltage_mv,plugged,phase");
                int k = 0, mv = 0, plugged = -1;
                while (!"done".equals(phase)) {
                    if (k++ % 20 == 0) { // voltage/plug state change slowly: once a second
                        Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                        if (bi != null) { mv = bi.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0); plugged = bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1); }
                    }
                    long ua = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                    w.println(SystemClock.elapsedRealtimeNanos() + "," + ua + "," + mv + "," + plugged + "," + phase);
                    Thread.sleep(50);
                }
            } catch (Exception e) { say("sampler stopped: " + e); }
        });
        sampler.start();
    }
}
