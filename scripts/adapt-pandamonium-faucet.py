"""Embed the pinned native Faucet screen; reuse direct transport and live language bindings."""
from pathlib import Path
import json,re,shutil,xml.etree.ElementTree as E
A='{http://schemas.android.com/apk/res/android}'
def adapt(root):
 project=Path(__file__).resolve().parents[1]
 p=root/'faucet/src/main/java/com/eurobuddha/faucet/MainActivity.java';s=p.read_text()
 def change(old,new,count=1):
  nonlocal s
  assert s.count(old)==count,('Faucet source drift',old,s.count(old));s=s.replace(old,new)
 change('package com.eurobuddha.faucet;','package com.eurobuddha.faucet;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
 change('    private DirectNodeApi mApi;', '    private DirectNodeApi mApi;\n    private volatile boolean mDestroyed;')
 change('        final View mainRoot = findViewById(R.id.pm_faucet_main);','        final View mainRoot = findViewById(R.id.pm_faucet_main);\n        Language.apply(mainRoot);')
 # Host navigation already handles system bars for every integrated app.
 a=s.index('        ViewCompat.setOnApplyWindowInsetsListener(');z=s.index('        // Construct the API',a)
 s=s[:a]+s[z:]
 change('                final boolean enabled = zResponse.optBoolean("enabled", false);','                final boolean enabled = zResponse != null && zResponse.optBoolean("enabled", false);')
 change('mMain.post(() -> {','mMain.post(() -> {\n                    if (mDestroyed) return;',3)
 a=s.index('        // If the node isn\'t installed');z=s.index('\n    }',a);s=s[:a]+s[z:]
 change('        super.onDestroy();','        mDestroyed = true;\n        mMain.removeCallbacksAndMessages(null);\n        super.onDestroy();')
 change('    private void getMyAddress() {','    private void getMyAddress() {\n        if (mDestroyed) return;')
 change('                if (!addr.isEmpty()) {','                if (Boolean.TRUE.equals(json.opt("status")) && !json.optBoolean("pending",false)\n                        && addr.matches("^(0x|Mx)[0-9A-Za-z]+$")) {')
 change('        if (addr.isEmpty()) {','        if (!addr.matches("^(0x|Mx)[0-9A-Za-z]+$")) {')
 change('    private void requestMinima() {','    private void requestMinima() {\n        if (mDestroyed) return;')
 change('            String body = null;','            String body = null;\n            HttpURLConnection conn = null;\n            int code = -1;')
 change('                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();','                conn = (HttpURLConnection) new URL(url).openConnection();\n                conn.setInstanceFollowRedirects(false);')
 change('                int code = conn.getResponseCode();','                code = conn.getResponseCode();')
 change('                conn.disconnect();','')
 change('                body = null;\n            }','                body = null;\n            } finally { if (conn != null) conn.disconnect(); }')
 change('            final String result = body;','            final String result = body;\n            final int statusCode = code;')
 change('                    boolean ok = data.optBoolean("status", false);','                    boolean ok = statusCode >= 200 && statusCode < 300 && Boolean.TRUE.equals(data.opt("status"));')
 # Bound remote output using the desktop faucet implementation's 64KiB limit.
 a=s.index('        BufferedReader reader =');z=s.index('\n    }',a)
 s=s[:a]+'''        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); InputStream stream = in) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = stream.read(buffer)) != -1) {
                if (out.size() + count > 65536) throw new java.io.IOException("Faucet response too large");
                out.write(buffer,0,count);
            }
            return out.toString("UTF-8");
        }'''+s[z:]
 change('            if (!done[0]) {','            if (!mDestroyed && !done[0]) {')
 change('        return !json.optBoolean("enabled", true);','        return json == null || !json.optBoolean("enabled", true);')
 a=s.index('    private void openMinimaCore() {');z=s.index('    // ----- Status box',a)
 s=s[:a]+'''    private void openMinimaCore() {
        startActivity(new Intent().setClassName(getPackageName(),"com.eurobuddha.minimacore.main.MainActivity"));
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
    }

'''+s[z:]
 change('private void showStatus(String msg, boolean success)', 'private void showStatus(CharSequence msg, boolean success)')
 # Live descriptors retain selected language during requests and configuration changes.
 catalog=json.loads((project/'localization/catalog.json').read_text())
 def resource(k):return 'l10n_faucet_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',k).lower()
 for key in ['fetching','getAddress','badAddress','enterAddress','requesting','request','failed']:
  literal=json.dumps(catalog['faucet.'+key]['en'],ensure_ascii=False)
  assert literal in s,(key,literal)
  s=s.replace(literal,'Language.msg('+resource(key)+')')
 s=s.replace('"Minima Core didn\'t respond. Is it installed and running?"','Language.msg(l10n_faucet_node_timeout)')
 change('data.optString("message", "Unexpected response from faucet.")','data.optString("message", "")')
 change('                    showStatus(msg, ok);','                    showStatus(msg.isEmpty() ? Language.msg(l10n_faucet_unexpected)\n                            : Language.msg(l10n_faucet_response,msg), ok);')
 for v in ['mGetAddrBtn','mRequestBtn','mStatus']:
  s=s.replace(v+'.setText(', 'Language.setText('+v+', ')
 s=s.replace('Talks to the local Minima Core node over the native broadcast-Intent IPC API','Talks directly to the embedded Minima Core node')
 s=s.replace('Construct the API - this auto-registers us with the node (broadcast).','Construct the in-process API for embedded-node readiness.')
 s=s.replace('Callback arrives on the broadcast-receiver thread - marshal to UI thread.','Marshal the direct callback to the UI thread.')
 s=s.replace('import android.widget.Toast;\n','').replace('import androidx.core.graphics.Insets;\n','').replace('import androidx.core.view.ViewCompat;\n','').replace('import androidx.core.view.WindowInsetsCompat;\n','')
 s=re.sub(r'    private static final String NODE_PKG[^\n]+\n','',s)
 p.write_text(s)
 p=root/'faucet/src/main/res/layout/pm_faucet_activity_main.xml';tree=E.parse(p);top=tree.getroot();body=list(top)[0]
 body.attrib.pop(A+'tag',None)
 mapping={catalog['faucet.'+k]['en']:resource(k) for k in ['openNode','brand','heading','intro','address','hint','getAddress','request','help','footer']}
 mapping['This app is not enabled yet']=resource('nodeNotReady')
 mapping['The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.']=resource('nodeHelp')
 for v in top.iter():
  for prop in ['text','hint']:
   raw=v.get(A+prop)
   if raw in mapping:v.set(A+prop,'@string/'+mapping[raw]);v.set(A+'tag','l10n:'+prop+'='+mapping[raw])
  if v.tag in ('Button','EditText'):v.set(A+'minHeight','48dp')
 header=E.Element('LinearLayout',{A+'layout_width':'match_parent',A+'layout_height':'wrap_content',A+'orientation':'horizontal',A+'gravity':'center_vertical',A+'tag':'pandamonium.app.toolbar'})
 title=next(v for v in body if v.get(A+'text')=='@string/'+resource('brand'));body.remove(title)
 title.set(A+'layout_width','0dp');title.set(A+'layout_weight','1');title.set(A+'layout_marginTop','0dp');title.set(A+'gravity','center');header.append(title);body.insert(0,header)
 E.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)
 p=root/'faucet/src/main/AndroidManifest.xml';tree=E.parse(p)
 for query in tree.getroot().findall('queries'):tree.getroot().remove(query)
 E.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)
 p=root/'faucet/build.gradle';s=p.read_text().replace('    buildFeatures {','    testOptions { unitTests.returnDefaultValues = true }\n    buildFeatures {').replace('dependencies {',"dependencies {\n    testImplementation 'org.mockito:mockito-core:5.12.0'")
 p.write_text(s)
 tests=project/'scripts/pandamonium-tests/faucet'
 if tests.exists():shutil.copytree(tests,root/'faucet',dirs_exist_ok=True)

 # Imported XML comments can leave whitespace-only lines; retain content with clean line endings.
 for resource_file in (root/'faucet/src/main/res').rglob('*.xml'):
  resource_file.write_text('\n'.join(line.rstrip() for line in resource_file.read_text().splitlines())+'\n')
