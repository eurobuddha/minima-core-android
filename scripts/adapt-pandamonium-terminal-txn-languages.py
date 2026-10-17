"""Localize transaction workbench display with shared bindings and owned dialogs.

Reuse its existing ID validator and the console clipboard size guard at bypassed call sites.
"""
from pathlib import Path
import json,re,xml.etree.ElementTree as E

def adapt(root):
 project=Path(__file__).resolve().parents[1];labels=json.loads((project/'localization/terminal-txn-labels.json').read_text());catalog=json.loads((project/'localization/catalog.json').read_text())
 def res(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[key]).replace('.','_').lower()
 def msg(key,*args):return 'Language.msg('+res(key)+('' if not args else ', '+', '.join(args))+')'
 def quote(s):return json.dumps(s,ensure_ascii=False)
 def change(s,old,new):
  assert old in s,('Txn localization source drift',old)
  return s.replace(old,new)
 folder=root/'terminalide/src/main/java/com/eurobuddha/terminalide/txn';p=folder/'TxnView.java';s=p.read_text();idx=s.index(';')+1
 s=s[:idx]+'''\nimport com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;
'''+s[idx:]
 s=change(s,'new AlertDialog.Builder(mActivity)','((com.eurobuddha.terminalide.MainActivity)mActivity).languageDialog()')
 for old,new in [('rejectBadField(String label','rejectBadField(CharSequence label'),('setStatus(String text','setStatus(CharSequence text'),('simple(String cmd, String okMsg','simple(String cmd, CharSequence okMsg'),('run(String cmd, String okMsg','run(String cmd, CharSequence okMsg')]:s=change(s,old,new)
 s=change(s,'label + " can\'t contain spaces"\n                    + (allowColon ? " or \';\'" : ", \':\' or \';\'")','allowColon ? '+msg('fieldInvalidColon','label')+' : '+msg('fieldInvalid','label'))
 s=change(s,'mStatus.setText(OutputFormatter.colored(text, color));','Language.setText(mStatus, text);\n        mStatus.setTextColor(color);')
 s=change(s,'"created \'" + id + "\' — now add inputs"',msg('created','id'))
 s=change(s,'String title = capped ? "Add input coin (first " + n + " of " + coins.length() + ")"\n                        : "Add input coin (" + coins.length() + " sendable)";', 'CharSequence title = capped ? '+msg('coinTitleCapped','n','coins.length()')+' : '+msg('coinTitle','coins.length()')+';')
 s=change(s,'View v = mActivity.getLayoutInflater().inflate(R.layout.pm_terminalide_dialog_output, null);','View v = mActivity.getLayoutInflater().inflate(R.layout.pm_terminalide_dialog_output, null);\n        Language.apply(v);')
 s=change(s,'"Inputs must equal outputs — any difference is BURNED. Add a change "\n                        + "output back to yourself. storestate:true only for covenant/phase coins."',msg('outputBody'))
 s=change(s,'"✖ no txn \'" + id + "\' — Create it first"','Language.join("✖ ", '+msg('missingTxn','id')+')')
 s=change(s,'"These leftovers would BURN on post. Send them back to "\n                                + "your own address?\\n\\n" + planned',msg('changeBody','planned.toString()'))
 s=change(s,'"✓ change added for all " + changes.size()\n                    + " token(s) — inputs now match outputs"',msg('changeAdded','changes.size()'))
 old='''"✖ change failed on token " + (idx + 1) + "/" + changes.size()
                            + ": " + json.optString("error", "txnoutput failed")
                            + "\\n\\n⚠ Still unbalanced (would BURN on post):\\n" + left
                            + "\\nFix these or Delete the txn before posting."'''
 s=change(s,old,msg('changeFailed','idx + 1','changes.size()','json.has("error") ? json.optString("error") : '+msg('outputFailed'),'left.toString()'))
 for field,key in [('port','portHint'),('value','valueHint'),('key','keyHint'),('burn','burnHint'),('data','importHint')]:
  s=change(s,field+'.setHint('+quote(catalog[labels[key]]['en'])+');','Language.hint('+field+', '+msg(key)+');')
 s=change(s,'"State variables ride on the transaction and are stored in outputs "\n                        + "with storestate:true. Unset ports read as empty — set every port your "\n                        + "script touches."',msg('stateBody'))
 s=change(s,'"Order matters: sign FIRST, then Basics, then Post. For script-coin "\n                        + "inputs sign with the specific key the script checks."',msg('signBody'))
 s=change(s,'"This broadcasts on-chain. Success = status:true; the PoW mines "\n                        + "asynchronously so istransaction:false at return is NORMAL — do not "\n                        + "re-post, watch the chain instead. Did you run Sign then Basics?"',msg('postBody'))
 s=change(s,'"✖ export failed: " + json.optString("error", "no data")',msg('exportFailed','json.has("error") ? json.optString("error") : '+msg('noData')))
 old='''                android.content.ClipboardManager cb = (android.content.ClipboardManager)
                        mActivity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                cb.setPrimaryClip(android.content.ClipData.newPlainText("txn", data));'''
 s=change(s,old,'''                if (!com.eurobuddha.terminalide.terminal.SessionExport.copy(mActivity, data, "txn")) {
                    setStatus('''+msg('copyFailed')+''', OutputFormatter.COL_ERROR);
                    return;
                }''')
 s=change(s,'"✓ exported to clipboard (" + data.length() + " chars) — import it on "\n                        + "another node with Import"',msg('exported','data.length()'))
 s=change(s,'"Delete \'" + id + "\'?"',msg('deleteTitle','id'))
 s=change(s,'StringBuilder sb = new StringBuilder(arr.length() + " pending transaction(s):\\n\\n");','Language.TextBuilder sb = new Language.TextBuilder().append('+msg('pending','arr.length()')+').append("\\n\\n");')
 s=change(s,'.append(ins).append(" in / ").append(outs).append(" out\\n");','.append('+msg('inOut','ins','outs')+').append("\\n");')
 s=change(s,'sb.append("\\nType an id above + Check to inspect one.");','sb.append("\\n").append('+msg('inspect')+');')
 s=change(s,'setStatus(sb.toString(), OutputFormatter.COL_PLAIN);','setStatus(sb.build(), OutputFormatter.COL_PLAIN);')
 s=change(s,'"✖ " + cmd + "\\n" + json.optString("error", "failed")\n                            + "\\n\\nTip: delete abandoned txns (they lock coins)."','Language.join("✖ ", cmd, "\\n", json.has("error") ? json.optString("error") : '+msg('failed')+', "\\n\\n", '+msg('deleteTip')+')')
 s=change(s,'"✓ " + okMsg','Language.join("✓ ", okMsg)')
 # refresh previously bypassed txnId() validation entirely.
 s=change(s,'String id = mTxnId.getText().toString().trim();\n        if (id.isEmpty() || mNode == null) return;','if (mTxnId.getText().toString().trim().isEmpty() || mNode == null) return;\n        String id = txnId();\n        if (id == null) return;')
 # A late reply for the previous edited ID must not describe the currently displayed draft.
 start=s.index('    public void refresh()');before=s[:start];after=s[start:]
 after=change(after,'public void onResult(JSONObject json) {','public void onResult(JSONObject json) {\n                if (!id.equals(mTxnId.getText().toString().trim())) return;')
 after=change(after,'mSummary.setText(OutputFormatter.colored(\n                            "no txn \'" + id + "\' — Create it first", OutputFormatter.COL_DIM));','Language.setText(mSummary, '+msg('missingTxn','id')+');\n                    mSummary.setTextColor(OutputFormatter.COL_DIM);')
 after=change(after,'StringBuilder sb = new StringBuilder();','Language.TextBuilder sb = new Language.TextBuilder();')
 old='''sb.append(tokenid.equals("0x00") ? "MINIMA" : shorten(tokenid))
                                    .append("  in:").append(in)
                                    .append("  out:").append(outp)
                                    .append("  diff:").append(diff).append("\\n");'''
 after=change(after,old,'sb.append('+msg('balanceRow','tokenid.equals("0x00") ? "MINIMA" : shorten(tokenid)','in','outp','diff')+').append("\\n");')
 after=change(after,'sb.append("burn: ").append(burn).append("\\n");','sb.append('+msg('burnValue','burn')+').append("\\n");')
 old='''sb.append("valid: basic=").append(valid.optBoolean("basic"))
                                .append(" signatures=").append(valid.optBoolean("signatures"))
                                .append(" mmrproofs=").append(valid.optBoolean("mmrproofs"))
                                .append(" scripts=").append(valid.optBoolean("scripts"));'''
 after=change(after,old,'sb.append('+msg('valid','valid.optBoolean("basic")','valid.optBoolean("signatures")','valid.optBoolean("mmrproofs")','valid.optBoolean("scripts")')+');')
 after=change(after,'sb.append("\\n⚠ inputs > outputs — the difference will be PERMANENTLY BURNED. "\n                            + "Add a change output unless intentional.");','sb.append("\\n").append('+msg('burnWarning')+');')
 after=change(after,'mSummary.setText(OutputFormatter.colored(sb.length() == 0\n                                ? "txn exists — add inputs/outputs" : sb.toString(),\n                        burnWarning ? OutputFormatter.COL_BOOL : OutputFormatter.COL_PLAIN));','Language.setText(mSummary, sb.build().length() == 0 ? '+msg('exists')+' : sb.build());\n                mSummary.setTextColor(burnWarning ? OutputFormatter.COL_BOOL : OutputFormatter.COL_PLAIN);')
 s=before+after
 s=change(s,'"The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry."','Language.msg(l10n_explorer_node_retry)')
 s=change(s,'"✖ " + message','Language.join("✖ ", com.eurobuddha.terminalide.terminal.TerminalMessages.message(message))')
 keys=['proofsAdded','idRequired','idInvalid','loadingCoins','noCoins','inputAdded','cancel','addOutput','add','amountAddressRequired','amount','address','tokenId','outputAdded','changeBusy','calculating','balanced','changeTitle','addChange','addressFailed','stateTitle','set','portValueRequired','port','value','stateSet','signTitle','sign','publicKey','signed','postTitle','post','burn','posted','exporting','importTitle','import','importData','imported','deleteBody','delete','deleted','listing','noPending']
 for key in keys:s=change(s,quote(catalog[labels[key]]['en']),msg(key))
 # Reuse ScriptsView/AuditRunner's explicit unreadable-row handling rather than dereferencing null.
 s=change(s,'JSONObject c = coins.optJSONObject(i);\n                    String amount', 'JSONObject c = coins.optJSONObject(i);\n                    if (c == null || c.optString("coinid", "").isEmpty()) {\n                        setStatus('+msg('incomplete')+', OutputFormatter.COL_ERROR);\n                        return;\n                    }\n                    String amount')
 s=change(s,'if (c == null) continue;\n                    String tokenid', 'if (c == null || c.optString("tokenid", "").isEmpty()) {\n                        mChangeInFlight = false;\n                        setStatus('+msg('incomplete')+', OutputFormatter.COL_ERROR);\n                        return;\n                    }\n                    String tokenid')
 s=change(s,'String diff = c.optString("difference", "0");', 'String diff = c.optString("difference", "");')
 s=change(s,'} catch (NumberFormatException ignored) {}\n                }', '} catch (NumberFormatException invalid) {\n                        mChangeInFlight = false;\n                        setStatus('+msg('incomplete')+', OutputFormatter.COL_ERROR);\n                        return;\n                    }\n                }')
 s=change(s,'JSONObject c = coins.optJSONObject(i);\n                            String tokenid', 'JSONObject c = coins.optJSONObject(i);\n                            if (c == null) {\n                                Language.setText(mSummary, '+msg('incomplete')+');\n                                mSummary.setTextColor(OutputFormatter.COL_ERROR);\n                                return;\n                            }\n                            String tokenid')
 s=change(s,'} catch (NumberFormatException ignored) {}', '} catch (NumberFormatException invalid) {\n                                Language.setText(mSummary, '+msg('incomplete')+');\n                                mSummary.setTextColor(OutputFormatter.COL_ERROR);\n                                return;\n                            }')
 s=change(s,'if (new BigDecimal(diff).compareTo(BigDecimal.ZERO) > 0) {', 'int balance = new BigDecimal(diff).compareTo(BigDecimal.ZERO);\n                        if (balance < 0) {\n                            mChangeInFlight = false;\n                            setStatus('+msg('negativeChange')+', OutputFormatter.COL_ERROR);\n                            return;\n                        }\n                        if (balance > 0) {')
 # The current bundled txncheck returns valid.signatures as an array, with an aggregate alongside it.
 s=change(s,'if (valid != null) {\n                        sb.append', 'if (valid != null) {\n                        Object signatures = resp.opt("allsignaturesvalid");\n                        if (!(signatures instanceof Boolean)) signatures = valid.opt("signatures");\n                        sb.append')
 s=change(s,'valid.optBoolean("signatures"), valid.optBoolean("mmrproofs")', '(signatures instanceof Boolean ? signatures : "?"), valid.optBoolean("mmrproofs")')
 p.write_text(s)
 a='{http://schemas.android.com/apk/res/android}';E.register_namespace('android',a[1:-1])
 static=['idHint','flow','inputButton','outputButton','changeButton','state','basics','list','noTxn','amountHint','addressHint','tokenHint','storestate','create','check','export','delete','sign','post','import','help']
 mapping={catalog[labels[k]]['en']:k for k in static}
 # The donor wording overstates the workbench's automatic enforcement.
 mapping[catalog[labels['help']]['en'].replace('Rules to follow:', 'Rules it enforces for you:')]='help'
 for name in ['view_txn','dialog_output']:
  p=root/f'terminalide/src/main/res/layout/pm_terminalide_{name}.xml';tree=E.parse(p)
  for view in tree.iter():
   for prop in ['text','hint']:
    raw=view.get(a+prop,'').replace('\\n','\n');key=mapping.get(raw)
    if key:view.set(a+prop,'@string/'+res(key));view.set(a+'tag','l10n:'+prop+'='+res(key))
  E.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)
