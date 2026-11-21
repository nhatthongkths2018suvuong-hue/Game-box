package com.minigame.fpt;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.DialogInterface;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.SparseIntArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity implements SurfaceHolder.Callback {

    // Bit nút RetroPad
    private static final int BIT_UP = 1 << 4, BIT_DOWN = 1 << 5, BIT_LEFT = 1 << 6, BIT_RIGHT = 1 << 7;
    private static final int BIT_L2 = 1 << 12, BIT_R2 = 1 << 13;

    private final EmuController emu = new EmuController();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private KeyMap keymap;

    private AspectSurfaceView gameView;
    private View rightPane;
    private TextView placeholder;
    private TextView status;
    private View controls;
    private ImageButton btnResume;
    private int aspectMode = 0;
    private static final String[] ASPECT_NAMES = {
            "Gốc (giữ tỷ lệ)",
            "Dãn kín màn hình",
            "Dãn theo cạnh dài (giữ tỷ lệ, cắt bớt)",
            "Dãn theo cạnh ngắn (giữ tỷ lệ)"
    };
    private ListView tree;
    private TreeAdapter adapter;
    private final ArrayList<Node> nodes = new ArrayList<>();

    private boolean fullscreen = false;
    private final int[] keyMask = new int[2];
    private final int[] axisMask = new int[2];
    private final SparseIntArray devicePort = new SparseIntArray();

    private File systemDir, saveDir;

    // -----------------------------------------------------------------------
    // Vòng đời
    // -----------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        keymap = new KeyMap(this);
        gameView = findViewById(R.id.gameView);
        rightPane = findViewById(R.id.right);
        placeholder = findViewById(R.id.placeholder);
        status = findViewById(R.id.status);
        controls = findViewById(R.id.controls);
        btnResume = findViewById(R.id.btnResume);
        ImageButton btnSettings = findViewById(R.id.btnSettings);
        ImageButton btnAspect = findViewById(R.id.btnAspect);
        btnResume.setImageDrawable(new IconDrawable(IconDrawable.PLAY, dp(28)));
        btnSettings.setImageDrawable(new IconDrawable(IconDrawable.GEAR, dp(28)));
        btnAspect.setImageDrawable(new IconDrawable(IconDrawable.ASPECT, dp(28)));
        setResumeEnabled(false);
        aspectMode = getSharedPreferences("app", MODE_PRIVATE).getInt("aspect_mode", 0);
        gameView.setMode(aspectMode);
        tree = findViewById(R.id.tree);

        gameView.getHolder().addCallback(this);

        adapter = new TreeAdapter();
        tree.setAdapter(adapter);
        tree.setSelector(new ColorDrawable(0x553399FF));
        tree.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onNodeClicked(position);
            }
        });

        btnResume.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resumeGame();
            }
        });
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMapDialog();
            }
        });
        btnAspect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                aspectMode = (aspectMode + 1) % ASPECT_NAMES.length;
                getSharedPreferences("app", MODE_PRIVATE).edit().putInt("aspect_mode", aspectMode).apply();
                gameView.setMode(aspectMode);
                Toast.makeText(MainActivity.this, "Tỷ lệ hình: " + ASPECT_NAMES[aspectMode],
                        Toast.LENGTH_SHORT).show();
            }
        });

        systemDir = new File(getFilesDir(), "system");
        saveDir = new File(getFilesDir(), "saves");
        prepareDirs();
        updateStatus();
        requestStorage();
        initUsb();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(usbScanTicker);
        if (emu.hasGame()) emu.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        startSync();
        ui.removeCallbacks(usbScanTicker);
        ui.post(usbScanTicker);
        if (fullscreen && emu.hasGame()) emu.resume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        shutdownUsb();
        emu.stop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // -----------------------------------------------------------------------
    // Surface
    // -----------------------------------------------------------------------
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Native.nativeSetSurface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Native.nativeRedraw();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Native.nativeSetSurface(null);
    }

    // -----------------------------------------------------------------------
    // Quyền truy cập bộ nhớ + thư mục + BIOS
    // -----------------------------------------------------------------------
    private void requestStorage() {
        if (Build.VERSION.SDK_INT >= 23
                && (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        } else {
            buildRoots();
            startSync();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        buildRoots();
        startSync();
    }

    private void prepareDirs() {
        File fb = new File(systemDir, "fbneo");
        for (String s : new String[]{"", "samples", "cheats", "hiscore", "blend", "patched"}) {
            new File(fb, s).mkdirs();
        }
        new File(saveDir, "fbneo").mkdirs();
    }

    // -----------------------------------------------------------------------
    // Core + BIOS do người dùng tự chép vào thư mục MiniGameBox
    // -----------------------------------------------------------------------
    private volatile boolean syncing = false;
    private volatile boolean pendingResync = false;

    /** Các thư mục có thể chứa cores/ và bios/ (theo thứ tự ưu tiên). */
    private ArrayList<File> dataRoots() {
        ArrayList<File> r = new ArrayList<>();
        File a = getExternalFilesDir(null);
        if (a != null) r.add(a);
        r.add(new File(Environment.getExternalStorageDirectory(), "MiniGameBox"));
        File[] st = new File("/storage").listFiles();
        if (st != null) {
            for (File f : st) {
                String n = f.getName();
                if (n.equals("self") || n.equals("emulated")) continue;
                r.add(new File(f, "MiniGameBox"));
            }
        }
        return r;
    }

    private File primaryDir() {
        File sd = new File(Environment.getExternalStorageDirectory(), "MiniGameBox");
        if (new File(sd, "cores").isDirectory()) return sd;
        File a = getExternalFilesDir(null);
        return a != null ? a : sd;
    }

    private File privateCore(String key) {
        return new File(new File(getFilesDir(), "cores"), key + "_libretro.so");
    }

    private void startSync() {
        if (syncing) {
            pendingResync = true;
            return;
        }
        syncing = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    do {
                        pendingResync = false;
                        doSync();
                    } while (pendingResync);
                } finally {
                    syncing = false;
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        updateStatus();
                    }
                });
            }
        }, "sync-thread").start();
    }

    private void doSync() {
        try {
            ArrayList<File> roots = dataRoots();
            for (int i = 0; i < roots.size() && i < 2; i++) {
                new File(roots.get(i), "cores").mkdirs();
                new File(roots.get(i), "bios").mkdirs();
            }
            File privCores = new File(getFilesDir(), "cores");
            privCores.mkdirs();
            Set<String> doneCores = new HashSet<>();
            Set<String> doneBios = new HashSet<>();

            for (File root : roots) {
                File[] cs = new File(root, "cores").listFiles();
                if (cs != null) {
                    for (File c : cs) {
                        String n = c.getName().toLowerCase(Locale.ROOT);
                        String key = n.startsWith("fceumm") ? "fceumm" : n.startsWith("fbneo") ? "fbneo" : null;
                        if (key == null || doneCores.contains(key)) continue;
                        if (n.endsWith(".so")) {
                            copyIfNeeded(c, privateCore(key), false);
                            doneCores.add(key);
                        } else if (n.endsWith(".so.zip") || n.endsWith(".zip")) {
                            copyIfNeeded(c, privateCore(key), true);
                            doneCores.add(key);
                        }
                    }
                }
                File[] bs = new File(root, "bios").listFiles();
                if (bs != null) {
                    for (File b : bs) {
                        if (!b.isFile()) continue;
                        String name = b.getName().toLowerCase(Locale.ROOT);
                        if (doneBios.contains(name)) continue;
                        copyIfNeeded(b, new File(systemDir, name), false);
                        copyIfNeeded(b, new File(new File(systemDir, "fbneo"), name), false);
                        doneBios.add(name);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Chép src -> dst nếu dst chưa có hoặc khác thời gian sửa; unzip=true thì giải nén file .so đầu tiên trong zip. */
    private void copyIfNeeded(File src, File dst, boolean unzip) throws Exception {
        if (dst.exists() && dst.lastModified() == src.lastModified() && dst.length() > 0) return;
        File tmp = new File(dst.getPath() + ".tmp");
        InputStream in = new FileInputStream(src);
        ZipInputStream zin = null;
        InputStream source = in;
        try {
            if (unzip) {
                zin = new ZipInputStream(in);
                ZipEntry e;
                boolean found = false;
                while ((e = zin.getNextEntry()) != null) {
                    if (e.getName().toLowerCase(Locale.ROOT).endsWith(".so")) {
                        found = true;
                        break;
                    }
                }
                if (!found) return;
                source = zin;
            }
            OutputStream os = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = source.read(buf)) > 0) os.write(buf, 0, n);
            } finally {
                os.close();
            }
        } finally {
            in.close();
        }
        if (dst.exists()) dst.delete();
        if (tmp.renameTo(dst)) dst.setLastModified(src.lastModified());
    }

    private void updateStatus() {
        boolean nes = privateCore("fceumm").exists();
        boolean neo = privateCore("fbneo").exists();
        boolean bios = new File(systemDir, "neogeo.zip").exists();
        String p = primaryDir().getAbsolutePath();
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendStatus(sb, "Core NES", nes);
        sb.append("   |   ");
        appendStatus(sb, "Core Neo Geo", neo);
        sb.append("   |   ");
        appendStatus(sb, "BIOS Neo Geo", bios);
        if (!(nes && neo && bios)) {
            sb.append("\nChép vào: ").append(p).append("/cores  và  ").append(p).append("/bios");
        }
        status.setText(sb);
    }

    private void appendStatus(SpannableStringBuilder sb, String name, boolean ok) {
        sb.append(name).append(": ");
        int start = sb.length();
        sb.append(ok ? "CÓ" : "THIẾU");
        sb.setSpan(new ForegroundColorSpan(ok ? 0xFF66BB6A : 0xFFEF5350), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void setResumeEnabled(boolean enabled) {
        btnResume.setEnabled(enabled);
        btnResume.setAlpha(enabled ? 1f : 0.35f);
    }

    // -----------------------------------------------------------------------
    // Cây thư mục
    // -----------------------------------------------------------------------
    private static class Node {
        final String label;
        final File file;
        final int depth;
        final boolean dir;
        boolean expanded = false;

        Node(String label, File file, int depth, boolean dir) {
            this.label = label;
            this.file = file;
            this.depth = depth;
            this.dir = dir;
        }
    }

    private static boolean isGameFile(String name) {
        String s = name.toLowerCase(Locale.ROOT);
        if (s.equals("neogeo.zip")) return false;
        return s.endsWith(".nes") || s.endsWith(".fds") || s.endsWith(".unf")
                || s.endsWith(".unif") || s.endsWith(".zip");
    }

    private void buildRoots() {
        nodes.clear();
        Set<String> seen = new HashSet<>();
        addRoot("Bộ nhớ trong", Environment.getExternalStorageDirectory(), seen);
        File[] st = new File("/storage").listFiles();
        if (st != null) {
            for (File f : st) {
                String n = f.getName();
                if (n.equals("self") || n.equals("emulated")) continue;
                addRoot("USB / thẻ nhớ: " + n, f, seen);
            }
        }
        adapter.notifyDataSetChanged();
        if (!nodes.isEmpty()) expand(0);
        tree.requestFocus();
    }

    private void addRoot(String label, File f, Set<String> seen) {
        if (f == null || !f.isDirectory() || !f.canRead()) return;
        String key;
        try {
            key = f.getCanonicalPath();
        } catch (Exception e) {
            key = f.getAbsolutePath();
        }
        if (seen.add(key)) nodes.add(new Node(label, f, 0, true));
    }

    private void expand(int pos) {
        Node n = nodes.get(pos);
        File[] kids = n.file.listFiles();
        ArrayList<Node> add = new ArrayList<>();
        if (kids != null) {
            Arrays.sort(kids, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File k : kids) {
                String name = k.getName();
                if (name.startsWith(".")) continue;
                if (k.isDirectory()) add.add(new Node(name, k, n.depth + 1, true));
                else if (isGameFile(name)) add.add(new Node(name, k, n.depth + 1, false));
            }
        }
        nodes.addAll(pos + 1, add);
        n.expanded = true;
        adapter.notifyDataSetChanged();
        if (add.isEmpty() && n.depth > 0) {
            Toast.makeText(this, "Thư mục không có game", Toast.LENGTH_SHORT).show();
        }
    }

    private void collapse(int pos) {
        Node n = nodes.get(pos);
        int i = pos + 1;
        while (i < nodes.size() && nodes.get(i).depth > n.depth) nodes.remove(i);
        n.expanded = false;
        adapter.notifyDataSetChanged();
    }

    private void onNodeClicked(int pos) {
        if (pos < 0 || pos >= nodes.size()) return;
        Node n = nodes.get(pos);
        if (n.dir) {
            if (n.expanded) collapse(pos);
            else expand(pos);
        } else {
            startGame(n.file);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private class TreeAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return nodes.size();
        }

        @Override
        public Object getItem(int i) {
            return nodes.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convertView, ViewGroup parent) {
            TextView t = convertView instanceof TextView ? (TextView) convertView
                    : new TextView(MainActivity.this);
            Node n = nodes.get(i);
            t.setTextColor(0xFFFFFFFF);
            t.setTextSize(18);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            t.setPadding(dp(10) + n.depth * dp(20), dp(10), dp(8), dp(10));
            String prefix = n.dir ? (n.expanded ? "\u25BC " : "\u25B6 ") : "    ";
            t.setText(prefix + n.label);
            return t;
        }
    }

    // -----------------------------------------------------------------------
    // Chạy game / tạm dừng
    // -----------------------------------------------------------------------
    private void startGame(File f) {
        String path = f.getAbsolutePath();
        if (emu.hasGame() && path.equals(emu.getCurrentPath())) {
            resumeGame();
            return;
        }
        String lower = f.getName().toLowerCase(Locale.ROOT);
        boolean neo = lower.endsWith(".zip");
        File coreFile = privateCore(neo ? "fbneo" : "fceumm");
        if (!coreFile.exists()) {
            if (syncing) {
                Toast.makeText(this, "Đang nạp file, thử lại sau giây lát", Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Thiếu core " + (neo ? "Neo Geo (fbneo)" : "NES (fceumm)"))
                    .setMessage("Hãy chép file " + (neo ? "fbneo_libretro_android.so" : "fceumm_libretro_android.so")
                            + " (hoặc bản .so.zip) vào thư mục:\n\n" + primaryDir().getAbsolutePath()
                            + "/cores\n\nrồi quay lại app.")
                    .setPositiveButton("OK", null)
                    .show();
            updateStatus();
            return;
        }

        clearInput();
        placeholder.setVisibility(View.GONE);
        setResumeEnabled(true);
        setFullscreen(true);
        Toast.makeText(this, "Bấm BACK hoặc nút Mode để tạm dừng", Toast.LENGTH_LONG).show();

        emu.start(coreFile.getAbsolutePath(), path, systemDir.getAbsolutePath(),
                saveDir.getAbsolutePath(), new EmuController.Listener() {
                    @Override
                    public void onLoaded() {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                gameView.setAspect(Native.nativeGetAspect());
                            }
                        });
                    }

                    @Override
                    public void onError(final String message) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "Lỗi: " + message
                                        + "\n(Neo Geo: kiểm tra BIOS neogeo.zip trong " + primaryDir().getAbsolutePath()
                                        + "/bios)", Toast.LENGTH_LONG).show();
                                setResumeEnabled(false);
                                placeholder.setVisibility(View.VISIBLE);
                                setFullscreen(false);
                            }
                        });
                    }
                });
    }

    private void resumeGame() {
        if (!emu.hasGame()) return;
        clearInput();
        setFullscreen(true);
        emu.resume();
    }

    private void pauseToMenu() {
        emu.pause();
        clearInput();
        setFullscreen(false);
    }

    private void setFullscreen(boolean on) {
        fullscreen = on;
        rightPane.setVisibility(on ? View.GONE : View.VISIBLE);
        controls.setVisibility(on ? View.GONE : View.VISIBLE);
        status.setVisibility(on ? View.GONE : View.VISIBLE);
        gameView.setCropAllowed(on);
        if (!on) tree.requestFocus();
    }

    private void clearInput() {
        ui.removeCallbacks(comboRunnable);
        comboPending = false;
        for (int p = 0; p < 2; p++) {
            keyMask[p] = 0;
            axisMask[p] = 0;
            usbDirMask[p] = 0;
            virtState[p] = 0;
            usbVirt[p] = 0;
            Native.nativeSetInput(p, 0);
        }
    }

    // -----------------------------------------------------------------------
    // Phím + tay cầm
    // -----------------------------------------------------------------------
    private int portFor(int deviceId) {
        int p = devicePort.get(deviceId, -1);
        if (p >= 0) return p;
        InputDevice d = InputDevice.getDevice(deviceId);
        int s = d == null ? 0 : d.getSources();
        boolean pad = (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        if (!pad) return 0;
        p = devicePort.size() == 0 ? 0 : 1;
        devicePort.put(deviceId, p);
        return p;
    }

    private void pushInput(int port) {
        int m = keyMask[port] | axisMask[port] | usbDirMask[port];
        Native.nativeSetInput(port, m);
        // Giữ Select + Start cùng lúc 0,7 giây = tạm dừng (cho tay cầm không có nút Mode)
        boolean both = (m & (BIT_SELECT | BIT_START)) == (BIT_SELECT | BIT_START);
        if (both && !comboPending) {
            comboPending = true;
            ui.postDelayed(comboRunnable, 700);
        } else if (!both && comboPending) {
            comboPending = false;
            ui.removeCallbacks(comboRunnable);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (!fullscreen) return super.dispatchKeyEvent(e);

        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(e);
        }
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        handleMappedKey(portFor(e.getDeviceId()), code, down, e.getRepeatCount() != 0);
        return true;
    }

    /** Xử lý 1 phím (từ Android hoặc từ tay cầm USB đọc trực tiếp) theo bảng gán phím. */
    private void handleMappedKey(int port, int code, boolean down, boolean repeat) {
        int idx = keymap.indexFor(code);
        if (idx < 0) {
            // Phím chưa gán: BACK mặc định = tạm dừng
            if (code == KeyEvent.KEYCODE_BACK && down && !repeat) pauseToMenu();
            return;
        }
        int id = KeyMap.IDS[idx];
        if (id == KeyMap.PAUSE) {
            if (down && !repeat) pauseToMenu();
            return;
        }
        if (down) keyMask[port] |= (1 << id);
        else keyMask[port] &= ~(1 << id);
        pushInput(port);
    }

    /** Đọc mọi trục của sự kiện thành mặt nạ bit "phím ảo" (xem KeyMap.VK_NAMES). */
    private static int virtualMask(MotionEvent ev) {
        final float t = 0.5f;
        int m = 0;
        float hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X), hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
        float x = ev.getAxisValue(MotionEvent.AXIS_X), y = ev.getAxisValue(MotionEvent.AXIS_Y);
        float z = ev.getAxisValue(MotionEvent.AXIS_Z), rz = ev.getAxisValue(MotionEvent.AXIS_RZ);
        float rx = ev.getAxisValue(MotionEvent.AXIS_RX), ry = ev.getAxisValue(MotionEvent.AXIS_RY);
        float lt = Math.max(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE));
        float rt = Math.max(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS));
        if (hy < -t) m |= 1 << 0;
        if (hy > t) m |= 1 << 1;
        if (hx < -t) m |= 1 << 2;
        if (hx > t) m |= 1 << 3;
        if (y < -t) m |= 1 << 4;
        if (y > t) m |= 1 << 5;
        if (x < -t) m |= 1 << 6;
        if (x > t) m |= 1 << 7;
        if (z < -t) m |= 1 << 8;
        if (z > t) m |= 1 << 9;
        if (rz < -t) m |= 1 << 10;
        if (rz > t) m |= 1 << 11;
        if (rx < -t) m |= 1 << 12;
        if (rx > t) m |= 1 << 13;
        if (ry < -t) m |= 1 << 14;
        if (ry > t) m |= 1 << 15;
        if (lt > t) m |= 1 << 16;
        if (rt > t) m |= 1 << 17;
        return m;
    }

    // Mặc định (khi chưa gán): HAT + cần trái -> hướng, trigger -> L2/R2
    private static final int[] VK_FALLBACK = {
            BIT_UP, BIT_DOWN, BIT_LEFT, BIT_RIGHT,
            BIT_UP, BIT_DOWN, BIT_LEFT, BIT_RIGHT,
            0, 0, 0, 0, 0, 0, 0, 0,
            BIT_L2, BIT_R2
    };
    private final int[] virtState = new int[2];

    /** Nhận trạng thái trục mới của 1 tay cầm: gán phím ảo theo bảng gán, phần chưa gán dùng mặc định. */
    private void applyVirtual(int port, int newState) {
        int changed = newState ^ virtState[port];
        virtState[port] = newState;
        int fallback = 0;
        for (int b = 0; b < KeyMap.VK_COUNT; b++) {
            int code = KeyMap.VK_BASE + b;
            boolean on = (newState >> b & 1) != 0;
            if (keymap.indexFor(code) >= 0) {
                if ((changed >> b & 1) != 0) handleMappedKey(port, code, on, false);
            } else if (on) {
                fallback |= VK_FALLBACK[b];
            }
        }
        axisMask[port] = fallback;
        pushInput(port);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (fullscreen && ev.isFromSource(InputDevice.SOURCE_JOYSTICK)
                && ev.getAction() == MotionEvent.ACTION_MOVE) {
            applyVirtual(portFor(ev.getDeviceId()), virtualMask(ev));
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    // -----------------------------------------------------------------------
    // Tay cầm USB đọc trực tiếp (khi Android không tự nhận)
    // -----------------------------------------------------------------------
    private static final String ACTION_USB_PERMISSION = "com.minigame.fpt.USB_PERMISSION";
    private static final int BIT_SELECT = 1 << 2, BIT_START = 1 << 3;

    private interface KeyCapture {
        void onKey(int keyCode);
    }

    private interface EventSink {
        void onEvent(String text);
    }

    private UsbManager usbManager;
    private BroadcastReceiver usbReceiver;
    private final HashMap<String, UsbPad> usbPads = new HashMap<>();
    private final HashSet<String> usbAsked = new HashSet<>();
    private final SparseIntArray usbPorts = new SparseIntArray();
    private final int[] usbDirMask = new int[2];
    private volatile String usbStatus = "chưa có tay cầm USB nào cần đọc";
    private KeyCapture usbCapture;
    private EventSink usbTestSink;
    private boolean comboPending = false;

    private final Runnable usbScanTicker = new Runnable() {
        @Override
        public void run() {
            scanUsb();
            ui.postDelayed(this, 2000);
        }
    };

    private final Runnable comboRunnable = new Runnable() {
        @Override
        public void run() {
            comboPending = false;
            if (fullscreen) pauseToMenu();
        }
    };

    private final UsbPad.Listener usbListener = new UsbPad.Listener() {
        @Override
        public void onButton(final UsbPad pad, final int index, final boolean down) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    onUsbButton(pad, index, down);
                }
            });
        }

        @Override
        public void onDirection(final UsbPad pad, final int retroMask) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    onUsbDirection(pad, retroMask);
                }
            });
        }

        @Override
        public void onRaw(UsbPad pad, final String hex) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (usbTestSink != null) usbTestSink.onEvent("RAW " + hex);
                }
            });
        }

        @Override
        public void onStatus(UsbPad pad, String text) {
            usbStatus = text;
        }

        @Override
        public void onClosed(final UsbPad pad) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (usbPads.get(pad.deviceName()) == pad) usbPads.remove(pad.deviceName());
                    int p = usbPorts.get(pad.padId(), 0);
                    usbDirMask[p] = 0;
                }
            });
        }
    };

    private void initUsb() {
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                UsbDevice d = (UsbDevice) i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (ACTION_USB_PERMISSION.equals(a)) {
                    if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) scanUsb();
                    else usbStatus = "bạn đã từ chối quyền truy cập USB";
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a) && d != null) {
                    UsbPad p = usbPads.get(d.getDeviceName());
                    if (p != null) p.shutdown();
                    usbAsked.remove(d.getDeviceName());
                }
            }
        };
        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, f);
    }

    private void shutdownUsb() {
        ui.removeCallbacks(usbScanTicker);
        ui.removeCallbacks(comboRunnable);
        try {
            if (usbReceiver != null) unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
        }
        for (UsbPad p : new ArrayList<>(usbPads.values())) p.shutdown();
    }

    /** Android đã tự nhận thiết bị này thành thiết bị nhập thì không cần đọc trực tiếp. */
    private boolean androidSeesDevice(UsbDevice d) {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice in = InputDevice.getDevice(id);
            if (in != null && in.getVendorId() == d.getVendorId()
                    && in.getProductId() == d.getProductId()) {
                return true;
            }
        }
        return false;
    }

    private void scanUsb() {
        if (usbManager == null) return;
        HashMap<String, UsbDevice> list = usbManager.getDeviceList();
        if (list == null) return;
        for (UsbDevice d : list.values()) {
            String key = d.getDeviceName();
            if (usbPads.containsKey(key)) continue;
            UsbInterface hid = null;
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface it = d.getInterface(i);
                if (it.getInterfaceClass() == UsbConstants.USB_CLASS_HID && it.getInterfaceProtocol() == 0) {
                    hid = it;
                    break;
                }
            }
            if (hid == null || androidSeesDevice(d)) continue;
            if (usbManager.hasPermission(d)) {
                UsbPad pad = new UsbPad(usbManager, d, hid, usbListener);
                usbPads.put(key, pad);
                usbStatus = "đang khởi động...";
                pad.start();
            } else if (usbAsked.add(key)) {
                usbStatus = "đang chờ bạn cấp quyền USB (bấm OK ở hộp thoại hiện ra)";
                Intent intent = new Intent(ACTION_USB_PERMISSION);
                intent.setPackage(getPackageName());
                PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent, 0);
                usbManager.requestPermission(d, pi);
            }
        }
    }

    private int usbPortFor(int usbId) {
        int p = usbPorts.get(usbId, -1);
        if (p >= 0) return p;
        p = usbPorts.size() == 0 ? 0 : 1;
        usbPorts.put(usbId, p);
        return p;
    }

    /** Quy ước số nút -> mã phím Android, theo bố cục tay cầm PS2: 0=tam giác, 1=tròn, 2=chéo, 3=vuông. */
    private static int usbButtonToKeyCode(int idx) {
        switch (idx) {
            case 0: return KeyEvent.KEYCODE_BUTTON_Y;
            case 1: return KeyEvent.KEYCODE_BUTTON_B;
            case 2: return KeyEvent.KEYCODE_BUTTON_A;
            case 3: return KeyEvent.KEYCODE_BUTTON_X;
            case 4: return KeyEvent.KEYCODE_BUTTON_L1;
            case 5: return KeyEvent.KEYCODE_BUTTON_R1;
            case 6: return KeyEvent.KEYCODE_BUTTON_L2;
            case 7: return KeyEvent.KEYCODE_BUTTON_R2;
            case 8: return KeyEvent.KEYCODE_BUTTON_SELECT;
            case 9: return KeyEvent.KEYCODE_BUTTON_START;
            case 10: return KeyEvent.KEYCODE_BUTTON_THUMBL;
            case 11: return KeyEvent.KEYCODE_BUTTON_THUMBR;
            default: return KeyEvent.KEYCODE_BUTTON_1 + Math.min(idx, 15);
        }
    }

    private void onUsbButton(UsbPad pad, int index, boolean down) {
        int code = usbButtonToKeyCode(index);
        if (usbTestSink != null) {
            usbTestSink.onEvent("USB nút " + (index + 1) + (down ? " xuống" : " nhả") + "  ("
                    + KeyEvent.keyCodeToString(code).replace("KEYCODE_", "") + ")");
        }
        if (!fullscreen) {
            if (down && usbCapture != null) usbCapture.onKey(code);
            return;
        }
        handleMappedKey(usbPortFor(pad.padId()), code, down, false);
    }

    private int lastUsbDir = 0;

    private void onUsbDirection(UsbPad pad, int mask) {
        int port = usbPortFor(pad.padId());
        usbDirMask[port] = mask;
        if (usbTestSink != null && mask != 0) {
            StringBuilder sb = new StringBuilder("USB hướng:");
            if ((mask & BIT_UP) != 0) sb.append(" LÊN");
            if ((mask & BIT_DOWN) != 0) sb.append(" XUỐNG");
            if ((mask & BIT_LEFT) != 0) sb.append(" TRÁI");
            if ((mask & BIT_RIGHT) != 0) sb.append(" PHẢI");
            usbTestSink.onEvent(sb.toString());
        }
        // đang ở hộp thoại gán phím: hướng USB = HAT_*
        int pressed = mask & ~lastUsbDir;
        lastUsbDir = mask;
        if (!fullscreen && usbCapture != null && pressed != 0) {
            if ((pressed & BIT_UP) != 0) usbCapture.onKey(KeyMap.VK_BASE);
            else if ((pressed & BIT_DOWN) != 0) usbCapture.onKey(KeyMap.VK_BASE + 1);
            else if ((pressed & BIT_LEFT) != 0) usbCapture.onKey(KeyMap.VK_BASE + 2);
            else if ((pressed & BIT_RIGHT) != 0) usbCapture.onKey(KeyMap.VK_BASE + 3);
        }
        if (fullscreen) {
            // nếu hướng đã được gán cho nút khác thì đi qua bảng gán, ngược lại giữ mặc định
            int vs = 0, rest = 0;
            if ((mask & BIT_UP) != 0) { if (keymap.indexFor(KeyMap.VK_BASE) >= 0) vs |= 1; else rest |= BIT_UP; }
            if ((mask & BIT_DOWN) != 0) { if (keymap.indexFor(KeyMap.VK_BASE + 1) >= 0) vs |= 2; else rest |= BIT_DOWN; }
            if ((mask & BIT_LEFT) != 0) { if (keymap.indexFor(KeyMap.VK_BASE + 2) >= 0) vs |= 4; else rest |= BIT_LEFT; }
            if ((mask & BIT_RIGHT) != 0) { if (keymap.indexFor(KeyMap.VK_BASE + 3) >= 0) vs |= 8; else rest |= BIT_RIGHT; }
            usbDirMask[port] = rest;
            int changed = vs ^ usbVirt[port];
            usbVirt[port] = vs;
            for (int b = 0; b < 4; b++) {
                if ((changed >> b & 1) != 0) handleMappedKey(port, KeyMap.VK_BASE + b, (vs >> b & 1) != 0, false);
            }
            pushInput(port);
        }
    }
    private final int[] usbVirt = new int[2];

    // -----------------------------------------------------------------------
    // Cài đặt phím gamepad
    // -----------------------------------------------------------------------
    private void showMapDialog() {
        String[] items = new String[KeyMap.IDS.length];
        for (int i = 0; i < items.length; i++) {
            items[i] = KeyMap.LABELS[i] + "   \u2192   " + keymap.keyName(i);
        }
        new AlertDialog.Builder(this)
                .setTitle("Cài đặt phím: chọn nút rồi bấm phím trên tay cầm")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        captureKey(which);
                    }
                })
                .setNeutralButton("Mặc định", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        keymap.reset();
                        showMapDialog();
                    }
                })
                .setNegativeButton("Kiểm tra tay cầm", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        showPadTest();
                    }
                })
                .setPositiveButton("Xong", null)
                .show();
    }

    // -----------------------------------------------------------------------
    // Kiểm tra tay cầm: cho biết Android có nhận tay cầm / sự kiện nào không
    // -----------------------------------------------------------------------
    private static final int[] TEST_AXES = {
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_RX, MotionEvent.AXIS_RY,
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER
    };
    private static final String[] TEST_AXIS_NAMES = {
            "X", "Y", "Z", "RZ", "RX", "RY", "HAT_X", "HAT_Y", "L_TRIGGER", "R_TRIGGER"
    };

    /** Nội dung phần đầu hộp thoại: thiết bị nhập Android thấy + thiết bị USB box thấy. */
    private String buildPadHead() {
        StringBuilder head = new StringBuilder("Thiết bị nhập Android thấy:\n");
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int src = d.getSources();
            head.append("- ").append(d.getName()).append("  [");
            if ((src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) head.append("tay cầm ");
            if ((src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) head.append("joystick ");
            if ((src & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD) head.append("dpad ");
            if ((src & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD) head.append("phím ");
            head.append("]\n");
        }

        head.append("\nThiết bị USB box thấy:\n");
        try {
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> usb = um == null ? null : um.getDeviceList();
            if (usb == null || usb.isEmpty()) {
                head.append("- (không có)\n");
            } else {
                for (UsbDevice u : usb.values()) {
                    head.append("- VID:PID ")
                            .append(String.format(Locale.US, "%04x:%04x", u.getVendorId(), u.getProductId()))
                            .append("  lớp ").append(u.getDeviceClass());
                    String pn = u.getProductName();
                    if (pn != null) head.append("  ").append(pn);
                    head.append("\n    giao diện (lớp/lớp con/giao thức):");
                    for (int i = 0; i < u.getInterfaceCount(); i++) {
                        UsbInterface it = u.getInterface(i);
                        head.append(' ').append(it.getInterfaceClass()).append('/')
                                .append(it.getInterfaceSubclass()).append('/')
                                .append(it.getInterfaceProtocol());
                    }
                    head.append('\n');
                }
            }
        } catch (Exception e) {
            head.append("- (không đọc được: ").append(e.getClass().getSimpleName()).append(")\n");
        }

        head.append("\nĐọc tay cầm trực tiếp qua USB: ").append(usbStatus).append("\n");
        head.append("\nCắm hoặc rút tay cầm, danh sách tự cập nhật sau vài giây.\n"
                + "Bấm nút hoặc gạt cần, sự kiện hiện bên dưới.\n\n");
        return head.toString();
    }

    private void showPadTest() {
        final TextView tv = new TextView(this);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(dp(20), dp(10), dp(20), dp(10));

        final ArrayList<String> log = new ArrayList<>();
        final String[] lastAxis = {""};

        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder(buildPadHead());
                if (log.isEmpty()) sb.append("(chưa có sự kiện nào)");
                for (String l : log) sb.append(l).append('\n');
                tv.setText(sb.toString());
            }
        };
        refresh.run();

        // Tự làm mới danh sách thiết bị mỗi 1,5 giây khi hộp thoại còn mở
        final Runnable ticker = new Runnable() {
            @Override
            public void run() {
                refresh.run();
                ui.postDelayed(this, 1500);
            }
        };
        ui.postDelayed(ticker, 1500);
        usbTestSink = new EventSink() {
            @Override
            public void onEvent(String text) {
                log.add(text);
                while (log.size() > 6) log.remove(0);
                refresh.run();
            }
        };

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Kiểm tra tay cầm")
                .setView(tv)
                .setPositiveButton("Đóng", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        showMapDialog();
                    }
                })
                .create();
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                ui.removeCallbacks(ticker);
                usbTestSink = null;
            }
        });

        dlg.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface d, int keyCode, KeyEvent ev) {
                if (ev.getAction() != KeyEvent.ACTION_DOWN || ev.getRepeatCount() != 0) return false;
                InputDevice dv = ev.getDevice();
                String name = dv != null ? dv.getName() : "?";
                log.add("PHÍM " + KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", "")
                        + " (" + keyCode + ")  <- " + name);
                while (log.size() > 6) log.remove(0);
                refresh.run();
                // BACK từ tay cầm thì giữ lại để xem, BACK từ remote thì cho đóng hộp thoại
                boolean fromPad = (ev.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
                return keyCode != KeyEvent.KEYCODE_BACK || fromPad;
            }
        });
        dlg.show();

        dlg.getWindow().getDecorView().setOnGenericMotionListener(new View.OnGenericMotionListener() {
            @Override
            public boolean onGenericMotion(View v, MotionEvent m) {
                if (!m.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false;
                StringBuilder ax = new StringBuilder();
                for (int i = 0; i < TEST_AXES.length; i++) {
                    float val = m.getAxisValue(TEST_AXES[i]);
                    if (Math.abs(val) > 0.3f) {
                        ax.append(TEST_AXIS_NAMES[i]).append('=')
                                .append(String.format(Locale.US, "%.1f", val)).append(' ');
                    }
                }
                String str = ax.toString();
                if (!str.equals(lastAxis[0])) {
                    lastAxis[0] = str;
                    if (!str.isEmpty()) {
                        InputDevice dv = m.getDevice();
                        log.add("TRỤC " + str + " <- " + (dv != null ? dv.getName() : "?"));
                        while (log.size() > 6) log.remove(0);
                        refresh.run();
                    }
                }
                return true;
            }
        });
    }

    private void captureKey(final int idx) {
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(KeyMap.LABELS[idx])
                .setMessage("Bấm một phím trên tay cầm để gán...\n(tự hủy sau 10 giây)")
                .setNegativeButton("Hủy", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        showMapDialog();
                    }
                })
                .create();
        final boolean[] done = {false};
        final Runnable timeout = new Runnable() {
            @Override
            public void run() {
                if (!done[0]) {
                    done[0] = true;
                    dlg.dismiss();
                    showMapDialog();
                }
            }
        };
        dlg.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface d, int keyCode, KeyEvent ev) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                        || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) return false;
                if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0 && !done[0]) {
                    done[0] = true;
                    ui.removeCallbacks(timeout);
                    keymap.set(idx, keyCode);
                    dlg.dismiss();
                    showMapDialog();
                }
                return true;
            }
        });
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                done[0] = true;
                usbCapture = null;
                ui.removeCallbacks(timeout);
            }
        });
        usbCapture = new KeyCapture() {
            @Override
            public void onKey(int keyCode) {
                if (done[0]) return;
                done[0] = true;
                ui.removeCallbacks(timeout);
                keymap.set(idx, keyCode);
                dlg.dismiss();
                showMapDialog();
            }
        };
        dlg.show();
        // Nút hướng/cần analog của tay cầm thường gửi dạng TRỤC, không phải phím
        dlg.getWindow().getDecorView().setOnGenericMotionListener(new View.OnGenericMotionListener() {
            private int last = 0;

            @Override
            public boolean onGenericMotion(View v, MotionEvent m) {
                if (!m.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false;
                int now = virtualMask(m);
                int pressed = now & ~last;
                last = now;
                if (pressed != 0 && !done[0]) {
                    done[0] = true;
                    ui.removeCallbacks(timeout);
                    keymap.set(idx, KeyMap.VK_BASE + Integer.numberOfTrailingZeros(pressed));
                    dlg.dismiss();
                    showMapDialog();
                }
                return true;
            }
        });
        ui.postDelayed(timeout, 10000);
    }
}
