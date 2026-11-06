package io.github.frostie77.laststand2;

import static org.junit.Assert.*;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import io.github.frostfrontend.FrostButtonMapper;
import io.github.frostfrontend.FrostFrontendView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.RobolectricTestRunner;

/** Exercise the real Activity routing, including Auto touch mode and mapping. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@LooperMode(LooperMode.Mode.PAUSED)
public class GamepadRoutingTest {
    private ActivityController<MainActivity> lifecycle;
    private MainActivity activity;
    private RecordingWebView web;
    private Button menu;
    private final List<String> scripts = new ArrayList<>();
    private final List<Boolean> focusRequestedAtDelivery = new ArrayList<>();
    private final List<ValueCallback<String>> completions = new ArrayList<>();

    private final class RecordingWebView extends WebView {
        int focusRequests;
        RecordingWebView() {
            super(activity); setFocusable(true); setFocusableInTouchMode(true);
        }
        @Override public boolean requestFocus(int direction, Rect previous) {
            focusRequests++;
            return super.requestFocus(direction, previous);
        }
        @Override public void evaluateJavascript(String script, ValueCallback<String> callback) {
            // Robolectric has no Chromium focus owner. Observe the native
            // focus request before bridge delivery instead of its stub state.
            scripts.add(script); focusRequestedAtDelivery.add(focusRequests > 0);
            if (callback != null) completions.add(callback);
        }
    }
    private Object field(String name) throws Exception {
        Field f=MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity);
    }
    private void setField(String name,Object value) throws Exception {
        Field f=MainActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(activity,value);
    }
    private void invoke(String name) throws Exception {
        Method m=MainActivity.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(activity);
    }
    @Before public void setup() throws Exception {
        lifecycle=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();
        activity=lifecycle.get();
        ((SharedPreferences)field("preferences")).edit().putBoolean("updates_automatic",false).apply();
        FrameLayout root=(FrameLayout)field("root"); root.removeAllViews();
        web=new RecordingWebView(); setField("webView",web);
        root.addView(web,new FrameLayout.LayoutParams(-1,-1));
        invoke("addAimController"); invoke("addFrontend");
        root.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));
        root.layout(0,0,1000,600);
        menu=(Button)field("menuButton"); menu.setFocusableInTouchMode(true);
        assertTrue(menu.requestFocus()); assertTrue(menu.hasFocus());
        scripts.clear(); focusRequestedAtDelivery.clear(); web.focusRequests=0;
        completions.clear();
    }
    @After public void teardown() { if (lifecycle!=null) lifecycle.pause().stop().destroy(); }
    private void key(int code,boolean down) {
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(down?KeyEvent.ACTION_DOWN:KeyEvent.ACTION_UP,code)));
    }
    private void axes(float move,float aim,float trigger) {
        MotionEvent.PointerProperties p=new MotionEvent.PointerProperties(); p.id=0;
        MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();
        c.setAxisValue(MotionEvent.AXIS_X,move); c.setAxisValue(MotionEvent.AXIS_Z,aim);
        c.setAxisValue(MotionEvent.AXIS_RTRIGGER,trigger);
        long now=SystemClock.uptimeMillis();
        MotionEvent e=MotionEvent.obtain(now,now,MotionEvent.ACTION_MOVE,1,
            new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},
            0,0,1,1,-1,0,InputDevice.SOURCE_JOYSTICK,0);
        try { assertTrue(activity.dispatchGenericMotionEvent(e)); } finally { e.recycle(); }
    }
    private void assertFocusedInput(String fragment) {
        for(int i=scripts.size()-1;i>=0;i--) if(scripts.get(i).contains(fragment)) {
            assertTrue("Game input arrived before native focus was requested: "+fragment,
                focusRequestedAtDelivery.get(i)); return;
        }
        fail("No game input: "+fragment+"; scripts="+scripts);
    }
    private void aim(float x,float y) throws Exception {
        Method method=MainActivity.class.getDeclaredMethod("jsAim",float.class,float.class);
        method.setAccessible(true); method.invoke(activity,x,y);
    }
    @Test public void busyRendererGetsLatestAimWithoutAQueueOfOldPositions() throws Exception {
        aim(0.1f,0.2f);
        for(int i=2;i<=90;i++) aim(i/100f,0.2f);
        assertEquals(1,scripts.size());
        completions.remove(0).onReceiveValue("null");
        assertEquals(2,scripts.size());
        assertTrue(scripts.get(1).contains("tls2Aim(0.9,0.2)"));
        completions.remove(0).onReceiveValue("null");
        assertEquals(2,scripts.size());
    }
    @Test public void buttonEdgesFlushLatestAimAndPauseDiscardsPendingMovement() throws Exception {
        aim(0.1f,0.2f); aim(0.8f,0.7f);
        key(KeyEvent.KEYCODE_BUTTON_A,true);
        assertTrue(scripts.get(1).contains("tls2Aim(0.8,0.7);window.tls2Key"));
        key(KeyEvent.KEYCODE_BUTTON_A,false);
        assertTrue(scripts.get(2).contains("'select',false,'button96'"));
        completions.remove(0).onReceiveValue("null");
        assertEquals(3,scripts.size());
        aim(0.3f,0.4f); aim(0.4f,0.5f);
        invoke("releaseGameInputs");
        int count=scripts.size();
        completions.remove(0).onReceiveValue("null");
        assertEquals(count,scripts.size());
    }
    @Test public void buttonRestoresFocusAndAutoHidesTouchBeforeDelivery() throws Exception {
        key(KeyEvent.KEYCODE_BUTTON_A,true);
        assertFocusedInput("'select',true,'button96'");
        assertEquals(View.GONE,((View)field("touchControls")).getVisibility());
        key(KeyEvent.KEYCODE_BUTTON_A,false);
        assertFocusedInput("'select',false,'button96'");
    }
    @Test public void firstAnalogTriggerPressRestoresFocusBeforeFire() {
        axes(0,0,0.7f); assertFocusedInput("'fire',true,'rightTrigger'");
        axes(0,0,0); assertFocusedInput("'fire',false,'rightTrigger'");
    }
    @Test public void remappingAndFrontendRoundTripKeepGamepadInputWorking() throws Exception {
        FrostButtonMapper mapper=(FrostButtonMapper)field("mapper");
        mapper.assign("reload",KeyEvent.KEYCODE_BUTTON_B);
        FrostFrontendView frontend=(FrostFrontendView)field("frontend");
        frontend.open(); frontend.close(); assertFalse(frontend.isOpen());
        assertTrue(menu.requestFocus());
        web.focusRequests=0;
        key(KeyEvent.KEYCODE_BUTTON_B,true);
        assertFocusedInput("'reload',true,'button97'");
        key(KeyEvent.KEYCODE_BUTTON_B,false);
        assertFocusedInput("'reload',false,'button97'");
    }
    @Test public void stickMovementAndAimRestoreFocusAfterSettingsClose() throws Exception {
        FrostFrontendView frontend=(FrostFrontendView)field("frontend");
        frontend.open(); frontend.close(); assertTrue(menu.requestFocus());
        // Closing the overlay requests another layout. Give the stub WebView
        // a real viewport so this asserts movement, not a zero-delta event.
        web.layout(0,0,1000,600);
        web.focusRequests=0;
        axes(0.9f,0.8f,0);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));
        assertFocusedInput("'right',true,'stick'");
        assertFocusedInput("window.tls2Aim(");
        axes(0,0,0); assertFocusedInput("'right',false,'stick'");
    }
}
