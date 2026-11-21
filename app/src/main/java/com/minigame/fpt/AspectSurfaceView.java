package com.minigame.fpt;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceView;

/** SurfaceView tự co theo tỉ lệ khung hình của game, nằm giữa vùng chứa. */
public class AspectSurfaceView extends SurfaceView {
    private float aspect = 4f / 3f;

    public AspectSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setAspect(float a) {
        if (a > 0.1f && Math.abs(a - aspect) > 0.001f) {
            aspect = a;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        int tw = w;
        int th = Math.round(w / aspect);
        if (th > h) {
            th = h;
            tw = Math.round(h * aspect);
        }
        setMeasuredDimension(tw, th);
    }
}
