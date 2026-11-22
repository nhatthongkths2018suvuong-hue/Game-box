package com.minigame.fpt;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.SparseIntArray;
import android.view.KeyEvent;

/** Bảng gán phím gamepad -> nút RetroPad, lưu riêng cho từng core (fceumm, fbneo...). */
public final class KeyMap {
    public static final int PAUSE = -1;

    /** Mã phím "ảo" cho trục tay cầm (HAT, cần analog, trigger). VK_BASE + chỉ số bit. */
    public static final int VK_BASE = 0x10000;
    public static final int VK_COUNT = 18;
    // bit 0-3: HAT lên/xuống/trái/phải; 4-7: trục X/Y; 8-11: Z, RZ; 12-15: RX, RY; 16: LT; 17: RT
    public static final String[] VK_NAMES = {
            "HAT_LÊN", "HAT_XUỐNG", "HAT_TRÁI", "HAT_PHẢI",
            "X_LÊN", "X_XUỐNG", "X_TRÁI", "X_PHẢI",
            "Z-", "Z+", "RZ-", "RZ+", "RX-", "RX+", "RY-", "RY+",
            "TRIGGER_L", "TRIGGER_R"
    };

    public static boolean isVirtual(int code) {
        return code >= VK_BASE && code < VK_BASE + VK_COUNT;
    }

    // ID nút RetroPad: B=0 Y=1 SELECT=2 START=3 UP=4 DOWN=5 LEFT=6 RIGHT=7 A=8 X=9 L=10 R=11 L2=12 R2=13
    public static final int[] IDS = {4, 5, 6, 7, 0, 8, 1, 9, 10, 11, 12, 13, 2, 3, PAUSE};

    public static final String[] LABELS = {
            "Lên", "Xuống", "Trái", "Phải",
            "B (nút dưới)", "A (nút phải)", "Y (nút trái)", "X (nút trên)",
            "L", "R", "L2", "R2",
            "Select / Coin", "Start",
            "Tạm dừng / Menu"
    };

    private static final int[] DEFAULTS = {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_MODE
    };

    private final SharedPreferences prefs;
    private final int[] keys = new int[IDS.length];
    private final SparseIntArray reverse = new SparseIntArray();

    private final String core;

    public KeyMap(Context ctx, String core) {
        this.core = core;
        prefs = ctx.getSharedPreferences("keymap", Context.MODE_PRIVATE);
        for (int i = 0; i < keys.length; i++) {
            // Chưa có cấu hình riêng của core -> dùng cấu hình chung cũ (nếu có) để không mất phím đã gán
            int legacy = prefs.getInt("k" + i, DEFAULTS[i]);
            keys[i] = prefs.getInt(core + "_k" + i, legacy);
        }
        rebuild();
    }

    private void rebuild() {
        reverse.clear();
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != 0) reverse.put(keys[i], i);
        }
    }

    private void save() {
        SharedPreferences.Editor e = prefs.edit();
        for (int i = 0; i < keys.length; i++) e.putInt(core + "_k" + i, keys[i]);
        e.apply();
    }

    /** Chỉ số hành động ứng với keyCode, hoặc -1 nếu chưa gán. */
    public int indexFor(int keyCode) {
        return reverse.get(keyCode, -1);
    }

    public void set(int idx, int keyCode) {
        for (int j = 0; j < keys.length; j++) {
            if (j != idx && keys[j] == keyCode) keys[j] = 0;
        }
        keys[idx] = keyCode;
        rebuild();
        save();
    }

    public void reset() {
        System.arraycopy(DEFAULTS, 0, keys, 0, keys.length);
        rebuild();
        save();
    }

    public String keyName(int idx) {
        if (keys[idx] == 0) return "(chưa gán)";
        if (isVirtual(keys[idx])) return VK_NAMES[keys[idx] - VK_BASE];
        return KeyEvent.keyCodeToString(keys[idx]).replace("KEYCODE_", "");
    }
}
