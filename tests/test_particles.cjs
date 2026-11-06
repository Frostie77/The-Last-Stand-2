const fs=require('fs'),path=require('path'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'..'),origin='https://appassets.androidplatform.net';
const temp=fs.mkdtempSync(path.join(require('os').tmpdir(),'tls2-particles-'));
const fixture=path.join(temp,'particles.swf');
require('child_process').execFileSync(process.env.TLS2_PYTHON||(process.platform==='win32'?'python':'python3'),[path.join(__dirname,'make_particle_fixture.py'),fixture]);
const runtime=process.env.TLS2_RUFFLE_PATH||path.join(root,'app/src/main/assets/www/ruffle');
(async()=>{
 const browser=await chromium.launch({...(process.env.TLS2_CHROMIUM_PATH?{executablePath:process.env.TLS2_CHROMIUM_PATH}:{}),headless:true,args:['--no-sandbox','--disable-dev-shm-usage','--enable-unsafe-swiftshader','--use-angle=swiftshader','--autoplay-policy=no-user-gesture-required']});
 const page=await browser.newPage({viewport:{width:1000,height:525}});
 await page.route('**/*',route=>{
  const u=new URL(route.request().url());let file,type;
  if(u.origin===origin&&u.pathname==='/assets/www/index.html'){file=path.join(root,'app/src/main/assets/www/index.html');type='text/html';}
  else if(u.origin===origin&&u.pathname.startsWith('/assets/www/ruffle/')){file=path.join(runtime,path.basename(u.pathname));type=file.endsWith('.wasm')?'application/wasm':'application/javascript';}
  else if(u.origin===origin&&u.pathname==='/game/the-last-stand-2.swf'){file=fixture;type='application/x-shockwave-flash';}
  return route.fulfill({contentType:type||'text/plain',body:file?fs.readFileSync(file):''});
 });
 const step=()=>page.evaluate(()=>{const p=document.querySelector('ruffle-player');p.particleStep(1);return p.particleState();});
 try{
  await page.goto(origin+'/assets/www/index.html');
  await page.waitForFunction(()=>typeof document.querySelector('ruffle-player')?.particleState==='function',null,{timeout:30000});
  let updates=0;
  for(const kind of ['bloodParticle','flameParticle']) for(const values of [[8,180,.35,4,9,-5],[9,0,1.5,-7,-4,2],[4,45,-.4,30,6,0],[100,360,0,0,0,0],[0,-90,.3,400,10,-3],[0,180,.35,4,0,-5]]){
   await page.evaluate(v=>document.querySelector('ruffle-player').particleReset(...v),[...values,kind]);
   for(let i=0;i<24;i++){
    const state=await step(),[old,fast]=state.split('#');
    assert.equal(fast,old,JSON.stringify({kind,values,i,state}));
    if(i===0&&values[0]===8) assert.equal(+old.split('|')[0],209,'Both implementations must move the particle');
    if(i===0&&values[0]===100) assert.equal(old.split('|').at(-1),'undefined','Faded particles must be removed');
    updates++;
   }
  }
  await page.evaluate(()=>{const p=document.querySelector('ruffle-player');p.particleReset(0,180,.35,4,9,-5,'flameParticle');p.particleStep(2);p.particleChangeMotion(30,5);});
  const state=await step(),[old,fast]=state.split('#');assert.equal(fast,old,'Other particle types must recalculate changed motion');
  console.log('PASS: '+updates+' paired particle updates match the independent reference, including removal and other effect types.');
  if(process.env.TLS2_BENCH==='1'){
   const timings=await page.evaluate(()=>{
    const p=document.querySelector('ruffle-player'),result={old:[],fast:[]};p.particleReset(0,180,.35,4,9,-5,'bloodParticle');
    p.particleBenchOld(1000);p.particleBenchFast(1000);
    for(let i=0;i<6;i++)for(const mode of i%2?['fast','old']:['old','fast']){
     const start=performance.now();p[mode==='old'?'particleBenchOld':'particleBenchFast'](5000);result[mode].push(performance.now()-start);
    }return result;
   });console.log(JSON.stringify({particleUpdateMilliseconds:timings}));
  }
 }finally{await browser.close();fs.rmSync(temp,{recursive:true,force:true});}
})().catch(e=>{console.error(e);process.exit(1)});
