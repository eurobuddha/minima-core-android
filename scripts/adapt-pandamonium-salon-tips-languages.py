"""Translate Salon tipping presentation while preserving the existing transport."""
from pathlib import Path
import json,re

def adapt(root):
    base=root/'salon/src/main/java/com/eurobuddha/salon'
    p=base/'MainActivity.java';s=p.read_text()
    def msg(key,args=''):
        resource='l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
        return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.'+resource+args+')'
    def change(old,new):
        nonlocal s
        assert s.count(old)==1,('Salon tip source drift',old,s.count(old));s=s.replace(old,new)
    change('java.util.List<String> fresh = new java.util.ArrayList<>();','java.util.List<CharSequence> fresh = new java.util.ArrayList<>();')
    change('for (String d : fresh) toast(d);','for (CharSequence d : fresh) toast(d);')
    change('private String describeTip(JSONObject coin)', 'private CharSequence describeTip(JSONObject coin)')
    change('String from = "someone", note = "";', 'String from = "", note = "";')
    change('''        return "💰 @" + from.replaceFirst("^@", "") + " tipped you " + amount + " " + TipTransport.label(tokenid)
                + (note.isEmpty() ? "" : " — " + note);''','''        CharSequence sender = from.isEmpty() ? '''+msg('creative.sender')+''' : "@" + from.replaceFirst("^@", "");
        return Language.join("💰 ", '''+msg('creative.tipFrom',', sender, amount, TipTransport.label(tokenid)')+''', note.isEmpty() ? "" : " — " + note);''')
    a=s.index('    private void tipDialog(');b=s.index('    /* ================= Messages',a);part=s[a:b]
    def replace(old,new,count=1):
        nonlocal part
        assert part.count(old)==count,('Salon tipping display drift',old,part.count(old));part=part.replace(old,new)
    for literal,key,count in [('Connect the node to tip.','salonTips.connect',1),('Currency: mxUSD','salonTips.currency',1),('Amount','mail.amount',1),('Checking your balance…','salonTips.checking',1),('Note (optional)','mail.note',1),('nice work!','salonTips.noteHint',1),('Send','wallet.send',1),('Cancel','common.cancel',1),('Enter a valid amount.','utxo.validAmount',2),('Enter an amount.','mail.enterAmount',1),('A tip is already sending — one moment.','salonTips.busy',1),('Checking balance…','salonTips.checking',1),('Sending tip…','salonTips.sending',1)]:
        args=', "mxUSD"' if literal=='Currency: mxUSD' else ''
        replace(json.dumps(literal,ensure_ascii=False),msg(key,args),count)
    for old,key,args in [
        ('"Currency: " + TipTransport.label(tokenid[0])','salonTips.currency',', TipTransport.label(tokenid[0])'),
        ('"Send a tip to @" + handle','salonTips.title',', handle'),
        ('"Not enough " + TipTransport.label(tokenid) + " (you have " + have.toPlainString() + ")"','salonTips.insufficient',', TipTransport.label(tokenid), have.toPlainString()'),
        ('"Tip sent to @" + handle + " — mining ⛏"','salonTips.submitted',', handle'),
        ('"Tip failed: " + msg','salonTips.failed',', HoldingMessages.message(msg)'),
        ('"Balance check failed: " + m','salonTips.balanceFailed',', HoldingMessages.message(m)')]:replace(old, 'Language.upper('+msg(key,args)+')' if key=='salonTips.currency' else msg(key,args))
    replace('curBtn.setText(', 'Language.setText(curBtn, ')
    replace('new android.app.AlertDialog.Builder(this)', 'new com.eurobuddha.minimacore.i18n.LanguageDialog(this)')
    # Only the preview read changes: capture its currency and ignore obsolete responses.
    start=part.index('        final Runnable refreshBal =');end=part.index('        refreshBal.run();',start)
    preview='''        final int[] balanceRequest = {0};
        final Runnable refreshBal = () -> {
            final int request = ++balanceRequest[0];
            final String requestedToken = tokenid[0];
            Language.setText(bal, MSG_CHECKING);
            node.cmd("balance tokenid:" + requestedToken, new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    String amount = null;
                    if (j != null && Boolean.TRUE.equals(j.opt("status"))) {
                        JSONArray rows = j.optJSONArray("response");
                        if (rows != null && rows.length() == 0) amount = "0";
                        else if (rows != null && rows.length() == 1) {
                            JSONObject row = rows.optJSONObject(0);
                            if (row != null && Args.isDecimal(row.optString("sendable", ""))) amount = row.optString("sendable");
                        }
                    }
                    final String have = amount;
                    runOnUiThread(() -> {
                        if (request != balanceRequest[0] || !requestedToken.equals(tokenid[0]) || isFinishing() || isDestroyed()) return;
                        Language.setText(bal, have == null ? MSG_UNAVAILABLE : MSG_AVAILABLE);
                    });
                }
                @Override public void onError(String ignored) {
                    runOnUiThread(() -> {
                        if (request == balanceRequest[0] && requestedToken.equals(tokenid[0]) && !isFinishing() && !isDestroyed()) Language.setText(bal, MSG_UNAVAILABLE);
                    });
                }
            });
        };
'''
    for marker,key,args in [('CHECKING','salonTips.checking',''),('UNAVAILABLE','salonTips.unavailable',''),('AVAILABLE','salonTips.available',', have, TipTransport.label(requestedToken)')]:preview=preview.replace('MSG_'+marker,msg(key,args))
    part=part[:start]+preview+part[end:]
    s=s[:a]+part+s[b:];p.write_text(s)
    # Reuse the fixed-diagnostic map without changing transport validation or commands.
    p=base/'HoldingMessages.java';s=p.read_text();cases=''
    for literal,key in [("that profile's tip address is malformed — not sending",'salonTips.badAddress'),('bad token id','salonTips.badToken'),('bad amount','utxo.validAmount'),('the node rejected the tip','utxo.rejected')]:
        cases+='            case '+json.dumps(literal,ensure_ascii=False)+': return '+msg(key)+';\n'
    assert s.count('            default: return reason;')==1
    s=s.replace('            default: return reason;',cases+'            default: return reason;');p.write_text(s)
