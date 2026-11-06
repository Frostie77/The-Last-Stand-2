package io.github.frostie77.laststand2;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import io.github.frostfrontend.FrostTouchControls;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, qualifiers="mdpi")
public class TouchControlsTest {
    private Context context;
    private final List<String> inputs = new ArrayList<>();
    private final List<float[]> positions = new ArrayList<>();
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("frost_touch_layout_the-last-stand-2",0).edit().clear().commit();
    }
    private LastStand2TouchControls controls() {
        LastStand2TouchControls view = new LastStand2TouchControls(context,new LastStand2TouchControls.Input() {
            @Override public void key(String key,boolean down) { inputs.add(key+":"+down); }
            @Override public void pointer(String kind,float x,float y) {
                inputs.add("pointer:"+kind); positions.add(new float[]{x,y});
            }
        });
        view.layout(0,0,1000,600); draw(view); return view;
    }
    private static void draw(LastStand2TouchControls view) {
        view.draw(new Canvas(Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888)));
    }
    private static float[] center(LastStand2TouchControls view,int index) throws Exception {
        draw(view);
        Field x = FrostTouchControls.class.getDeclaredField("centerX"), y = FrostTouchControls.class.getDeclaredField("centerY");
        x.setAccessible(true); y.setAccessible(true);
        return new float[]{((float[])x.get(view))[index],((float[])y.get(view))[index]};
    }
    private static void touch(LastStand2TouchControls view,int action,int[] ids,float...xy) {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[ids.length];
        for (int i=0;i<ids.length;i++) {
            props[i]=new MotionEvent.PointerProperties(); props[i].id=ids[i]; props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[i]=new MotionEvent.PointerCoords(); coords[i].x=xy[i*2]; coords[i].y=xy[i*2+1]; coords[i].pressure=1;
        }
        long now=SystemClock.uptimeMillis();
        MotionEvent e=MotionEvent.obtain(now,now,action,ids.length,props,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try { assertTrue(view.onTouchEvent(e)); } finally { e.recycle(); }
    }
    private static void drag(LastStand2TouchControls view,int index,float x,float y) throws Exception {
        touch(view,MotionEvent.ACTION_DOWN,new int[]{7},center(view,index));
        touch(view,MotionEvent.ACTION_MOVE,new int[]{7},x,y);
        touch(view,MotionEvent.ACTION_UP,new int[]{7},x,y);
    }
    @Test public void simultaneousMovementAimAndReloadOwnSeparatePointers() throws Exception {
        LastStand2TouchControls view=controls();
        float[] m=center(view,0),r=center(view,1);
        touch(view,MotionEvent.ACTION_DOWN,new int[]{7},m[0]+40,m[1]);
        touch(view,MotionEvent.ACTION_POINTER_DOWN | 1<<8,new int[]{7,3},m[0]+40,m[1],500,200);
        touch(view,MotionEvent.ACTION_POINTER_DOWN | 2<<8,new int[]{7,3,9},m[0]+40,m[1],500,200,r[0],r[1]);
        // Android reordered the IDs: releasing reload must retain aim and movement.
        touch(view,MotionEvent.ACTION_POINTER_UP,new int[]{9,7,3},r[0],r[1],m[0]+40,m[1],500,200);
        assertEquals(Arrays.asList("right:true","pointer:down","reload:true","reload:false"),inputs);
        touch(view,MotionEvent.ACTION_MOVE,new int[]{3,7},550,230,m[0]+40,m[1]);
        touch(view,MotionEvent.ACTION_POINTER_UP,new int[]{3,7},550,230,m[0]+40,m[1]);
        assertFalse(inputs.contains("right:false"));
        touch(view,MotionEvent.ACTION_UP,new int[]{7},m[0]+40,m[1]);
        assertTrue(inputs.contains("pointer:up")); assertTrue(inputs.contains("right:false"));
    }
    @Test public void cancelReleasesFireAndAllKeys() throws Exception {
        LastStand2TouchControls view=controls(); float[] r=center(view,1);
        touch(view,MotionEvent.ACTION_DOWN,new int[]{0},500,200);
        touch(view,MotionEvent.ACTION_POINTER_DOWN | 1<<8,new int[]{0,5},500,200,r[0],r[1]);
        touch(view,MotionEvent.ACTION_CANCEL,new int[]{5,0},r[0],r[1],500,200);
        assertEquals(Arrays.asList("pointer:down","reload:true","pointer:cancel","reload:false"),inputs);
    }
    @Test public void relativeCombatTouchNeverSnapsAndRetouchRetainsAim() {
        LastStand2TouchControls view=controls(); view.setCombat(true);
        view.setAimPosition(0.3f,0.4f);
        touch(view,MotionEvent.ACTION_DOWN,new int[]{3},700,250);
        assertArrayEquals(new float[]{0.3f,0.4f},positions.get(0),0.0001f);
        touch(view,MotionEvent.ACTION_MOVE,new int[]{3},750,280);
        assertArrayEquals(new float[]{0.3675f,0.4675f},positions.get(1),0.0001f);
        touch(view,MotionEvent.ACTION_UP,new int[]{3},750,280);
        touch(view,MotionEvent.ACTION_DOWN,new int[]{8},450,180);
        assertArrayEquals(new float[]{0.3675f,0.4675f},positions.get(3),0.0001f);
        touch(view,MotionEvent.ACTION_CANCEL,new int[]{8},450,180);
        assertEquals("pointer:cancel",inputs.get(inputs.size()-1));
    }
    @Test public void menuTransitionCancelsHeldAimAndRestoresDirectTaps() {
        LastStand2TouchControls view=controls(); view.setCombat(true);
        touch(view,MotionEvent.ACTION_DOWN,new int[]{4},700,250);
        view.setCombat(false);
        assertEquals(Arrays.asList("pointer:down","pointer:cancel"),inputs);
        touch(view,MotionEvent.ACTION_UP,new int[]{4},700,250);
        touch(view,MotionEvent.ACTION_DOWN,new int[]{1},650,180);
        assertArrayEquals(new float[]{0.65f,0.3f},positions.get(positions.size()-1),0.0001f);
    }
    @Test public void directPreferenceAndSensitivityWorkIndependently() {
        LastStand2TouchControls view=controls(); view.setCombat(true);
        view.configureAim(false,1,false);
        touch(view,MotionEvent.ACTION_DOWN,new int[]{4},700,240);
        assertArrayEquals(new float[]{0.7f,0.4f},positions.get(0),0.0001f);
        view.configureAim(true,0,false); view.setAimPosition(0.4f,0.4f);
        touch(view,MotionEvent.ACTION_DOWN,new int[]{3},600,200);
        touch(view,MotionEvent.ACTION_MOVE,new int[]{3},700,230);
        assertArrayEquals(new float[]{0.48f,0.44f},positions.get(positions.size()-1),0.0001f);
    }
    @Test public void stationaryFingerDoesNotFloodPointerEventsAndEdgesRespondImmediately() {
        LastStand2TouchControls view=controls(); view.setCombat(true);
        touch(view,MotionEvent.ACTION_DOWN,new int[]{3},600,200);
        touch(view,MotionEvent.ACTION_MOVE,new int[]{3},600,200);
        assertEquals(1,inputs.size());
        touch(view,MotionEvent.ACTION_MOVE,new int[]{3},2000,0);
        assertEquals(1f,positions.get(1)[0],0.0001f);
        touch(view,MotionEvent.ACTION_MOVE,new int[]{3},1950,0);
        assertEquals(0.9325f,positions.get(2)[0],0.0001f);
    }
    @Test public void savedLayoutRestoresAllButtonsAndTheirHitAreas() throws Exception {
        LastStand2TouchControls view=controls(); view.beginEditing();
        float[][] targets={{170,300},{450,240},{680,240}};
        for(int i=0;i<3;i++) drag(view,i,targets[i][0],targets[i][1]);
        assertTrue(inputs.isEmpty()); view.finishEditing(true);
        LastStand2TouchControls restored=controls();
        for(int i=0;i<3;i++) assertArrayEquals(targets[i],center(restored,i),0.01f);
        touch(restored,MotionEvent.ACTION_DOWN,new int[]{5},targets[2]);
        touch(restored,MotionEvent.ACTION_UP,new int[]{5},targets[2]);
        assertEquals(Arrays.asList("swap:true","swap:false"),inputs);
    }
    @Test public void editCancelAndResetDoNotLeakInputOrOverwriteSavedLayout() throws Exception {
        LastStand2TouchControls view=controls();
        view.beginEditing(); drag(view,1,420,200); view.finishEditing(true);
        view.beginEditing(); view.resetLayout(); view.finishEditing(false);
        assertArrayEquals(new float[]{420,200},center(view,1),0.01f);
        view.beginEditing(); drag(view,1,700,300); view.finishEditing(false);
        assertArrayEquals(new float[]{420,200},center(view,1),0.01f);
        view.beginEditing(); view.resetLayout(); view.finishEditing(true);
        assertFalse(view.hasCustomLayout()); assertTrue(inputs.isEmpty());
    }
}
