"""Bind remaining Future Cash panels; preserve stored logs and financial operations."""
from pathlib import Path
import json,re

# Explicit UI-only catalog entries, populated from the reviewed panel translations.
KEYS = ['guardianStarted', 'notConnected', 'startNode', 'safeReused', 'freshDestination', 'guardianOff', 'needsDestination', 'onlyOpen', 'pickDestination', 'checking', 'readingStakes', 'watchingRisk', 'maturingRescue', 'autoWaiting', 'autoWaitingBody', 'protected', 'watchClosed', 'cleanWait', 'chain', 'daemon', 'running', 'off', 'needsSetup', 'daemonOn', 'daemonSetup', 'daemonOff', 'sweepsTo', 'thisNodeCopy', 'externalCopy', 'stopGuardian', 'startGuardian', 'pickRescue', 'readySafe', 'safeMatured', 'noSafe', 'autoOn', 'turnOff', 'autoOff', 'turnOnAuto', 'collectAll', 'riskStakes', 'riskMatured', 'riskPending', 'noRiskWatching', 'noRisk', 'rescueOnCopy', 'destinationCopy', 'thisNode', 'external', 'vaultLocked', 'setupRescue', 'changeDestination', 'stopRescue', 'rescueWhere', 'rescueLocalBody', 'externalHint', 'rescueTitle', 'cancel', 'freshLocal', 'useExternal', 'rescueAddress', 'checkingAddress', 'externalRescue', 'verifiedClean', 'reuseUnverified', 'activityEmpty', 'resetHistory', 'resetTitle', 'resetBody', 'reset', 'retiredCoins', 'recoverBody', 'recoverCoins', 'recovered', 'nothingRecover', 'harden', 'hardenBody', 'emptyRetire', 'holdsCoins', 'retire', 'retired', 'sweep', 'swept', 'nothingSwept', 'allRetired', 'retireWarning', 'collectsPosted', 'nothingCollect', 'helpQuick', 'helpQuickBody', 'helpRace', 'helpRaceBody', 'helpOutage', 'helpOutageBody', 'helpMatured', 'helpMaturedBody', 'helpHarden', 'helpHardenBody', 'activity']

SOURCE_TEXT = {'helpQuickBody': '1. Start the embedded node in Minima Core.\n2. Start the guardian on the Guardian tab.\n3. Stakes tab: enter an amount, pick when it unlocks, press Lock it.\n\nWhen the date arrives the guardian collects it for you, safely.', 'helpOutageBody': "Collecting carries on. Your last good result stays in force, and the app re-checks it against your node's own key counters — no internet needed. It only pauses if a key has actually signed again since, or after 24 hours without a successful audit."}

