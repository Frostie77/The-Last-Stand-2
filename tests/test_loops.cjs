const fs=require('fs'),path=require('path'),assert=require('node:assert/strict'),{chromium}=require('playwright');
const root=path.resolve(__dirname,'..'),origin='https://appassets.androidplatform.net';
const temp=fs.mkdtempSync(path.join(require('os').tmpdir(),'tls2-loops-')),fixture=path.join(temp,'loops.swf');
require('child_process').execFileSync(process.env.TLS2_PYTHON||(process.platform==='win32'?'python':'python3'),[path.join(__dirname,'make_loop_fixture.py'),fixture],{env:{...process.env,PYTHONDONTWRITEBYTECODE:'1'}});
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
 try{
  await page.goto(origin+'/assets/www/index.html');
  await page.waitForFunction(()=>typeof document.querySelector('ruffle-player')?.lookupState==='function',null,{timeout:30000});
  for(const name of ['collisionTest','sortZombieDepths','sortDeadCats'])for(const values of [[0,0,1],[1,1,2],[8,5,3],[32,16,5]])for(const mutate of [0,1]){
   const result=await page.evaluate(({name,values,mutate})=>{
    const p=document.querySelector('ruffle-player'),out=[];
    for(const prefix of ['old_','fast_']){p.lookupReset(...values,mutate);p.lookupRun(prefix+name,1);out.push(p.lookupState());}
    return out;
   },{name,values,mutate});
   assert.equal(result[1],result[0],JSON.stringify({name,values,mutate,result}));
   if(values[0]===8&&!mutate)assert.equal(+result[0].split('|')[0],900,'The fixture must perform the list calculation');
  }
  console.log('PASS: rewritten loops match reference counters, list mutations, empty/crowded lists and shared branch targets.');
  if(process.env.TLS2_BENCH==='1')console.log(JSON.stringify(await page.evaluate(()=>{
   const p=document.querySelector('ruffle-player'),result={old:[],fast:[]};
   for(let i=0;i<6;i++)for(const mode of i%2?['fast','old']:['old','fast']){
    p.lookupReset(32,16,5,0);const start=performance.now();p.lookupRun(mode+'_collisionTest',80);result[mode].push(performance.now()-start);
   }return {mutableListLoopMilliseconds:result};
  })));
 }finally{await browser.close();fs.rmSync(temp,{recursive:true,force:true});}
})().catch(e=>{console.error(e);process.exit(1)});
