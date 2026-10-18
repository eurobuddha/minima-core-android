// Reuse FILTR's real-browser fixture: isolated loopback content and installed Chrome.
const {chromium}=require(process.env.PANDAMONIUM_PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),http=require('node:http');
const root=path.resolve(__dirname,'../..');
const java=fs.readFileSync(path.join(root,'integrated/salon/src/main/java/com/eurobuddha/salon/MainActivity.java'),'utf8');
const template=JSON.parse(java.match(/private static final String SALON_HTML = ("[^\n]*");/)[1]);
const catalog=JSON.parse(fs.readFileSync(path.join(root,'localization/catalog.json'),'utf8'));
const shots=path.join(root,'build/localization/salon-page-screenshots');fs.mkdirSync(shots,{recursive:true});
const literal='作品 Русский Українська <img src=x onerror="window.injected=true"> {name}';
const profile={name:literal,handle:'artist',bio:'Gallery · Галерея · 画廊',about:'About is my literal text',banner:'image.png',avatar:'image.png',
 links:[{label:literal,url:'https://example.invalid/作品?q=one&x=two'},{label:'Unsafe',url:'java\nscript:window.injected=true'},{label:'Relative',url:'./relative'}],
 gallery:[{type:'image',url:'image.png'},{type:'audio',url:'sound.wav'},{type:'video',url:'movie.webm'}],posts:[{text:literal},{text:'Newest post',type:'image',media:'image.png'}]};
