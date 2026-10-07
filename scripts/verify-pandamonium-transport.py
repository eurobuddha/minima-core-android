#!/usr/bin/env python3
"""Architecture guard: bundled node clients must not regain a cross-process transport."""
from pathlib import Path
import re, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
A='{http://schemas.android.com/apk/res/android}'
errors=[]
for app in (ROOT/'integrated').iterdir():
    if not app.is_dir() or not (app/'src/main').exists():continue
    if "implementation project(':minimaapi')" not in (app/'build.gradle').read_text():errors.append(str(app)+': shared direct API dependency missing')
    clients=list((app/'src/main/java').rglob('NodeApi.java'))
    if len(clients)!=1 or 'new DirectNodeApi(' not in clients[0].read_text():errors.append(str(app)+': direct client missing')
    for p in (app/'src/main/java').rglob('*.java'):
        text=p.read_text()
        if re.search(r'new\s+MinimaAPI\s*\(|\bMINIMA_API_(?:CMD|FILE|NOTIFY|REGISTER|RESPONSE)\b|class\s+NodeTransport(?:Service)?\b|Intent\.ACTION_DELETE',text):errors.append(str(p.relative_to(ROOT))+': forbidden internal transport or package reset')
    tree=ET.parse(app/'src/main/AndroidManifest.xml')
    for component in tree.getroot().find('application'):
        if component.get(A+'process'):errors.append(str(app)+': secondary process')
        if component.get(A+'name','').endswith(('.NodeTransportService','.MinimaNotifyReceiver')):errors.append(str(app)+': legacy node component')
        if component.tag=='activity' and component.get(A+'exported')!='false':errors.append(str(app)+': exported bundled screen')
if errors:raise SystemExit('\n'.join(errors))
print('All bundled clients use DirectNodeApi; no internal IPC relay, notification receiver, secondary process or whole-package reset action remains.')
