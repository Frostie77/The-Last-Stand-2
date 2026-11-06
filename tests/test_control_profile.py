#!/usr/bin/env python3
"""Load the real Activity control initializer with the pinned frontend validator."""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/io/github/frostie77/laststand2/MainActivity.java'
FRONTEND = ROOT / 'third_party/FrostFrontend/frostfrontend/src/main/java/io/github/frostfrontend'
PREFERENCES = '''package android.content;
public interface SharedPreferences {
 boolean contains(String key);
 int getInt(String key, int fallback);
 Editor edit();
 interface Editor { Editor putInt(String key, int value); void apply(); }
}'''
KEY_EVENT = '''package android.view;
public final class KeyEvent {
 public static final int KEYCODE_BUTTON_A=96, KEYCODE_BUTTON_B=97,
  KEYCODE_BUTTON_X=99, KEYCODE_BUTTON_Y=100,
  KEYCODE_BUTTON_L1=102, KEYCODE_BUTTON_R1=103,
  KEYCODE_BUTTON_L2=104, KEYCODE_BUTTON_R2=105,
  KEYCODE_BUTTON_THUMBL=106, KEYCODE_BUTTON_THUMBR=107,
  KEYCODE_BUTTON_START=108, KEYCODE_BUTTON_SELECT=109;
}'''
DRIVER = '''import android.content.SharedPreferences;
import android.view.KeyEvent;
import io.github.frostfrontend.FrostControlProfile;
import io.github.frostfrontend.FrostButtonMapper;

public final class ControlProfileStartupTest {
 private static final FrostControlProfile CONTROL_PROFILE = __INITIALIZER__;
 private static SharedPreferences preferences(boolean stalePause) {
  return new SharedPreferences() {
   public boolean contains(String key) {
    return stalePause && key.equals(CONTROL_PROFILE.preferencePrefix() + "pause");
   }
   public int getInt(String key, int fallback) { return KeyEvent.KEYCODE_BUTTON_SELECT; }
   public Editor edit() { throw new UnsupportedOperationException("Read-only fixture"); }
  };
 }
 public static void main(String[] args) {
  FrostButtonMapper fresh = CONTROL_PROFILE.load(preferences(false));
  if (!fresh.unmappedActions().isEmpty()) throw new AssertionError("Missing default actions");
  for (FrostButtonMapper.Action action : fresh.actions()) {
   if (!FrostButtonMapper.isAssignableButton(fresh.buttonFor(action.id)))
    throw new AssertionError("Invalid startup binding: " + action.id);
  }
  FrostButtonMapper restored = CONTROL_PROFILE.load(preferences(true));
  if (restored.buttonFor("pause") != fresh.buttonFor("pause"))
   throw new AssertionError("Unsupported saved Pause binding must retain a valid default");
  System.out.println("PASS: Activity control profile initializes and loads fresh/stale preferences.");
 }
}'''


def main():
    source = APP.read_text(encoding='utf-8')
    match = re.search(r'private static final FrostControlProfile CONTROL_PROFILE\s*=\s*(.*?);', source, re.S)
    if not match:
        raise RuntimeError('Activity control profile declaration was not found')
    java = shutil.which('java')
    if not java:
        raise RuntimeError('JDK 17 java is required')
    with tempfile.TemporaryDirectory(prefix='tls2-profile-test-') as temporary:
        work = Path(temporary)
        sources = {
            'android/content/SharedPreferences.java': PREFERENCES,
            'android/view/KeyEvent.java': KEY_EVENT,
            'ControlProfileStartupTest.java': DRIVER.replace('__INITIALIZER__', match.group(1)),
        }
        for name in ('FrostButtonMapper.java', 'FrostControlProfile.java'):
            sources['io/github/frostfrontend/' + name] = (FRONTEND / name).read_text(encoding='utf-8')
        for relative, text in sources.items():
            file = work / relative
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(text, encoding='utf-8')
        classes = work / 'classes'
        subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-encoding', 'UTF-8',
                        '-d', str(classes), *map(str, work.rglob('*.java'))], check=True)
        subprocess.run([java, '-cp', str(classes), 'ControlProfileStartupTest'], check=True)


if __name__ == '__main__':
    main()
