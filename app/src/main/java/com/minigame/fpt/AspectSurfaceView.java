package com.minigame.fpt;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceView;

/**
 * SurfaceView tự đổi kích thước theo chế độ tỷ lệ hình:
 * 0 = gốc (giữ tỷ lệ, vừa khung), 1 = dãn kín cả 2 cạnh,
 * 2 = dãn theo cạnh dài (giữ tỷ lệ, cắt phần thừa), 3 = dãn theo cạnh ngắn (giữ tỷ lệ).
 */
public class AspectSurfaceView extends SurfaceView {
    public static final int MODE_ORIGINAL = 0;
    public static final int MODE_STRETCH = 1;
    public static final int MODE_LONG = 2;
    public static final int MODE_SHORT = 3;

    private float aspect = 4f / 3f;
    private int mode = MODE_ORIGINAL;
    private boolean cropAllowed = false; // chỉ cho phép tràn ra ngoài khi đang full màn hình

    public AspectSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setAspect(float a) {
        if (a > 0.1f && Math.abs(a - aspect) > 0.001f) {
            aspect = a;
            requestLayout();
        }
    }

    public void setMode(int m) {
        if (m != mode) {
            mode = m;
            requestLayout();
        }
    }

    public void setCropAllowed(boolean allowed) {
        if (allowed != cropAllowed) {
            cropAllowed = allowed;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float w = MeasureSpec.getSize(widthSpec);
        float h = MeasureSpec.getSize(heightSpec);

        // Giữ tỷ lệ, vừa khung
        float cw = w;
        float ch = w / aspect;
        if (ch > h) {
            ch = h;
            cw = h * aspect;
        }

        float tw = cw, th = ch;
        switch (mode) {
            case MODE_STRETCH:
                tw = w;
                th = h;
                break;
            case MODE_LONG:
                if (cropAllowed) {
                    if (w >= h) {
                        tw = w;
                        th = w / aspect;
                    } else {
                        th = h;
                        tw = h * aspect;
                    }
                }
                break;
            case MODE_SHORT:
                if (cropAllowed) {
                    if (w >= h) {
                        th = h;
                        tw = h * aspect;
                    } else {
                        tw = w;
                        th = w / aspect;
                    }
                }
                break;
            default:
                break;
        }
        setMeasuredDimension(Math.round(tw), Math.round(th));
    }
}