const before=JSON.stringify(profile);let requests=0,pendingProfile;
const wav=Buffer.alloc(44+16000);wav.write('RIFF');wav.writeUInt32LE(wav.length-8,4);wav.write('WAVEfmt ',8);wav.writeUInt32LE(16,16);wav.writeUInt16LE(1,20);wav.writeUInt16LE(1,22);wav.writeUInt32LE(8000,24);wav.writeUInt32LE(16000,28);wav.writeUInt16LE(2,32);wav.writeUInt16LE(16,34);wav.write('data',36);wav.writeUInt32LE(16000,40);
const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a2ioAAAAASUVORK5CYII=','base64');
const server=http.createServer((req,res)=>{
 const u=new URL(req.url,'http://localhost');
 if(u.pathname.endsWith('/profile.json')){requests++;res.setHeader('Content-Type','application/json');if(u.pathname.startsWith('/error/')){res.writeHead(404);res.end(JSON.stringify(profile));}else if(u.pathname.startsWith('/invalid/'))res.end('null');else if(u.pathname.startsWith('/slow/'))pendingProfile=res;else res.end(JSON.stringify(profile));return;}
 if(u.pathname.endsWith('/image.png')){res.setHeader('Content-Type','image/png');res.end(png);return;}
 if(u.pathname.endsWith('/sound.wav')){
  res.setHeader('Content-Type','audio/wav');res.setHeader('Accept-Ranges','bytes');
  const range=/^bytes=(\d+)-(\d*)$/.exec(req.headers.range||'');
  const start=range?Number(range[1]):0,end=range&&range[2]?Math.min(Number(range[2]),wav.length-1):wav.length-1;
  if(range){res.statusCode=206;res.setHeader('Content-Range',`bytes ${start}-${end}/${wav.length}`);}
  res.setHeader('Content-Length',end-start+1);res.end(wav.subarray(start,end+1));return;
 }
 if(u.pathname.endsWith('/movie.webm')){res.writeHead(404);res.end();return;}
 res.setHeader('Content-Type','text/html; charset=utf-8');res.end(template.replaceAll('__SALON_LANGUAGE__',u.searchParams.get('publisher')||'en'));
});
(async()=>{
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const browser=await chromium.launch({headless:true,...(process.env.PANDAMONIUM_CHROME?{executablePath:process.env.PANDAMONIUM_CHROME}:{})});
 try{
  const context=await browser.newContext({viewport:{width:390,height:844},locale:'uk-UA'}),page=await context.newPage(),errors=[];
  page.on('pageerror',e=>errors.push(e.message));await page.route('**/*',route=>new URL(route.request().url()).hostname==='127.0.0.1'?route.continue():route.abort());
  const origin='http://127.0.0.1:'+server.address().port;
  await page.goto(origin+'/index.html');await page.waitForSelector('h1');
  assert.equal(await page.locator('html').getAttribute('lang'),'en','Browser locale must not choose the language');
  assert.deepEqual(await page.locator('#salon-language option').evaluateAll(nodes=>nodes.map(n=>[n.value,n.textContent])),[['en','English'],['zh-Hans','简体中文'],['ru','Русский'],['uk','Українська']]);
  await page.waitForFunction(()=>{const a=document.querySelector('audio');return a.readyState>=2&&a.seekable.length&&a.seekable.end(0)>.25;});
  await page.evaluate(()=>{
   window.originalNodes=[...document.querySelectorAll('#b img,#b audio,#b video,#b a')];
   window.originalContent=[...document.querySelectorAll('h1,.bio,.post,a.l')].map(n=>n.textContent);
   const audio=document.querySelector('audio');audio.currentTime=.25;audio.volume=.3;audio.playbackRate=1.5;
  });
  await page.waitForFunction(()=>document.querySelector('audio').currentTime===.25&&!document.querySelector('audio').seeking);
  const startRequests=requests;
  for(const code of ['en','zh-Hans','ru','uk']){
   await page.selectOption('#salon-language',code);
   assert.equal(await page.locator('html').getAttribute('lang'),code);
   assert.equal(await page.locator('label[for=salon-language]').textContent(),catalog['language.title'][code]);
   assert.deepEqual(await page.locator('.k').allTextContents(),['salonSocial.about','creative.links','nftWallet.gallery','salonSocial.posts'].map(k=>catalog[k][code]));
   assert.equal(await page.locator('h1').textContent(),literal);assert.equal(await page.locator('h1 img').count(),0);
   assert.equal(await page.locator('a.l').nth(1).getAttribute('href'),'');assert.equal(await page.locator('a.l').nth(2).getAttribute('href'),'./relative');
   assert.ok(await page.evaluate(()=>originalNodes.every((n,i)=>n===document.querySelectorAll('#b img,#b audio,#b video,#b a')[i])),'Language change replaced media/links');
   assert.ok(await page.evaluate(()=>JSON.stringify(originalContent)===JSON.stringify([...document.querySelectorAll('h1,.bio,.post,a.l')].map(n=>n.textContent))));
   assert.deepEqual(await page.evaluate(()=>{const a=document.querySelector('audio');return [a.currentTime,a.volume,a.playbackRate,a.paused]}),[.25,.3,1.5,true]);
   assert.equal(await page.evaluate(()=>window.injected),undefined);
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Horizontal overflow in '+code);
   await page.screenshot({path:path.join(shots,'salon-page-'+code+'.png')});
  }
  assert.equal(requests,startRequests,'Language changes refetched the profile');
  await page.reload();await page.waitForSelector('h1');assert.equal(await page.locator('html').getAttribute('lang'),'uk','Manual choice was not restored');
  await page.evaluate(()=>localStorage.removeItem('salon.language'));
  await page.goto(origin+'/index.html?publisher=ru');await page.waitForSelector('h1');assert.equal(await page.locator('html').getAttribute('lang'),'ru');
  await page.evaluate(()=>localStorage.setItem('salon.language','unsupported'));
  await page.goto(origin+'/slow/index.html?publisher=zh-Hans');
  assert.equal(await page.locator('[data-i18n="salonPage.loading"]').textContent(),catalog['salonPage.loading']['zh-Hans']);
  await page.selectOption('#salon-language','uk');assert.equal(await page.locator('[data-i18n="salonPage.loading"]').textContent(),catalog['salonPage.loading'].uk);
  assert.ok(pendingProfile);pendingProfile.end(JSON.stringify(profile));pendingProfile=null;
  await page.waitForSelector('h1');assert.equal(await page.locator('.k').first().textContent(),catalog['salonSocial.about'].uk);
  for(const route of ['error','invalid']){
   await page.goto(origin+'/'+route+'/index.html');await page.waitForSelector('[data-i18n="salonPage.unavailable"]');
   for(const code of ['en','zh-Hans','ru','uk']){await page.selectOption('#salon-language',code);assert.equal(await page.locator('#b').textContent(),catalog['salonPage.unavailable'][code]);}
   assert.equal(await page.locator('h1').count(),0);
  }
  const blocked=await context.newPage();await blocked.addInitScript(()=>Object.defineProperty(window,'localStorage',{get(){throw new Error('Storage unavailable')}}));
  await blocked.goto(origin+'/index.html?publisher=zh-Hans');await blocked.waitForSelector('h1');await blocked.selectOption('#salon-language','ru');assert.equal(await blocked.locator('html').getAttribute('lang'),'ru');await blocked.close();
  await page.goto(origin+'/index.html');await page.waitForSelector('h1');await page.setViewportSize({width:1280,height:900});await page.selectOption('#salon-language','ru');await page.screenshot({path:path.join(shots,'salon-page-desktop-ru.png')});
  assert.deepEqual(errors,[]);assert.equal(JSON.stringify(profile),before);
  console.log('PASS: four explicit languages, publisher default, saved/blocked storage, retained media state and literal content, safe URLs, late response, HTTP/malformed errors, mobile/desktop layout');
  await context.close();
 }finally{await browser.close();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;server.closeAllConnections();server.close();});
