package com.skyinnovations.e12;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.*;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.*;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.*;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;

@SuppressLint("MissingPermission")
public class MainActivity extends Activity {
    static final UUID SVC = UUID.fromString("0000aa12-0000-1000-8000-00805f9b34fb");
    static final UUID WR = UUID.fromString("0000aa13-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final String CAM = "http://192.168.169.1/";

    WebView web;
    Handler ui = new Handler(Looper.getMainLooper());
    BluetoothAdapter bt;
    BluetoothLeScanner scanner;
    BluetoothGatt gatt;
    BluetoothGattCharacteristic wChar;
    final Set<String> seen = new HashSet<>();
    final ArrayDeque<Runnable> ops = new ArrayDeque<>();
    boolean busy = false;

    ConnectivityManager cm;
    ConnectivityManager.NetworkCallback netCb;
    Network camNet;

    // ---------- lifecycle ----------
    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        bt = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(this, "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
        askPermissions();
    }

    @Override protected void onDestroy() {
        disconnectWifi();
        if (gatt != null) gatt.close();
        super.onDestroy();
    }

    void askPermissions() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        List<String> need = new ArrayList<>();
        for (String x : p) if (checkSelfPermission(x) != PackageManager.PERMISSION_GRANTED) need.add(x);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    // ---------- JS helpers ----------
    void js(String fn, Object... args) {
        StringBuilder sb = new StringBuilder("window.").append(fn).append("&&window.").append(fn).append("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(JSONObject.quote(String.valueOf(args[i])));
        }
        sb.append(")");
        ui.post(() -> web.evaluateJavascript(sb.toString(), null));
    }
    void log(String m) { js("onLog", m); }

    static String hex(byte[] a) {
        StringBuilder sb = new StringBuilder();
        for (byte x : a) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }

    // ---------- BLE ----------
    final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            BluetoothDevice d = r.getDevice();
            String name = d.getName();
            if (name == null && r.getScanRecord() != null) name = r.getScanRecord().getDeviceName();
            if (name == null) return;
            if (seen.add(d.getAddress())) js("onDevice", name, d.getAddress());
        }
        @Override public void onScanFailed(int e) { log("فشل البحث: " + e); }
    };

    @JavascriptInterface public void startScan() {
        if (bt == null || !bt.isEnabled()) { log("شغّل البلوتوث أولاً"); return; }
        askPermissions();
        seen.clear();
        scanner = bt.getBluetoothLeScanner();
        if (scanner == null) { log("البلوتوث غير جاهز"); return; }
        scanner.startScan(scanCb);
        log("جارٍ البحث…");
        ui.postDelayed(this::stopScan, 10000);
    }

    @JavascriptInterface public void stopScan() {
        try { if (scanner != null) scanner.stopScan(scanCb); } catch (Exception ignored) {}
    }

    @JavascriptInterface public void connect(String addr) {
        stopScan();
        if (gatt != null) { gatt.close(); gatt = null; }
        wChar = null; ops.clear(); busy = false;
        BluetoothDevice d = bt.getRemoteDevice(addr);
        log("جارٍ الاتصال بـ " + addr);
        gatt = d.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE);
    }

    void enqueue(Runnable r) { synchronized (ops) { ops.add(r); } next(); }
    void next() {
        Runnable r;
        synchronized (ops) {
            if (busy || ops.isEmpty()) return;
            busy = true; r = ops.poll();
        }
        r.run();
    }
    void done() { synchronized (ops) { busy = false; } next(); }

    final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) { log("متصل، جارٍ قراءة الخدمات…"); g.discoverServices(); }
            else if (state == BluetoothProfile.STATE_DISCONNECTED) { wChar = null; js("onState", "disconnected"); log("انقطع الاتصال"); }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService s = g.getService(SVC);
            if (s == null) { log("لم أجد خدمة AA12 — هل اخترت السماعة الصحيحة؟"); return; }
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                int p = c.getProperties();
                if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                    enqueue(() -> {
                        g.setCharacteristicNotification(c, true);
                        BluetoothGattDescriptor d = c.getDescriptor(CCCD);
                        if (d == null) { done(); return; }
                        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        if (!g.writeDescriptor(d)) done();
                    });
                }
                if (WR.equals(c.getUuid())) wChar = c;
            }
            if (wChar == null) { log("لم أجد خاصية الكتابة AA13"); return; }
            enqueue(() -> { js("onState", "ready"); log("جاهز ✅"); done(); });
        }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) { done(); }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) { done(); }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            js("onNotify", hex(c.getValue()));
        }
    };

    /** hex = "CMD PAYLOAD..." e.g. "22 30" — builds AB 55 frame */
    @JavascriptInterface public void send(String hexStr) {
        if (gatt == null || wChar == null) { log("غير متصل"); return; }
        String[] parts = hexStr.trim().split("\\s+");
        int cmd = Integer.parseInt(parts[0], 16);
        int n = parts.length - 1, len = n + 2, sum = cmd;
        byte[] f = new byte[5 + n + 1];
        f[0] = (byte) 0xAB; f[1] = 0x55; f[2] = (byte) (len >> 8); f[3] = (byte) len; f[4] = (byte) cmd;
        for (int i = 0; i < n; i++) { int v = Integer.parseInt(parts[i + 1], 16); f[5 + i] = (byte) v; sum += v; }
        f[5 + n] = (byte) (sum & 0xFF);
        js("onSent", hex(f));
        enqueue(() -> {
            wChar.setValue(f);
            wChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            if (!gatt.writeCharacteristic(wChar)) done();
        });
    }

    // ---------- Wi-Fi ----------
    @JavascriptInterface public void connectWifi(String ssid, String pass) {
        disconnectWifi();
        WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder()
                .setSsid(ssid).setWpa2Passphrase(pass).build();
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec).build();
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { camNet = n; js("onWifi", "connected"); }
            @Override public void onUnavailable() { camNet = null; js("onWifi", "failed"); }
            @Override public void onLost(Network n) { camNet = null; js("onWifi", "lost"); }
        };
        log("جارٍ الاتصال بشبكة " + ssid + "…");
        cm.requestNetwork(req, netCb, 60000);
    }

    @JavascriptInterface public void disconnectWifi() {
        if (netCb != null) { try { cm.unregisterNetworkCallback(netCb); } catch (Exception ignored) {} }
        netCb = null; camNet = null;
    }

    byte[] httpGet(String url) throws IOException {
        if (camNet == null) throw new IOException("غير متصل بشبكة السماعة");
        HttpURLConnection c = (HttpURLConnection) camNet.openConnection(new URL(url));
        c.setConnectTimeout(8000); c.setReadTimeout(30000);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384]; int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return out.toByteArray();
        } finally { c.disconnect(); }
    }

    @JavascriptInterface public void listFiles() {
        new Thread(() -> {
            try { js("onFiles", new String(httpGet(CAM + "app/getfilelist"), "UTF-8")); }
            catch (Exception e) { log("فشل جلب القائمة: " + e.getMessage()); }
        }).start();
    }

    static boolean isJpeg(byte[] b) { return b != null && b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8; }

    @JavascriptInterface public void thumb(String name) {
        new Thread(() -> {
            try {
                byte[] b = null;
                try { b = httpGet(CAM + "app/getthumbnail?file=" + URLEncoder.encode(name, "UTF-8")); } catch (Exception ignored) {}
                if (!isJpeg(b)) {
                    // الصورة المصغرة غير متاحة: نحمّل الصورة الأصلية ونصغّرها
                    byte[] full = httpGet(CAM + name);
                    if (!isJpeg(full)) throw new IOException("الملف ليس صورة (" + (full == null ? 0 : full.length) + " بايت)");
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inSampleSize = 4;
                    android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(full, 0, full.length, o);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out);
                    b = out.toByteArray();
                }
                js("onThumb", name, "data:image/jpeg;base64," + Base64.encodeToString(b, Base64.NO_WRAP));
            } catch (Exception e) {
                log("تعذّر عرض " + name + ": " + e.getMessage());
                js("onThumb", name, "");
            }
        }).start();
    }

    @JavascriptInterface public void save(String name) {
        new Thread(() -> {
            try {
                byte[] b = httpGet(CAM + name);
                String file = name.substring(name.lastIndexOf('/') + 1);
                boolean video = file.toLowerCase().endsWith(".mp4") || file.toLowerCase().endsWith(".mov");
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, file);
                v.put(MediaStore.MediaColumns.MIME_TYPE, video ? "video/mp4" : "image/jpeg");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/E12");
                Uri col = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                Uri u = getContentResolver().insert(col, v);
                try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(b); }
                js("onSaved", name, "1");
            } catch (Exception e) { js("onSaved", name, "0"); log("فشل الحفظ: " + e.getMessage()); }
        }).start();
    }
}
