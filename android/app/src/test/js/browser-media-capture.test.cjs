const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../../main/kotlin/com/reianix/reianix_local/downloads/BrowserMediaCapture.kt'),'utf8');
const script=source.split('val SCRIPT="""')[1].split('""".trimIndent()')[0].replaceAll("${'$'}",'$');
const playlist='https://cdn.example/i/token/h.jpg';
const body='#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1800000,RESOLUTION=1280x720\n720.jpg\n#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360\n360.jpg\n';
function page(responseUrl=playlist){
  const messages=[],timers=[],listeners={};
  const rect=()=>({left:0,right:400,top:0,bottom:500,width:400,height:500});
  const node={nodeType:1,id:'',className:'',parentElement:null,getBoundingClientRect:rect,getAttribute:()=>null};
  const declared={...node,src:playlist,getAttribute:k=>k==='type'?'application/vnd.apple.mpegurl':null};
  const player={...node,querySelectorAll:()=>[],querySelector:()=>video};
  const video={...node,currentSrc:'blob:https://site.example/player',src:'',readyState:0,duration:NaN,autoplay:false,muted:false,loop:false,
    closest:()=>player,querySelectorAll:()=>[declared],querySelector:()=>declared};
  const control={...node,textContent:'720p',closest:s=>s==='button'?control:s.includes('app-player-download')?{...node,closest:()=>player}:null};
  const doc={querySelectorAll:s=>s==='video'?[video]:s.includes('source')?[declared]:[],addEventListener:(e,f)=>{(listeners[e]??=[]).push(f)}};
  const response={url:responseUrl,status:200,headers:{get:k=>k==='content-type'?'image/jpeg':String(body.length)},clone:()=>({body:{getReader:()=>{
    let read=false;return {read:async()=>read?{done:true}:(read=true,{done:false,value:new TextEncoder().encode(body)}),releaseLock(){}};
  }}})};
  const win={addEventListener(){},ReiAnixMedia:{postMessage:s=>messages.push(JSON.parse(s))},fetch:async()=>response};
  win.top=win;win.parent=win;
  class XHR{addEventListener(){}};XHR.prototype.open=function(){};
  const context={window:win,document:doc,parent:win,innerWidth:400,innerHeight:800,location:{href:'https://site.example/anime'},URL,Date,Math,Number,Set,Map,TextDecoder,XMLHttpRequest:XHR,
    fetch:(...a)=>win.fetch(...a),getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'}),setTimeout:f=>timers.push(f),clearTimeout(){},AbortController,MutationObserver:class{observe(){}}};
  vm.runInNewContext(script,context);
  return {messages,listeners,control,win,flush:()=>{for(const f of timers.splice(0))f()}};
}
test('recognizes an explicitly typed HLS playlist behind a jpg URL',async()=>{
  const p=page();await p.win.fetch(playlist);await new Promise(setImmediate);p.flush();
  assert.deepEqual(p.messages.filter(m=>m.origin==='master').map(m=>m.quality).sort(),['360p','720p']);
});
test('trusted Vidstack download selection opens a native offer with the selected quality',()=>{
  const p=page();let prevented=false;
  for(const listener of p.listeners.click??[])listener({isTrusted:true,target:p.control,preventDefault(){prevented=true},stopImmediatePropagation(){}});
  assert.equal(prevented,true);
  assert.equal(p.messages.find(m=>m.kind==='download')?.quality,'720p');
});
test('synthetic page clicks cannot trigger a download offer',()=>{
  const p=page();for(const listener of p.listeners.click??[])listener({isTrusted:false,target:p.control,preventDefault(){assert.fail()},stopImmediatePropagation(){}});
  assert.equal(p.messages.some(m=>m.kind==='download'),false);
});

test('does not inspect an unrelated image response as a playlist',async()=>{
  const p=page('https://cdn.example/poster.jpg');await p.win.fetch('https://cdn.example/poster.jpg');await new Promise(setImmediate);p.flush();
  assert.equal(p.messages.some(m=>m.origin==='master'),false);
});
