// Exercise the production HTML bridge, including actual canvas target/offsets.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync('app/src/main/assets/www/index.html','utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const events = [], hooks = {};
const canvasHooks = {};
let ruffleHasFocus = true;
let suspended = 0, resumed = 0, loadedConfig;
const phases = [];
const rect = {left:100,top:40,right:900,bottom:460,width:800,height:420};
const canvas = {offsetWidth:800,offsetHeight:420,getBoundingClientRect:()=>rect,
  addEventListener:(type,listener)=>canvasHooks[type]=listener,
  removeEventListener:type=>delete canvasHooks[type],
  dispatchEvent:e=>{events.push(e);if(e.type==='pointerdown'||ruffleHasFocus)e.preventDefault();}};
let canvases=[canvas];
const player = {shadowRoot:{querySelectorAll:()=>canvases},
  focus(){if(context.document.activeElement!==player)ruffleHasFocus=true;context.document.activeElement=player;},
  blur(){context.document.activeElement=null;ruffleHasFocus=false;},
  dispatchEvent:e=>{events.push(e);if(ruffleHasFocus)e.preventDefault();},ruffle:()=>({load:async options=>{loadedConfig=options;},
    suspend:()=>suspended++,resume:()=>resumed++})};
const game={style:{},appendChild(){},getBoundingClientRect:()=>rect};
class Event {
  constructor(type,options){this.type=type;this.defaultPrevented=false;Object.assign(this,options);}
  preventDefault(){this.defaultPrevented=true;}
}
const context = {console,innerWidth:1000,innerHeight:500,PointerEvent:Event,KeyboardEvent:Event,
  URLSearchParams,location:{search:''},
  LastStand2Host:{reportPhase:phase=>phases.push(phase)},
  document:{hidden:false,getElementById:id=>id==='game'?game:{remove(){}},
    addEventListener:(id,fn)=>hooks['document:'+id]=fn},
  addEventListener:(id,fn)=>hooks[id]=fn,
  RufflePlayer:{newest:()=>({createPlayer:()=>player})}};
