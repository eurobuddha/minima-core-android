"""Translate Vestr display bindings; preserve its contract model, maths and command construction."""
from pathlib import Path
import json,re,importlib.util,xml.etree.ElementTree as E

def adapt(root):
 project=Path(__file__).resolve().parents[1];labels=json.loads((project/'localization/vestr-labels.json').read_text());catalog=json.loads((project/'localization/catalog.json').read_text());folder=root/'vestr/src/main/java/com/eurobuddha/vestr'
 def res(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[key]).replace('.','_').lower()
 def msg(key,*args):return 'Language.msg('+res(key)+('' if not args else ', '+', '.join(args))+')'
 def change(s,old,new):
  assert old in s,('Vestr display source drift',old)
  return s.replace(old,new)
 def fixed(s,keys):
  for key in keys:s=change(s,json.dumps(catalog[labels[key]]['en'],ensure_ascii=False),msg(key))
  return s
 def load(name):
  p=folder/(name+'.java');s=p.read_text();return p,s.replace('package com.eurobuddha.vestr;','package com.eurobuddha.vestr;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
 def save(p,s):
  if p.stem=='MainActivity':s=change(s,'Toast.makeText(this, message, Toast.LENGTH_SHORT)','Toast.makeText(this, VestrMessages.message(message), Toast.LENGTH_SHORT)')
  if p.stem in ['CreateContractActivity','ContractDetailActivity']:s=change(s,'Language.msg(l10n_explorer_node_retry) : m','Language.msg(l10n_explorer_node_retry) : VestrMessages.message(m)')
  s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*(?:\.[A-Za-z_][A-Za-z_0-9]*)*)\.setText\(',r'Language.setText(\1, ',s)
  s=s.replace("Language.setText(Language, ", "Language.setText(")
  p.write_text(s)
 p,s=load('MainActivity');s=fixed(s,['creator','collector','about']);s=s.replace('new String[]{Language.msg','new CharSequence[]{Language.msg').replace('String[] labels =','CharSequence[] labels =')
 s=change(s,'"Minima Core is not installed."','Language.msg(l10n_filez_no_core)')
 s=change(s,'setContentView(R.layout.pm_vestr_activity_main);','setContentView(R.layout.pm_vestr_activity_main);\n        Language.apply(findViewById(R.id.pm_vestr_main));')
 s=change(s,'    static final String NODE_PKG','    @Override public void onConfigurationChanged(android.content.res.Configuration config) {\n        super.onConfigurationChanged(config);\n        Language.configurationChanged(this);\n    }\n\n    static final String NODE_PKG');save(p,s)
 p=folder/'MainPager.java';s=p.read_text().replace('String[] titles','CharSequence[] titles');p.write_text(s)
 p,s=load('AboutView');s=change(s,'"Lock tokens in a vesting contract that releases gradually over time. "\n                + "Create a contract, track it, and withdraw what has vested."',msg('intro'));s=fixed(s,['create','collectFrom','footer']);s=s.replace('cta(String text','cta(CharSequence text');save(p,s)
 p,s=load('CreatorView');s=fixed(s,['createAction','calculateAction','myContracts','noContracts']);s=s.replace('button(String text','button(CharSequence text');save(p,s)
 p,s=load('CollectorView');s=fixed(s,['collect','collectHelp','noCollect']);save(p,s)
 p,s=load('SubActivity')
 for name,param in [('title','t'),('label','text'),('input','hint'),('pickerButton','initial'),('primaryButton','text')]:s=change(s,name+'(String '+param,name+'(CharSequence '+param)
 s=change(s,'setStatus(TextView t, String msg','setStatus(TextView t, CharSequence msg')
 s=change(s,'((TextView) findViewById(R.id.pm_vestr_subTitle)).setText(t);','Language.setText((TextView) findViewById(R.id.pm_vestr_subTitle), t);')
 s=change(s,'e.setHint(hint);','Language.hint(e, hint);')
 s=change(s,'final View root = findViewById(R.id.pm_vestr_subRoot);','final View root = findViewById(R.id.pm_vestr_subRoot);\n        Language.apply(root);')
 s=change(s,'@Override protected void onDestroy() { super.onDestroy();','@Override protected void onDestroy() { for (android.app.AlertDialog d : new java.util.ArrayList<>(languagePickers.keySet())) d.dismiss(); languagePickers.clear(); super.onDestroy();')
 s=change(s,'    protected NodeApi node;', '''    // Android caches the calendar grid locale. Recreate its window using the platform's
    // saved picker state, keeping callbacks and unconfirmed values without submitting them.
    private final java.util.Map<android.app.AlertDialog, java.util.function.Supplier<? extends android.app.AlertDialog>> languagePickers = new java.util.LinkedHashMap<>();
    protected <T extends android.app.AlertDialog> T languagePicker(java.util.function.Supplier<T> factory) {
        T picker = factory.get();
        languagePickers.put(picker, factory);
        picker.setOnDismissListener(d -> languagePickers.remove(picker));
        picker.setOnShowListener(d -> {
            Language.setText(picker.getButton(android.app.AlertDialog.BUTTON_POSITIVE), Language.msg(android.R.string.ok));
            Language.setText(picker.getButton(android.app.AlertDialog.BUTTON_NEGATIVE), Language.msg(android.R.string.cancel));
        });
        return picker;
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        for (java.util.Map.Entry<android.app.AlertDialog, java.util.function.Supplier<? extends android.app.AlertDialog>> entry : new java.util.ArrayList<>(languagePickers.entrySet())) {
            android.app.AlertDialog old = entry.getKey();
            Bundle state = old.onSaveInstanceState();
            old.setOnDismissListener(null);
            old.dismiss();
            languagePickers.remove(old);
            android.app.AlertDialog replacement = languagePicker(entry.getValue());
            replacement.onRestoreInstanceState(state);
        }
    }
    protected NodeApi node;''');save(p,s)
 for name in ['CalculatorActivity','CreateContractActivity']:
  p,s=load(name)
  s=change(s,'    private final SimpleDateFormat fmt = new SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.ENGLISH);','')
  s=change(s,'fmt.format(ms)','Language.stamp(ms, "yMMMdHm")')
  if name=='CalculatorActivity':
   s=fixed(s,['calculateTitle','amount','start','end','pickStart','pickEnd','grace','calculate','validAmount','pickBoth','endAfter','syncing'])
   s=change(s,'"Released per " + grace.label + ": " + ContractUi.amount(perGrace)',msg('released','VestrText.grace(grace)','ContractUi.amount(perGrace)'))
   s=change(s,'List<String> g =','List<CharSequence> g =');s=change(s,'g.add(gr.label)','g.add(VestrText.grace(gr))')
   s=change(s,'ArrayAdapter<String> ga = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, g);','ArrayAdapter<CharSequence> ga = VestrText.choices(this, g);')
  else:
   s=change(s,'j.optString("error", "Could not create the contract.")','j.has("error") ? j.optString("error") : '+msg('createFailed'))
   s=fixed(s,['create','token','withdrawal','addressHint','amount','start','end','pickStart','pickEnd','graceHelp','burn','passwordHelp','password','reviewCreate','validAddress','validAmount','positive','startRequired','endRequired','futureStart','endAfter','syncingTip','validating','invalidAddress','validateFailed','creating','pending','created'])
   s=change(s,'List<String> graces =','List<CharSequence> graces =');s=change(s,'graces.add(g.label)','graces.add(VestrText.grace(g))')
   s=change(s,'ArrayAdapter<String> ga = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, graces);','ArrayAdapter<CharSequence> ga = VestrText.choices(this, graces);')
   s=change(s,'List<String> tokenNames','List<CharSequence> tokenNames')
   s=change(s,'private ArrayAdapter<String> tokenAdapter()','private ArrayAdapter<CharSequence> tokenAdapter()')
   s=change(s,'ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, tokenNames);','ArrayAdapter<CharSequence> a = VestrText.choices(this, tokenNames);')
   s=change(s,'String name = Util.isMinima(tid) ? "Minima" : Util.tokenName(b.opt("token"), tid);','CharSequence name = VestrText.token(b.opt("token"), tid);')
   s=change(s,'tokenNames.add(name + "  (" + Util.tidyAmount(b.optString("sendable", "0")) + ")");','tokenNames.add(Language.join(name, "  (", Util.tidyAmount(b.optString("sendable", "0")), ")"));')
   s=change(s,'"The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry."','Language.msg(l10n_explorer_node_retry)')
  s=change(s,'new DatePickerDialog(this,','languagePicker(() -> new DatePickerDialog(this,');s=change(s,'new TimePickerDialog(this,','languagePicker(() -> new TimePickerDialog(this,')
  s=change(s,'c.get(Calendar.MINUTE), true).show();','c.get(Calendar.MINUTE), true)).show();')
  s=change(s,'c.get(Calendar.DAY_OF_MONTH)).show();','c.get(Calendar.DAY_OF_MONTH))).show();')
  save(p,s)
 p,s=load('ContractUi')
 s=change(s,'"Contract " + shortHex(c.uid)',msg('contractUid','shortHex(c.uid)'));s=change(s,'amount(c.amount) + " " + c.tokenName','Language.join(amount(c.amount), " ", VestrText.token(c.raw.opt("token"), c.tokenid))')
 s=change(s,'"Collect " + grace(c.graceHours)',msg('collectCadence','grace(c.graceHours)'))
 s=fixed(s,['totalVested','collected'])
 s=change(s,'MainActivity act, String k, String v','MainActivity act, CharSequence k, String v');s=change(s,'k + ": " + v','Language.join(k, ": ", v)')
 s=change(s,'MainActivity act, String s, int color','MainActivity act, CharSequence s, int color')
 s=change(s,'static String grace(int hours)','static CharSequence grace(int hours)');s=change(s,'VestingContract.Grace.fromHours(hours).label','VestrText.grace(VestingContract.Grace.fromHours(hours))')
 start=s.index('    static String relative(');end=s.index('\n    /** Convert',start)
 s=s[:start]+'''    static CharSequence relative(long whenMs, String verb) {
        if (whenMs <= 0) return "";
        long diff = whenMs - System.currentTimeMillis();
        boolean past = diff < 0;
        long a = Math.abs(diff);
        long days = TimeUnit.MILLISECONDS.toDays(a), hours = TimeUnit.MILLISECONDS.toHours(a), mins = TimeUnit.MILLISECONDS.toMinutes(a);
        int count = (int)Math.min(Integer.MAX_VALUE, days > 0 ? days : hours > 0 ? hours : mins);
        int unit = days > 0 ? com.eurobuddha.minimacore.i18n.R.plurals.l10n_vestr_days
                : hours > 0 ? com.eurobuddha.minimacore.i18n.R.plurals.l10n_vestr_hours : com.eurobuddha.minimacore.i18n.R.plurals.l10n_vestr_minutes;
        return Language.msg("start".equals(verb) ? (past ? l10n_vestr_started_ago : l10n_vestr_starts_in)
                : (past ? l10n_vestr_ended_ago : l10n_vestr_ends_in), Language.count(unit, count));
    }
''' +s[end:];save(p,s)
 p,s=load('ContractDetailActivity')
 s=change(s,'gr + " blocks"','Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_vestr_blocks, (int)Math.min(Integer.MAX_VALUE, gr))')
 s=change(s,'VestingContract.Grace.fromHours(c.graceHours).label','VestrText.grace(VestingContract.Grace.fromHours(c.graceHours))')
 s=change(s,'"Collect " + ContractUi.amount(canCollect) + " " + c.tokenName',msg('collectAmount','ContractUi.amount(canCollect)','VestrText.token(c.raw.opt("token"), c.tokenid)'))
 s=change(s,'(!mathsReady ? "…" : ContractUi.amount(canCollect)) + " " + c.tokenName','Language.join(!mathsReady ? "…" : ContractUi.amount(canCollect), " ", VestrText.token(c.raw.opt("token"), c.tokenid))')
 for amount in ['c.collected()','c.total','c.amount']:s=change(s,'ContractUi.amount('+amount+') + " " + c.tokenName','Language.join(ContractUi.amount('+amount+'), " ", VestrText.token(c.raw.opt("token"), c.tokenid))')
 s=change(s,'j.optString("error", "Collect failed")','j.has("error") ? j.optString("error") : '+msg('collectFailed'))
 s=change(s,'"Failed: " + msg',msg('failed','msg'))
 s=fixed(s,['contract','available','collected','totalVested','remaining','collectEvery','graceRemaining','ready','starts','ends','startBlock','endBlock','tokenId','coinId','withdrawal','contractId','networkFee','loading','nothing','copied','collecting','collectedOk'])
 s=change(s,'"The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry."','Language.msg(l10n_explorer_node_retry)')
 s=change(s,'addStat(String k, String v)','addStat(CharSequence k, CharSequence v)');s=change(s,'addCopy(String k, final String v)','addCopy(CharSequence k, final String v)');s=change(s,'fail(String msg, String id)','fail(CharSequence msg, String id)')
 # Bound the value column so long names/dates cannot consume the translated label column.
 s=change(s,'        vt.setTypeface(Typeface.DEFAULT_BOLD);\n        row.addView(vt);','        vt.setTypeface(Typeface.DEFAULT_BOLD);\n        vt.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));\n        vt.setGravity(Gravity.END);\n        row.addView(vt);')
 save(p,s)
 # This is the same bound-row adapter used by the core send screen, scoped to the library (no app dependency).
 helper='''package com.eurobuddha.vestr;
import android.content.Context;
import android.widget.ArrayAdapter;
import android.view.View;
import android.view.ViewGroup;
import org.json.JSONObject;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;
final class VestrText {
    private VestrText() {}
    static CharSequence grace(VestingContract.Grace grace) {
        int[] resources={l10n_vestr_none,l10n_vestr_daily,l10n_vestr_weekly,l10n_vestr_monthly,l10n_vestr_quarterly,l10n_vestr_halfyearly,l10n_vestr_yearly};
        return Language.msg(resources[grace.ordinal()]);
    }
    // Same source checks as Util.tokenName: preserve literal metadata, translate only its absent-name fallback.
    static CharSequence token(Object token, String id) {
        if(Util.isMinima(id))return "Minima";
        if(token instanceof String)return (String)token;
        if(token instanceof JSONObject) {
            Object name=((JSONObject)token).opt("name");
            if(name instanceof JSONObject) {
                JSONObject meta=(JSONObject)name;
                if(meta.has("name") && !meta.isNull("name"))return meta.optString("name");
            } else if(name instanceof String && !((String)name).isEmpty())return (String)name;
        }
        return Language.msg(l10n_vestr_token);
    }
    static ArrayAdapter<CharSequence> choices(Context context, java.util.List<CharSequence> values) {
        return new ArrayAdapter<CharSequence>(context, android.R.layout.simple_spinner_item, values) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                View row=super.getView(position,recycled,parent);
                Language.setText(row.findViewById(android.R.id.text1),getItem(position));return row;
            }
            @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
                View row=super.getDropDownView(position,recycled,parent);
                Language.setText(row.findViewById(android.R.id.text1),getItem(position));return row;
            }
        };
    }
}
'''
 (folder/'VestrText.java').write_text(helper)
 a='{http://schemas.android.com/apk/res/android}';E.register_namespace('android',a[1:-1])
 mapping={'vestr is not enabled yet':'l10n_vestr_not_enabled','The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.':'l10n_explorer_node_retry','Open Minima Core':'l10n_filez_open_core'}
 for name in ['activity_main','activity_sub']:
  p=root/f'vestr/src/main/res/layout/pm_vestr_{name}.xml';tree=E.parse(p)
  for v in tree.iter():
   raw=v.get(a+'text')
   if raw in mapping:v.set(a+'text','@string/'+mapping[raw]);v.set(a+'tag','l10n:text='+mapping[raw])
   if v.get(a+'id','').endswith('backBtn'):v.set(a+'contentDescription','@string/l10n_explorer_back');v.set(a+'tag','l10n:description=l10n_explorer_back')
  E.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)

 spec=importlib.util.spec_from_file_location('vestr_diagnostics',project/'scripts/adapt-pandamonium-wallet-error-languages.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
 source=module.build_source(json.loads((project/'localization/vestr-errors.json').read_text()),catalog)
 source=source.replace('com.eurobuddha.wallet','com.eurobuddha.vestr').replace('WalletMessages','VestrMessages').replace('Explicit wallet failures only.','Explicit Vestr transport failures only.').replace('adapt-pandamonium-wallet-error-languages.py from localization/wallet-errors.json','adapt-pandamonium-vestr-languages.py')
 (folder/'VestrMessages.java').write_text(source)

 p=folder/'VestrDesign.java';s=p.read_text();s=change(s,'0xFF6A6A78','0xFF9A9AA8');s=change(s,'0xFFE74C3C','0xFFFF5B5B');p.write_text(s)
