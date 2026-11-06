// Temporary instrumentation installed by the desktop capture helper.
(() => {
  window.__tls2PerformanceCapture?.restore();
  if (typeof tls2Ready === 'undefined' || !tls2Ready) throw Error('Wait until the imported game is loaded.');
  if (tls2MenuPaused || tls2BackgroundPaused || document.hidden) throw Error('Close FrostFrontend and leave the game playing.');
  const p = document.querySelector('ruffle-player'), canvas = canvasForPointer();
  let renderer = null;
  try {
    const gl = canvas?.getContext('webgl2') || canvas?.getContext('webgl');
    if (gl) {
      const info = gl.getExtension('WEBGL_debug_renderer_info');
      renderer = info ? gl.getParameter(info.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER);
    }
  } catch (_) {}
  const round = n => Math.round(n * 100) / 100;
  const state = () => ({phase:tls2Phase, paused:[tls2MenuPaused,tls2BackgroundPaused], hidden:document.hidden});
  const d = {started:performance.now(), before:state(), intervals:[], raf:0, lastFrame:0,
    stopped:false, longTasks:{count:0,totalMs:0,maxMs:0},
    aim:{calls:0,totalMs:0,maxMs:0}, key:{calls:0,totalMs:0,maxMs:0},
    oldAim:window.tls2Aim, oldKey:window.tls2Key};
  const measure = (original, stats) => function() {
    const start = performance.now();
    try { return original.apply(this,arguments); }
    finally {
      const duration = performance.now()-start;
      stats.calls++; stats.totalMs+=duration; stats.maxMs=Math.max(stats.maxMs,duration);
    }
  };
  d.aimWrapper = measure(d.oldAim,d.aim); d.keyWrapper = measure(d.oldKey,d.key);
  const frame = timestamp => {
    if (d.stopped) return;
    if (d.lastFrame && d.intervals.length < 8192) d.intervals.push(timestamp-d.lastFrame);
    d.lastFrame=timestamp; d.raf=requestAnimationFrame(frame);
  };
  if (typeof PerformanceObserver === 'function' && PerformanceObserver.supportedEntryTypes?.includes('longtask')) {
    d.observer = new PerformanceObserver(list => {
      for (const entry of list.getEntries()) {
        if (entry.startTime < d.started) continue;
        d.longTasks.count++; d.longTasks.totalMs+=entry.duration;
        d.longTasks.maxMs=Math.max(d.longTasks.maxMs,entry.duration);
      }
    });
    d.observer.observe({entryTypes:['longtask']});
  }
  d.restore = () => {
    d.stopped=true; cancelAnimationFrame(d.raf); d.observer?.disconnect();
    if (window.tls2Aim === d.aimWrapper) window.tls2Aim=d.oldAim;
    if (window.tls2Key === d.keyWrapper) window.tls2Key=d.oldKey;
    if (window.__tls2PerformanceCapture === d) delete window.__tls2PerformanceCapture;
  };
  d.finish = () => {
    const sorted=d.intervals.slice().sort((a,b)=>a-b), count=sorted.length;
    const timings = stats => ({calls:stats.calls,totalMs:round(stats.totalMs),maxMs:round(stats.maxMs)});
    const result = {before:d.before,after:state(),observedSeconds:round((performance.now()-d.started)/1000),
      canvas:canvas?[canvas.width,canvas.height]:null, viewport:[innerWidth,innerHeight,devicePixelRatio],
      configuredQuality:p?.ruffle().loadedConfig?.quality, swfFrameRate:p?.ruffle().metadata?.frameRate, renderer,
      pageFrameIntervals:{count,medianMs:count?round(sorted[Math.floor(count/2)]):null,
        p95Ms:count?round(sorted[Math.min(count-1,Math.floor(count*.95))]):null,
        maxMs:count?round(sorted[count-1]):null,over50ms:sorted.filter(n=>n>50).length,over100ms:sorted.filter(n=>n>100).length},
      longTasks:{supported:!!d.observer,...d.longTasks,totalMs:round(d.longTasks.totalMs),maxMs:round(d.longTasks.maxMs)},
      aim:timings(d.aim),key:timings(d.key),note:'Page frame intervals are not Flash simulation FPS.'};
    d.restore(); return result;
  };
  window.__tls2PerformanceCapture=d; window.tls2Aim=d.aimWrapper; window.tls2Key=d.keyWrapper;
  d.raf=requestAnimationFrame(frame);
  return d.before;
})()
