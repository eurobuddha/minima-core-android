"""Bind ETH Wallet display text using the established shared native dialog and text helpers."""
from pathlib import Path
import json,re,importlib.util

def adapt(root):
 project=Path(__file__).resolve().parents[1];labels=json.loads((project/'localization/eth-labels.json').read_text());catalog=json.loads((project/'localization/catalog.json').read_text())
 folder=root/'ethwallet/src/main/java/com/eurobuddha/ethwallet';p=folder/'MainActivity.java';s=p.read_text()
 imports='\nimport com.eurobuddha.minimacore.i18n.Language;\nimport com.eurobuddha.minimacore.i18n.LanguageDialog;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;\n'
 def msg(k,*args):
  key='l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[k]).replace('.','_').lower()
  return 'Language.msg('+key+('' if not args else ', '+', '.join(args))+')'
 def change(old,new,count=None):
  nonlocal s
  assert old in s,('ETH display source drift',old)
  if count is not None:assert s.count(old)==count,(old,s.count(old))
  s=s.replace(old,new)
 change('package com.eurobuddha.ethwallet;','package com.eurobuddha.ethwallet;\n'+imports)
 # Join compile-time literal continuations before matching the explicitly reviewed UI phrases.
 pattern=r'("(?:\\.|[^"\\])*"[ \t\r\n]*\+[ \t\r\n]*"(?:\\.|[^"\\])*")'
 while re.search(pattern,s):
  def join(m):
   parts=re.findall(r'"(?:\\.|[^"\\])*"',m[0]);return json.dumps(''.join(json.loads(x) for x in parts),ensure_ascii=False)
  s=re.sub(pattern,join,s)
 dynamic={
  '"Imported " + shortAddr(wallet.address())':msg('imported','shortAddr(wallet.address())'),
  '"v" + BuildConfig.VERSION_NAME + "  ·  " + (wallet.isImported() ? "imported key" : "node-derived") + (lastUpdate > 0 ? "  ·  updated " + ago(lastUpdate) : "")':'Language.join("v", BuildConfig.VERSION_NAME, "  ·  ", wallet.isImported() ? '+msg('importedKey')+' : '+msg('nodeDerived')+', lastUpdate > 0 ? Language.join("  ·  ", '+msg('updated','Language.since(lastUpdate)')+') : "")',
  '"⚠ " + ethErr':'Language.join("⚠ ", ethErr)',
  '"Token: " + chosen[0] + "  (tap to change · balance " + balOf(chosen[0]) + ")"':msg('tokenBalance','chosen[0]','balOf(chosen[0])'),
  '"Token " + sym + " is no longer in your list"':msg('missingToken','sym'),
  '"Won\'t send: " + ge.getMessage()':msg('refuse','ge.getMessage()'),
  '"Won\'t send: " + rpcHost() + " reports a gas price of " + g\n                            + " gwei, far above normal. Check the RPC endpoint in Settings."':msg('highPrice','rpcHost()','g'),
  '"Won\'t send: " + rpcHost() + " estimated " + gl\n                            + " gas for this transfer, far above normal. Check the RPC endpoint in Settings."':msg('highGas','rpcHost()','gl'),
  '"Couldn\'t prepare: " + e.getMessage()':msg('prepareFailed','e.getMessage()'),
  '"⚠ The fee is about " + pct + "% of the amount you\'re sending."':msg('feePercent','pct'),
  '"Send failed: " + e.getMessage()':msg('sendFailed','e.getMessage()'),
  '"Broadcast.\\n\\n" + tx':msg('broadcast','tx'),
  '"Added " + tk.symbol':msg('added','tk.symbol'),
  '"Not an ERC20? " + e.getMessage()':msg('notErc20','e.getMessage()'),
  '"Send " + tk.symbol':msg('sendToken','tk.symbol'),
  'k + ":  " + v':'Language.join(k, ":  ", v)',
 }
 for old,new in dynamic.items():change(old,new)
 # Every remaining exact catalog literal is UI-owned; protocol strings are excluded.
 dynamic_keys={'imported','updated','tokenBalance','missingToken','refuse','highPrice','highGas','prepareFailed','feePercent','sendFailed','broadcast','added','notErc20','sendToken','importedKey','nodeDerived'}
 for k in labels:
  if k in dynamic_keys:continue
  change(json.dumps(catalog[labels[k]]['en'],ensure_ascii=False),msg(k))
 change('"The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry."','Language.msg(l10n_explorer_node_retry)')
 change('private String ethAddr, ethBal = "—", ethErr;','private String ethAddr, ethBal = "—";\n    private CharSequence ethErr;')
 change('private void authThen(String title, String detail, Runnable action)','private void authThen(CharSequence title, CharSequence detail, Runnable action)')
 change('private static final String[] FEE_TIERS','private static final CharSequence[] FEE_TIERS')
 change('String w = null;','CharSequence w = null;')
 change('private void addPill(LinearLayout row, String label, Runnable onClick)','private void addPill(LinearLayout row, CharSequence label, Runnable onClick)')
 change('private TextView kvLine(String k, String v)','private TextView kvLine(CharSequence k, CharSequence v)')
 change('private void toast(String s)','private void toast(CharSequence s)')
 change('private String rpcHost()','private CharSequence rpcHost()')
 change('new String[]{','new CharSequence[]{',2)
 change('.setPrompt('+msg('scanPrompt')+')','.setPrompt('+msg('scanPrompt')+'.toString())')
 change('new AlertDialog.Builder(this)','languageDialog()')
 # Preserve every caller dismissal handler while owning the lifetime, as in the terminal dialogs.
 hook='''    private final java.util.Set<AlertDialog> languageDialogs = new java.util.HashSet<>();
    private LanguageDialog languageDialog() {
        return new LanguageDialog(this) {
            private android.content.DialogInterface.OnDismissListener dismissed;
            @Override public LanguageDialog setOnDismissListener(android.content.DialogInterface.OnDismissListener action) {
                dismissed = action;
                return this;
            }
            @Override public AlertDialog create() {
                AlertDialog value = super.create();
                languageDialogs.add(value);
                final android.content.DialogInterface.OnDismissListener callback = dismissed;
                value.setOnDismissListener(d -> {
                    languageDialogs.remove(value);
                    if (callback != null) callback.onDismiss(d);
                });
                return value;
            }
        };
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
    }

'''
 change('    private static final String PREFS',hook+'    private static final String PREFS',1)
 change('        super.onDestroy();','        for (AlertDialog value : new java.util.ArrayList<>(languageDialogs)) value.dismiss();\n        languageDialogs.clear();\n        super.onDestroy();',1)
 s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*(?:\.[A-Za-z_][A-Za-z_0-9]*)*)\.setText\(',r'Language.setText(\1, ',s)
 s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*)\.setHint\(',r'Language.hint(\1, ',s)
 # Three equally sized actions wrap translated labels instead of extending beyond the screen.
 start=s.index('    private void addPill(');end=s.index('    /**',start);part=s[start:end]
 old='new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)';assert old in part
 s=s[:start]+part.replace(old,'new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)')+s[end:]
 # The timestamp is now a live descriptor; remove the unused English-only formatter.
 start=s.index('    private static String ago(');s=s[:start]+s[s.index('\n    }',start)+6:]
 # Keep engine exceptions/protocol data unchanged; translate recognized application failures at display boundaries.
 for prefix in ['l10n_eth_wallet_refuse','l10n_eth_wallet_prepare_failed','l10n_eth_wallet_send_failed','l10n_eth_wallet_not_erc20']:
  variable='ge' if prefix.endswith('_refuse') else 'e'
  old='Language.msg('+prefix+', '+variable+'.getMessage())'
  assert s.count(old)==1,old
  s=s.replace(old,'Language.msg('+prefix+', EthMessages.error('+variable+'))')
 change('ethErr = "RPC: " + e.getMessage();','ethErr = Language.join("RPC: ", EthMessages.error(e));',1)
 change('                        : msg;','                        : EthMessages.message(msg);',1)
 change('sensitive ? "private key" : "eth"','sensitive ? Language.text(this, l10n_eth_wallet_private_key) : "eth"',1)
 # Accessible touch targets for actions; non-interactive badges retain their compact size.
 change('p.setOnClickListener(v -> onClick.run());','p.setMinimumHeight(dp(48));\n        p.setOnClickListener(v -> onClick.run());',1)
 change('scan.setOnClickListener(v ->','scan.setMinimumHeight(dp(48));\n        scan.setOnClickListener(v ->',1)
 change('max.setOnClickListener(v ->','max.setMinimumHeight(dp(48));\n        max.setOnClickListener(v ->',1)
 change('p.setOnClickListener(v -> { tier[0]','p.setMinimumHeight(dp(48));\n            p.setOnClickListener(v -> { tier[0]',1)
 p.write_text(s)
 loader=importlib.util.spec_from_file_location('eth_message_builder',Path(__file__).with_name('adapt-pandamonium-wallet-error-languages.py'))
 module=importlib.util.module_from_spec(loader);loader.loader.exec_module(module)
 source=module.build_source(json.loads((project/'localization/eth-errors.json').read_text()),catalog)
 source=source.replace('package com.eurobuddha.wallet;','package com.eurobuddha.ethwallet;').replace('WalletMessages','EthMessages')
 source=source.replace('Explicit wallet failures only.','Explicit ETH Wallet failures only.').replace('adapt-pandamonium-wallet-error-languages.py from localization/wallet-errors.json','adapt-pandamonium-eth-languages.py from localization/eth-errors.json')
 (folder/'EthMessages.java').write_text(source)
 # The app paints a fixed dark palette. Reuse Future Cash's Material dark surface attributes so
 # native dialog/input text matches the same palette regardless of the system day/night choice.
 p=root/'ethwallet/src/main/res/values/themes.xml';s=p.read_text()
 assert s.count('Theme.Material3.DayNight.NoActionBar')==1
 s=s.replace('Theme.Material3.DayNight.NoActionBar','Theme.Material3.Dark.NoActionBar')
 s=s.replace('    </style>','''
        <item name="colorPrimary">#F7931A</item>
        <item name="colorAccent">#F7931A</item>
        <item name="colorSurface">#15151F</item>
        <item name="colorSurfaceContainerHigh">#15151F</item>
        <item name="colorOnSurface">#FFFFFF</item>
        <item name="colorOnSurfaceVariant">#9A9AA8</item>
        <item name="android:colorBackground">#0A0A0F</item>
        <item name="android:textColorPrimary">#FFFFFF</item>
        <item name="android:textColorSecondary">#9A9AA8</item>
    </style>''',1)
 p.write_text(s)
 p=folder/'Design.java' ;s=p.read_text();s=s.replace('import android.content.Context;','import android.content.Context;\nimport com.eurobuddha.minimacore.i18n.Language;').replace('pill(Context c, String text,','pill(Context c, CharSequence text,').replace('t.setText(text);','Language.setText(t, text);').replace('0xFF6A6A78','0xFF9A9AA8').replace('0xFFE74C3C','0xFFFF5B5B');p.write_text(s)
