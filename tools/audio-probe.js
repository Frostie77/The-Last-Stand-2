(() => {
  window.__tls2AudioCapture?.restore();
  if (typeof tls2Ready === 'undefined' || !tls2Ready || tls2Phase !== 'combat' ||
      tls2MenuPaused || tls2BackgroundPaused || document.hidden)
    throw Error('Leave the game open in a night fight and close FrostFrontend.');
  if (typeof AudioBufferSourceNode !== 'function') throw Error('Audio scheduling is unavailable.');

  const prototype = AudioBufferSourceNode.prototype;
  const descriptor = Object.getOwnPropertyDescriptor(prototype, 'start');
  const original = prototype.start;
  const lastEnd = new WeakMap();
  const started = performance.now();
  const timedSoundsAtStart = window.tls2TimedCombatSounds || 0;
  const weapon = () => document.querySelector('ruffle-player')?.tls2GameInputState?.()?.split('|')[0];
  const before = {phase:tls2Phase, weapon:weapon()};
  const stats = {buffers:0, bufferFrames:{}, sampledValues:0, aboveFullScale:0,
    peak:0, scheduledGaps:0, totalGapMs:0, maxGapMs:0};
  let lastContext;

  function start(when) {
    const result = original.apply(this, arguments);
    const context = this.context, buffer = this.buffer;
    // Ruffle schedules complete stereo chunks with a single start time.
    if (arguments.length !== 1 || !Number.isFinite(when) ||
        context.state !== 'running' || buffer?.numberOfChannels !== 2) return result;
    const playAt = Math.max(when, context.currentTime);
    const previous = lastEnd.get(context);
    if (previous !== undefined) {
      const gapMs = (playAt - previous) * 1000;
      if (gapMs > 3) { // Allow for one Web Audio render quantum.
        stats.scheduledGaps++;
        stats.totalGapMs += gapMs;
        stats.maxGapMs = Math.max(stats.maxGapMs, gapMs);
      }
    }
    lastEnd.set(context, playAt + buffer.duration);
    lastContext = context;
    stats.buffers++;
    stats.bufferFrames[buffer.length] = (stats.bufferFrames[buffer.length] || 0) + 1;
    for (let channel = 0; channel < 2; channel++) {
      const data = buffer.getChannelData(channel);
      stats.sampledValues += data.length;
      for (let i = 0; i < data.length; i++) {
        const peak = Math.abs(data[i]);
        if (peak > stats.peak) stats.peak = peak;
        if (peak > 1) stats.aboveFullScale++;
      }
    }
    return result;
  }

  const capture = {
    restore() {
      if (prototype.start === start) {
        if (descriptor) Object.defineProperty(prototype, 'start', descriptor);
        else delete prototype.start;
      }
      if (window.__tls2AudioCapture === capture) delete window.__tls2AudioCapture;
    },
    finish() {
      const round = n => Math.round(n * 1000) / 1000;
      const report = {...stats, peak:round(stats.peak),
        totalGapMs:round(stats.totalGapMs), maxGapMs:round(stats.maxGapMs),
        elapsedSeconds:round((performance.now() - started) / 1000),
        before, after:{phase:tls2Phase, weapon:weapon(), hidden:document.hidden},
        contextState:lastContext?.state ?? null,
        tailGapMs:lastContext ? round(Math.max(0, lastContext.currentTime - lastEnd.get(lastContext)) * 1000) : null,
        preparedCombatSounds:window.tls2PreparedCombatSounds || 0,
        timedCombatSounds:(window.tls2TimedCombatSounds || 0) - timedSoundsAtStart,
        combatPeakLimiter:window.tls2CombatPeakLimiter === true};
      capture.restore();
      return report;
    }
  };
  prototype.start = start;
  window.__tls2AudioCapture = capture;
  return before;
})()
