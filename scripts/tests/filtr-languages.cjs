// Reuses the Parlons language-browser.cjs loopback server and synthetic browser pattern.
// Real FILTR assets, WebGL and DOM. No wallet/node/owner data or external requests.
const {chromium}=require(process.env.PANDAMONIUM_PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),http=require('node:http');
const root=path.resolve(__dirname,'../..'),assets=path.join(root,'integrated/atelier/src/main/assets/filtr');
const screenshots=path.join(root,'build/localization/filtr-screenshots');fs.mkdirSync(screenshots,{recursive:true});
const server=http.createServer((req,res)=>{
  const name=req.url.split('?')[0]==='/'?'/filtr.html':req.url.split('?')[0];
  const file=path.resolve(assets,'.'+name);
  if(!file.startsWith(assets+path.sep)||!fs.existsSync(file)||!fs.statSync(file).isFile()){res.writeHead(404);res.end();return;}
  res.setHeader('Content-Type',file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html');res.end(fs.readFileSync(file));
});
(async()=>{
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const browser=await chromium.launch({headless:true,...(process.env.PANDAMONIUM_CHROME?{executablePath:process.env.PANDAMONIUM_CHROME}:{}),args:['--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
 try {
  const context=await browser.newContext({viewport:{width:390,height:844},locale:'uk-UA'}),page=await context.newPage(),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/*',route=>new URL(route.request().url()).hostname==='127.0.0.1'?route.continue():route.abort());
  await page.addInitScript(()=>{window.nativeCalls=[];window.AndroidFiltr={getLanguage:()=> 'en',toastMsg:m=>nativeCalls.push(['toast',m]),saveImage:(n,b)=>nativeCalls.push(['save',n,b]),sendToMint:b=>nativeCalls.push(['mint',b]),sendToPhoto:b=>nativeCalls.push(['photo',b])};});
  await page.goto('http://127.0.0.1:'+server.address().port);
  await page.waitForFunction(()=>FILTR.engine!==null);
  assert.equal(await page.locator('html').getAttribute('lang'),'en');
  await page.evaluate(()=>FiltrI18n.setLanguage('uk'));
  await page.waitForTimeout(100);
  assert.equal(await page.locator('#filtr-tool-crop').textContent(),'Обрізання');
  assert.equal(await page.evaluate(()=>FSHEET_SEEN_IMG),false,'Translation must not count as image loading');
  assert.equal(await page.evaluate(()=>FSHEET_ACTIVE),null);
  // Every control, enum, effect, post effect and built-in preset has a real catalogue entry.
  await page.evaluate(()=>{
   for(const code of ['en','zh-Hans','ru','uk']) {
    FiltrI18n.setLanguage(code);
    for(const fx of FILTR_FX) {
     FiltrI18n.t('filtr.effect.'+fx.id);
     for(const def of fx.c) {
      FiltrI18n.t('filtr.control.'+def.k);
      for(const value of def.lo||def.o||[])FiltrI18n.t('filtr.option.'+value);
     }
     FILTR.settings.active=fx.id;filtrBuildFxControls();
    }
    for(const def of FILTR_ADJUST)FiltrI18n.t('filtr.control.'+def.k);
    for(const post of FILTR_POST){FiltrI18n.t('filtr.post.'+post.id);for(const def of post.c)FiltrI18n.t('filtr.control.'+def.k);}
    for(const preset of FiltrEngine.BUILTIN_PRESETS)FiltrI18n.t('filtr.preset.'+preset.id.replace(/-/g,''));
   }
  });
  await page.evaluate(async()=>{
   const cv=document.createElement('canvas');cv.width=320;cv.height=240;
   const ctx=cv.getContext('2d');ctx.fillStyle='#d73318';ctx.fillRect(0,0,320,240);ctx.fillStyle='#1348aa';ctx.fillRect(60,40,90,130);
   const image=new Image();image.src=cv.toDataURL();await image.decode();
   FILTR.settings=FiltrEngine.freshSettings();filtrLoadImage(image,'Crop <img> 中文 {name}.png');
   FILTR.settings.active='dithering';filtrSyncPanels();
   filtrAddAnn('speech',{x:110,y:90});FILTR.ann[0].text='Effects <img onerror=alert(1)> 中文 {name}';filtrAnnPanel();filtrDrawAnn();
   filtrSetTool('crop');FILTR.crop={x:10,y:20,w:100,h:80};filtrDrawCrop();
  });
  await page.waitForTimeout(150);
  await page.evaluate(()=>fsheetOpen('annotate'));
  await page.waitForTimeout(100);
  await page.evaluate(()=>{
   window.originalInput=document.querySelector('#filtr-annotate-controls input[type=text]');
   originalInput.focus();originalInput.setSelectionRange(2,7);
   window.originalControls=[...document.querySelectorAll('#filtr-fx-controls input,#filtr-fx-controls select')];
   window.originalCanvas=document.getElementById('filtr-canvas');
   window.savedState=JSON.stringify({settings:FILTR.settings,ann:FILTR.ann,hist:FILTR.hist,fut:FILTR.fut,crop:FILTR.crop,tool:FILTR.tool,sel:FILTR.sel,zoom:FILTR.zoom,panX:FILTR.panX,panY:FILTR.panY,name:FILTR.name,full:FILTR.full.toDataURL()});
  });
  for(const code of ['en','zh-Hans','ru','uk']) {
   await page.evaluate(code=>FiltrI18n.setLanguage(code),code);
   await page.waitForTimeout(80);
   assert.ok(await page.evaluate(()=>originalInput===document.querySelector('#filtr-annotate-controls input[type=text]')));
   assert.equal(await page.evaluate(()=>originalInput.selectionStart),2);assert.equal(await page.evaluate(()=>originalInput.selectionEnd),7);
   assert.ok(await page.evaluate(()=>originalControls.every((el,i)=>el===document.querySelectorAll('#filtr-fx-controls input,#filtr-fx-controls select')[i])));
   assert.ok(await page.evaluate(()=>originalCanvas===document.getElementById('filtr-canvas')));
   assert.ok(await page.evaluate(()=>savedState===JSON.stringify({settings:FILTR.settings,ann:FILTR.ann,hist:FILTR.hist,fut:FILTR.fut,crop:FILTR.crop,tool:FILTR.tool,sel:FILTR.sel,zoom:FILTR.zoom,panX:FILTR.panX,panY:FILTR.panY,name:FILTR.name,full:FILTR.full.toDataURL()})),code+' changed edits/history/view');
   assert.equal(await page.evaluate(()=>FSHEET_ACTIVE),'annotate');
   assert.equal(await page.locator('#filtr-overlay img').count(),0);
   assert.match(await page.locator('#filtr-meta').textContent(),/Crop <img> 中文 \{name\}/);
   const applyBox=await page.locator('#filtr-apply').boundingBox();
   assert.ok(applyBox.x>=0 && applyBox.x+applyBox.width<=390,'Apply is clipped in '+code);
   assert.equal(await page.locator('#filtr-undo').getAttribute('aria-label'),await page.evaluate(()=>FiltrI18n.t('filtr.ui.undo')));
   await page.screenshot({path:path.join(screenshots,'filtr-'+code+'.png')});
  }
  // UI selection still writes the original enum, not its translated label.
  await page.evaluate(()=>{
   const select=document.querySelector('#filtr-fx-controls select');select.value='atkinson';select.dispatchEvent(new Event('change'));
   if(FILTR.settings.dithering.method!=='atkinson')throw Error('Translated enum written into engine settings');
   filtrStatus('filtr.ui.baking');FiltrI18n.setLanguage('ru');
   if(document.getElementById('filtr-status').textContent!=='Применение эффекта…')throw Error('Status did not translate');
   filtrStatus('');FiltrI18n.setLanguage('en');if(document.getElementById('filtr-status').textContent!=='')throw Error('Cleared status returned');
  });
  await page.setViewportSize({width:1280,height:900});await page.waitForTimeout(150);
  assert.equal(await page.evaluate(()=>FSHEET_ON),false);
  await page.evaluate(()=>FiltrI18n.setLanguage('ru'));await page.screenshot({path:path.join(screenshots,'filtr-desktop-ru.png')});
  assert.deepEqual(errors,[]);
  assert.deepEqual(await page.evaluate(()=>nativeCalls),[],'Language changes must not export, mint or save');
  // Export uses real canvas/Blob/FileReader; the mocked Android bridge only records the bytes.
  await page.evaluate(()=>{FILTR.name='作品 Русский Українська.png';filtrDownload(false);filtrDownload(false);FILTR.name='New artwork.png';});
  await page.waitForFunction(()=>nativeCalls.some(c=>c[0]==='save'));
  assert.equal(await page.evaluate(()=>nativeCalls.filter(c=>c[0]==='save').length),1);
  assert.equal(await page.locator('#filtr-save-png').isDisabled(),true);
  const exportCall=await page.evaluate(()=>nativeCalls.find(c=>c[0]==='save'));
  assert.equal(exportCall[1],'作品 Русский Українська-filtr.png');
  assert.ok(exportCall[2].startsWith('iVBOR'),'PNG bytes changed at native boundary');
  for(const code of ['en','zh-Hans','ru','uk']) {
   await page.evaluate(code=>FiltrI18n.setLanguage(code),code);
   assert.equal(await page.locator('#filtr-status').textContent(),await page.evaluate(()=>FiltrI18n.t('filtr.ui.saving')));
  }
  await page.evaluate(()=>filtrSaveFinished(true));
  assert.equal(await page.locator('#filtr-save-png').isDisabled(),false);
  assert.equal(await page.locator('#filtr-status').textContent(),await page.evaluate(()=>FiltrI18n.t('filtr.ui.saved')));
  await page.evaluate(()=>filtrSaveFinished(false));
  assert.equal(await page.locator('#filtr-status').textContent(),await page.evaluate(()=>FiltrI18n.t('filtr.ui.exportFailed')));
  await page.evaluate(()=>{const original=filtrExport;try{filtrExport=()=>{throw Error('renderer unavailable');};filtrDownload(false);}finally{filtrExport=original;}});
  assert.equal(await page.locator('#filtr-save-png').isDisabled(),false);
  assert.equal(await page.evaluate(()=>FILTR_SAVE_PENDING),false);
  assert.equal(await page.evaluate(()=>nativeCalls.filter(c=>c[0]==='save').length),1);
  assert.deepEqual(errors,[]);
  console.log('PASS: four languages preserve editor state; real PNG export preserves captured Unicode filename, prevents duplicates, recovers after render failure and waits for native save result');
  await context.close();
 } finally {await browser.close();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;server.closeAllConnections();server.close();});
