const fs=require('fs'),vm=require('vm'),assert=require('node:assert/strict');
const script=fs.readFileSync('tools/performance-probe.js','utf8');
let now=100,aimCalls=0,keyCalls=0,disconnected=0,observer,serial=0;
const frames=new Map();
class Observer {
 static supportedEntryTypes=['longtask'];
 constructor(callback){observer=callback;}
 observe(){}
 disconnect(){disconnected++;}
}
const oldAim=function(x,y){assert.equal(this,context);aimCalls++;now+=3;return x+y;};
const oldKey=function(id,down,source){keyCalls++;now+=2;if(id==='bad')throw Error('fixture');return source;};
const gl={getExtension:()=>null,getParameter:()=> 'Adreno fixture',RENDERER:123};
const canvas={width:800,height:420,getContext:()=>gl};
const context={tls2Ready:true,tls2MenuPaused:false,tls2BackgroundPaused:false,tls2Phase:'combat',
 tls2Aim:oldAim,tls2Key:oldKey,canvasForPointer:()=>canvas,innerWidth:1920,innerHeight:1080,devicePixelRatio:2,
 performance:{now:()=>now},PerformanceObserver:Observer,
 requestAnimationFrame:fn=>{frames.set(++serial,fn);return serial;},cancelAnimationFrame:id=>frames.delete(id),
 document:{hidden:false,querySelector:()=>({ruffle:()=>({loadedConfig:{quality:'low'},metadata:{frameRate:30}})})}};
context.window=context;vm.createContext(context);
vm.runInContext(script,context);
assert.equal(context.tls2Aim.call(context,.2,.3),.5);assert.equal(context.tls2Key('fire',true,'trigger'),'trigger');
assert.throws(()=>context.tls2Key('bad',false,'button'),/fixture/);
const tick=t=>{const [id,fn]=frames.entries().next().value;frames.delete(id);now=t;fn(t);};
tick(120);tick(136);tick(152);tick(312);
observer({getEntries:()=>[{startTime:0,duration:999},{startTime:153,duration:160}]});
const result=context.__tls2PerformanceCapture.finish();
assert.equal(result.aim.calls,1);assert.equal(result.aim.totalMs,3);assert.equal(result.key.calls,2);
assert.equal(result.pageFrameIntervals.maxMs,160);assert.equal(result.pageFrameIntervals.over100ms,1);
assert.equal(result.longTasks.count,1);assert.equal(result.longTasks.totalMs,160);
assert.equal(result.renderer,'Adreno fixture');assert.equal(result.swfFrameRate,30);
assert.equal(context.tls2Aim,oldAim);assert.equal(context.tls2Key,oldKey);
assert.equal(frames.size,0);assert.equal(disconnected,1);assert.equal(aimCalls,1);assert.equal(keyCalls,2);
assert.equal(context.__tls2PerformanceCapture,undefined);
context.tls2MenuPaused=true;assert.throws(()=>vm.runInContext(script,context),/Close FrostFrontend/);
context.tls2MenuPaused=false;vm.runInContext(script,context);
const newer=()=>{};context.tls2Aim=newer;context.__tls2PerformanceCapture.restore();
assert.equal(context.tls2Aim,newer);assert.equal(frames.size,0);
console.log('PASS: performance timing, original input delivery, exceptions, pause guard and complete restoration.');
