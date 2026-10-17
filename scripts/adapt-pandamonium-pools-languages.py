"""PandaPools shell and registry display; reuse retained Casino/Parlons language bindings."""
from pathlib import Path
import json,re,importlib.util,xml.etree.ElementTree as E

def adapt(root):
 project=Path(__file__).resolve().parents[1];folder=root/'pandapools/src/main/java/com/eurobuddha/pandapools'
 catalog=json.loads((project/'localization/catalog.json').read_text());spec=json.loads((project/'localization/pools-messages.json').read_text())
 def change(s,old,new,count=1):
  assert s.count(old)==count,('PandaPools source drift',old,s.count(old));return s.replace(old,new)
 imports='\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;\n'
 def read(name):
  p=folder/(name+'.java');return p,change(p.read_text(),'package com.eurobuddha.pandapools;','package com.eurobuddha.pandapools;'+imports)
 builder=importlib.util.spec_from_file_location('pools_message_builder',project/'scripts/adapt-pandamonium-wallet-error-languages.py');m=importlib.util.module_from_spec(builder);builder.loader.exec_module(m)
 source=m.build_source(spec,catalog).replace('com.eurobuddha.wallet','com.eurobuddha.pandapools').replace('WalletMessages','PoolsMessages').replace('Explicit wallet failures only.','Explicit PandaPools application labels only.').replace('adapt-pandamonium-wallet-error-languages.py from localization/wallet-errors.json','adapt-pandamonium-pools-languages.py from localization/pools-messages.json')
 (folder/'PoolsMessages.java').write_text(source)
 p,s=read('BaseView');s=change(s,'        root = LayoutInflater.from(a).inflate(layoutRes, null);','        root = LayoutInflater.from(a).inflate(layoutRes, null);\n        Language.apply(root);');p.write_text(s)
 p=folder/'MainPager.java';s=p.read_text();s=change(s,'String[] titles','CharSequence[] titles',2);p.write_text(s)
 p,s=read('MainActivity')
 s=change(s,'        setContentView(R.layout.pm_pandapools_activity_main);','        setContentView(R.layout.pm_pandapools_activity_main);\n        Language.apply(findViewById(R.id.pm_pandapools_main));\n        Language.description(findViewById(R.id.pm_pandapools_designToggle), Language.msg(l10n_pools_style_description));')
 s=change(s,'((TextView) findViewById(R.id.pm_pandapools_brandSub)).setText("LIQUIDITY POOLS   ·   v" + ver);','Language.setText((TextView) findViewById(R.id.pm_pandapools_brandSub), Language.join(Language.msg(l10n_pools_subtitle), "   ·   v", ver));')
 s=change(s,'"Finish your transaction first, then switch theme."','Language.msg(l10n_pools_busy_theme)')
 s=change(s,'"This device has no battery-optimisation screen. Exclude PandaPools from battery "\n                            + "optimisation for Minima Core in Android Settings."','Language.msg(l10n_pools_battery_missing)')
 s=change(s,'String[] titles = { "SWAP", "POOLS", "MY LP", "WALLET", "ACTIVITY" };','CharSequence[] titles = { Language.msg(l10n_pools_swap), Language.msg(l10n_pools_pools), Language.msg(l10n_pools_my_lp), Language.msg(l10n_pools_wallet), Language.msg(l10n_pools_activity) };')
 hook='''    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        // Retain pages, inputs, current tab and pending callbacks. Changing language does not refresh pools.
        TabLayout tabs = findViewById(R.id.pm_pandapools_tabs);
        if (pager != null && pager.getAdapter() != null && tabs != null)
            for (int i = 0; i < tabs.getTabCount(); i++)
                tabs.getTabAt(i).setText(pager.getAdapter().getPageTitle(i).toString());
    }

'''
 s=change(s,'    @Override protected void onStop() {',hook+'    @Override protected void onStop() {');p.write_text(s)
 p,s=read('Ui')
 s=change(s,'android.content.ClipData.newPlainText(label, fullValue)','android.content.ClipData.newPlainText(PoolsMessages.message(label).toString(), fullValue)')
 s=change(s,'"Copied " + label','Language.msg(l10n_pools_copied, PoolsMessages.message(label))')
 s=change(s,'t.setText(label + ":  " + fullValue + "   (tap to copy)");','Language.setText(t, Language.msg(l10n_pools_identifier, PoolsMessages.message(label), fullValue));');p.write_text(s)
 p,s=read('PoolsView')
 s=change(s,'status("Scan error: " + msg);','status(Language.msg(l10n_pools_scan_error, msg));')
 s=change(s,'status("Scanning the pool registry…");','status(Language.msg(l10n_pools_scanning));')
 s=change(s,'summary.setText("no active pools");','Language.setText(summary, Language.msg(l10n_pools_no_active));')
 s=change(s,'status("No live pools found in the registry yet. Create one to seed liquidity.");','status(Language.msg(l10n_pools_no_live));')
 s=change(s,'summary.setText(pools.size() + (pools.size() == 1 ? " pool" : " pools")\n                + " · " + trim(aggMinima) + " MINIMA aggregate depth");','Language.setText(summary, Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_pools_summary, pools.size(), pools.size(), trim(aggMinima)));')
 s=change(s,'status("No funded pools to combine yet.");','status(Language.msg(l10n_pools_no_funded));')
 s=change(s,'"MINIMA / " + tl + "  ·  combined"','Language.msg(l10n_pools_combined_title, tl)')
 for raw,key in [('pools combined','combined_count'),('total reserves','total_reserves'),('aggregate spot price','aggregate_price'),('tradeable depth','depth'),('reserves','reserves'),('spot price','spot_price'),('fees accrued (K/KMIN−1)','fees'),('pool','pool')]:s=change(s,'kv('+json.dumps(raw,ensure_ascii=False)+',','kv(Language.msg(l10n_pools_'+key+'),')
 s=change(s,'p.address + "  (tap to copy)"','Language.msg(l10n_pools_copy_value, p.address)')
 s=change(s,'private TextView line(String s,','private TextView line(CharSequence s,')
 s=change(s,'        t.setText(s);','        Language.setText(t, s);')
 s=change(s,'private View kv(String k, String v)','private View kv(CharSequence k, CharSequence v)')
 s=change(s,'kk.setText(k);','Language.setText(kk, k);');s=change(s,'vv.setText(v);','Language.setText(vv, v);')
 s=change(s,'private void status(String s)','private void status(CharSequence s)');s=change(s,'s.isEmpty() ? View.GONE','s.length() == 0 ? View.GONE');s=change(s,'st.setText(s);','Language.setText(st, s);');p.write_text(s)
 a='{http://schemas.android.com/apk/res/android}';auto='{http://schemas.android.com/apk/res-auto}';E.register_namespace('android',a[1:-1]);E.register_namespace('app',auto[1:-1])
 def resource(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
 for file in ['pm_pandapools_activity_main.xml','pm_pandapools_view_pools.xml']:
  p=root/'pandapools/src/main/res/layout'/file;tree=E.parse(p)
  for v in tree.iter():
   text=v.get(a+'text')
   if text in spec['fixed']:
    key=resource(spec['fixed'][text]);assert not v.get(a+'tag');v.set(a+'text','@string/'+key);v.set(a+'tag','l10n:text='+key)
   if v.get(a+'id','').endswith(('designToggle','poolsRefresh','poolsIndivTab','poolsCombinedTab')):v.set(a+'minHeight','48dp');v.set(a+'minWidth','48dp');v.set(a+'gravity','center')
  tree.write(p,encoding='utf-8',xml_declaration=True)

 spec=importlib.util.spec_from_file_location("pools_swap_languages",project/"scripts/adapt-pandamonium-pools-swap-languages.py");module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

 spec=importlib.util.spec_from_file_location("pools_wallet_activity_languages",project/"scripts/adapt-pandamonium-pools-wallet-activity-languages.py");module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

 spec=importlib.util.spec_from_file_location("pools_tools_languages",project/"scripts/adapt-pandamonium-pools-tools-languages.py");module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

 spec=importlib.util.spec_from_file_location("pools_lp_languages",project/"scripts/adapt-pandamonium-pools-lp-languages.py");module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

 spec=importlib.util.spec_from_file_location("pools_notices_languages",project/"scripts/adapt-pandamonium-pools-notices-languages.py");module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

 spec=importlib.util.spec_from_file_location("pools_recovery_languages",project/"scripts/adapt-pandamonium-pools-recovery-languages.py");module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

 spec=importlib.util.spec_from_file_location("pools_create_languages",project/"scripts/adapt-pandamonium-pools-create-languages.py");module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

 spec=importlib.util.spec_from_file_location("pools_liquidity_languages",project/"scripts/adapt-pandamonium-pools-liquidity-languages.py");module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

 spec=importlib.util.spec_from_file_location("pools_calculator_languages",project/"scripts/adapt-pandamonium-pools-calculator-languages.py");module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

 spec=importlib.util.spec_from_file_location("pools_keeper_languages",project/"scripts/adapt-pandamonium-pools-keeper-languages.py");module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)

 spec=importlib.util.spec_from_file_location("pools_reserve_reports",project/"scripts/adapt-pandamonium-pools-reserve-reports.py");module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.adapt(root)
