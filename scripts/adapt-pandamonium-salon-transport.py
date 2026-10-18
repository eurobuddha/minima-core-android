"""Use the host's existing Maxima node and media service inside Salon."""
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

def adapt(root):
    ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
    project=Path(__file__).resolve().parents[1]
    java=root/'salon/src/main/java/com/eurobuddha/salon'
    shutil.copy2(project/'scripts/pandamonium-sources/salon/MaximaLink.java',java/'MaximaLink.java')
    (java/'MaximaLinkReceiver.java').unlink()
    p=java/'SalonNotifyReceiver.java';s=p.read_text().replace('{@link MaximaLinkReceiver}', '{@link MaximaLink}');p.write_text(s)
    p=root/'salon/build.gradle';s=p.read_text().replace('dependencies {', "dependencies {\n    implementation project(':maxima-core')");p.write_text(s)
    p=root/'salon/src/main/AndroidManifest.xml';tree=ET.parse(p);manifest=tree.getroot();a='{http://schemas.android.com/apk/res/android}'
    queries=manifest.find('queries')
    for child in list(queries):
        if child.get(a+'name')=='com.eurobuddha.maxima.app':queries.remove(child)
    for child in list(manifest.findall('uses-permission')):
        if child.get(a+'name')=='com.eurobuddha.maxima.app.permission.USE_MAXIMA':manifest.remove(child)
    app=manifest.find('application')
    for child in list(app.findall('receiver')):
        if child.get(a+'name')=='com.eurobuddha.salon.MaximaLinkReceiver':app.remove(child)
    ET.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)
    p=java/'MainActivity.java';s=p.read_text()
    start=s.index('        // Maxima transport handshake:');end=s.index('        MaximaLink.connect(this);',start)
    s=s[:start]+'        // Refresh the embedded host transport; its service owns start and stop.\n'+s[end:]
    start=s.index('                card.addView(Design.note(this, "You are the server.');end=s.index('                break;',start)
    s=s[:start]+"""                TextView meshNote=Design.note(this, "");
                Language.setText(meshNote, Language.join(
                        Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_transport_mesh_help), "\\n\\n",
                        Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_transport_mesh_usage, mibOf(meshUsageBytes()), MAX_PROFILE_MESH_BYTES >> 20), "\\n\\n",
                        Language.msg(MaximaLink.isReady(this) ? com.eurobuddha.minimacore.i18n.R.string.l10n_salon_transport_connected
                                : com.eurobuddha.minimacore.i18n.R.string.l10n_salon_transport_connecting)));
                card.addView(meshNote, lp(0, 6, 0, 2));
"""+s[end:]
    p.write_text(s)
