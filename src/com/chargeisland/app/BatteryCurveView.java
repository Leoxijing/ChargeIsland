package com.chargeisland.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Locale;

/** 简单的电量-时间折线图。 */
public class BatteryCurveView extends View {

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BatteryCurveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        gridPaint.setColor(0x22FFFFFF);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);

        linePaint.setColor(0xFFFFC400);
        linePaint.setStrokeWidth(4f);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint.setColor(0x33FFC400);
        fillPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(0x88FFFFFF);
        textPaint.setTextSize(28f);

        pointPaint.setColor(0xFFFFC400);
        pointPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float padL = 8f, padR = 8f, padT = 24f, padB = 36f;
        float iw = w - padL - padR, ih = h - padT - padB;

        // 网格：0/50/100%
        for (int p = 0; p <= 100; p += 50) {
            float y = padT + ih * (1 - p / 100f);
            canvas.drawLine(padL, y, w - padR, y, gridPaint);
            canvas.drawText(p + "%", padL + 2, y - 6, textPaint);
        }

        ArrayList<long[]> pts = ChargeDataStore.snapshot();
        if (pts.size() < 2) {
            canvas.drawText("充电开始后自动生成电量曲线", padL + 10, padT + ih / 2, textPaint);
            return;
        }

        long t0 = pts.get(0)[0];
        long t1 = pts.get(pts.size() - 1)[0];
        if (t1 <= t0) t1 = t0 + 1;

        Path line = new Path();
        Path fill = new Path();
        for (int i = 0; i < pts.size(); i++) {
            long[] p = pts.get(i);
            float x = padL + iw * (p[0] - t0) / (float) (t1 - t0);
            float y = padT + ih * (1 - p[1] / 100f);
            if (i == 0) {
                line.moveTo(x, y);
                fill.moveTo(x, padT + ih);
                fill.lineTo(x, y);
            } else {
                line.lineTo(x, y);
                fill.lineTo(x, y);
            }
        }
        float lastX = padL + iw;
        fill.lineTo(lastX, padT + ih);
        fill.close();

        canvas.drawPath(fill, fillPaint);
        canvas.drawPath(line, linePaint);

        // 最新点
        long[] last = pts.get(pts.size() - 1);
        float lx = padL + iw * (last[0] - t0) / (float) (t1 - t0);
        float ly = padT + ih * (1 - last[1] / 100f);
        canvas.drawCircle(lx, ly, 8f, pointPaint);

        // 时间轴标注
        String startLabel = formatTime(t0);
        String endLabel = formatTime(t1);
        canvas.drawText(startLabel, padL, h - 8, textPaint);
        float endW = textPaint.measureText(endLabel);
        canvas.drawText(endLabel, w - padR - endW, h - 8, textPaint);
    }

    private String formatTime(long ms) {
        return String.format(Locale.getDefault(), "%tH:%tM", ms, ms);
    }
}
