package io.github.frostie77.laststand2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import io.github.frostfrontend.FrostTouchControls;

/** A movable movement pad and two buttons, plus direct touch aim/fire. */
public final class LastStand2TouchControls extends FrostTouchControls {
    public interface Input {
        void key(String key, boolean down);
        void pointer(String kind, float x, float y);
    }
    private final Input input;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String[] keys = {"left", "right", "up", "down", "reload", "swap"};
    private final boolean[] pressed = new boolean[6];
    private final int[] owners = {-1, -1, -1};
    private int aimPointer = -1;
    private float radius, thumbX, thumbY;
    private int sizeIndex = 1, opacityIndex = 1, padMode, positionIndex = 1;
    private boolean swapped;

    public LastStand2TouchControls(Context context, Input input) {
        super(context, "the-last-stand-2", "movement", "reload", "swap");
        this.input = input;
    }
    public void configure(int size, int opacity, int pad, boolean swapped, int position) {
        sizeIndex = Math.max(0, Math.min(2, size));
        opacityIndex = Math.max(0, Math.min(2, opacity));
        padMode = pad == 1 ? 1 : 0; this.swapped = swapped;
        positionIndex = Math.max(0, Math.min(2, position)); invalidate();
    }
    @Override protected void computeLayout() {
        setLayoutMirrored(swapped);
        radius = dp(59) * new float[]{0.84f, 1f, 1.16f}[sizeIndex];
        float inset = dp(new float[]{14, 26, 42}[positionIndex]);
        float mx = swapped ? getWidth() - inset - radius : inset + radius;
        float bx = swapped ? inset + radius : getWidth() - inset - radius;
        float y = getHeight() - inset - radius;
        positionControl(0, mx, y, radius * 1.04f, radius * 1.27f);
        positionControl(1, bx, y, radius * 0.56f, radius * 0.65f);
        positionControl(2, bx + (swapped ? 1 : -1) * radius * 1.45f, y,
            radius * 0.56f, radius * 0.65f);
    }
    private void set(int index, boolean down) {
        if (pressed[index] == down) return;
        pressed[index] = down; input.key(keys[index], down); invalidate();
    }
    private void move(float x, float y) {
        float dx = (x - centerX[0]) / radius, dy = (y - centerY[0]) / radius;
        float distance = (float) Math.hypot(dx, dy);
        if (distance < 0.27f) { dx = 0; dy = 0; }
        else if (distance > 1f) { dx /= distance; dy /= distance; }
        thumbX = dx; thumbY = dy;
        set(0, dx < -0.28f); set(1, dx > 0.28f);
        set(2, dy < -0.28f); set(3, dy > 0.28f); invalidate();
    }
    private void aim(String kind, float x, float y) {
        input.pointer(kind, x / Math.max(1, getWidth()), y / Math.max(1, getHeight()));
    }
    @Override protected boolean onGameplayTouch(MotionEvent event) {
        int action = event.getActionMasked(), index = event.getActionIndex();
        if (action == MotionEvent.ACTION_CANCEL) { releaseAll(); return true; }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int id = event.getPointerId(index);
            float x = event.getX(index), y = event.getY(index);
            // Buttons win overlapping hit areas. Each finger retains its role
            // until release, even when Android reorders pointer indices.
            int hit = -1;
            for (int c = 2; c >= 0; c--)
                if (Math.hypot(x - centerX[c], y - centerY[c]) <= radius * (c == 0 ? 1.27f : 0.65f)) { hit = c; break; }
            if (hit >= 0) {
                if (owners[hit] == -1) {
                    owners[hit] = id;
                    if (hit == 0) move(x, y); else set(hit + 3, true);
                }
            } else if (aimPointer == -1) {
                aimPointer = id; aim("down", x, y);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            int movement = event.findPointerIndex(owners[0]);
            if (movement >= 0) move(event.getX(movement), event.getY(movement));
            int pointer = event.findPointerIndex(aimPointer);
            if (pointer >= 0) aim("move", event.getX(pointer), event.getY(pointer));
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int id = event.getPointerId(index);
            for (int c = 0; c < owners.length; c++) if (owners[c] == id) {
                owners[c] = -1;
                if (c == 0) { thumbX = thumbY = 0; for (int k = 0; k < 4; k++) set(k, false); }
                else set(c + 3, false);
            }
            if (aimPointer == id) { aim("up", event.getX(index), event.getY(index)); aimPointer = -1; }
            if (action == MotionEvent.ACTION_UP) releaseAll();
            invalidate(); return true;
        }
        return true;
    }
    @Override protected void releaseGameKeys() {
        java.util.Arrays.fill(owners, -1);
        if (aimPointer != -1) input.pointer("cancel", 0, 0);
        aimPointer = -1; thumbX = thumbY = 0;
        for (int k = 0; k < pressed.length; k++) set(k, false);
        invalidate();
    }
    @Override protected void drawControls(Canvas canvas) {
        int alpha = new int[]{80, 136, 196}[opacityIndex];
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.argb(alpha, 28, 28, 24));
        if (padMode == 0) {
            canvas.drawCircle(centerX[0], centerY[0], radius, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(3));
            paint.setColor(Color.argb(Math.min(255, alpha + 65), 204, 197, 163));
            canvas.drawCircle(centerX[0], centerY[0], radius, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(centerX[0] + thumbX * radius * 0.52f,
                centerY[0] + thumbY * radius * 0.52f, radius * 0.43f, paint);
        } else {
            float x = centerX[0], y = centerY[0], w = radius * 0.34f, r = radius;
            Path cross = new Path();
            cross.moveTo(x-w,y-r); cross.lineTo(x+w,y-r); cross.lineTo(x+w,y-w);
            cross.lineTo(x+r,y-w); cross.lineTo(x+r,y+w); cross.lineTo(x+w,y+w);
            cross.lineTo(x+w,y+r); cross.lineTo(x-w,y+r); cross.lineTo(x-w,y+w);
            cross.lineTo(x-r,y+w); cross.lineTo(x-r,y-w); cross.lineTo(x-w,y-w); cross.close();
            canvas.drawPath(cross, paint);
            paint.setColor(Color.argb(Math.min(255, alpha+70), 189, 152, 95));
            if (pressed[0]) canvas.drawRect(x-r,y-w,x-w,y+w,paint);
            if (pressed[1]) canvas.drawRect(x+w,y-w,x+r,y+w,paint);
            if (pressed[2]) canvas.drawRect(x-w,y-r,x+w,y-w,paint);
            if (pressed[3]) canvas.drawRect(x-w,y+w,x+w,y+r,paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(3));
            paint.setColor(Color.argb(Math.min(255,alpha+80), 204,197,163)); canvas.drawPath(cross,paint);
        }
        for (int c = 1; c <= 2; c++) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(alpha, pressed[c+3] ? 153 : 37, pressed[c+3] ? 69 : 34, 30));
            canvas.drawCircle(centerX[c], centerY[c], radius * 0.56f, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2));
            paint.setColor(Color.argb(Math.min(255,alpha+70), 224,216,184));
            canvas.drawCircle(centerX[c], centerY[c], radius * 0.56f, paint);
            paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(12));
            canvas.drawText(c == 1 ? "Reload" : "Switch", centerX[c], centerY[c] - (paint.ascent()+paint.descent())/2, paint);
        }
    }
}