context.window=context;
vm.createContext(context); vm.runInContext(script,context);
const key=(id,down,source)=>context.tls2Key(id,down,source);
const filtered=type=>events.filter(e=>e.type===type);
(async()=>{
  await hooks.load();
  assert.equal(context.RufflePlayer.config.quality,'low');
  assert.equal(loadedConfig.quality,'low');
  context.tls2Aim(0.7,0.6);
  let e=filtered('pointermove').at(-1);
  assert.equal(e.offsetX,600); assert.equal(e.offsetY,260);
  assert.equal(e.buttons,0);
  key('fire',true,'button105'); key('fire',true,'rightTrigger');
  key('select',true,'button96');
  assert.equal(filtered('pointerdown').length,1);
  key('fire',false,'button105'); key('fire',false,'rightTrigger');
  assert.equal(filtered('pointerup').length,0);
  context.tls2Aim(0.6,0.4);
  assert.equal(filtered('pointermove').at(-1).buttons,1);
  key('select',false,'button96'); assert.equal(filtered('pointerup').length,1);

  events.length=0;
  key('up',true,'stick'); key('up',true,'touch'); key('up',false,'stick');
  assert.equal(filtered('keyup').length,0);
  key('up',false,'touch'); assert.equal(filtered('keydown')[0].key,'w');
  assert.equal(filtered('keyup').length,1);

  // Stretched canvas: client coordinates must convert back to local CSS pixels.
  rect.width=1600;rect.height=840;rect.right=1700;rect.bottom=880;
  context.tls2Aim(0.7,0.6);
  e=filtered('pointermove').at(-1);
  assert.equal(e.offsetX,300); assert.equal(e.offsetY,130);
  context.tls2TouchPointer('down',0.8,0.5);
  assert.equal(filtered('pointerdown').at(-1).offsetX,350);
  context.tls2TouchPointer('cancel',0,0);
  assert.equal(filtered('pointerup').at(-1).offsetX,350);

  key('fire',true,'button105'); key('reload',true,'touch');
  context.tls2SetMenuPaused(true);
  assert.equal(filtered('pointerup').at(-1).buttons,0);
  assert.equal(filtered('keyup').at(-1).code,'KeyR');
  const count=events.length;
  key('fire',true,'button105'); context.tls2Aim(0.1,0.1);
  assert.equal(events.length,count);
  context.tls2SetBackgroundPaused(true); context.tls2SetMenuPaused(false);
  assert.equal(resumed,0); assert.equal(suspended,1);
  context.tls2SetBackgroundPaused(false); assert.equal(resumed,1);
  key('swap',true,'touch'); hooks.blur();
  assert.equal(filtered('keyup').at(-1).code,'Space');
  // Scene detection must not cancel gamepad buttons/axes, whose native state
  // changes only on a later physical event. Touch cancels only its own source.
  key('right',true,'stick');
  key('fire',true,'rightTrigger'); key('fire',true,'touchAim');
  events.length=0;
  player.onFSCommand('tls2Phase','combat');
  assert.equal(filtered('pointerup').length,0);
  assert.equal(filtered('keyup').length,0);
  context.tls2TouchPointer('cancel',0,0);
  assert.equal(filtered('pointerup').length,0);
  const phaseEvents=events.length;
  player.onFSCommand('tls2Phase','combat');
  player.onFSCommand('tls2Phase','invalid');
  assert.equal(events.length,phaseEvents);
  player.onFSCommand('tls2Phase','menu');
  assert.equal(filtered('pointerup').length,0);
  assert.equal(filtered('keyup').length,0);
  key('right',false,'stick'); key('fire',false,'rightTrigger');
  assert.equal(filtered('keyup').at(-1).code,'KeyD');
  assert.equal(filtered('pointerup').length,1);
  assert.deepEqual(phases,['combat','menu']);
  // Android may leave DOM focus intact while Ruffle's internal focus is lost.
  // The old soft focus() call alone cannot deliver these key transitions.
  events.length=0; ruffleHasFocus=false; context.document.activeElement=player;
  key('down',true,'stick');
  assert.equal(filtered('keydown').length,2);
  assert.equal(filtered('keydown')[0].defaultPrevented,false);
  assert.equal(filtered('keydown')[1].defaultPrevented,true);
  assert.equal(ruffleHasFocus,true);
  key('down',false,'stick'); assert.equal(filtered('keyup').length,1);
  // A pointer leave must be followed by another enter even on the same canvas.
  const entered=filtered('pointerenter').length;
  canvasHooks.pointerleave();
  context.tls2Aim(0.4,0.5);
  assert.equal(filtered('pointerenter').length,entered+1);
  context.tls2Aim(0.41,0.5);
  assert.equal(filtered('pointerenter').length,entered+1);
  canvasHooks.pointercancel(); ruffleHasFocus=false;
  context.tls2Aim(0.42,0.5);
  assert.equal(ruffleHasFocus,true);
  assert.equal(filtered('pointermove').at(-1).defaultPrevented,true);
  key('fire',true,'rightTrigger'); key('fire',false,'rightTrigger');
  assert.equal(filtered('pointerdown').at(-1).defaultPrevented,true);
  // A still-connected old canvas can be hidden after a renderer change.
  // Route to the currently visible canvas instead of trusting connectivity.
  const oldRect=canvas.getBoundingClientRect;
  canvas.isConnected=true; canvas.getBoundingClientRect=()=>({width:0,height:0});
  let replacementMoves=0;
  const replacement={...canvas,getBoundingClientRect:oldRect,
    dispatchEvent:e=>{if(e.type==='pointermove')replacementMoves++;e.preventDefault();}};
  canvases=[canvas,replacement]; context.tls2Aim(0.43,0.5);
  assert.equal(replacementMoves,1);
  canvas.getBoundingClientRect=oldRect; canvases=[canvas];
  // Raster budget is in physical pixels, independent of Android display density.
  context.devicePixelRatio=2;
  context.tls2SetRenderResolution(0);
  assert.equal(parseFloat(game.style.width)*2,800);
  assert.equal(parseFloat(game.style.height)*2,420);
  context.tls2SetRenderResolution(1);
  assert.equal(parseFloat(game.style.width)*2,1600);
  context.tls2SetRenderResolution(2);
  assert.ok(parseFloat(game.style.width)*2>1600);
  context.tls2SetDisplayMode('stretch');
  assert.equal(parseFloat(game.style.width),1000);
  assert.equal(parseFloat(game.style.height),500);
  context.tls2SetRenderResolution(0);
  assert.equal(game.style.transform,'scale(2.5,2.380952380952381)');
  for(const requested of ['low','medium','high','bogus']) {
    const isolated={...context,RufflePlayer:{},location:{search:'?quality='+requested}};
    isolated.window=isolated;vm.runInNewContext(script,isolated);
    assert.equal(isolated.RufflePlayer.config.quality,requested==='bogus'?'low':requested);
  }
  console.log('PASS: pointer scaling, input ownership, scene transitions, render resolution and pause/lifecycle.');
})().catch(e=>{console.error(e);process.exit(1)});
