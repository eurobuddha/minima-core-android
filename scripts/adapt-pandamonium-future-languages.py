"""Localize Future Cash audit and payment controls using the shared live bindings.

Retain input views across status refreshes; never translate protocol commands,
addresses, saved audit evidence, or stored diagnostics.
"""
from pathlib import Path

IMPORTS = '''
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;
'''

def adapt(root):
    folder = root/'futurecash-next/src/main/java/com/eurobuddha/futurecashnext'
    p=folder/'Ui.java';s=p.read_text()
    s=s.replace('package com.eurobuddha.futurecashnext;', 'package com.eurobuddha.futurecashnext;\n'+IMPORTS)
    # Keep copyable's raw String value: it is also the clipboard payload.
    s=s.replace('Context c, String s', 'Context c, CharSequence s')
    s=s.replace('Context c, String labelText', 'Context c, CharSequence labelText')
    s=s.replace('Context c, String hint', 'Context c, CharSequence hint')
    s=s.replace('t.setText(s);', 'Language.setText(t, s);').replace('b.setText(labelText);','Language.setText(b, labelText);')
    s=s.replace('s.toUpperCase()', 'Language.upper(s)').replace('e.setHint(hint);','Language.hint(e, hint);')
    s=s.replace('c, "Copied", android.widget.Toast.LENGTH_SHORT', 'c, Language.msg(l10n_future_copied), android.widget.Toast.LENGTH_SHORT')
    # Horizontal rows center children; asymmetric top margins otherwise clip button bottoms.
    assert s.count('        b.setLayoutParams(lp);') == 1
    s=s.replace('        b.setLayoutParams(lp);', '        lp.gravity = Gravity.TOP;\n        b.setLayoutParams(lp);')
    # Palette refresh is separate from editing configuration, preserving cursor/selection.
    old='''        e.setHintTextColor(Design.DIM2());
        e.setTextColor(Design.TEXT());
        e.setTextSize(15);'''
    assert old in s;s=s.replace(old,'        e.setTextSize(15);')
    old='''        e.setBackground(Design.stroked(c, Design.SURFACE2(), 14));
        e.setPadding(dp(c, 14), dp(c, 13), dp(c, 14), dp(c, 13));'''
    assert old in s;s=s.replace(old,'        styleField(e);')
    marker='    /** A hairline divider, for separating rows inside one card. */'
    s=s.replace(marker,'''    public static void styleField(EditText e) {
        final Context c = e.getContext();
        e.setHintTextColor(Design.DIM2());
        e.setTextColor(Design.TEXT());
        e.setBackground(Design.stroked(c, Design.SURFACE2(), 14));
        e.setPadding(dp(c, 14), dp(c, 13), dp(c, 14), dp(c, 13));
    }

'''+marker)
    start=s.index('    public static String maturesIn(')
    end=s.index('    public static String timeAgo(',start)
    s=s[:start]+'''    public static CharSequence maturesIn(long block, long tip) {
        final long blocks = Math.max(0, block - tip);
        final long mins = Math.round(blocks * 50 / 60.0);
        return mins < 90 ? Language.msg(l10n_future_in_minutes, mins)
                : mins < 1440 ? Language.msg(l10n_future_in_hours, Math.round(mins / 60.0))
                : Language.msg(l10n_future_in_days, Math.round(mins / 1440.0));
    }

''' + s[end:]
    p.write_text(s)
    p=folder/'Design.java';s=p.read_text().replace('package com.eurobuddha.futurecashnext;', 'package com.eurobuddha.futurecashnext;\n'+IMPORTS)
    s=s.replace('pill(Context c, String text,', 'pill(Context c, CharSequence text,').replace('t.setText(text);','Language.setText(t, text);');p.write_text(s)
    p=folder/'MainActivity.java';s=p.read_text()
    def change(old,new,count=1):
        nonlocal s
        assert s.count(old)==count,('Future Cash localization drift',old,s.count(old))
        s=s.replace(old,new)
    def msg(key,*args):
        import re
        resource=re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).lower()
        return 'Language.msg(l10n_future_'+resource+('' if not args else ', '+', '.join(args))+')'
    change('package com.eurobuddha.futurecashnext;', 'package com.eurobuddha.futurecashnext;\n'+IMPORTS)
    change('    private String tab = "guardian";', '''    private String tab = "guardian";
    // Status updates rebuild cards; keep the user's payment draft and selected delay.
    private EditText lockAmount, lockRecipient;
    private int lockPreset = 1;
    private long lockBlocks = 1728;
''')
    change('        sub.setTextColor(Design.DIM2());','        sub.setTextColor(Design.DIM2());\n        Language.setText(sub, '+msg('subtitle')+');')
    change('        toggle.setText(Design.isDark() ? "☀" : "☾");','''        toggle.setText(Design.isDark() ? "☀" : "☾");
        Language.description(toggle, Design.isDark() ? Language.msg(l10n_future_light_theme)
                : Language.msg(l10n_future_dark_theme));''')
    change('''        final String[][] defs = {{"guardian", "Guardian"}, {"lock", "Stakes"},
                {"activity", "Activity"}, {"help", "Help"}};
        for (String[] d : defs) {
            final boolean active = tab.equals(d[0]);''','''        final String[] ids = {"guardian", "lock", "activity", "help"};
        final CharSequence[] labels = {Language.msg(l10n_future_guardian), Language.msg(l10n_future_stakes),
                Language.msg(l10n_future_activity), Language.msg(l10n_future_help)};
        for (int i = 0; i < ids.length; i++) {
            final String id = ids[i];
            final boolean active = tab.equals(id);''')
    change('t.setText(d[1]);','Language.setText(t, labels[i]);')
    change('tab = d[0];','tab = id;')
    change('''pairBanner.setText("⚠ The embedded node is unavailable. Open "
                + "Minima Core and check its status. Until then nothing can be collected "
                + "or rescued.");''','Language.setText(pairBanner, '+msg('nodeUnavailable')+');')
    change('private void toast(String s)', 'private void toast(CharSequence s)')
    # Audit view only. Errors remain literal alongside a bound localized explanation.
    start=s.index('    private View auditCard()');end=s.index('    private View daemonCard()',start)
    part=s[start:end].replace('String msg;', 'CharSequence msg;').replace('final String meta;', 'final CharSequence meta;')
    replacements={
        '"Auditing your keys…"':msg('auditing'),
        '''"⚠ Your safe destination is a REUSED address (signed ×"
                    + bad[1] + "). Rescuing there would move your money onto another exposed key — "
                    + "set a fresh destination below.\\n\\n" + bad[0]''':msg('reusedDestination','bad[1]','bad[0]'),
        '''"⚠ Key-reuse risk detected (reused ×" + lastAudit.worstReuse + "). At-risk stakes "
                    + "are collected and swept to your safe address."''':msg('reuseDetected','lastAudit.worstReuse'),
        '"Key-reuse risk is recorded for this wallet. Review the key details and your rescue destination."':msg('riskRecorded'),
        '''err + " "
                    + (v != null && v.ok ? "Using your last good audit (" + Ui.timeAgo(v.at) + ") — collecting continues."
                            : "Auto-collect of safe stakes is paused until an audit succeeds; your funds "
                              + "are untouched and stay locked in the contract.")''': 'Language.join(err, " ", v != null && v.ok ? '+msg('lastGoodAudit','Language.since(v.at)')+' : '+msg('auditPaused')+')',
        '"No audit yet. Tap Re-audit to check your keys."':msg('noAudit'),
        '"The audit is incomplete or out of date. Re-audit before trusting the result."':msg('incompleteAudit'),
        '"No known key reuse was found in the checked keys."':msg('clearAudit'),
        '"Key audit"':msg('keyAudit'),
        '" · re-checked against your node"':msg('reconfirmed'),
        '" · needs a fresh audit before safe stakes auto-collect"':msg('freshAuditNeeded'),
        '"audited " + Ui.timeAgo(at) + meta':msg('audited','Language.since(at)','meta'),
        '"Re-audit"':msg('reaudit'),
    }
    for old,new in replacements.items():
        assert part.count(old)==1,('Audit localization drift',old);part=part.replace(old,new)
    s=s[:start]+part+s[end:]
    start=s.index('    private View keyTableCard()');end=s.index('    private void maybeAudit(',start)
    part=s[start:end].replace('String verdict =', 'CharSequence verdict =')
    replacements={
        '"Key detail"':msg('keyDetail'),
        '"Run an audit to see every key\'s on-chain usage."':msg('runAudit'),
        '''"Classic keys: " + lastAudit.classicKeys
                + " · Block keys: " + lastAudit.blockKeys
                + (lastAudit.unknownKeys == 0 ? "" : " · Unknown: " + lastAudit.unknownKeys)''':'Language.join('+msg('keyFamilies','lastAudit.classicKeys','lastAudit.blockKeys')+', lastAudit.unknownKeys == 0 ? "" : '+msg('unknownKeys','lastAudit.unknownKeys')+')',
        '''"Both key sets are checked for known signature reuse. "
                + "The spend-count estimate applies to classic keys only; block keys use leaf positions."''':msg('bothFamilies'),
        '"  RE-USED ×" + r.reuseCount':msg('reused','r.reuseCount'),
        '"  AT RISK"':msg('atRisk'), '"  No known reuse"':msg('noReuse'), '"  Not fully checked"':msg('notChecked'),
        '''(r.family == KeyAudit.Family.CLASSIC ? "Classic · counter "
                    : r.family == KeyAudit.Family.BLOCK ? "Block · leaf position " : "Unknown key type · ")
                    + r.local''':'(r.family == KeyAudit.Family.CLASSIC ? '+msg('classicCounter','r.local')+' : r.family == KeyAudit.Family.BLOCK ? '+msg('blockPosition','r.local')+' : '+msg('unknownType','r.local')+')',
        '''"Only your PUBLIC keys are ever sent to the audit service — they can't "
                + "be used to take your money."''':msg('publicOnly'),
    }
    for old,new in replacements.items():
        assert part.count(old)==1,('Key table localization drift',old);part=part.replace(old,new)
    s=s[:start]+part+s[end:]
    # Reuse input widgets, not editable text snapshots. No replacement during a language change.
    change('''        final EditText amount = Ui.field(this, "Amount (MINIMA)", true);
        c.addView(amount);''','''        if (lockAmount == null) lockAmount = Ui.field(this, Language.msg(l10n_future_amount), true);
        final EditText amount = lockAmount;
        attachField(c, amount);''')
    change('''        final long[] blocks = {1728};   // ~1 day
        final TextView preview = Ui.text(this, "Unlocks in ~1 day", Design.DIM(), 12, false);''','''        final CharSequence[] durations = {Language.msg(l10n_future_hour), Language.msg(l10n_future_day),
                Language.msg(l10n_future_week), Language.msg(l10n_future_month)};
        final TextView preview = Ui.text(this, Language.msg(l10n_future_unlock_preview,
                durations[lockPreset], lockBlocks), Design.DIM(), 12, false);''')
    change('        final String[] labels = {"+1 hour", "+1 day", "+1 week", "+1 month"};\n','')
    change('''            presets.addView(Ui.ghost(this, labels[i], v -> {
                blocks[0] = opts[idx][1];
                preview.setText("Unlocks in ~" + labels[idx].substring(1) + " · " + blocks[0] + " blocks");
            }));''','''            presets.addView(Ui.ghost(this, Language.join("+", durations[i]), v -> {
                lockPreset = idx;
                lockBlocks = opts[idx][1];
                Language.setText(preview, Language.msg(l10n_future_unlock_preview, durations[idx], lockBlocks));
            }));''')
    change('        c.addView(presets);','''        final android.widget.HorizontalScrollView presetScroll = new android.widget.HorizontalScrollView(this);
        presetScroll.setHorizontalScrollBarEnabled(false);
        presetScroll.addView(presets);
        c.addView(presetScroll);''')
    change('''        final EditText recipient = Ui.field(this, "Recipient Mx… (leave blank to pay yourself)", false);
        c.addView(recipient);''','''        if (lockRecipient == null) lockRecipient = Ui.field(this, Language.msg(l10n_future_recipient), false);
        final EditText recipient = lockRecipient;
        attachField(c, recipient);''')
    change('            final String rcp = recipient.getText().toString().trim();', '''            final String rcp = recipient.getText().toString().trim();
            // Capture the delay alongside amount/recipient; queued work must not read a later edit.
            final long requestedBlocks = lockBlocks;''')
    change('guardian.createFutureCash(amt, "0x00", blocks[0], payoutHex)', 'guardian.createFutureCash(amt, "0x00", requestedBlocks, payoutHex)')
    change('"Lock a new payment"',msg('lockPayment'))
    change('"Lock it", v ->',msg('lock')+', v ->')
    change('"Couldn\'t resolve that recipient address."',msg('resolveFailed'))
    change('"✓ Locked " + amt + " MINIMA · matures block " + res.value',msg('locked','amt','res.value'))
    change('''"Once locked, nobody — not even you — can unlock it early. Collected "
                + "coins can ONLY go to the payout address; that is enforced on-chain."''',msg('lockWarning'))
    change('    private void collectAllSafe() {','''    private static void attachField(LinearLayout parent, EditText field) {
        final boolean focused = field.hasFocus();
        if (field.getParent() instanceof android.view.ViewGroup)
            ((android.view.ViewGroup) field.getParent()).removeView(field);
        Ui.styleField(field);
        parent.addView(field);
        if (focused) field.requestFocus();
    }

    private void collectAllSafe() {''')
    change('                toast(res.ok ? Language.msg(l10n_future_locked, amt, res.value) : res.error);\n                refresh();', '                toast(res.ok ? Language.msg(l10n_future_locked, amt, res.value) : res.error);\n                if (res.ok) runOnUiThread(() -> clearSubmittedDraft(amt, rcp, requestedBlocks));\n                refresh();')
    change('    private static void attachField(', '    /** Clear only the submitted draft; keep any edits made while that payment was queued. */\n    private void clearSubmittedDraft(String amount, String recipient, long blocks) {\n        if (lockAmount == null || lockRecipient == null || lockBlocks != blocks) return;\n        if (!amount.equals(lockAmount.getText().toString().trim())\n                || !recipient.equals(lockRecipient.getText().toString().trim())) return;\n        lockAmount.setText("");\n        lockRecipient.setText("");\n    }\n\n    private static void attachField(')
    start=s.index('    private void renderStakes()');end=s.index('    private View lockCard()',start)
    part=s[start:end]
    replacements={
        '"Your stakes"':msg('yourStakes'),
        'ready > 0 ? ready + " ready" : stakes.size() + " pending"':'ready > 0 ? '+msg('readyCount','ready')+' : '+msg('pendingCount','stakes.size()'),
        '"No stakes yet. Lock one below and it appears here, "\n                    + "soonest to unlock first."':msg('noStakes'),
        '" token"':msg('token'),
        '"reused payout"':msg('reusedPayout'),
        '"ready to collect"':msg('readyToCollect'),
        '"unlocks " + Ui.maturesIn(s.matureBlock, tip)':msg('unlocks','Ui.maturesIn(s.matureBlock, tip)'),
        '"block " + s.matureBlock + " → " + s.payout':msg('payoutBlock','s.matureBlock','s.payout'),
        '"Collect"':msg('collect'),
        '"Collect posted — confirms in about a block."':msg('collectPosted'),
    }
    for old,new in replacements.items():
        assert part.count(old)==1,('Stakes localization drift',old);part=part.replace(old,new)
    s=s[:start]+part+s[end:]
    p.write_text(s)

if __name__=='__main__':
    import sys
    adapt(Path(sys.argv[1]))
