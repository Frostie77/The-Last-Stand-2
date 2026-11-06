const fs=require('fs'),vm=require('vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('capture-controller-input.ps1','utf8');
const script=source.match(/\$tls2InstallProbe = @'\r?\n([\s\S]*?)\r?\n'@/)[1];
let tick,cleared=0,now=0,gunFrame=910,waiting=12,keys=0,aims=0;
const hooks=[];
const p={tls2GameInputState:()=>`sawnOff|1|true|true|false|false|false|7|${gunFrame}|10|600|200|function|function|false`,
 addEventListener:(type,f)=>hooks.push([type,f]),removeEventListener:(type,f)=>{const i=hooks.findIndex(x=>x[0]===type&&x[1]===f);hooks.splice(i,1)}};
const context={tls2Ready:true,tls2Phase:'combat',tls2MenuPaused:false,tls2BackgroundPaused:false,sources:{},
 performance:{now:()=>now},canvasForPointer:()=>p,
 document:{querySelector:()=>p,hasFocus:()=>true,activeElement:{tagName:'RUFFLE-PLAYER'}},
 LastStand2Host:{inputState:()=>JSON.stringify({aimInFlight:true,aimPending:true,aimWaitingMs:waiting})},
 tls2Key:()=>keys++,tls2Aim:()=>aims++,setInterval:f=>{tick=f;return 7},clearInterval:id=>{assert.equal(id,7);cleared++}};
context.window=context;vm.createContext(context);const oldKey=context.tls2Key,oldAim=context.tls2Aim;
vm.runInContext(script,context);
assert.equal(context.__tls2InputCapture.before.flash.weapon,'sawnOff');
assert.equal(context.__tls2InputCapture.before.flash.reloading,true);
context.tls2Key('reload',true,'button99');context.tls2Aim(.6,.4);
now=500;gunFrame=940;waiting=500;tick();
const report=context.__tls2InputCapture.report();assert.equal(report.stateSamples[0].flash.gunFrame,940);
assert.equal(report.stateSamples[0].native.aimWaitingMs,500);assert.equal(report.keyCalls[0][1],'reload');
context.__tls2InputCapture.restore();assert.equal(context.tls2Key,oldKey);assert.equal(context.tls2Aim,oldAim);
assert.equal(cleared,1);assert.equal(hooks.length,0);assert.equal(keys,1);assert.equal(aims,1);
delete p.tls2GameInputState;delete context.LastStand2Host;
vm.runInContext(script,context);assert.equal(context.__tls2InputCapture.before.flash.available,false);
assert.equal(context.__tls2InputCapture.before.native,null);context.__tls2InputCapture.restore();
console.log('PASS: reload/Flash/native state, timed samples, input delivery, older builds and complete cleanup.');
