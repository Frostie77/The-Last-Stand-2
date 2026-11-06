package io.github.frostie77.laststand2;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import org.junit.Test;

public class SceneMarkerTest {
    @Test public void unrecognizedAndMalformedCopiesAreNeverRewritten() throws Exception {
        for (byte[] movie : new byte[][] { {}, {'C','W','S',8,1,2,3,4},
                {'F','W','S',8,0,0,0,0,1,2,3,4}, {'n','o','t','s','w','f'} }) {
            assertArrayEquals(movie,LastStand2SceneMarker.patch(new ByteArrayInputStream(movie)));
        }
    }
}