def adapt(root):
    folder=root/'futurecash-next/src/main/java/com/eurobuddha/futurecashnext'
    p=folder/'MainActivity.java';s=p.read_text()
    def change(old,new,count=1):
        nonlocal s
        assert s.count(old)==count,('Future Cash panel drift',old,s.count(old))
        s=s.replace(old,new)
    def msg(key,*args):
        key=re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).lower()
        return 'Language.msg(l10n_future_'+key+('' if not args else ', '+', '.join(args))+')'
    change('import android.app.AlertDialog;','import androidx.appcompat.app.AlertDialog;\nimport com.eurobuddha.minimacore.i18n.LanguageDialog;')
    change('    private LinearLayout content, tabsBar;', '''    private final java.util.Set<AlertDialog> dialogs = new java.util.HashSet<>();
    private LanguageDialog dialog() {
        final Context themed = new androidx.appcompat.view.ContextThemeWrapper(this, Design.isDark()
                ? R.style.pm_futurecash_next_Base_Theme_FutureCash_DialogDark
                : R.style.pm_futurecash_next_Base_Theme_FutureCash_Light);
        return new LanguageDialog(themed) {
            @Override public AlertDialog create() {
                AlertDialog value = super.create();
                dialogs.add(value);
                value.setOnDismissListener(d -> dialogs.remove(value));
                return value;
            }
        };
    }

    private LinearLayout content, tabsBar;''')
    change('    @Override protected void onDestroy() {\n        super.onDestroy();','''    @Override protected void onDestroy() {
        for (AlertDialog value : new java.util.ArrayList<>(dialogs)) value.dismiss();
        dialogs.clear();
        super.onDestroy();''')
    change('new AlertDialog.Builder(this, Design.dialogTheme())','dialog()',2)
    change('        final LinearLayout actions = Ui.row(this);','        final LinearLayout actions = Ui.column(this);')
    # Allow translated action labels to wrap without competing horizontally.
    for name,endname in [('safeCard','riskCard'),('riskCard','rescueDialog')]:
        start=s.index('    private View '+name+'(');end=s.index('    private ',start+15)
        part=s[start:end];assert part.count('final LinearLayout row = Ui.row(this);')==1
        s=s[:start]+part.replace('final LinearLayout row = Ui.row(this);','final LinearLayout row = Ui.column(this);')+s[end:]
    change('final String headline;', 'final CharSequence headline;')
    change('String detail', 'CharSequence detail',3)
    change('final String body;', 'final CharSequence body;')
    change('private View helpCard(String title, String body)', 'private View helpCard(CharSequence title, CharSequence body)')
    changes={
        '"Watching — " + atRisk + " at-risk address" + (atRisk == 1 ? "" : "es")':msg('watchingRisk','atRisk'),
        '"Auto-collect is OFF — " + n + " stake" + (n == 1 ? "" : "s") + " waiting"':msg('autoWaiting','n'),
        '''"The guardian is watching your stakes, even with the app closed."
                   + (cfg.is(Cfg.AUTO_COLLECT_SAFE, false) ? ""
                      : " Auto-collect is off, so clean matured stakes will wait for you; "
                        + "at-risk ones are still rescued.")''':'Language.join('+msg('watchClosed')+', cfg.is(Cfg.AUTO_COLLECT_SAFE, false) ? "" : '+msg('cleanWait')+')',
        '''Ui.amount(String.valueOf(st.readySafeA)) + " MINIMA in " + (int) st.readySafeN
                  + " matured stake" + (st.readySafeN == 1 ? "" : "s") + " on clean addresses."''':msg('safeMatured','Ui.amount(String.valueOf(st.readySafeA))','(int) st.readySafeN'),
        '''Ui.amount(String.valueOf(st.readyRiskA)) + " MINIMA in " + (int) st.readyRiskN
                    + " matured stake(s) paying out to REUSED addresses — rescuing to safety now."''':msg('riskMatured','Ui.amount(String.valueOf(st.readyRiskA))','(int) st.readyRiskN'),
        '''Ui.amount(String.valueOf(st.pendRiskA)) + " MINIMA in " + (int) st.pendRiskN
                    + " at-risk stake(s) maturing (soonest "
                    + Ui.maturesIn((long) st.pendRiskSoonest, tip) + "). The guardian collects and "
                    + "sweeps each the moment it matures."''':msg('riskPending','Ui.amount(String.valueOf(st.pendRiskA))','(int) st.pendRiskN','Ui.maturesIn((long) st.pendRiskSoonest, tip)'),
        '''(rescueOn ? "rescue ON → " : "destination: ")
                    + (guardian.wallet().sameNodeMode() ? "this node" : "external") + " · tap to copy"''':'Language.msg(rescueOn ? l10n_future_rescue_on_copy : l10n_future_destination_copy, guardian.wallet().sameNodeMode() ? '+msg('thisNode')+' : '+msg('external')+')',
        '"Rescue ON → " + r.value':msg('rescueAddress','r.value'),
        '''"Rescue ON → external " + addr
                          + (count == 0 ? " (verified clean)" : " (reuse check unverified — will re-check)")''':msg('externalRescue','addr','count == 0 ? '+msg('verifiedClean')+' : '+msg('reuseUnverified')),
        '"⚠ " + n + " coin(s) stuck on retired addresses"':msg('retiredCoins','n'),
        '"Recover " + n + " coin(s)"':msg('recoverCoins','n'),
        '"Recovered " + r.swept + " coin(s) to " + r.dest':msg('recovered','r.swept','r.dest'),
        '"holds " + ra.coins + " coin(s) — sweep them off first"':msg('holdsCoins','ra.coins'),
        '"Swept " + res.swept + " coin(s) → " + res.dest':msg('swept','res.swept','res.dest'),
        '"Posted " + done + " collect(s) — they confirm in about a block."':msg('collectsPosted','done'),
    }
    for old,new in changes.items():change(old,new)
    # Collapse only literal concatenations when matching reviewed UI copy. Skip comments.
    catalog=json.loads((Path(__file__).resolve().parent.parent/'localization/catalog.json').read_text())
    lookup={SOURCE_TEXT.get(key, catalog['future.'+key]['en']):key for key in KEYS}
    token=r'"(?:\\.|[^"\\])*"'
    expression=re.compile(r'(?P<comment>//[^\n]*|/\*[\s\S]*?\*/)|(?P<literal>'+token+r'(?:\s*\+\s*'+token+r')*)')
    found=set()
    def literal(match):
        if match.group('comment') is not None:return match.group(0)
        original=match.group(0);value=''.join(json.loads(t) for t in re.findall(token,original))
        if value not in lookup:return original
        key=lookup[value];found.add(key);return msg(key)
    s=expression.sub(literal,s)
    dynamic={'watchingRisk','autoWaiting','watchClosed','cleanWait','safeMatured','riskMatured','riskPending','rescueOnCopy','destinationCopy','thisNode','external','rescueAddress','externalRescue','verifiedClean','reuseUnverified','retiredCoins','recoverCoins','recovered','holdsCoins','swept','collectsPosted'}
    assert set(KEYS)-found-dynamic==set(),('Unbound Future Cash messages',set(KEYS)-found-dynamic)
    p.write_text(s)
    # Material-compatible light counterpart for the shared LanguageDialog builder.
    p=root/'futurecash-next/src/main/res/values/themes.xml';s=p.read_text()
    assert 'pm_futurecash_next_Base.Theme.FutureCash.Light' not in s
    s=s.replace('</resources>','''    <style name="pm_futurecash_next_Base.Theme.FutureCash.DialogDark" parent="pm_futurecash_next_Base.Theme.FutureCash">
        <item name="colorSurface">#12151C</item>
        <item name="colorSurfaceContainerHigh">#12151C</item>
        <item name="colorOnSurface">#EEF0F4</item>
        <item name="colorOnSurfaceVariant">#8B909C</item>
    </style>
    <style name="pm_futurecash_next_Base.Theme.FutureCash.Light" parent="Theme.Material3.Light.NoActionBar">
        <item name="colorPrimary">#F7931A</item>
        <item name="colorAccent">#F7931A</item>
        <item name="colorSurface">#FFFFFF</item>
        <item name="colorSurfaceContainerHigh">#FFFFFF</item>
        <item name="colorOnSurface">#181B22</item>
        <item name="colorOnSurfaceVariant">#6F7583</item>
        <item name="android:colorBackground">#F3F4F7</item>
    </style>
</resources>''')
    p.write_text(s)

if __name__=='__main__':
    import sys
    adapt(Path(sys.argv[1]))
