"""Retained four-language onboarding fields and identity status messages."""
from pathlib import Path
import re,json

def adapt(root):
    p=root/'salon/src/main/java/com/eurobuddha/salon/MainActivity.java';s=p.read_text()
    def msg(key,args=''):
        resource='l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
        return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.'+resource+args+')'
    def change(old,new):
        nonlocal s
        assert old in s, ('Salon setup language drift',old)
        s=s.replace(old,new)
    change('private TextView btn(String label,','private TextView btn(CharSequence label,')
    change('private void toast(String m)','private void toast(CharSequence m)')
    change('private void addKvPlain(LinearLayout parent, String k, String v)',
           'private void addKvPlain(LinearLayout parent, CharSequence k, CharSequence v)')
    start=s.index('    private void addKvPlain(');end=s.index('    /** kv row:',start)
    s=s[:start]+s[start:end].replace('k.toUpperCase()','Language.upper(k)')+s[end:]
    change('private EditText field(LinearLayout parent, String label, String value, boolean secret, String hint)',
           'private EditText field(LinearLayout parent, CharSequence label, String value, boolean secret, CharSequence hint)')
    start=s.index('    private EditText field(');end=s.index('    private EditText fieldMulti(',start)
    s=s[:start]+s[start:end].replace('label.toUpperCase()','Language.upper(label)').replace('e.setHint(hint)','Language.hint(e, hint)')+s[end:]
    start=s.index('    private void renderOnboard(');end=s.index('    private void publishSalon(',start)
    part=s[start:end]
    pairs=[('№ 1 · Open your Salon', 'salonSetup.open'), ('Your identity becomes a signed token you own forever. Your page is a file you host and edit any time. First set hosting, then claim your handle.', 'salonSetup.intro'), ('Hosting', 'salonSetup.hosting'), ('Destination', 'salonSetup.destination'), ('none set — required', 'salonSetup.noDestination'), ('Set up hosting', 'salonSetup.setupHosting'), ('Manage hosting', 'salonSetup.manageHosting'), ('No server? Publish to Blossom — free public file hosting on the nostr network, zero setup.', 'salonSetup.blossomHelp'), ('Publish with no server (Blossom)', 'salonSetup.blossom'), ('Claim your handle', 'salonSetup.claimHandle'), ('Handle', 'salonSetup.handle'), ('Bio', 'salonSetup.bio'), ('e.g. eurobuddha', 'salonSetup.handleExample'), ('one line about you', 'salonSetup.bioHint'), ('Claim my Salon', 'salonSetup.claim'), ('Already claiming — please wait.', 'salonSetup.claiming'), ('Handle and display name are required.', 'salonSetup.required'), ('Set a hosting destination first.', 'salonSetup.hostingFirst'), ('Waiting for Minima Core.', 'salonSetup.waitingNode'), ('Fetching your key… try again in a moment.', 'salonSetup.fetchingKey'), ('Publishing your page…', 'salonSetup.publishing'), ('Page live. Checking identity…', 'salonSetup.checking'), ('Please remove { } or line breaks from your name/bio.', 'salonSetup.safeMetadata'), ('Minting… confirming (leave open).', 'salonSetup.confirming'), ("Minted — confirming. Reopen shortly, it'll adopt.", 'salonSetup.pending'), ('Your Salon is open.', 'salonSetup.opened'), ('No hosted page URL known yet.', 'salonSetup.noHostedUrl'), ('Restoring your page…', 'salonSetup.restoring'), ("Couldn't reach your hosted page.", 'salonSetup.unreachable'), ('Page restored from your hosting.', 'salonSetup.restored'), ('Nothing to restore — your hosted page is empty.', 'salonSetup.emptyHosted'), ('Display name', 'mail.displayName')]
    for literal,key in pairs:
        old=json.dumps(literal,ensure_ascii=False)
        assert old in part, ('Salon setup literal drift',literal)
        part=part.replace(old,msg(key))
    part=part.replace('"Hosting failed: " + e.getMessage()',msg('salonSetup.hostingFailed',', e.getMessage()'))
    part=part.replace('"Mint failed: " + json.optString("error", "rejected")',
        msg('salonSetup.mintFailed',', json.has("error") ? json.optString("error") : '+msg('salonSetup.rejected')))
    part=part.replace('"Mint failed: " + m',msg('salonSetup.mintFailed',', m'))
    part=part.replace('status.setText(', 'Language.setText(status, ')
    s=s[:start]+part+s[end:];p.write_text(s)
    p=root/'salon/build.gradle';s=p.read_text()
    s=s.replace("    testImplementation 'junit:junit:4.13.2'", "    testImplementation 'org.robolectric:robolectric:4.14.1'\n    testImplementation 'junit:junit:4.13.2'")
    p.write_text(s)
