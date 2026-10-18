"""Scope Salon picker/upload callbacks to their captured identity, host and target."""
from pathlib import Path
import re

def adapt(root):
    p=root/'salon/src/main/java/com/eurobuddha/salon/MainActivity.java';s=p.read_text()
    def msg(key,args=''):
        return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()+args+')'
    src=(Path(__file__).parent/'pandamonium-sources/salon/MediaUploads.java.inc').read_text()
    for name,key,args in [('BUSY','busy',''),('HOST','host',''),('CHOOSE','choose',''),('PICKER_FAILED','pickerFailed',''),('RESELECT','reselect',''),('CHANGED','changed',''),('SENDING','sending',''),('UPLOADING','uploading',''),('MESH_FULL','meshFull',', mibOf(usage), MAX_PROFILE_MESH_BYTES >> 20'),('FAILED','failed',', mediaError(error)'),('UPLOADED','uploaded',''),('READ_FAILED','readFailed',''),('SIZE_VIDEO','size',', 60'),('SIZE_AUDIO','size',', 25'),('NO_REFERENCE','noReference','')]:
        assert 'MSG_'+name in src;src=src.replace('MSG_'+name,msg('salonUpload.'+key,args))
    a=s.index('    private void pickMedia(');b=s.index('    @Override protected void onActivityResult(',a);s=s[:a]+src+s[b:]
    marker='''        super.onActivityResult(req, res, data);
''';assert marker in s;s=s.replace(marker,marker+'''        if (req >= PICK_AVATAR && req <= PICK_DM_IMG) { receiveMedia(req, res, data); return; }
''')
    a=s.index('        if (r == PICK_DM_IMG) {');b=s.index('    private void promptMediaTitle(',a)
    s=s[:a]+'    }\n\n'+s[b:]
    a=s.index('    private void attachDmMedia() {');b=s.index('    /* ---------------- messaging key backup',a)
    s=s[:a]+'''    private void attachDmMedia() {
        pickMedia(PICK_DM_IMG, "image/*");
    }

'''+s[b:]
    old='    private void go(Screen s) {';assert old in s;s=s.replace(old,old+'\n        if (screen != s) cancelMediaRequests();')
    old='        if (!peerpk.equals(threadPeer)) { replyBody';assert old in s;s=s.replace(old,'        if (!peerpk.equals(threadPeer)) { cancelMediaRequests(); replyBody')
    old='        io.shutdownNow();';assert old in s;s=s.replace(old,'        cancelMediaRequests();\n'+old)
    old='replyBody = ""; replyFrom = ""; replyPeer = ""; threadDraft = "";   // consumed — clear before the re-render'
    assert old in s
    s=s.replace(old, 'replyBody = ""; replyFrom = ""; replyPeer = "";\n        if (mediaRef == null || mediaRef.isEmpty()) threadDraft = ""; // A photo does not consume unsent text.')
    # Unknown activity results are not media uploads. Existing backup branches keep their own handling.
    p.write_text(s)
