package com.minigame.fpt;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (emu.hasGame()) emu.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        startSync();
        if (fullscreen && emu.hasGame()) emu.resume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
        for (int p = 0; p < 2; p++) {
            keyMask[p] = 0;
            axisMask[p] = 0;
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
        Native.nativeSetInput(port, keyMask[port] | axisMask[port]);
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
        int idx = keymap.indexFor(code);

        if (idx < 0) {
            // Phím chưa gán: BACK mặc định = tạm dừng
            if (code == KeyEvent.KEYCODE_BACK && down && e.getRepeatCount() == 0) pauseToMenu();
            return true;
        }
        int id = KeyMap.IDS[idx];
        if (id == KeyMap.PAUSE) {
            if (down && e.getRepeatCount() == 0) pauseToMenu();
            return true;
        }
        int port = portFor(e.getDeviceId());
        if (down) keyMask[port] |= (1 << id);
        else keyMask[port] &= ~(1 << id);
        pushInput(port);
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (fullscreen && ev.isFromSource(InputDevice.SOURCE_JOYSTICK)
                && ev.getAction() == MotionEvent.ACTION_MOVE) {
            int port = portFor(ev.getDeviceId());
            float hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
            float x = ev.getAxisValue(MotionEvent.AXIS_X);
            float y = ev.getAxisValue(MotionEvent.AXIS_Y);
            float lt = Math.max(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                    ev.getAxisValue(MotionEvent.AXIS_BRAKE));
            float rt = Math.max(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                    ev.getAxisValue(MotionEvent.AXIS_GAS));
            int m = 0;
            if (hx < -0.5f || x < -0.5f) m |= BIT_LEFT;
            if (hx > 0.5f || x > 0.5f) m |= BIT_RIGHT;
            if (hy < -0.5f || y < -0.5f) m |= BIT_UP;
            if (hy > 0.5f || y > 0.5f) m |= BIT_DOWN;
            if (lt > 0.5f) m |= BIT_L2;
            if (rt > 0.5f) m |= BIT_R2;
            axisMask[port] = m;
            pushInput(port);
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

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
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER
    };
    private static final String[] TEST_AXIS_NAMES = {
            "X", "Y", "Z", "RZ", "HAT_X", "HAT_Y", "L_TRIGGER", "R_TRIGGER"
    };

    private void showPadTest() {
        final TextView tv = new TextView(this);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(14);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(dp(20), dp(10), dp(20), dp(10));

        StringBuilder head = new StringBuilder("Android đang thấy các thiết bị nhập:\n");
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
        head.append("\nBấm nút hoặc gạt cần trên tay cầm, sự kiện hiện bên dưới.\n"
                + "Nếu không hiện gì: Android không nhận tay cầm.\n\n");
        final String headStr = head.toString();
        final ArrayList<String> log = new ArrayList<>();
        final String[] lastAxis = {""};

        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder(headStr);
                if (log.isEmpty()) sb.append("(chưa có sự kiện nào)");
                for (String l : log) sb.append(l).append('\n');
                tv.setText(sb.toString());
            }
        };
        refresh.run();

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

        dlg.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface d, int keyCode, KeyEvent ev) {
                if (ev.getAction() != KeyEvent.ACTION_DOWN || ev.getRepeatCount() != 0) return false;
                InputDevice dv = ev.getDevice();
                String name = dv != null ? dv.getName() : "?";
                log.add("PHÍM " + KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", "")
                        + " (" + keyCode + ")  <- " + name);
                while (log.size() > 8) log.remove(0);
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
                        while (log.size() > 8) log.remove(0);
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
                ui.removeCallbacks(timeout);
            }
        });
        dlg.show();
        ui.postDelayed(timeout, 10000);
    }
}
