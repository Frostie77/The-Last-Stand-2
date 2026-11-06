const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'..'),repo=root;
const runtime=process.env.TLS2_RUFFLE_PATH||path.join(root,'app/src/main/assets/www/ruffle');
const temporary=fs.mkdtempSync(path.join(require('os').tmpdir(),'tls2-input-'));
const fixture=path.join(temporary,'input.swf');
require('child_process').execFileSync(process.env.TLS2_PYTHON||(process.platform==='win32'?'python':'python3'),[path.join(__dirname,'make_input_fixture.py'),fixture]);
const origin='https://appassets.androidplatform.net';
const mode=process.env.TLS2_BASELINE_HTML?'old':'new';
const quality=process.env.TLS2_QUALITY||'low';
(async()=>{
 const browser=await chromium.launch({...(process.env.TLS2_CHROMIUM_PATH?{executablePath:process.env.TLS2_CHROMIUM_PATH}:{}),headless:true,args:['--no-sandbox','--disable-dev-shm-usage','--enable-unsafe-swiftshader','--use-angle=swiftshader','--autoplay-policy=no-user-gesture-required']});
 const page=await browser.newPage({viewport:{width:1000,height:525},deviceScaleFactor:1});
 await page.addInitScript(()=>{
  window.multisampleAllocations=[];
  const gl=WebGL2RenderingContext.prototype,original=gl.renderbufferStorageMultisample;
  gl.renderbufferStorageMultisample=function(target,samples,format,width,height){
   window.multisampleAllocations.push({samples,width,height});
   return original.call(this,target,samples,format,width,height);
  };
 });
 const logs=[];page.on('console',m=>{logs.push(m.text());if(logs.length>40)logs.shift()});page.on('pageerror',e=>logs.push('ERROR '+e.message));
 await page.route('**/*',async route=>{
  const u=new URL(route.request().url());let file,type;
  if(u.origin===origin&&u.pathname==='/assets/www/index.html'){file=mode==='old'?process.env.TLS2_BASELINE_HTML:path.join(repo,'app/src/main/assets/www/index.html');type='text/html';}
  else if(u.origin===origin&&u.pathname.startsWith('/assets/www/ruffle/')){file=path.join(runtime,path.basename(u.pathname));type=file.endsWith('.wasm')?'application/wasm':'application/javascript';}
  else if(u.origin===origin&&u.pathname==='/game/the-last-stand-2.swf'){file=fixture;type='application/x-shockwave-flash';}
  return route.fulfill({contentType:type||'text/plain',body:file?fs.readFileSync(file):''});
 });
 const probe=()=>page.evaluate(()=>document.querySelector('ruffle-player').tls2FixtureState().split('|').map(v=>v==='true'?true:v==='false'?false:+v));
 const key=(id,down,source)=>page.evaluate(p=>window.tls2Key(...p),[id,down,source]);
 try{
  await page.goto(origin+'/assets/www/index.html?quality='+quality);
  await page.waitForFunction(()=>typeof document.querySelector('ruffle-player')?.tls2FixtureState==='function',null,{timeout:30000});
  const rendering=await page.evaluate(()=>({quality:document.querySelector('ruffle-player').ruffle().loadedConfig.quality,allocations:window.multisampleAllocations}));
  if(mode==='new') {
   assert.equal(rendering.quality,quality);
   if(quality==='low') assert.ok(rendering.allocations.every(a=>a.samples<=1),'Off must not allocate multisample renderbuffers');
  }
  console.log(JSON.stringify({mode,rendering}));
  await page.evaluate(()=>{const p=document.querySelector('ruffle-player');p.focus();p.dispatchEvent(new FocusEvent('focusin',{bubbles:true,composed:true}));window.tls2Aim(.4,.5)});
  let before=await probe();await key('down',true,'stick');await page.waitForTimeout(160);let held=await probe();await key('down',false,'stick');
  await key('fire',true,'rightTrigger');await page.waitForTimeout(80);await key('fire',false,'rightTrigger');let first=await probe();
  console.log(JSON.stringify({mode,first:{before,held,after:first}}));assert.equal(held[2],true);assert.ok(held[0]>before[0]);assert.equal(first[1],before[1]+1);
  await page.evaluate(()=>{player.onFSCommand('tls2Phase','combat');player.onFSCommand('tls2Phase','menu');player.onFSCommand('tls2Phase','combat');});
  // Prime the cache in this encounter, then reproduce an Android focus/leave
  // transition without changing the still-focused host or replacing its canvas.
  await page.evaluate(()=>{window.tls2Aim(.4,.5);const p=document.querySelector('ruffle-player'),c=canvasForPointer();p.focus();p.dispatchEvent(new FocusEvent('focusout',{bubbles:true,composed:true,relatedTarget:null}));c.dispatchEvent(new PointerEvent('pointerleave',{pointerId:10,pointerType:'mouse'}));});
  before=await probe();await key('down',true,'stick');await page.waitForTimeout(160);held=await probe();await key('down',false,'stick');
  await page.evaluate(()=>window.tls2Aim(.4,.5));await key('fire',true,'rightTrigger');await page.waitForTimeout(80);await key('fire',false,'rightTrigger');let after=await probe();
  console.log(JSON.stringify({mode,afterTransition:{before,held,after}}));
  if(mode==='old'){assert.equal(held[2],false);assert.equal(held[0],before[0]);assert.equal(after[1],before[1]);console.log('PASS: original bridge reproduces dropped keyboard and fire inputs in actual Ruffle.');}
  else{assert.equal(held[2],true);assert.ok(held[0]>before[0]);assert.equal(after[1],before[1]+1);console.log('PASS: keyboard polling, frame movement and onPress firing recover in actual pinned Ruffle.');}
  if(process.env.TLS2_PROFILE==='1') {
   const cdp=await page.context().newCDPSession(page);
   await page.evaluate(fs.readFileSync(path.join(root,'tools/performance-probe.js'),'utf8'));
   await cdp.send('Performance.enable');await cdp.send('Profiler.enable');
   await cdp.send('Profiler.setSamplingInterval',{interval:2000});await cdp.send('Profiler.start');
   await key('down',true,'stick');await page.waitForTimeout(1200);await key('down',false,'stick');
   const {profile}=await cdp.send('Profiler.stop'),metrics=await cdp.send('Performance.getMetrics');
   const report=await page.evaluate(()=>window.__tls2PerformanceCapture.finish());
   assert.ok(profile.samples.length>0);assert.ok(metrics.metrics.some(m=>m.name==='TaskDuration'));
   assert.ok(report.pageFrameIntervals.count>0);assert.equal(report.key.calls,2);
   assert.equal(await page.evaluate(()=>!!window.__tls2PerformanceCapture),false);
   console.log(JSON.stringify({cpuSamples:profile.samples.length,wasmSymbols:profile.nodes.filter(n=>n.callFrame.url.startsWith('wasm:')).slice(0,4).map(n=>n.callFrame.functionName),page:report}));
   await cdp.send('Profiler.disable');await cdp.send('Performance.disable');await cdp.detach();
   console.log('PASS: actual Chromium CPU profiler, metrics and temporary probe with pinned Ruffle.');
  }
 }catch(e){console.error(JSON.stringify({mode,error:e.message,logs:logs.slice(-12)}));const dir=path.join(root,'.bootstrap');fs.mkdirSync(dir,{recursive:true});await page.screenshot({path:path.join(dir,'recovery-'+mode+'-failure.png')});throw e;}
 finally{await browser.close();fs.rmSync(temporary,{recursive:true,force:true})}
})().catch(e=>{console.error(e);process.exit(1)});
