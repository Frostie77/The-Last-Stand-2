"""Independent particle reference and production fast path; no game assets."""
from pathlib import Path
import struct
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
NAMES = ['_alpha', 'particleFade', 'removeMovieClip', '_rotation', 'rotation', 'x',
         'angle', 'Math', 'cos', 'HorzEnergy', '_x', '_y', 'VertEnergy', 'gravity',
         'partType', 'bloodParticle', 'this']
u16 = lambda n: struct.pack('<H', n)


def action(op, payload=b''):
    return bytes([op]) + (u16(len(payload)) + payload if op >= 128 else b'')


def push(value):
    if isinstance(value, str):
        payload = b'\x09' + u16(NAMES.index(value)) if value in NAMES else b'\0' + value.encode() + b'\0'
    elif isinstance(value, int):
        payload = b'\x07' + struct.pack('<i', value)
    else:
        raw = struct.pack('<d', value)
        payload = b'\x06' + raw[4:] + raw[:4]
    return action(0x96, payload)


def var(name): return push(name) + b'\x1c'
def member(obj, name): return obj + push(name) + b'\x4e'
def root(name): return member(var('_root'), name)


def call(obj, name, arguments):
    return b''.join(v if isinstance(v, bytes) else push(v) for v in reversed(arguments)) + push(len(arguments)) + obj + push(name) + b'\x52'


def function(name, body, params=()):
    header = name.encode() + b'\0' + u16(len(params)) + b''.join(p.encode() + b'\0' for p in params) + u16(len(body) + 1)
    return action(0x9b, header) + body + b'\0'


def register(name):
    external = member(member(var('flash'), 'external'), 'ExternalInterface')
    return var('__' + name) + var('_root') + push(name) + push(3) + external + push('addCallback') + b'\x52\x17'


def production_actions():
    # Compile the same Java emitter used when the APK serves the imported SWF.
    with tempfile.TemporaryDirectory(prefix='tls2-particle-code-') as directory:
        work = Path(directory)
        constants = ','.join('"' + name + '"' for name in NAMES)
        driver = '''package io.github.frostie77.laststand2;
import java.nio.file.*; import java.util.*;
public class ParticleFixtureCode {
 public static void main(String[] args) throws Exception {
  Map<String,Integer> pool=new LinkedHashMap<>();
  String[] names={CONSTANTS};
  for(int i=0;i<names.length;i++) pool.put(names[i],i);
  Files.write(Path.of(args[0]),LastStand2SceneMarker.particleFunction(pool));
  Files.write(Path.of(args[1]),LastStand2SceneMarker.followReticle());
  Files.write(Path.of(args[2]),LastStand2SceneMarker.combatPointer(true));
  Files.write(Path.of(args[3]),LastStand2SceneMarker.combatPointer(false));
  Files.write(Path.of(args[4]),LastStand2SceneMarker.stageReleaseOutside(true));
 }
}'''.replace('CONSTANTS', constants)
        source = work / 'ParticleFixtureCode.java'
        source.write_text(driver)
        subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-d', str(work), str(source),
                        str(ROOT / 'app/src/main/java/io/github/frostie77/laststand2/LastStand2SceneMarker.java'),
                        str(ROOT / 'app/src/main/java/io/github/frostie77/laststand2/LastStand2LoopOptimizer.java')], check=True)
        binary = work / 'particle.bin'
        follow = work / 'follow.bin'
        enter = work / 'enter.bin'
        leave = work / 'leave.bin'
        release = work / 'release.bin'
        subprocess.run(['java', '-cp', str(work), 'io.github.frostie77.laststand2.ParticleFixtureCode',
                        str(binary), str(follow), str(enter), str(leave), str(release)], check=True)
        data = binary.read_bytes()
        length = int.from_bytes(data[1:3], 'little')
        end = data.index(0, 3) + 1
        header = b'fastMove\0' + data[end:3 + length]
        return (action(0x8e, header) + data[3 + length:], follow.read_bytes(),
                enter.read_bytes(), leave.read_bytes(), release.read_bytes())


def pointer_fixture(follow, enter, leave, release):
    script = call(var('_root'), 'createEmptyMovieClip', ['aimer', 100]) + b'\x17'
    script += call(root('aimer'), 'startDrag', [1]) + b'\x17' + follow
    for clip, depth, x1, y1, x2, y2 in [('stageBG', 4, 0, 0, 800, 420), ('options', 5, 7, 395, 35, 415)]:
        script += call(var('_root'), 'createEmptyMovieClip', [clip, depth]) + b'\x17'
        script += call(root(clip), 'beginFill', [0, 100]) + b'\x17'
        script += call(root(clip), 'moveTo', [x1, y1]) + b'\x17'
        for x, y in [(x2, y1), (x2, y2), (x1, y2), (x1, y1)]:
            script += call(root(clip), 'lineTo', [x, y]) + b'\x17'
        script += call(root(clip), 'endFill', []) + b'\x17'
    for clip, event, count in [('stageBG', 'onPress', 'pressed'), ('stageBG', 'onRelease', 'released'),
                               ('options', 'onRelease', 'opened')]:
        script += push(count) + push(0) + b'\x1d'
        increment = push(count) + var(count) + b'\x50\x1d'
        script += root(clip) + push(event) + function('', increment) + b'\x4f'
    script += release + enter
    cursor = member(root('aimer'), '_x')
    for value in [member(root('aimer'), '_y'), root('_xmouse'), root('_ymouse')]:
        cursor += push('|') + b'\x21' + value + b'\x21'
    script += function('__reticleState', cursor + b'\x3e') + register('reticleState')
    freeze = root('aimer') + push('onEnterFrame') + b'\x3a\x17'
    script += function('__reticleFreeze', freeze) + register('reticleFreeze')
    state = var('pressed')
    for field in [var('released'), var('opened'), member(root('aimer'), 'onPress') + b'\x44',
                  member(root('stageBG'), 'onReleaseOutside') + b'\x44']:
        state += push('|') + b'\x21' + field + b'\x21'
    script += function('__pointerState', state + b'\x3e') + register('pointerState')
    script += function('__pointerMenu', leave) + register('pointerMenu')
    return script


