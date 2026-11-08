const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const zlib = require('node:zlib');

const source = fs.readFileSync('app/src/main/assets/www/combat-audio.js', 'utf8');
const url = 'https://appassets.androidplatform.net/game/the-last-stand-2.swf';
const prefix = Buffer.from([8, 0, 0, 35, 1, 0]);
const end = Buffer.alloc(2);
function sound(id) {
  const tag = Buffer.alloc(12);
  tag.writeUInt16LE((14 << 6) | 10, 0);
  tag.writeUInt16LE(id, 2);
  tag[4] = 0x2b; // MP3, 22.05 kHz, 16 bit, stereo.
  tag.writeUInt32LE(2, 5);
  tag.writeInt16LE(1, 9);
  tag[11] = 0xff;
  return tag;
}
const linkages = Buffer.from('sound_pistolFire\0\xf7\x04sound_hitBody1\0', 'latin1');
const exportTag = Buffer.alloc(6 + linkages.length);
exportTag.writeUInt16LE((56 << 6) | (exportTag.length - 2), 0);
exportTag.writeUInt16LE(2, 2);
exportTag.writeUInt16LE(1267, 4);
linkages.copy(exportTag, 6);
const body = Buffer.concat([prefix, sound(1267), sound(1212), sound(1271), exportTag, end]);
const header = Buffer.from([67, 87, 83, 8, 0, 0, 0, 0]);
header.writeUInt32LE(body.length + 8, 4);
const original = Buffer.concat([header, zlib.deflateSync(body)]);

async function run({marked, bytes = original}) {
  let decodes = 0, fetches = 0;
  const originalResponse = new Response(bytes, {
    headers: marked ? {'X-Tls2-Combat-Audio': '1'} : {}
  });
  Object.defineProperty(originalResponse, 'url', {value:url});
  const fetch = async () => { fetches++; return originalResponse; };
  const starts = [], connections = [];
  class AudioSource {
    constructor(context) { this.context = context; }
    connect(target) { connections.push(target); return target; }
    start(...args) { starts.push(args); return 'started'; }
  }
  const context = {
    console:{warn(){}}, location:{href:'https://appassets.androidplatform.net/assets/www/index.html'},
    Request, Response, Headers, URL, Blob, DecompressionStream, TextDecoder, fetch, AudioBufferSourceNode:AudioSource,
    OfflineAudioContext: class {
      constructor(_, __, rate) { assert.equal(rate, 22050); }
      async decodeAudioData(encoded) {
        assert.equal(encoded.byteLength, 1);
        if (++decodes === 2) throw Error('Codec rejected the second selected clip.');
        const channels = [Float32Array.of(1, 0.5, -1), Float32Array.of(-1, -0.5, 1)];
        return {sampleRate:22050, length:3, numberOfChannels:2, getChannelData:ch=>channels[ch]};
      }
    }
  };
  context.window = context;
  vm.runInNewContext(source, context);
  context.tls2PrepareCombatAudio('/game/the-last-stand-2.swf');
  assert.equal(await context.fetch('/assets/runtime.js'), originalResponse);
  const result = await context.fetch(new Request(url));
  assert.equal(fetches, 2);
  assert.equal(context.fetch, fetch);
  assert.equal(result.url, url);
  return {result, originalResponse, decodes, prepared:context.tls2PreparedCombatSounds,
    limiter:context.tls2CombatPeakLimiter, AudioSource, starts, connections, context};
}

(async () => {
  const unknown = await run({marked:false});
  assert.equal(unknown.result, unknown.originalResponse);
  assert.equal(unknown.decodes, 0);
  assert.equal(unknown.limiter, undefined);
  const malformed = await run({marked:true, bytes:original.subarray(0, 12)});
  assert.deepEqual(Buffer.from(await malformed.result.arrayBuffer()), original.subarray(0, 12));
  const {result, decodes, prepared, limiter, AudioSource, starts, connections, context} = await run({marked:true});
  const movie = Buffer.from(await result.arrayBuffer());
  assert.equal(prepared, 1);
  assert.equal(decodes, 2);
  assert.equal(movie.toString('ascii', 0, 3), 'FWS');
  assert.equal(movie.readUInt32LE(4), movie.length);
  assert.equal(Number(result.headers.get('Content-Length')), movie.length);
  const at = 8 + prefix.length;
  assert.deepEqual(movie.subarray(8, at), prefix);
  assert.equal(movie.readUInt16LE(at), (14 << 6) | 63);
  assert.equal(movie.readUInt32LE(at + 2), 15);
  assert.equal(movie.readUInt16LE(at + 6), 1267);
  assert.equal(movie[at + 8], 0x3b);
  assert.equal(movie.readUInt32LE(at + 9), 2);
  assert.deepEqual([0,1,2,3].map(i=>movie.readInt16LE(at + 13 + 2*i)), [16384,-16384,-32768,32767]);
  assert.deepEqual(movie.subarray(at + 21), Buffer.concat([sound(1212), sound(1271), exportTag, end]));
  assert.equal(limiter, true);
  const createNode = () => ({gain:{}, connect(target) { this.output = target; }});
  const bus = createNode(), shaper = createNode(), effectGain = createNode();
  const mixer = {
    state:'running', destination:{}, currentTime:12,
    createGain:() => bus.output ? effectGain : bus,
    createWaveShaper:() => shaper,
    createBufferSource:() => new AudioSource(mixer)
  };
  assert.equal(context.tls2PlayCombatSound('sound_pistolFire', 100), false);
  const source = new AudioSource(mixer);
  const channels = [Float32Array.of(0, .5, 1.3, 4), Float32Array.of(0, -.5, -.65, -4)];
  source.buffer = {numberOfChannels:2, getChannelData:index=>channels[index]};
  assert.equal(source.start(12), 'started');
  assert.deepEqual(starts, [[12]]);
  assert.equal(channels[0][1], .5);
  assert.equal(channels[1][1], -.5);
  assert.ok(channels[0][2] > .8 && channels[0][2] < channels[0][3] && channels[0][3] < .94);
  assert.equal(channels[1][2], -channels[0][2] / 2);
  assert.equal(channels[1][3], -channels[0][3]);
  assert.equal(source.connect(mixer.destination), mixer.destination);
  assert.equal(connections[0], bus);
  assert.equal(bus.output, shaper);
  assert.equal(shaper.output, mixer.destination);
  assert.equal(bus.gain.value, 0.25);
  assert.equal(shaper.curve[4096 + 512], 0.5);
  assert.ok(shaper.curve.at(-1) > 0.94 && shaper.curve.at(-1) < 0.98);
  assert.equal(context.tls2PlayCombatSound('sound_pistolFire', 100), true);
  mixer.currentTime += 0.0625;
  assert.equal(context.tls2PlayCombatSound('sound_PISTOLFire', 40), true);
  assert.deepEqual(starts, [[12], [12, 1/22050, 2/22050], [12.0625, 1/22050, 2/22050]]);
  assert.deepEqual(connections, [bus, bus, effectGain]);
  assert.equal(effectGain.gain.value, 0.4);
  assert.equal(effectGain.output, bus);
  assert.equal(context.tls2PlayCombatSound('sound_hitBody1', 100), false);
  mixer.state = 'suspended';
  assert.equal(context.tls2PlayCombatSound('sound_pistolFire', 100), false);
  assert.equal(context.tls2TimedCombatSounds, 2);
  console.log('PASS: SWF PCM preparation, direct sound timing and volume, peak ceiling and fallback.');
})().catch(error => { console.error(error); process.exitCode = 1; });
