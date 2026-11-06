// Exercise the production HTML bridge, including actual canvas target/offsets.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync('app/src/main/assets/www/index.html','utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const events = [], hooks = {};
let suspended = 0, resumed = 0;
const rect = {left:100,top:40,right:900,bottom:460,width:800,height:420};
const canvas = {offsetWidth:800,offsetHeight:420,getBoundingClientRect:()=>rect,
  dispatchEvent:e=>events.push(e)};
const player = {shadowRoot:{querySelectorAll:()=>[canvas]}, focus(){},
  dispatchEvent:e=>events.push(e),ruffle:()=>({load:async()=>{},
    suspend:()=>suspended++,resume:()=>resumed++})};
const game={style:{},appendChild(){},getBoundingClientRect:()=>rect};
class Event { constructor(type,options){this.type=type;Object.assign(this,options);} }
const context = {console,innerWidth:1000,innerHeight:500,PointerEvent:Event,KeyboardEvent:Event,
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
  console.log('PASS: pointer targeting/scaling, fire and movement ownership, touch cancellation, pause and lifecycle.');
})().catch(e=>{console.error(e);process.exit(1)});