def build(destination):
    # Reference equations deliberately retain dynamic with-scope lookups.
    fade = push('_alpha') + var('_alpha') + var('particleFade') + b'\x0b\x1d'
    remove = call(var('this'), 'removeMovieClip', []) + b'\x17'
    fade += var('_alpha') + push(8) + b'\x48\x12' + action(0x9d, struct.pack('<h', len(remove))) + remove
    rotation = var('this') + push('_rotation') + member(var('this'), '_rotation') + var('rotation') + b'\x47\x4f'
    horizontal = member(var('this'), 'angle') + push(3.141592653589793) + b'\x0c' + push(180) + b'\x0d'
    horizontal = var('this') + push('x') + call(var('Math'), 'cos', [horizontal]) + var('HorzEnergy') + b'\x0c\x4f'
    move_x = push('_x') + var('_x') + member(var('this'), 'x') + b'\x0b\x1d'
    # Store the new velocity before adding it to y, matching assignment order.
    move_y = push('VertEnergy') + var('VertEnergy') + var('gravity') + b'\x47\x1d'
    move_y += push('_y') + var('_y') + var('VertEnergy') + b'\x47\x1d'
    body = fade + rotation + horizontal + move_x + move_y
    reference = function('oldMove', var('this') + action(0x94, u16(len(body))) + body)
    particle, follow, enter, leave, release = production_actions()
    script = action(0x88, u16(len(NAMES)) + b''.join(n.encode() + b'\0' for n in NAMES)) + reference + particle
    script += pointer_fixture(follow, enter, leave, release)
    initialize = b''
    for name, depth, implementation in [('oldRef', 1, 'oldMove'), ('fastRef', 2, 'fastMove')]:
        initialize += push(name) + call(var('_root'), 'createEmptyMovieClip', [name + 'Display', depth]) + b'\x1d'
        initialize += root(name) + push('run') + var(implementation) + b'\x4f'
        values = [('_x', 200), ('_y', 100), ('_alpha', 100), ('_rotation', 7), ('particleFade', var('fade')),
                  ('angle', var('angle')), ('gravity', var('gravity')), ('rotation', var('rotation')),
                  ('HorzEnergy', var('horz')), ('VertEnergy', var('vert')), ('partType', var('kind'))]
        for key, value in values:
            initialize += root(name) + push(key) + (value if isinstance(value, bytes) else push(value)) + b'\x4f'
    script += function('__particleReset', initialize, ['fade', 'angle', 'gravity', 'rotation', 'horz', 'vert', 'kind']) + register('particleReset')

    def loop(receivers):
        start = push('i') + push(0) + b'\x3c'
        condition = var('i') + var('n') + b'\x48\x12'
        update = b''.join(call(root(name), 'run', []) + b'\x17' for name in receivers)
        update += push('i') + var('i') + b'\x50\x1d'
        back = action(0x99, struct.pack('<h', -(len(condition) + 5 + len(update) + 5)))
        return start + condition + action(0x9d, struct.pack('<h', len(update) + 5)) + update + back
    for name, receivers in [('particleStep', ['oldRef', 'fastRef']), ('particleBenchOld', ['oldRef']), ('particleBenchFast', ['fastRef'])]:
        script += function('__' + name, loop(receivers), ['n']) + register(name)
    state = b''
    for index, name in enumerate(['oldRef', 'fastRef']):
        if index: state += push('#') + b'\x21'
        for field_index, field in enumerate(['_x', '_y', '_alpha', '_rotation', 'x', 'VertEnergy']):
            if field_index: state += push('|') + b'\x21'
            state += member(root(name), field)
            if index or field_index: state += b'\x21'
        state += push('|') + b'\x21' + root(name + 'Display') + b'\x44\x21'
    script += function('__particleState', state + b'\x3e') + register('particleState')
    change = b''
    for name in ['oldRef', 'fastRef']:
        for field, argument in [('angle', 'newAngle'), ('HorzEnergy', 'newEnergy')]:
            change += root(name) + push(field) + var(argument) + b'\x4f'
    script += function('__particleChangeMotion', change, ['newAngle', 'newEnergy']) + register('particleChangeMotion') + b'\x07\0'
    bits = f'{15:05b}' + ''.join(f'{v:015b}' for v in [0, 16000, 0, 8400])
    bits += '0' * (-len(bits) % 8)
    rect = int(bits, 2).to_bytes(len(bits) // 8, 'big')
    tag = lambda t, payload: u16((t << 6) | 63) + struct.pack('<I', len(payload)) + payload
    movie = rect + u16(35 * 256) + u16(1) + tag(12, script) + u16(64) + u16(0)
    destination.write_bytes(b'FWS\x08' + struct.pack('<I', len(movie) + 8) + movie)


if __name__ == '__main__':
    build(Path(sys.argv[1]))
