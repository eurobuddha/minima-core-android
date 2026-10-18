"""Reuse Salon MiniDapp fixed-path GitHub updates for native profile publication."""
from pathlib import Path


def adapt(root):
    source = Path(__file__).parent/'pandamonium-sources/salon'
    folder = root/'salon/src/main/java/com/eurobuddha/salon'
    def change(s, old, new):
        assert s.count(old) == 1, ('Salon GitHub profile source drift', old)
        return s.replace(old, new)
    p=folder/'Hosting.java';s=p.read_text()
    s=change(s,'    /** Directory transport (IPFS backends):', '    /** Explicit opt-in to replacing only mutable profile files at their stable URLs. */\n    interface ProfileUploader {\n        String putProfileFile(byte[] bytes, String relPath, String mime) throws HostingException;\n    }\n\n    /** Directory transport (IPFS backends):');p.write_text(s)
    p=folder/'GithubUploader.java';s=p.read_text()
    s=change(s,'implements Hosting.Uploader {','implements Hosting.Uploader, Hosting.ProfileUploader {')
    s=change(s,' *  itself refuses overwrites with 422 — surfaced honestly. */',' *  itself refuses overwrites with 422. Only putProfileFile opts in to stable profile updates. */')
    s=change(s,'    @Override public boolean exists(', (source/'GithubProfileUpload.java.inc').read_text()+'\n    @Override public boolean exists(');p.write_text(s)
    p=folder/'MainActivity.java';s=p.read_text()
    cases=[]
    for reason,key in [('CONFIGURATION','configuration'),('READ','read'),('INVALID','invalid'),('CONFLICT','conflict'),('WRITE','write'),('UNAVAILABLE','unavailable')]:
        args=', failure.status' if reason in ['READ','CONFLICT','WRITE'] else ''
        cases.append('                case '+reason+': return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_github_'+key+args+');')
    extra='\n        if (error instanceof GithubUploader.ProfileFailure) {\n            GithubUploader.ProfileFailure failure = (GithubUploader.ProfileFailure) error;\n            switch (failure.reason) {\n'+'\n'.join(cases)+'\n            }\n        }'
    s=change(s,'    private CharSequence mediaError(Exception error) {','    private CharSequence mediaError(Exception error) {'+extra)
    s=change(s,'l10n_salon_setup_hosting_failed, e.getMessage()', 'l10n_salon_setup_hosting_failed, mediaError(e)')
    p.write_text(s)
