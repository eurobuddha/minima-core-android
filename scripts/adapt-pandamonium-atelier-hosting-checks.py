"""Reuse Salon's saved-host checks and Pandamonium's explicit host navigation."""
from pathlib import Path
import shutil

def adapt(root):
    source=Path(__file__).parent/'pandamonium-sources/atelier'
    target=root/'atelier/src/main/java/com/eurobuddha/statenft'
    shutil.copy2(source/'HostingStore.java',target/'HostingStore.java')
    p=target/'MainActivity.java';s=p.read_text()
    part=(source/'HostingChecks.java.inc').read_text()
    messages={
        'CHANGED':('atelier_hosting_changed',''), 'STORAGE':('atelier_hosting_storage',''),
        'TESTING_NAME':('creative_hosting_testing_name',', op.profile.name()'),
        'TEST_TITLE':('atelier_hosting_test_title',', op.profile.name()'),
        'TESTED':('atelier_hosting_tested',', url'), 'TURBO_TESTED':('atelier_hosting_turbo_tested',', url'),
        'FAILED':('creative_hosting_failed',', error.getMessage()==null?error.getClass().getSimpleName():error.getMessage()'),
        'TRUST_TITLE':('atelier_hosting_verify_key',''), 'TRUST_BODY':('atelier_hosting_verify_body',', fp'),
        'PIN':('creative_hosting_trust_pin',''), 'CANCEL':('common_cancel',''), 'OK':('common_ok','')}
    for marker,(key,args) in sorted(messages.items(),key=lambda x:-len(x[0])):
        part=part.replace('MSG_'+marker,'Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_'+key+args+')')
    a=s.index('    private void testProfile(Hosting.Profile p)');b=s.index('    /** Explicit wallet backup',a)
    s=s[:a]+part+'\n'+s[b:]
    for old in ['        screenGeneration++;','        imageIo.shutdownNow();']:
        assert s.count(old)==1;s=s.replace(old,'        cancelHostingCheck();\n'+old)
    old='    @Override protected void onDestroy() {'
    assert s.count(old)==1
    s=s.replace(old,'    @Override protected void onStart() {\n        super.onStart();\n        hostingStopped=false;\n    }\n\n    @Override protected void onStop() {\n        hostingStopped=true;\n        cancelHostingCheck();\n        super.onStop();\n    }\n\n'+old)
    a=s.index('    private void openMinimaCore() {');b=s.index('    private void toast(',a)
    s=s[:a]+'''    private void openMinimaCore() {
        cancelHostingCheck();
        // Same explicit destination and task behavior as Pandamonium's drawer.
        Intent intent=new Intent();
        intent.setClassName(getPackageName(),"com.eurobuddha.minimacore.main.MainActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }

'''+s[b:]
    old='                toast(removed[0] + " regular NFT" + (removed[0] == 1 ? "" : "s")\n                        + " unfiled from collections — see Singles");'
    assert s.count(old)==1
    s=s.replace(old,'                toast(Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_atelier_gallery_unfiled, removed[0]));')
    p.write_text(s)
