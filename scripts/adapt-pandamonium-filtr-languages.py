"""FILTR display-only bindings. Keep the engine, settings keys and user annotations literal."""
from pathlib import Path
import json,re,shutil

def adapt(root):
    project=Path(__file__).resolve().parents[1]
    assets=root/'atelier/src/main/assets/filtr'
    def change(text,old,new):
        assert old in text, ('FILTR source drift',old)
        return text.replace(old,new)
    # Retained-DOM binding functions reused from Parlons panel/i18n.js; shared selector owns choice.
    shutil.copy2(project/'scripts/pandamonium-sources/atelier/filtr-i18n.js',assets/'filtr-i18n.js')
    catalog=json.loads((project/'localization/catalog.json').read_text())
    messages={code:{key:value[code] for key,value in catalog.items() if key.startswith('filtr.')} for code in ['en','zh-Hans','ru','uk']}
    (assets/'filtr-messages.js').write_text('window.FiltrMessages = '+json.dumps(messages,ensure_ascii=False,separators=(',',':'))+';\n')
    p=assets/'filtr.html';s=p.read_text()
    ids={'filtr-drop-hint':'dropHint','filtr-meta':'noImage','filtr-tool-crop':'crop','filtr-fliph':'flipH','filtr-flipv':'flipV','filtr-tool-erase':'erase','filtr-resize':'resize','filtr-tool-bubble':'bubble','filtr-tool-text':'text','filtr-undo':'undo','filtr-redo':'redo','filtr-fit':'fit','filtr-apply':'apply','filtr-fx-name':'effect','filtr-save-mint':'sendMint','filtr-save-photo':'sendPhoto','filtr-save-png':'downloadPng','filtr-save-jpg':'downloadJpg','filtr-sheet-title':'adjust'}
    for ident,key in ids.items():
        pattern=r'(<(?:button|span|p|h3)\b[^>]*\bid="'+re.escape(ident)+r'"[^>]*)(>)'
        s,n=re.subn(pattern,lambda m:m[1]+' data-i18n="filtr.ui.'+key+'"'+m[2],s)
        assert n==1,ident
    for literal,key in [('Import','import'),('Effects','effects'),('Presets','presets')]:
        s=change(s,'<p class="wizard-kick">'+literal+'</p>','<p class="wizard-kick" data-i18n="filtr.ui.'+key+'">'+literal+'</p>')
    for literal,key in [('Adjustments','adjustments'),('Post FX','post'),('Annotate','annotate'),('Save','save')]:
        s=change(s,'<h4>'+literal+'<span','<h4><span data-i18n="filtr.ui.'+key+'">'+literal+'</span><span')
    for literal,key in [('Import','import'),('Transform','transform'),('Effects','effects'),('Presets','presets'),('Adjust','adjust'),('Post FX','post'),('Annotate','annotate'),('Save','save')]:
        s=change(s,'<span class="cl">'+literal+'</span>','<span class="cl" data-i18n="filtr.ui.'+key+'">'+literal+'</span>')
    for cls,key in [('fe-title','emptyTitle'),('fe-sub','emptyHelp'),('cert-note','saveHelp')]:
        s=change(s,'class="'+cls+'"','class="'+cls+'" data-i18n="filtr.ui.'+key+'"')
    s=change(s,'<p><b>FILTR is installed','<p data-i18n="filtr.ui.unsupported"><b>FILTR is installed')
    for literal,key in [('rotate left','rotateLeft'),('rotate right','rotateRight'),('hold to compare','compare'),('bake the current look into the image, then layer the next effect','applyHelp'),('expand','expand'),('close','close')]:
        s=change(s,'title="'+literal+'"','title="'+literal+'" data-i18n-title="filtr.ui.'+key+'" data-i18n-aria-label="filtr.ui.'+key+'"')
    s=change(s,'<script src="filtr-engine.js">','<script src="filtr-messages.js"></script>\n<script src="filtr-i18n.js"></script>\n<script src="filtr-engine.js">')
    s=change(s,'filtr-engine.js + filtr.js + styles.css are byte-identical copies\n     enforced by test/run.sh\'s bridge parity gate.', 'The engine stays byte-identical. Display bindings are adapted for the shared four-language selector.')
    for ident,key,glyph in [('filtr-undo','undo','↶'),('filtr-redo','redo','↷')]:
        s=change(s,'data-i18n="filtr.ui.'+key+'">'+key.title()+'</button>',
            'data-i18n-title="filtr.ui.'+key+'" data-i18n-aria-label="filtr.ui.'+key+'">'+glyph+'</button>')
    s=change(s,'  body { padding: 0.7rem; }', '  body { padding: 0.7rem; }\n  @media (max-width: 900px) {\n    .filtr-cat .cl { font-size: 0.5rem; line-height: 1.2; height: 2.4em;\n      white-space: normal; overflow-wrap: anywhere; text-overflow: clip; }\n    .filtr-topbar #filtr-undo, .filtr-topbar #filtr-redo { font-size: 1rem; line-height: 1; }\n  }')
    p.write_text(s)
    p=assets/'filtr.js';s=p.read_text()
    # All these literals occur only at explicit application-owned presentation sites.
    for literal,key in [('could not load that image','loadFailed'),('load an image first','loadFirst'),('edited image loaded into the NFT wizard','sentMint'),('edited image sent to the Photo Cartoon pack','sentPhoto')]:
        s=change(s,'toast("'+literal+'")','toast(FiltrI18n.t("filtr.ui.'+key+'"))')
    for literal,key in [('baking…','baking'),('bake failed','bakeFailed'),('applied — pick the next effect','applied'),('export failed','exportFailed'),('encode failed','encodeFailed'),('downloaded','downloaded'),('could not fit the artimage budget','budgetFailed')]:
        s=change(s,'filtrStatus("'+literal+'"','filtrStatus("filtr.ui.'+key+'"')
    s=change(s,'  el.innerText = msg || "";', '  if (msg) FiltrI18n.bind(el, msg); else FiltrI18n.text(el, "");')
    start=s.index('  $("filtr-meta").innerText =');end=s.index('\n}',start)
    s=s[:start]+"""  FiltrI18n.render($("filtr-meta"), function () {
    return FILTR.full ? FiltrI18n.t("filtr.ui.imageMeta", {name:FILTR.name,width:FILTR.full.width,height:FILTR.full.height}) +
      (FILTR.src.width !== FILTR.full.width ? FiltrI18n.t("filtr.ui.editingSize", {width:FILTR.src.width}) : "") : FiltrI18n.t("filtr.ui.noImage");
  });"""+s[end:]
    s=change(s,'  $("filtr-zoominfo").innerText = "zoom " + Math.round(FILTR.zoom * 100) + "%" +\n    (FILTR.fitted ? " · fit" : "");', '  FiltrI18n.render($("filtr-zoominfo"), function () { return FiltrI18n.t("filtr.ui.zoom", {percent:Math.round(FILTR.zoom * 100)}) + (FILTR.fitted ? FiltrI18n.t("filtr.ui.fitSuffix") : ""); });')
    s=change(s,'    b.innerText = label;', '    if (label.indexOf("filtr.") === 0) FiltrI18n.bind(b,label); else FiltrI18n.text(b,label);')
    s=change(s,'  function note(t) {','  function note(t, vars) {')
    s=change(s,'    s.innerText = t;','    FiltrI18n.bind(s,t,vars);')
    for literal,key in [('drag on the image, then','cropHelp'),('Apply crop','applyCrop'),('ratio:','ratio'),('Free','free'),('Soft','soft'),('Hard','hard'),('tap the image to place a bubble','bubbleHelp'),('tap the image to place text','textHelp'),('pan: drag · zoom: wheel / pinch','panHelp')]:
        s=change(s,'"'+literal+'"','"filtr.ui.'+key+'"')
    s=change(s,'note("paint to erase · brush " + FILTR.brush + "px")','note("filtr.ui.eraseHelp", {size:FILTR.brush})')
    s=change(s,'prompt("New long-edge size in px (64–" + FILTR_MAX_FULL + "):",','prompt(FiltrI18n.t("filtr.ui.resizePrompt", {max:FILTR_MAX_FULL}),')
    s=change(s,'prompt("Text:",','prompt(FiltrI18n.t("filtr.ui.textPrompt"),')
    s=change(s,'type === "text" ? "YOUR TEXT" : "WEN MOON?"','FiltrI18n.t(type === "text" ? "filtr.ui.defaultText" : "filtr.ui.defaultBubble")')
    s=change(s,'host.innerHTML = "<p class=\'filtr-note\'>use the Bubble or Text tool, or tap " +\n      "an annotation to edit it</p>";', 'host.innerHTML = "<p class=\'filtr-note\'></p>";\n    FiltrI18n.bind(host.firstChild,"filtr.ui.annotationHelp");')
    # Two separate row builders: annotation labels and setting labels.
    s=change(s,'    l.innerText = label;', '    FiltrI18n.bind(l,"filtr.annotation."+label);\n    FiltrI18n.attribute(el,"aria-label","filtr.annotation."+label);')
    s=change(s,'row(a.type === "text" ? "text color" : "fill",','row(a.type === "text" ? "textColor" : "fill",')
    for var in ['tp','fn']:
        s=change(s,'s.innerText = '+var+';','FiltrI18n.bind(s,"filtr.annotation."+'+var+');')
    s=change(s,'del.innerText = "Delete annotation";','FiltrI18n.bind(del,"filtr.ui.deleteAnnotation");')
    s=change(s,'li.innerText = fx.label;','FiltrI18n.bind(li,"filtr.effect."+fx.id);')
    s=change(s,'$("filtr-fx-name").innerText = fx.label;','FiltrI18n.bind($("filtr-fx-name"),"filtr.effect."+fx.id);')
    s=change(s,'  l.innerText = label;','  FiltrI18n.bind(l,"filtr.control."+label);\n  FiltrI18n.attribute(el,"aria-label","filtr.control."+label);')
    s=change(s,'var label = cdef.lb || cdef.k.replace(/([A-Z])/g, " $1").toLowerCase();','var label = cdef.k;')
    s=change(s,'s.innerText = "" + (cdef.lo ? cdef.lo[oi] : opt);','FiltrI18n.bind(s,"filtr.option."+(cdef.lo ? cdef.lo[oi] : opt));')
    s=change(s,'op.text = "" + cdef.o[o2];','FiltrI18n.bind(op,"filtr.option."+cdef.o[o2]);')
    s=change(s,'host.innerHTML = "<p class=\'filtr-note\'>no effect — geometry, adjustments, " +\n      "annotations and post fx still apply</p>";', 'host.innerHTML = "<p class=\'filtr-note\'></p>";\n    FiltrI18n.bind(host.firstChild,"filtr.ui.noEffectHelp");')
    s=change(s,'lab.innerText = pd.id.replace(/([A-Z])/g, " $1").toLowerCase();','FiltrI18n.bind(lab,"filtr.post."+pd.id);')
    s=change(s,'chk.checked = !!p.enabled;','chk.checked = !!p.enabled;\n      FiltrI18n.attribute(chk,"aria-label","filtr.post."+pd.id);')
    s=change(s,'s.innerText = col;','FiltrI18n.bind(s,"filtr.option."+col);')
    s=change(s,'b.innerText = p.name;','FiltrI18n.bind(b,"filtr.preset."+p.id.replace(/-/g,""));')
    s=change(s,'toast(p.name);','toast(FiltrI18n.t("filtr.preset."+p.id.replace(/-/g,"")));')
    p.write_text(s)
    p=assets/'filtr-sheet.js';s=p.read_text()
    s=change(s,'fsheetEl("filtr-sheet-title").textContent = FSHEET_TITLES[cat] || "";','FiltrI18n.bind(fsheetEl("filtr-sheet-title"),"filtr.ui."+cat);')
    s=change(s,'if ((meta.textContent || "").indexOf("no image") === -1) {','if (FILTR.full) {')
    p.write_text(s)
    p=root/'atelier/src/main/java/com/eurobuddha/statenft/FiltrActivity.java';s=p.read_text()
    s=change(s,'    private ValueCallback<Uri[]> filePick;', '    private ValueCallback<Uri[]> filePick;\n    private WebView web;\n    private final Runnable languageChanged = this::refreshLanguage;')
    s=change(s,'WebView web = new WebView(this);','web = new WebView(this);')
    s=change(s,'        web.addJavascriptInterface', """        web.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) { refreshLanguage(); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return !"file:///android_asset/filtr/filtr.html".equals(request.getUrl().toString());
            }
        });
        Language.addListener(languageChanged);
        web.addJavascriptInterface""")
    s=change(s,'    private class Bridge {', """    private void refreshLanguage() {
        if (web != null && "file:///android_asset/filtr/filtr.html".equals(web.getUrl())) {
            web.evaluateJavascript("window.FiltrI18n && FiltrI18n.setLanguage(" +
                    org.json.JSONObject.quote(Language.code()) + ");", null);
        }
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        refreshLanguage();
    }

    @Override protected void onResume() { super.onResume(); refreshLanguage(); }

    @Override protected void onDestroy() {
        Language.removeListener(languageChanged);
        if (filePick != null) { filePick.onReceiveValue(null); filePick = null; }
        if (web != null) { web.removeJavascriptInterface("AndroidFiltr"); web.destroy(); web = null; }
        super.onDestroy();
    }

    private class Bridge {
        @JavascriptInterface public String getLanguage() { return Language.code(); }
""")
    s=change(s,'engine bundle, tab UI and styles are byte-identical copies, parity-gated','engine bundle is unchanged; display labels follow the shared selector')
    p.write_text(s)
