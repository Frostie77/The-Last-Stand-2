(() => {
  // DefineSound IDs for weapon fire, impacts, and the working chainsaw
  // in the verified Not Doppler v1.31 movie.
  const combatSounds = new Set([
    1127, 1223, 1227, 1228, 1231, 1232, 1236, 1247, 1248, 1249, 1252,
    1255, 1266, 1267, 1269, 1270, 1271, 1272, 1273, 1274, 1275
  ]);
  const defineSoundTag = 14;

  function installPeakLimiter() {
    if (window.tls2CombatPeakLimiter || typeof AudioBufferSourceNode !== 'function') return;
    const prototype = AudioBufferSourceNode.prototype;
    const start = prototype.start;
    // The pinned mixer schedules complete stereo buffers with a start time.
    // Limit their summed peaks before Web Audio acquires the buffer data.
    prototype.start = function(when) {
      const buffer = this.buffer;
      if (arguments.length === 1 && Number.isFinite(when) && buffer?.numberOfChannels === 2) {
        const left = buffer.getChannelData(0), right = buffer.getChannelData(1);
        const knee = 0.8, room = 0.14;
        for (let i = 0; i < left.length; i++) {
          const peak = Math.max(Math.abs(left[i]), Math.abs(right[i]));
          const excess = peak - knee;
          if (excess > 0) {
            const gain = (knee + room * excess / (room + excess)) / peak;
            left[i] *= gain;
            right[i] *= gain;
          }
        }
      }
      return start.apply(this, arguments);
    };
    window.tls2CombatPeakLimiter = true;
  }

  async function decodeSound(sound, contexts) {
    const flags = sound.getUint8(2);
    const rateCode = (flags >> 2) & 3;
    const sampleRate = rateCode === 3 ? 44100 : rateCode === 2 ? 22050 : 0;
    const channels = 1 + (flags & 1);
    const samples = sound.getUint32(3, true);
    const skip = sound.getInt16(7, true);
    if (flags >> 4 !== 2 || !sampleRate || !samples || samples > 10 * sampleRate || skip < 0)
      return null;

    let decoded;
    try {
      const context = contexts[sampleRate] ??= new OfflineAudioContext(1, 1, sampleRate);
      const start = sound.byteOffset + 9; // DefineSound header and MP3 seek samples.
      decoded = await context.decodeAudioData(sound.buffer.slice(start, sound.byteOffset + sound.byteLength));
    } catch (_) {
      return null; // Keep the original MP3 if WebView cannot decode this clip.
    }
    if (decoded.numberOfChannels !== channels || decoded.sampleRate !== sampleRate ||
        decoded.length < skip + samples) return null;

    // Preserve the SWF sound ID, sample rate, channels and duration. Format 3
    // is signed little-endian PCM. Flash's encoder delay is removed here.
    const pcm = new Uint8Array(6 + 7 + samples * channels * 2);
    const output = new DataView(pcm.buffer);
    output.setUint16(0, (defineSoundTag << 6) | 63, true);
    output.setUint32(2, pcm.length - 6, true);
    output.setUint16(6, sound.getUint16(0, true), true);
    output.setUint8(8, 0x30 | (flags & 0x0d) | 2);
    output.setUint32(9, samples, true);
    for (let ch = 0; ch < channels; ch++) {
      const input = decoded.getChannelData(ch);
      for (let i = 0, at = 13 + ch * 2; i < samples; i++, at += channels * 2) {
        const value = Math.max(-32768, Math.min(32767, Math.round(input[skip + i] * 32768)));
        output.setInt16(at, value, true);
      }
    }
    return pcm;
  }

  async function prepareMovie(original) {
    const header = new Uint8Array(original, 0, 8);
    if (header[0] !== 67 || header[1] !== 87 || header[2] !== 83) return original;
    const compressed = new Blob([new Uint8Array(original, 8)]);
    const body = new Uint8Array(await new Response(
      compressed.stream().pipeThrough(new DecompressionStream('deflate'))).arrayBuffer());
    if (body.length + 8 !== new DataView(original).getUint32(4, true)) return original;

    const movie = new DataView(body.buffer), contexts = {};
    const pieces = [header];
    let at = Math.ceil((5 + 4 * (body[0] >> 3)) / 8) + 4;
    let previous = 0, count = 0;
    while (at + 2 <= body.length) {
      const start = at;
      const tag = movie.getUint16(at, true);
      const code = tag >> 6;
      let size = tag & 63;
      at += 2;
      if (size === 63) {
        size = movie.getUint32(at, true);
        at += 4;
      }
      const end = at + size;
      if (end > body.length) return original;
      if (code === defineSoundTag && size > 9 && combatSounds.has(movie.getUint16(at, true))) {
        const pcm = await decodeSound(new DataView(body.buffer, at, size), contexts);
        if (pcm) {
          pieces.push(body.subarray(previous, start), pcm);
          previous = end;
          count++;
        }
      }
      if (code === 0) break;
      at = end;
    }
    if (!count) return original;
    pieces.push(body.subarray(previous));
    const result = new Uint8Array(pieces.reduce((size, piece) => size + piece.length, 0));
    let position = 0;
    for (const piece of pieces) {
      result.set(piece, position);
      position += piece.length;
    }
    result[0] = 70; // FWS, with an uncompressed body.
    new DataView(result.buffer).setUint32(4, result.length, true);
    window.tls2PreparedCombatSounds = count;
    return result;
  }

  window.tls2PrepareCombatAudio = path => {
    if (typeof OfflineAudioContext !== 'function' || typeof DecompressionStream !== 'function') return;
    const gameUrl = new URL(path, location.href).href;
    const originalFetch = window.fetch;
    // Pinned Ruffle streams the movie using window.fetch. Keep its original
    // URL so the SWF's origin, relative paths and SharedObject keys survive.
    window.fetch = async function(input, options) {
      const url = input instanceof Request ? input.url : new URL(input, location.href).href;
      if (url === gameUrl) window.fetch = originalFetch;
      const response = await originalFetch.call(this, input, options);
      if (url !== gameUrl || !response.ok || response.headers.get('X-Tls2-Combat-Audio') !== '1')
        return response;

      const original = await response.arrayBuffer();
      let prepared = original;
      try {
        prepared = await prepareMovie(original);
        if (prepared !== original) installPeakLimiter();
      }
      catch (error) { console.warn('Combat audio preparation failed', error); }
      const headers = new Headers(response.headers);
      headers.delete('Content-Encoding');
      headers.set('Content-Length', prepared.byteLength);
      const movie = new Response(prepared, {headers});
      Object.defineProperty(movie, 'url', {value:response.url || gameUrl});
      return movie;
    };
  };
})();
