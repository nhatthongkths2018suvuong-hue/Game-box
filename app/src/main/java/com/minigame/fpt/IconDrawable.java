package com.minigame.fpt;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Icon vẽ bằng code (không cần file ảnh): tiếp tục, cài đặt (bánh răng), tỷ lệ hình. */
public class IconDrawable extends Drawable {
    public static final int PLAY = 0;
    public static final int GEAR = 1;
    public static final int ASPECT = 2;

    private final int type;
    private final int size;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public IconDrawable(int type, int sizePx) {
        this.type = type;
        this.size = sizePx;
        p.setColor(0xFFFFFFFF);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float r = Math.min(b.width(), b.height()) / 2f;
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();

        if (type == PLAY) {
            Path path = new Path();
            path.moveTo(cx - r * 0.45f, cy - r * 0.7f);
            path.lineTo(cx + r * 0.8f, cy);
            path.lineTo(cx - r * 0.45f, cy + r * 0.7f);
            path.close();
            p.setStyle(Paint.Style.FILL);
            c.drawPath(path, p);
        } else if (type == GEAR) {
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 8; i++) {
                c.save();
                c.rotate(i * 45f, cx, cy);
                c.drawRect(cx - r * 0.15f, cy - r, cx + r * 0.15f, cy - r * 0.6f, p);
                c.restore();
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.3f);
            c.drawCircle(cx, cy, r * 0.58f, p);
        } else {
            // Khung 4 góc (biểu tượng phóng to) + hình chữ nhật nhỏ ở giữa
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.17f);
            float a = r * 0.92f;
            float l = r * 0.5f;
            c.drawLine(cx - a, cy - a, cx - a + l, cy - a, p);
            c.drawLine(cx - a, cy - a, cx - a, cy - a + l, p);
            c.drawLine(cx + a, cy - a, cx + a - l, cy - a, p);
            c.drawLine(cx + a, cy - a, cx + a, cy - a + l, p);
            c.drawLine(cx - a, cy + a, cx - a + l, cy + a, p);
            c.drawLine(cx - a, cy + a, cx - a, cy + a - l, p);
            c.drawLine(cx + a, cy + a, cx + a - l, cy + a, p);
            c.drawLine(cx + a, cy + a, cx + a, cy + a - l, p);
            p.setStyle(Paint.Style.FILL);
            c.drawRect(cx - r * 0.38f, cy - r * 0.26f, cx + r * 0.38f, cy + r * 0.26f, p);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        p.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        p.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }
}
