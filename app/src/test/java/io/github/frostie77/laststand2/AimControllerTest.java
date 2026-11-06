package io.github.frostie77.laststand2;

import static org.junit.Assert.*;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@LooperMode(LooperMode.Mode.PAUSED)
public class AimControllerTest {
    private AimController controller;
    private final List<float[]> positions=new ArrayList<>();
    private final List<String> buttons=new ArrayList<>();
    @Before public void setup() {
        View view=new View(RuntimeEnvironment.getApplication()); view.layout(0,0,1000,600);
        controller=new AimController(view,new AimController.Input() {
            @Override public void aim(float x,float y) { positions.add(new float[]{x,y}); }
            @Override public void button(int code,boolean down,String source) { buttons.add(code+":"+down+":"+source); }
        });
    }
    private void axes(float x,float y,float trigger) {
        MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=0;
        MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();
        c.setAxisValue(MotionEvent.AXIS_Z,x);c.setAxisValue(MotionEvent.AXIS_RZ,y);
        c.setAxisValue(MotionEvent.AXIS_RTRIGGER,trigger);
        long now=SystemClock.uptimeMillis();
        MotionEvent e=MotionEvent.obtain(now,now,MotionEvent.ACTION_MOVE,1,
            new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,0,1,1,-1,0,InputDevice.SOURCE_JOYSTICK,0);
        try { controller.accept(e); } finally { e.recycle(); }
    }
    private void frames(int ms) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }
    @Test public void relativeCursorRetainsPositionAtNeutralAndReleaseStopsTicker() {
        controller.setPosition(0.2f,0.3f);axes(0.9f,0,0);frames(64);
        float[] p=positions.get(positions.size()-1);
        assertTrue(p[0]>0.2f); assertEquals(0.3f,p[1],0.0001f);
        axes(0,0,0);frames(32);int count=positions.size();frames(64);
        assertEquals(count,positions.size());
        axes(0.9f,0,0);frames(32);controller.release();count=positions.size();frames(64);
        assertEquals(count,positions.size());
    }
    @Test public void triggerHysteresisAndLifecycleProduceOnePressAndRelease() {
        axes(0,0,0.6f);axes(0,0,0.45f);axes(0,0,0.2f);axes(0,0,0.7f);controller.release();
        assertEquals(List.of("105:true:rightTrigger","105:false:rightTrigger",
            "105:true:rightTrigger","105:false:rightTrigger"),buttons);
    }
    @Test public void pushingPastScreenEdgeDoesNotSendStationaryAim() {
        controller.setPosition(1f,0.5f); axes(0.9f,0,0); frames(100);
        assertTrue(positions.isEmpty());
        axes(-0.9f,0,0); frames(32);
        assertFalse(positions.isEmpty()); controller.release();
    }
    @Test public void aimInversionDoesNotAffectMovementKeysAndDriftDoesNotMoveCursor() {
        controller.configure(1,1,true,true);axes(0.1f,0.1f,0);frames(64);
        assertTrue(positions.isEmpty());axes(0.7f,0.7f,0);frames(64);
        float[] p=positions.get(positions.size()-1);
        assertTrue(p[0]<0.5f);assertTrue(p[1]<0.5f);assertTrue(buttons.isEmpty());controller.release();
    }
}
