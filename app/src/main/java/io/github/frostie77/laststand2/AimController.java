package io.github.frostie77.laststand2;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/** Relative mouse cursor and analogue trigger support for Android gamepads. */
final class AimController {
    interface Input {
        void aim(float x, float y);
        void button(int code, boolean down, String source);
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final View viewport;
    private final Input input;
    private float x = 0.5f, y = 0.5f, axisX, axisY;
    private float speed = 0.9f, deadzone = 0.22f;
    private boolean invertX, invertY, running, leftTrigger, rightTrigger;
    private long lastTime;

    AimController(View viewport, Input input) { this.viewport = viewport; this.input = input; }

    void configure(int speedIndex, int zoneIndex, boolean invertX, boolean invertY) {
        speed = new float[]{0.55f, 0.9f, 1.4f}[Math.max(0, Math.min(2, speedIndex))];
        deadzone = new float[]{0.14f, 0.22f, 0.32f}[Math.max(0, Math.min(2, zoneIndex))];
        this.invertX = invertX; this.invertY = invertY;
    }

    void setPosition(float x, float y) {
        this.x = Math.max(0, Math.min(1, x));
        this.y = Math.max(0, Math.min(1, y));
    }

    boolean accept(MotionEvent event) {
        InputDevice device = event.getDevice();
        boolean zAxes = device == null || device.getMotionRange(MotionEvent.AXIS_Z) != null;
        axisX = event.getAxisValue(zAxes ? MotionEvent.AXIS_Z : MotionEvent.AXIS_RX);
        axisY = event.getAxisValue(zAxes ? MotionEvent.AXIS_RZ : MotionEvent.AXIS_RY);
        float right = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS));
        float left = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE));
        boolean nextRight = right > (rightTrigger ? 0.35f : 0.55f);
        boolean nextLeft = left > (leftTrigger ? 0.35f : 0.55f);
        if (nextRight != rightTrigger) {
            rightTrigger = nextRight;
            input.button(KeyEvent.KEYCODE_BUTTON_R2, nextRight, "rightTrigger");
        }
        if (nextLeft != leftTrigger) {
            leftTrigger = nextLeft;
            input.button(KeyEvent.KEYCODE_BUTTON_L2, nextLeft, "leftTrigger");
        }
        boolean moving = Math.hypot(axisX, axisY) > deadzone;
        if (moving && !running) {
            running = true; lastTime = SystemClock.uptimeMillis(); handler.post(tick);
        }
        return moving || nextLeft || nextRight;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            float magnitude = (float) Math.hypot(axisX, axisY);
            if (magnitude <= deadzone) { running = false; return; }
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.05f, Math.max(0, now - lastTime) / 1000f);
            lastTime = now;
            float response = Math.min(1f, (magnitude - deadzone) / (1f - deadzone));
            float pixels = Math.min(viewport.getWidth(), viewport.getHeight()) * speed * response * dt;
            float previousX = x, previousY = y;
            x += axisX / magnitude * pixels / Math.max(1, viewport.getWidth()) * (invertX ? -1 : 1);
            y += axisY / magnitude * pixels / Math.max(1, viewport.getHeight()) * (invertY ? -1 : 1);
            setPosition(x, y);
            if (x != previousX || y != previousY) input.aim(x, y);
            handler.postDelayed(this, 16);
        }
    };

    void release() {
        handler.removeCallbacks(tick); running = false; axisX = axisY = 0;
        if (rightTrigger) input.button(KeyEvent.KEYCODE_BUTTON_R2, false, "rightTrigger");
        if (leftTrigger) input.button(KeyEvent.KEYCODE_BUTTON_L2, false, "leftTrigger");
        rightTrigger = leftTrigger = false;
    }
}
