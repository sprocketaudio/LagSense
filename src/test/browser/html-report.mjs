import {spawn} from 'node:child_process';
import fs from 'node:fs/promises';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
const root=process.cwd(),base=path.join(root,'work/html-export'),examples=path.join(base,'examples');
const browser=process.argv[2]||'C:/Program Files/Google/Chrome/Application/chrome.exe';
const tag=process.argv[3]||'chrome';
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const proc=spawn(browser,['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--disable-background-networking','--disable-component-update','--disable-sync','--remote-debugging-port=0','--user-data-dir='+path.join(base,'browser-'+tag),'about:blank'],{windowsHide:true,stdio:['ignore','pipe','pipe']});
let stderr='';proc.stderr.on('data',chunk=>stderr+=chunk.toString());
let socket,failures=[],requests=[],runtimeErrors=[],logs=[];
const summary={browser:tag,checks:[],timings:{},networkRequests:[],errors:[]};
try{
  let match;for(let i=0;i<200;i++){match=stderr.match(/DevTools listening on (ws:\/\/[^\s]+)/);if(match)break;if(proc.exitCode!=null)throw Error('Browser exited: '+stderr);await delay(50);}if(!match)throw Error('No browser debug endpoint: '+stderr);
  const endpoint=match[1],port=new URL(endpoint).port,targets=await(await fetch('http://127.0.0.1:'+port+'/json/list')).json(),target=targets.find(t=>t.type==='page');
  socket=new WebSocket(target.webSocketDebuggerUrl);await new Promise((r,j)=>{socket.addEventListener('open',r,{once:true});socket.addEventListener('error',j,{once:true});});
  let id=0;const pending=new Map();socket.addEventListener('message',event=>{const data=JSON.parse(event.data);if(data.id){const p=pending.get(data.id);if(p){pending.delete(data.id);clearTimeout(p.timer);data.error?p.reject(Error(JSON.stringify(data.error))):p.resolve(data.result);}}else if(data.method==='Network.requestWillBeSent')requests.push(data.params.request.url);else if(data.method==='Runtime.exceptionThrown')runtimeErrors.push(data.params.exceptionDetails);else if(data.method==='Log.entryAdded')logs.push(data.params.entry);});
  const send=(method,params={})=>new Promise((resolve,reject)=>{const n=++id,timer=setTimeout(()=>{pending.delete(n);reject(Error('CDP timeout '+method));},60000);pending.set(n,{resolve,reject,timer});socket.send(JSON.stringify({id:n,method,params}));});
  const evaluate=async expression=>{const result=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true,userGesture:true});if(result.exceptionDetails)throw Error(JSON.stringify(result.exceptionDetails));return result.result.value;};
  const wait=async expression=>{const start=Date.now();while(Date.now()-start<60000){if(await evaluate(expression))return;await delay(35);}throw Error('Timed out: '+expression+'\n'+JSON.stringify(await evaluate('({failed:document.documentElement.dataset.failed,notices:document.querySelector("#notices")?.textContent,activity:document.querySelector("#activity")?.textContent})')));};
  const navigate=async file=>{requests=[];runtimeErrors=[];logs=[];const start=Date.now();const url=pathToFileURL(path.resolve(examples,file)).href;await send('Page.navigate',{url});await wait('location.href==='+JSON.stringify(url)+'&&document.readyState==="complete"&&(document.documentElement?.dataset.ready==="true"||document.documentElement?.dataset.failed==="true")');assert.equal(await evaluate('document.documentElement.dataset.failed'),undefined);await evaluate('document.fonts.ready.then(()=>true)');summary.timings[file]=Date.now()-start;};
  const click=selector=>evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  const tab=async view=>{await click('nav button[data-view="'+view+'"]');await wait('document.querySelector("#activity").textContent===""&&!document.querySelector("#explorer").hidden');};
  const change=async(id,value)=>{await evaluate(`(()=>{let e=document.getElementById(${JSON.stringify(id)});e.value=${JSON.stringify(value)};e.dispatchEvent(new Event('change',{bubbles:true}));})()`);await wait('document.querySelector("#activity").textContent===""');};
  const search=async value=>{await evaluate(`(()=>{let e=document.getElementById('search');e.value=${JSON.stringify(value)};e.dispatchEvent(new Event('input',{bubbles:true}));})()`);await delay(210);await wait('document.querySelector("#activity").textContent===""');};
  const checkPage=async name=>{assert.equal(runtimeErrors.length,0,'Runtime errors '+name+': '+JSON.stringify(runtimeErrors));const external=requests.filter(u=>/^https?:/.test(u));assert.equal(external.length,0,'External requests '+external);assert.equal(logs.filter(e=>e.level==='error').length,0,'Console errors '+JSON.stringify(logs));assert.equal(await evaluate('globalThis.lagsenseInjected'),undefined);assert.equal(await evaluate('document.querySelectorAll("img").length'),0);summary.checks.push(name);};
  for(const file of (await fs.readdir(examples)).filter(name=>name.endsWith('.html'))){const source=await fs.readFile(path.join(examples,file),'utf8');assert(!/@font-face|data:font|;base64,|DecompressionStream|atob\(/.test(source),'Embedded or encoded payload in '+file);assert(!source.includes('Font licence'));const match=source.match(/<script id="report-data" type="application\/json">([\s\S]*?)<\/script>/);assert(match,'Plain JSON data missing in '+file);const document=JSON.parse(match[1]);if(file==='large.html'){assert.equal(document.data.entries.length,100000);assert.equal(document.data.diagnostics.callbacks.length,9001);}assert(source.startsWith('<!doctype html>\n<!--\nLagSense performance report'));}
  summary.checks.push('all fixtures contain readable complete JSON, formatted source, no fonts or encoded/compressed payloads');
  await send('Page.enable');await send('Runtime.enable');await send('Network.enable');await send('Log.enable');await send('Emulation.setDeviceMetricsOverride',{width:1440,height:1100,deviceScaleFactor:1,mobile:false});
  const themes=[['charcoal_ice','#a3dce2'],['midnight_violet','#c7b2ff'],['obsidian_gold','#d8b76a']];
  for(const[theme,accent]of themes){await navigate('small-'+theme+'.html');assert.equal(await evaluate('document.documentElement.dataset.theme'),theme);assert.equal((await evaluate('getComputedStyle(document.documentElement).getPropertyValue("--accent")')).trim(),accent);assert.match(await evaluate('document.querySelector("#capture-context").textContent'),/9007199254740993/);const image=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});await fs.writeFile(path.join(base,tag+'-'+theme+'.png'),Buffer.from(image.data,'base64'));await checkPage('initial theme '+theme);if(theme==='obsidian_gold'){await send('DOM.enable');await send('CSS.enable');const doc=await send('DOM.getDocument');const node=await send('DOM.querySelector',{nodeId:doc.root.nodeId,selector:'.metadata dt'});summary.fonts=await send('CSS.getPlatformFontsForNode',{nodeId:node.nodeId});summary.loadedFonts=await evaluate('Array.from(document.fonts).map(f=>({family:f.family,status:f.status}))');assert(summary.fonts.fonts.length>0&&summary.fonts.fonts.every(f=>!f.isCustomFont),'Report uses installed system fonts only');assert.equal(await evaluate('document.fonts.size'),0);}}
  const activityText=await evaluate(`Array.from(document.querySelectorAll('.summary-block h3')).find(h=>h.textContent==='Server Activity').parentElement.textContent`);
  assert.match(activityText,/Active · Insufficient Data/);assert.match(activityText,/Loaded chunks \(avg \/ peak\)13\.0 \/ 18/);
  assert.match(activityText,/Ticking chunks \(avg \/ peak\)7\.0 \/ 10/);assert.match(activityText,/minecraft:the_nether/);
  summary.checks.push('activity totals preserve earlier samples and render a per-dimension breakdown');
  const eventColours = new Set();
  for (const [theme] of themes) {
    await navigate('small-'+theme+'.html');
    const coverage = await evaluate(`(() => {
      const swatches = Array.from(document.querySelectorAll('.coverage-legend .swatch'), swatch => {
        const style = getComputedStyle(swatch);
        return {colour: style.backgroundColor,pattern: style.backgroundImage};
      });
      const segments = Array.from(document.querySelectorAll('.coverage-bar > span'), segment => {
        const style = getComputedStyle(segment);
        return {colour:style.backgroundColor,pattern:style.backgroundImage};
      });
      return {swatches,segments,text:document.querySelector('.coverage-bar').textContent};
    })()`);
    assert.deepEqual(coverage.segments,coverage.swatches);
    assert.equal(new Set(coverage.segments.map(item=>item.colour)).size,4);
    assert.match(coverage.segments[2].pattern,/repeating-linear-gradient/);
    assert.match(coverage.text,/Events40%Other35%/);
    eventColours.add(coverage.segments[1].colour);
  }
  assert.equal(eventColours.size,3);
  summary.checks.push('tick breakdown uses distinct theme colours, a striped overlap and direct labels');
  const panelLayout = () => evaluate(`Array.from(document.querySelectorAll('.summary-grid > section'), panel => {
    const {x,y,width,height} = panel.getBoundingClientRect();
    return {title: panel.querySelector('h3').textContent,x,y,width,height};
  })`);
  const panelTitles = ['Capture details','Server Activity','Server tick time breakdown',
    'Captured diagnostics','Highest measured targets','Highest measured chunks'];
  for (const width of [1440, 1024, 390]) {
    await send('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});
    await navigate('small-midnight_violet.html');
    const panels = await panelLayout();
    assert.deepEqual(panels.map(panel=>panel.title),panelTitles);
    if (width===390) {
      assert(panels.every(panel=>panel.x===panels[0].x));
      assert(panels.slice(1).every((panel,index)=>panel.y>panels[index].y));
      const widths = await evaluate('({scroll:document.documentElement.scrollWidth,visible:document.documentElement.clientWidth})');
      assert(widths.scroll<=widths.visible,`Mobile page overflows: ${JSON.stringify(widths)}`);
    } else {
      assert(panels.every(panel=>Math.abs(panel.height-panels[0].height)<=1));
      for (const index of [0,2,4]) {
        assert.equal(panels[index].y,panels[index+1].y);
        assert(panels[index].x<panels[index+1].x);
        assert(Math.abs(panels[index].width-panels[index+1].width)<=1);
      }
    }
    await click('.summary-grid details summary');
    assert(await evaluate('document.querySelector(".summary-grid details").open'));
  }
  await send('Emulation.setDeviceMetricsOverride',{width:1440,height:1100,deviceScaleFactor:1,mobile:false});
  summary.checks.push('equal desktop Summary panels stay in order, hotspots side by side, single-column mobile and expandable dimensions');
  await navigate('shortened-stacks.html');
  assert.equal(await evaluate('document.querySelectorAll("#notices .notice").length'),1);
  assert.match(await evaluate('document.getElementById("notices").textContent'),/Deeper calls may be missing/);
  assert.match(await evaluate('document.getElementById("notices").textContent'),/2 of 2 sampled stacks/);
  assert.equal(await evaluate('document.querySelector("#notices code").textContent'),'-XX:FlightRecorderOptions=stackdepth=128');
  assert(!/Budget|methods retained|Short calls/.test(await evaluate('document.getElementById("notices").textContent')));
  await click('.diagnostic-details summary');
  assert.match(await evaluate('document.querySelector(".diagnostic-details").textContent'),/Stacks shortened by the JVM2/);
  await tab('paths');
  assert.equal(await evaluate('document.querySelectorAll("#notices .notice").length'),0,'Warnings are collapsed off Summary');
  assert.equal(await evaluate('document.getElementById("capture-limitations").hidden'),false);
  await click('#capture-limitations summary');
  assert(await evaluate('document.getElementById("capture-limitations").open'));
  assert.equal(await evaluate('document.querySelector("#capture-limitations[open] code").textContent'),'-XX:FlightRecorderOptions=stackdepth=128');
  assert.match(await evaluate('document.querySelector("#capture-limitations[open] ul").textContent'),/For deeper call stacks, add this JVM argument/);
  for (const width of [780,700,390]) {
    await send('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});
    assert(await evaluate(`(() => {
      const menu = document.querySelector('#capture-limitations ul').getBoundingClientRect();
      return menu.left>=0 && menu.right<=document.documentElement.clientWidth;
    })()`),`Limitation details fit a ${width}px viewport`);
  }
  await send('Emulation.setDeviceMetricsOverride',{width:1440,height:1100,deviceScaleFactor:1,mobile:false});
  await click('nav button[data-view="summary"]');
  await send('Page.bringToFront');
  await evaluate('document.querySelector(".notice-dismiss").focus()');
  assert(await evaluate('document.activeElement.matches(".notice-dismiss")'));
  await send('Input.dispatchKeyEvent',{type:'keyDown',key:' ',code:'Space',windowsVirtualKeyCode:32,text:' '});
  await send('Input.dispatchKeyEvent',{type:'keyUp',key:' ',code:'Space',windowsVirtualKeyCode:32});
  await wait('!document.querySelector("#notices .notice")');
  await tab('targets');
  await click('nav button[data-view="summary"]');
  assert.equal(await evaluate('document.querySelectorAll("#notices .notice").length'),0,'Dismissal survives tab changes');
  await navigate('critical.html');
  assert.equal(await evaluate('document.querySelectorAll("#notices .notice.error").length'),1);
  await click('.notice-dismiss');
  assert.match(await evaluate('document.querySelector("#capture-limitations summary").textContent'),/Critical capture limitation/);
  assert.equal(await evaluate('document.getElementById("capture-limitations").hidden'),false);
  await navigate('idle.html');
  assert.match(await evaluate('document.getElementById("overview").textContent'),/Idle · Healthy/);
  assert.equal(await evaluate('document.querySelectorAll("#notices .notice").length'),0);
  assert(await evaluate('document.getElementById("capture-limitations").hidden'));
  summary.checks.push('concise sampled-stack warning, keyboard dismissal, persistent critical indicator and quiet idle report');
  const neutralShares=async name=>{const result=await evaluate(`(()=>{const rows=Array.from(document.querySelectorAll('#result-rows .value')).filter(e=>e.textContent.includes('%'));return {count:rows.length,neutral:rows.every(e=>getComputedStyle(e).color===getComputedStyle(document.documentElement).color)};})()`);assert(result.count>0,'Expected sample percentages in '+name);assert(result.neutral,'Percentage severity colour in '+name);};
  for(const[theme]of themes){
    await navigate('small-'+theme+'.html');await tab('cpu');await neutralShares(theme+' server CPU by mod');await change('group','actions');await neutralShares(theme+' server CPU actions');
    await change('scope','background');await neutralShares(theme+' background CPU actions');await change('group','mod');await neutralShares(theme+' background CPU by mod');
    await tab('paths');await neutralShares(theme+' call hierarchy');await change('group','methods');await neutralShares(theme+' running code');
    await tab('targets');assert(await evaluate('Array.from(document.querySelectorAll("#result-rows .value")).some(e=>getComputedStyle(e).color!==getComputedStyle(document.documentElement).color)'),'Measured timings retain their colours');
  }
  summary.checks.push('neutral server/background CPU and call-path percentages in all three themes; timing colours retained');
  await navigate('validation.html');
  const footer=await evaluate(`(()=>{const f=document.querySelector('footer');return {text:f.firstElementChild.textContent,licenses:f.querySelectorAll('.license').length,help:f.querySelector('#report-help')!=null};})()`);
  assert.equal(footer.text,'Captured measurements · read-only report');assert.equal(footer.licenses,0);assert(footer.help);
  await evaluate('document.querySelector("#report-help").open=true');assert.equal(await evaluate('document.querySelector("#report-help").open'),true);await evaluate('document.querySelector("#report-help").open=false');summary.checks.push('system fonts, no licence section, retained measurement help');
  await tab('targets');assert.equal(await evaluate('document.getElementById("group").value'),'instances');assert.equal(await evaluate('document.getElementById("sort").value'),'cost');assert.equal(await evaluate('document.getElementById("dimension").value'),'All');assert.equal(await evaluate('document.getElementById("category").value'),'All');
  await change('group','mod');await click('#result-rows tr .row-label');await wait('document.getElementById("activity").textContent===""');
  assert(await evaluate('Array.from(document.querySelectorAll("#result-rows tr")).some(r=>r.textContent.includes("fixture:machine")&&r.querySelector(".branch")==null)'));
  await change('group','mod-type');await click('#result-rows tr .row-label');await wait('document.getElementById("activity").textContent===""');assert(await evaluate('Array.from(document.querySelectorAll("#result-rows tr")).some(r=>r.textContent.includes("fixture:machine")&&r.querySelector(".branch")!=null)'));
  await change('dimension','minecraft:the_nether');await change('category','Entity');await change('sort','name');await tab('cpu');assert.equal(await evaluate('document.getElementById("group").value'),'mod');assert.equal(await evaluate('document.getElementById("scope").value'),'server');assert.equal(await evaluate('document.getElementById("sort").value'),'cost');await tab('paths');assert.equal(await evaluate('document.getElementById("group").value'),'hierarchy');await tab('events');assert.equal(await evaluate('document.getElementById("group").value'),'mod');
  await tab('targets');assert.equal(await evaluate('document.getElementById("dimension").value'),'All');assert.equal(await evaluate('document.getElementById("category").value'),'All');assert.equal(await evaluate('document.getElementById("group").value'),'instances');summary.checks.push('mod versus mod-type hierarchy and in-game default groups/thread/sorts/filters');
  await navigate('validation.html');await tab('targets');assert.equal(await evaluate('document.querySelectorAll("#result-rows tr").length'),4);const targetImage=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});await fs.writeFile(path.join(base,tag+'-targets.png'),Buffer.from(targetImage.data,'base64'));assert.equal(await evaluate('Array.from(document.querySelectorAll("#result-rows button")).filter(b=>b.textContent==="Copy TP Command").length'),3);
  await evaluate('Object.defineProperty(navigator,"clipboard",{configurable:true,value:{writeText:async value=>{globalThis.lastCopied=value;}}})');
  await evaluate('Array.from(document.querySelectorAll("#result-rows button")).find(b=>b.textContent==="Copy TP Command").click()');await wait('globalThis.lastCopied!=null');assert.equal(await evaluate('globalThis.lastCopied'),'/lagsense tp location minecraft:overworld -17 -40 -33');assert(await evaluate('document.querySelector("#result-rows").textContent.includes("Copied!")'));summary.checks.push('negative coordinates, recorded Y, capture-independent command and Copied feedback');
  await search('minecraft:the_nether');assert.equal(await evaluate('document.querySelectorAll("#result-rows tr").length'),1);await evaluate('Array.from(document.querySelectorAll("#result-rows button")).find(b=>b.textContent==="Copy TP Command").click()');await wait('globalThis.lastCopied.includes("the_nether")');assert.equal(await evaluate('globalThis.lastCopied'),'/lagsense tp location minecraft:the_nether -1 64 -1');
  await search('fixture:missing');assert.equal(await evaluate('document.querySelectorAll("#result-rows tr").length'),1);assert.equal(await evaluate('Array.from(document.querySelectorAll("#result-rows button")).filter(b=>b.textContent==="Copy TP Command").length'),0);assert(!/Missing coordinates/.test(await evaluate('document.getElementById("notices").textContent')));await evaluate('Array.from(document.querySelectorAll("#result-rows button")).find(b=>b.textContent==="Details").click()');await wait('!document.getElementById("selection").hidden');assert.match(await evaluate('document.getElementById("detail-body").textContent'),/coordinates are missing or invalid/);summary.checks.push('dimension handling, missing-position suppression and local details');
  await search('fixture:machine');await evaluate('navigator.clipboard.writeText=async()=>{throw Error("blocked")};document.execCommand=()=>false;Array.from(document.querySelectorAll("#result-rows button")).find(b=>b.textContent==="Copy TP Command").click()');await wait('document.getElementById("manual-copy")!=null');assert.equal(await evaluate('document.getElementById("manual-copy").value'),'/lagsense tp location minecraft:overworld -17 -40 -33');summary.checks.push('clipboard permission fallback');
  assert.equal(await evaluate('Array.from(document.querySelectorAll("#result-rows button")).filter(b=>b.textContent==="Mark").length'),0);
  await tab('chunks');await change('dimension','All');
  const parentRows=await evaluate('document.querySelectorAll("#result-rows tr").length');
  await click('#result-rows tr .row-label');await wait('document.getElementById("activity").textContent===""');assert(await evaluate('document.querySelectorAll("#result-rows tr").length')>parentRows);
  const expandedRows=await evaluate('document.querySelectorAll("#result-rows tr").length');
  await evaluate('document.querySelector("#result-rows tr .location button").click()');await wait('!document.getElementById("selection").hidden');assert.equal(await evaluate('document.querySelectorAll("#result-rows tr").length'),expandedRows);
  await evaluate('document.querySelector("#result-rows tr .value").click()');await wait('document.getElementById("activity").textContent===""');assert.equal(await evaluate('document.querySelectorAll("#result-rows tr").length'),parentRows);summary.checks.push('whole parent row toggles children, Details is explicit and does not expand, no HTML Mark buttons');await evaluate('Object.defineProperty(navigator,"clipboard",{configurable:true,value:{writeText:async value=>{globalThis.lastCopied=value;}}})');await evaluate('Array.from(document.querySelectorAll("#result-rows button")).find(b=>b.textContent==="Copy TP Command").click()');await wait('globalThis.lastCopied.includes("-17 -40 -33")');assert.equal(await evaluate('document.querySelectorAll("#result-rows tr").length'),parentRows);summary.checks.push('chunk commands prefer recorded hotspot including underground Y without expanding');
  await tab('cpu');await change('scope','background');assert.equal(await evaluate('document.querySelectorAll("#result-rows tr").length'),2);await change('group','actions');assert.equal(await evaluate('document.querySelectorAll("#result-rows tr").length'),2);assert(await evaluate('document.querySelector("#result-rows").textContent.includes("#2")&&document.querySelector("#result-rows").textContent.includes("#3")'));
  await tab('paths');await change('scope','2');await change('group','methods');assert.match(await evaluate('document.querySelector("#result-rows").textContent'),/appeng.Network#plan/);assert(!/unknown.Library/.test(await evaluate('document.querySelector("#result-rows").textContent')));summary.checks.push('background identity and call-path separation');
  await tab('events');await change('group','actions');assert.equal(await evaluate('document.querySelectorAll("#result-rows tr").length'),2);assert(await evaluate('document.querySelector("#result-rows").textContent.includes("</script>")'));await checkPage('hostile report strings are inert, with no runtime/network/CSP errors');
  await tab('map');await delay(100);assert.equal(await evaluate('document.querySelector("#chunk-map").width>0'),true);await click('#map-minus');await delay(70);await checkPage('offline chunk heatmap');
  const image=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});await fs.writeFile(path.join(base,tag+'-map.png'),Buffer.from(image.data,'base64'));
  await navigate('chunk-fallback.html');await tab('chunks');assert.equal(await evaluate('document.querySelectorAll("#result-rows tr").length'),1);await evaluate('Object.defineProperty(navigator,"clipboard",{configurable:true,value:{writeText:async value=>{globalThis.lastCopied=value;}}});Array.from(document.querySelectorAll("#result-rows button")).find(b=>b.textContent==="Copy TP Command").click()');await wait('globalThis.lastCopied!=null');assert.equal(await evaluate('globalThis.lastCopied'),'/lagsense tp chunk minecraft:the_end -2 -3');await checkPage('chunk centre command when hotspot is unavailable');
  await navigate('frames.html');assert.match(await evaluate('document.getElementById("overview").textContent'),/GPU timing/);await checkPage('client frame export');
  await navigate('old.html');await tab('cpu');await change('scope','background');assert.match(await evaluate('document.getElementById("empty").textContent'),/no saved background/);await tab('paths');assert.match(await evaluate('document.getElementById("empty").textContent'),/no saved call paths/);await checkPage('older capture without new fields');
  await navigate('limited.html');assert.match(await evaluate('document.getElementById("notices").textContent'),/Incomplete|budget|partial/);await checkPage('incomplete metadata preserved');
  await navigate('comparison.html');await tab('changes-types');const manifest=JSON.parse(await fs.readFile(path.join(examples,'manifest.json'),'utf8'));const comparison=await evaluate('Array.from(document.querySelectorAll("#result-rows tr")).map(r=>({name:r.querySelector(".row-label").textContent,delta:r.querySelector(".value").textContent}))');for(const expected of manifest.comparisonTypes){const actual=comparison.find(r=>r.name===expected.name);assert(actual,'Missing comparison type '+expected.name+' in '+JSON.stringify(comparison)+' status '+await evaluate('document.getElementById("activity").textContent'));assert.equal(Number(actual.delta.replace(/,/g,'')),Number(expected.delta.toFixed(2)));}
  await click('#result-rows .branch');await wait('document.getElementById("activity").textContent===""');assert(await evaluate('document.querySelectorAll("#result-rows tr").length>4'));await change('direction','lower');assert(await evaluate('Array.from(document.querySelectorAll("#result-rows .value")).every(e=>Number(e.textContent.replace(/,/g,""))<=0)'));await checkPage('chronological comparisons, expansion and direction filters');
  for(const[theme]of themes){await navigate('comparison.html');await change('theme',theme);await tab('changes-types');await click('#result-rows .location button');await wait('!document.getElementById("selection").hidden');
    assert(await evaluate('Array.from(document.querySelectorAll("#detail-body .metadata dd")).every(e=>getComputedStyle(e).color===getComputedStyle(document.documentElement).color)'),'Comparison metadata including percentage changes remains neutral in '+theme);
    assert(await evaluate('Array.from(document.querySelectorAll("#result-rows .value")).some(e=>getComputedStyle(e).color!==getComputedStyle(document.documentElement).color)'),'Absolute comparison differences retain direction colours');}
  summary.checks.push('neutral comparison percentage details and preserved absolute difference colours in all themes');
  await navigate('old-comparison.html');await tab('changes-types');assert(await evaluate('document.querySelectorAll("#result-rows tr").length>0'));await checkPage('legacy aggregate comparison');
  await navigate('legacy-format.html');await tab('targets');assert.equal(await evaluate('document.querySelectorAll("#result-rows tr").length'),1);await checkPage('format 1 JSON with the current viewer');
  await navigate('chunk.html');await tab('targets');assert.equal(await evaluate('document.querySelectorAll("#result-rows tr").length'),1);await checkPage('compact standalone chunk entries');
  await navigate('precision.html');await tab('targets');await search('fixture:precise');await click('#result-rows tr:first-child button[aria-label^="Details"]');await wait('!document.getElementById("selection").hidden');assert.match(await evaluate('document.getElementById("detail-body").textContent'),/9223372036854775806/);assert.match(await evaluate('document.getElementById("detail-body").textContent'),/9007199254740993/);assert.match(await evaluate('document.getElementById("detail-body").textContent'),/12345678-1234-1234-1234-123456789abc/);await checkPage('exact large integers and UUIDs survive the compact worker path');
  await navigate('large.html');summary.largeLoadMs=summary.timings['large.html'];const begin=Date.now();await tab('targets');assert.equal(await evaluate('document.querySelectorAll("#result-rows tr").length'),100,await evaluate('document.getElementById("result-title").textContent+" / "+document.getElementById("page-status").textContent+" / "+document.getElementById("search").value+" / "+document.getElementById("activity").textContent'));assert.match(await evaluate('document.getElementById("page-status").textContent'),/100,000/);await change('page-number','1000');assert.equal(await evaluate('document.querySelectorAll("#result-rows tr").length'),100,await evaluate('document.getElementById("result-title").textContent+" / "+document.getElementById("page-status").textContent+" / "+document.getElementById("search").value+" / "+document.getElementById("activity").textContent'));assert.match(await evaluate('document.getElementById("page-status").textContent'),/100,000/);
  await evaluate('scrollTo(0,0)');
  await click('#result-rows tr:first-child button[aria-label^="Details for"]');
  await wait('document.activeElement.id === "detail-title"');
  assert(await evaluate(`(() => {
    const title = document.getElementById('detail-title').getBoundingClientRect();
    return scrollY > 0 && title.top >= 0 && title.bottom <= innerHeight;
  })()`));
  await click('#detail-close');
  assert(await evaluate('document.activeElement.matches("#result-rows tr:first-child button[aria-label^=Details]")'));
  summary.checks.push('Details scrolls and focuses the pane and returns focus to the result on close');
  await search('fixture:target_99999');assert.equal(await evaluate('document.querySelectorAll("#result-rows tr").length'),1);summary.largeSearchMs=Date.now()-begin;
  await tab('events');await change('group','actions');assert.equal(await evaluate('document.querySelectorAll("#result-rows tr").length'),100,await evaluate('document.getElementById("result-title").textContent+" / "+document.getElementById("page-status").textContent+" / "+document.getElementById("search").value+" / "+document.getElementById("activity").textContent'));assert.match(await evaluate('document.getElementById("page-status").textContent'),/9,001/);await search('handler_9000');assert.equal(await evaluate('document.querySelectorAll("#result-rows tr").length'),1);await checkPage('100,000 targets and 9,001 handlers retained and reachable, DOM paginated');
  const sizeAudit=path.join(root,'work/html-size-audit/sizes.json');
  if(await fs.stat(sizeAudit).catch(()=>null)){
    summary.sizeAudit=[];
    for(const report of JSON.parse(await fs.readFile(sizeAudit,'utf8'))){
      const variants={};
      for(const [name,file]of Object.entries({original:report.source,whitespace:report.whitespace,compact:report.compact})){
        const full=path.resolve(root,file),trials=[];
        for(let trial=0;trial<3;trial++){await navigate(full);trials.push(summary.timings[full]);}
        await tab('targets');
        const count=await evaluate('document.getElementById("page-status").textContent');
        variants[name]={medianLoadMs:trials.sort((a,b)=>a-b)[1],trials,targetStatus:count};
        if(name==='compact'){
          for(const view of ['chunks','map','cpu','paths','events']){
            const present=await evaluate(`Boolean(document.querySelector('nav button[data-view="${view}"]'))`);
            if(present){await click('nav button[data-view="'+view+'"]');await wait('document.getElementById("activity").textContent===""');}
          }
        }
        await checkPage('size benchmark '+name+' '+path.basename(file));
      }
      assert.equal(variants.original.targetStatus,variants.compact.targetStatus);
      assert.equal(variants.original.targetStatus,variants.whitespace.targetStatus);
      summary.sizeAudit.push({source:report.source,variants});
    }
  }
  const completeFixture=path.join(root,'work/profiling-limits/complete-256m.html');
  if(await fs.stat(completeFixture).catch(()=>null)){
    await navigate('../../profiling-limits/complete-256m.html');
    await tab('cpu');await change('group','actions');assert.match(await evaluate('document.getElementById("page-status").textContent'),/5,001/);
    await search('run4999');assert.equal(await evaluate('document.querySelectorAll("#result-rows tr").length'),1);
    await change('scope','background');await search('run4998');assert.equal(await evaluate('document.querySelectorAll("#result-rows tr").length'),1);
    await tab('paths');await change('scope','server');await change('group','methods');await search('deep119');assert.equal(await evaluate('document.querySelectorAll("#result-rows tr").length'),1);
    await evaluate('Array.from(document.querySelectorAll("#result-rows button")).find(b=>b.textContent==="Details").click()');await wait('!document.getElementById("selection").hidden');
    assert.match(await evaluate('document.getElementById("detail-body").textContent'),/Effective call-path budget MiB/);
    await checkPage('complete collection beyond former CPU limits, server/background search, deep call paths and budget details');
  }
  summary.totalDOM=await evaluate('document.getElementsByTagName("*").length');assert(summary.totalDOM<2000);summary.status='passed';
  await fs.writeFile(path.join(base,'browser-'+tag+'-results.json'),JSON.stringify(summary,null,2));console.log(JSON.stringify(summary,null,2));
  await send('Browser.close');
}catch(error){summary.status='failed';summary.errors=[String(error),...runtimeErrors,...logs.filter(e=>e.level==='error')];console.error(JSON.stringify(summary,null,2));await fs.writeFile(path.join(base,'browser-'+tag+'-results.json'),JSON.stringify(summary,null,2));process.exitCode=1;try{if(socket?.readyState===1)socket.send(JSON.stringify({id:999999,method:'Browser.close'}));}catch(ignore){}setTimeout(()=>proc.kill(),1000).unref();}
