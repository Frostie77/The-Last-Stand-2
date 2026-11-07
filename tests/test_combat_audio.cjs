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
const body = Buffer.concat([prefix, sound(1267), sound(1212), sound(1271), end]);
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
  const context = {
    console:{warn(){}}, location:{href:'https://appassets.androidplatform.net/assets/www/index.html'},
    Request, Response, Headers, URL, Blob, DecompressionStream, fetch,
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
  return {result, originalResponse, decodes, prepared:context.tls2PreparedCombatSounds};
}

(async () => {
  const unknown = await run({marked:false});
  assert.equal(unknown.result, unknown.originalResponse);
  assert.equal(unknown.decodes, 0);
  const malformed = await run({marked:true, bytes:original.subarray(0, 12)});
  assert.deepEqual(Buffer.from(await malformed.result.arrayBuffer()), original.subarray(0, 12));
  const {result, decodes, prepared} = await run({marked:true});
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
  assert.deepEqual(movie.subarray(at + 21), Buffer.concat([sound(1212), sound(1271), end]));
  console.log('PASS: one-shot SWF fetch, PCM layout and delay, original URL and unsupported-clip fallback.');
})().catch(error => { console.error(error); process.exitCode = 1; });
