"""Bind Salon hosting tests and trust consent to the original account/destination."""
from pathlib import Path
import shutil

def adapt(root):
    source=Path(__file__).parent/'pandamonium-sources/salon'
    target=root/'salon/src/main/java/com/eurobuddha/salon'
    shutil.copy2(source/'HostingStore.java',target/'HostingStore.java')
    p=target/'MainActivity.java';s=p.read_text()
    part=(source/'HostingChecks.java.inc').read_text()
    messages={
        'CHANGED':('salon_publish_changed',''), 'STORAGE':('salon_publish_storage',''),
        'TESTING':('creative_hosting_testing',''), 'TESTING_NAME':('creative_hosting_testing_name',', op.profile.name()'),
        'TESTED':('creative_hosting_tested',', url'), 'FAILED':('creative_hosting_failed',', error.getMessage()'),
        'TRUST_TITLE':('creative_hosting_trust_title',''), 'TRUST_BODY':('creative_hosting_trust_body',', op.profile.cfgStr("host"), fp'),
        'PIN':('creative_hosting_trust_pin',''), 'CANCEL':('common_cancel',''), 'NOT_TRUSTED':('creative_hosting_not_trusted','')}
    for marker,(key,args) in sorted(messages.items(),key=lambda x:-len(x[0])):
        part=part.replace('MSG_'+marker,'Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_'+key+args+')')
    a=s.index('    private void testProfile(Hosting.Profile p)');b=s.index('    /* ================= node key',a)
    s=s[:a]+part+'\n'+s[b:]
    for old in ['        renderEpoch++;','        io.shutdownNow();']:
        assert s.count(old)==1;s=s.replace(old,'        cancelHostingCheck();\n'+old)
    p.write_text(s)
    # The complete Robolectric suite exceeds Gradle's default 512 MiB test heap.
    p=root/'salon/build.gradle';s=p.read_text()
    old='unitTests.includeAndroidResources = true'
    assert s.count(old)==1
    p.write_text(s.replace(old,old+"; unitTests.all { maxHeapSize = '2g' }"))
