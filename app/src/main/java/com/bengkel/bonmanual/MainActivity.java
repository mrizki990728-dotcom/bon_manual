package com.bengkel.bonmanual;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {

    private static final int REQ_BT_PERMS = 1001;
    private static final int REQ_ENABLE_BT = 1002;
    private static final int REQ_STORAGE = 1003;

    private WebView webView;
    private BluetoothAdapter btAdapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String, Conn> conns = new ConcurrentHashMap<String, Conn>();

    private int pendingScanId = -1;
    private String pendingSaveName, pendingSaveMime, pendingSaveB64;

    private static class Conn {
        BluetoothGatt gatt;
        int connectId = -1;
        int mtu = 23;
        int writeId = -1;
        byte[] writeData;
        int writeOffset;
        BluetoothGattCharacteristic writeChar;
        int writeType;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        btAdapter = bm != null ? bm.getAdapter() : null;

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message)
                        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { result.confirm(); }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface d) { result.cancel(); }
                        }).show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message)
                        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { result.confirm(); }
                        })
                        .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { result.cancel(); }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface d) { result.cancel(); }
                        }).show();
                return true;
            }
        });

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        webView.evaluateJavascript("window.__onBack ? window.__onBack() : false", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (!"true".equals(value)) {
                    MainActivity.super.onBackPressed();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        for (Conn c : conns.values()) {
            try { if (c.gatt != null) { c.gatt.disconnect(); c.gatt.close(); } } catch (Exception ignored) { }
        }
        conns.clear();
        super.onDestroy();
    }

    /* ======================= Pemanggilan balik ke JS ======================= */

    private void jsResolve(int id, String rawJson) {
        final String js = "window.__nativeCb(" + id + ",true," + rawJson + ")";
        mainHandler.post(new Runnable() { public void run() { webView.evaluateJavascript(js, null); } });
    }

    private void jsReject(int id, String message) {
        final String js = "window.__nativeCb(" + id + ",false," + JSONObject.quote(message == null ? "error" : message) + ")";
        mainHandler.post(new Runnable() { public void run() { webView.evaluateJavascript(js, null); } });
    }

    private void jsDisconnected(String address) {
        final String js = "window.__btDisconnected&&window.__btDisconnected(" + JSONObject.quote(address) + ")";
        mainHandler.post(new Runnable() { public void run() { webView.evaluateJavascript(js, null); } });
    }

    /* ======================= Antarmuka JS -> Android ======================= */

    private class Bridge {

        @JavascriptInterface
        public void btRequestDevice(final int id) {
            runOnUiThread(new Runnable() { public void run() { startDevicePicker(id); } });
        }

        @JavascriptInterface
        public void btConnect(final int id, final String address) {
            runOnUiThread(new Runnable() { public void run() { connectDevice(id, address); } });
        }

        @JavascriptInterface
        public void btDisconnect(final String address) {
            runOnUiThread(new Runnable() {
                public void run() {
                    Conn c = conns.remove(address);
                    if (c != null && c.gatt != null) {
                        try { c.gatt.disconnect(); c.gatt.close(); } catch (Exception ignored) { }
                    }
                }
            });
        }

        @JavascriptInterface
        public void btWrite(final int id, final String address, final String svc, final String chr, final String b64) {
            runOnUiThread(new Runnable() { public void run() { startWrite(id, address, svc, chr, b64); } });
        }

        @JavascriptInterface
        public void print() {
            runOnUiThread(new Runnable() {
                public void run() {
                    PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                    PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter("Bon_Bengkel");
                    pm.print("Bon Bengkel", adapter, new PrintAttributes.Builder().build());
                }
            });
        }

        @JavascriptInterface
        public void saveFile(final String name, final String mime, final String b64) {
            runOnUiThread(new Runnable() { public void run() { doSave(name, mime, b64); } });
        }
    }

    /* ======================= Izin ======================= */

    private boolean hasBtPermissions() {
        if (Build.VERSION.SDK_INT < 23) return true;
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT_PERMS);
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_BT_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0;
        for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) granted = false;

        if (requestCode == REQ_BT_PERMS) {
            int id = pendingScanId;
            pendingScanId = -1;
            if (id == -1) return;
            if (granted) startDevicePicker(id);
            else jsReject(id, "Izin Bluetooth ditolak");
        } else if (requestCode == REQ_STORAGE) {
            if (granted && pendingSaveName != null) {
                doSave(pendingSaveName, pendingSaveMime, pendingSaveB64);
            } else {
                Toast.makeText(this, "Izin penyimpanan ditolak, file tidak dapat disimpan.", Toast.LENGTH_LONG).show();
            }
            pendingSaveName = pendingSaveMime = pendingSaveB64 = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ENABLE_BT) {
            int id = pendingScanId;
            pendingScanId = -1;
            if (id == -1) return;
            if (resultCode == RESULT_OK) startDevicePicker(id);
            else jsReject(id, "Bluetooth tidak diaktifkan");
        }
    }

    /* ======================= Pilih perangkat (scan BLE) ======================= */

    private void startDevicePicker(final int id) {
        if (btAdapter == null) {
            jsReject(id, "Perangkat ini tidak mendukung Bluetooth");
            return;
        }
        if (!hasBtPermissions()) {
            pendingScanId = id;
            requestBtPermissions();
            return;
        }
        if (!btAdapter.isEnabled()) {
            pendingScanId = id;
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT);
            return;
        }
        final BluetoothLeScanner scanner = btAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            jsReject(id, "Scanner Bluetooth tidak tersedia");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 31) {
            Toast.makeText(this, "Pastikan Lokasi/GPS aktif agar printer terdeteksi.", Toast.LENGTH_LONG).show();
        }

        final List<BluetoothDevice> found = new ArrayList<BluetoothDevice>();
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        final boolean[] picked = {false};

        final ScanCallback callback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice d = result.getDevice();
                for (BluetoothDevice x : found) {
                    if (x.getAddress().equals(d.getAddress())) return;
                }
                found.add(d);
                String name = null;
                try { name = d.getName(); } catch (SecurityException ignored) { }
                adapter.add((name == null || name.length() == 0 ? "(Tanpa nama)" : name) + "\n" + d.getAddress());
            }
        };

        try {
            scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback);
        } catch (Exception e) {
            jsReject(id, "Gagal memulai pencarian: " + e.getMessage());
            return;
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Pilih Printer Bluetooth");
        b.setAdapter(adapter, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                picked[0] = true;
                try { scanner.stopScan(callback); } catch (Exception ignored) { }
                BluetoothDevice d = found.get(which);
                String name = null;
                try { name = d.getName(); } catch (SecurityException ignored) { }
                try {
                    JSONObject o = new JSONObject();
                    o.put("address", d.getAddress());
                    o.put("name", name == null ? JSONObject.NULL : name);
                    jsResolve(id, o.toString());
                } catch (Exception e) {
                    jsReject(id, e.getMessage());
                }
            }
        });
        b.setNegativeButton("Batal", null);
        b.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                try { scanner.stopScan(callback); } catch (Exception ignored) { }
                if (!picked[0]) jsReject(id, "Pemilihan perangkat dibatalkan");
            }
        });
        b.show();
    }

    /* ======================= Koneksi GATT ======================= */

    private void connectDevice(final int id, final String address) {
        try {
            if (btAdapter == null) { jsReject(id, "Bluetooth tidak tersedia"); return; }
            BluetoothDevice dev = btAdapter.getRemoteDevice(address);
            Conn old = conns.remove(address);
            if (old != null && old.gatt != null) {
                try { old.gatt.disconnect(); old.gatt.close(); } catch (Exception ignored) { }
            }
            final Conn c = new Conn();
            c.connectId = id;
            conns.put(address, c);
            if (Build.VERSION.SDK_INT >= 23) {
                c.gatt = dev.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                c.gatt = dev.connectGatt(this, false, gattCallback);
            }
            mainHandler.postDelayed(new Runnable() {
                public void run() {
                    if (c.connectId == id) {
                        c.connectId = -1;
                        conns.remove(address);
                        try { if (c.gatt != null) { c.gatt.disconnect(); c.gatt.close(); } } catch (Exception ignored) { }
                        jsReject(id, "Waktu koneksi habis");
                    }
                }
            }, 20000);
        } catch (Exception e) {
            jsReject(id, "Gagal terhubung: " + e.getMessage());
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String addr = gatt.getDevice().getAddress();
            Conn c = conns.get(addr);
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (c == null) { gatt.close(); return; }
                if (!gatt.requestMtu(247)) gatt.discoverServices();
            } else {
                try { gatt.close(); } catch (Exception ignored) { }
                if (c == null) return;
                if (c.gatt != null && c.gatt != gatt) return;
                conns.remove(addr);
                if (c.connectId != -1) {
                    int id = c.connectId;
                    c.connectId = -1;
                    jsReject(id, "Gagal terhubung (status " + status + ")");
                } else {
                    jsDisconnected(addr);
                }
                synchronized (c) {
                    if (c.writeId != -1) {
                        int wid = c.writeId;
                        c.writeId = -1;
                        jsReject(wid, "Koneksi printer terputus");
                    }
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Conn c = conns.get(gatt.getDevice().getAddress());
            if (c != null && status == BluetoothGatt.GATT_SUCCESS) c.mtu = mtu;
            gatt.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Conn c = conns.get(gatt.getDevice().getAddress());
            if (c == null || c.connectId == -1) return;
            int id = c.connectId;
            c.connectId = -1;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                jsReject(id, "Gagal membaca layanan printer");
                return;
            }
            try {
                JSONArray services = new JSONArray();
                for (BluetoothGattService s : gatt.getServices()) {
                    JSONObject so = new JSONObject();
                    so.put("uuid", s.getUuid().toString());
                    JSONArray chars = new JSONArray();
                    for (BluetoothGattCharacteristic ch : s.getCharacteristics()) {
                        int p = ch.getProperties();
                        JSONObject co = new JSONObject();
                        co.put("uuid", ch.getUuid().toString());
                        co.put("write", (p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0);
                        co.put("writeNoResp", (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0);
                        chars.put(co);
                    }
                    so.put("chars", chars);
                    services.put(so);
                }
                JSONObject result = new JSONObject();
                result.put("services", services);
                jsResolve(id, result.toString());
            } catch (Exception e) {
                jsReject(id, e.getMessage());
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Conn c = conns.get(gatt.getDevice().getAddress());
            if (c == null) return;
            synchronized (c) {
                if (c.writeId == -1) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    int wid = c.writeId;
                    c.writeId = -1;
                    jsReject(wid, "Gagal mengirim data (status " + status + ")");
                    return;
                }
                writeNext(c);
            }
        }
    };

    /* ======================= Tulis data ke printer ======================= */

    private void startWrite(int id, String address, String svc, String chr, String b64) {
        Conn c = conns.get(address);
        if (c == null || c.gatt == null) {
            jsReject(id, "Printer tidak terhubung");
            return;
        }
        try {
            BluetoothGattService service = c.gatt.getService(UUID.fromString(svc));
            BluetoothGattCharacteristic ch = service == null ? null : service.getCharacteristic(UUID.fromString(chr));
            if (ch == null) {
                jsReject(id, "Saluran tulis printer tidak ditemukan");
                return;
            }
            synchronized (c) {
                if (c.writeId != -1) {
                    jsReject(id, "Printer sedang sibuk");
                    return;
                }
                c.writeId = id;
                c.writeData = Base64.decode(b64, Base64.DEFAULT);
                c.writeOffset = 0;
                c.writeChar = ch;
                int props = ch.getProperties();
                c.writeType = (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                        ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
                writeNext(c);
            }
        } catch (Exception e) {
            jsReject(id, "Gagal mengirim data: " + e.getMessage());
        }
    }

    /** Harus dipanggil dalam synchronized(c). */
    private void writeNext(Conn c) {
        if (c.writeOffset >= c.writeData.length) {
            int id = c.writeId;
            c.writeId = -1;
            jsResolve(id, "null");
            return;
        }
        int chunk = Math.max(20, c.mtu - 3);
        int end = Math.min(c.writeData.length, c.writeOffset + chunk);
        byte[] part = Arrays.copyOfRange(c.writeData, c.writeOffset, end);
        c.writeOffset = end;
        writePart(c, part, 0);
    }

    private void writePart(final Conn c, final byte[] part, final int tries) {
        boolean ok;
        try {
            c.writeChar.setWriteType(c.writeType);
            c.writeChar.setValue(part);
            ok = c.gatt.writeCharacteristic(c.writeChar);
        } catch (Exception e) {
            ok = false;
        }
        if (ok) return;
        if (tries < 100) {
            mainHandler.postDelayed(new Runnable() {
                public void run() {
                    synchronized (c) {
                        if (c.writeId != -1) writePart(c, part, tries + 1);
                    }
                }
            }, 30);
        } else {
            synchronized (c) {
                if (c.writeId != -1) {
                    int id = c.writeId;
                    c.writeId = -1;
                    jsReject(id, "Gagal mengirim data ke printer");
                }
            }
        }
    }

    /* ======================= Simpan file ke folder Download ======================= */

    private void doSave(String name, String mime, String b64) {
        try {
            String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            byte[] data = Base64.decode(b64, Base64.DEFAULT);

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new Exception("Tidak dapat membuat file");
                OutputStream os = getContentResolver().openOutputStream(uri);
                os.write(data);
                os.close();
            } else {
                if (Build.VERSION.SDK_INT >= 23
                        && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    pendingSaveName = name;
                    pendingSaveMime = mime;
                    pendingSaveB64 = b64;
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
                    return;
                }
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, safe);
                int n = 1;
                int dot = safe.lastIndexOf('.');
                String base = dot > 0 ? safe.substring(0, dot) : safe;
                String ext = dot > 0 ? safe.substring(dot) : "";
                while (f.exists()) {
                    f = new File(dir, base + " (" + n + ")" + ext);
                    n++;
                }
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(data);
                fos.close();
                MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, null, null);
            }
            Toast.makeText(this, "Tersimpan di folder Download: " + safe, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Gagal menyimpan file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
