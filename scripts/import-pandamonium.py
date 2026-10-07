#!/usr/bin/env python3
"""Snapshot the existing native apps as isolated Android libraries. Run explicitly, never at build time.
Financial algorithms are retained; explicit adapters change packaging, node transport and host lifecycle boundaries.
The lock file records source commits and hashes, including local uncommitted source changes.
"""
from pathlib import Path
import hashlib, json, re, shutil, subprocess, sys, tempfile, xml.etree.ElementTree as ET
import importlib.util
spec = importlib.util.spec_from_file_location("direct_adapt", Path(__file__).with_name("adapt-pandamonium-direct.py"))
direct_adapt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(direct_adapt)

ROOT = Path(__file__).resolve().parents[1]
APPS = ['ethwallet','atomix','pandapools','futurecash-next','pandadex','casino','blockexplorer','filez','history','vestr','entropy','terminalide','mail']
HOST = 'com.eurobuddha.pandamonium'
ANDROID = '{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android', ANDROID[1:-1])
ET.register_namespace('tools', 'http://schemas.android.com/tools')

def import_app(name, target_root=None):
    source = ROOT.parent / name
    target = (target_root or ROOT / 'integrated') / name
    if target.exists():
        raise SystemExit(f'{target} exists: review/remove the snapshot explicitly before reimporting')
    gradle = (source/'app/build.gradle').read_text()
    namespace = re.search(r"namespace '([^']+)'", gradle)[1]
    version = re.search(r'versionName "([^"]+)"', gradle)[1]
    code = re.search(r'versionCode (\d+)', gradle)[1]
    prefix = 'pm_' + name.replace('-', '_') + '_'
    resources = {}
    for path in (source/'app/src/main/res').rglob('*'):
        if not path.is_file(): continue
        kind = path.parent.name.split('-')[0]
        if kind != 'values': resources[(kind,path.name.split('.')[0])] = prefix + path.name.split('.')[0]
        if path.suffix == '.xml':
            text = path.read_text()
            for ident in re.findall(r'@\+id/([\w.]+)',text): resources[('id',ident)] = prefix+ident
            if kind == 'values':
                tree = ET.fromstring(text)
                for child in tree:
                    ident = child.get('name')
                    if ident:
                        typ = child.get('type') if child.tag == 'item' else child.tag
                        typ = {'string-array':'array','integer-array':'array','declare-styleable':'styleable'}.get(typ,typ)
                        resources[(typ,ident)] = prefix+ident
    def transform(text, java=False, filename=""):
        # Resource names are global after library merging; isolate both declarations and references.
        for (kind,old),new in sorted(resources.items(),key=lambda x:-len(x[0][1])):
            text = re.sub(r'(@\+?'+re.escape(kind)+r'/)'+re.escape(old)+r'(?![\w.])',lambda m:m[1]+new,text)
            if java:
                text = re.sub(r'(?<!android\.)(\bR\.'+re.escape(kind)+r'\.)'+re.escape(old.replace('.','_'))+r'\b',lambda m:m[1]+new.replace('.','_'),text)
        if java:
            # ETH Wallet and AtomiX both ship com.eurobuddha.comms helpers with different implementations.
            text = text.replace('com.eurobuddha.comms',namespace+'.integratedcomms')
            # Preserve PandaDEX's bounded SDK, with its own class namespace.
            if name == 'pandadex':
                text = text.replace('com.eurobuddha.minimaapi',namespace+'.integratedsdk')
                text = text.replace('intent.setPackage(MinimaAPIMessages.MINIMA_BASE_CLASS);','intent.setPackage("'+HOST+'");')
            # Keep protocol action strings stable; all actual node destinations are internal.
            if filename != 'MinimaAPIMessages.java':
                text = text.replace('"com.eurobuddha.minimacore"', '"'+HOST+'"')
            if name in ('pandapools', 'pandadex'):
                text = text.replace('"node-transport"', '"'+name+'-node-transport"')
            if filename == 'Bip39Test.java':
                text = text.replace('src/main/res/raw/bip39_english.txt', 'src/main/res/raw/pm_entropy_bip39_english.txt')
            if filename == 'CreationEvidenceTest.java':
                text = text.replace('../contract/reviews/0.4.4', 'src/test/resources/creation-evidence')
            # Launch shortcuts and Filez's explicit URI grant must address the embedded node.
            text = text.replace('getLaunchIntentForPackage("com.eurobuddha.minimacore")','getLaunchIntentForPackage("'+HOST+'")')
            text = text.replace('grantUriPermission("com.eurobuddha.minimacore"','grantUriPermission("'+HOST+'"')
            text = text.replace('getPackageName() + ".fileprovider"','getPackageName() + ".'+name.replace('-','_')+'.fileprovider"')
            text = text.replace('"com.eurobuddha.filez.fileprovider"','"'+HOST+'.filez.fileprovider"')
            # Never let ETH vault recovery delete the casino's encryption master key.
            if name in ('ethwallet','casino'):
                alias = 'pandamonium_'+name+'_master'
                text = text.replace('new MasterKey.Builder(ctx)', 'new MasterKey.Builder(ctx, "'+alias+'")')
                text = text.replace('"_androidx_security_master_key_"','"'+alias+'"')
            # Base's foreground node uses 1001 too; keep its notification independent.
            if name == 'casino': text = text.replace('int FG_ID = 1001;', 'int FG_ID = 5101;')
        return text
    hashes={}
    for sub in ['main','test']:
        start=source/'app/src'/sub
        if not start.exists():continue
        for path in start.rglob('*'):
            if not path.is_file():continue
            rel=path.relative_to(source/'app')
            hashes[str(rel)] = hashlib.sha256(path.read_bytes()).hexdigest()
            dest=target/rel
            if 'res' in rel.parts and path.parent.name.split('-')[0] != 'values':
                dest=dest.with_name(prefix+dest.name)
            dest.parent.mkdir(parents=True,exist_ok=True)
            if path.suffix not in ('.xml','.java'):
                shutil.copy2(path,dest);continue
            text=transform(path.read_text(),path.suffix=='.java',path.name)
            if path.parent.name.split('-')[0]=='values':
                tree=ET.fromstring(text)
                for child in tree:
                    old=child.get('name')
                    if old:
                        typ=child.get('type') if child.tag=='item' else child.tag
                        typ={'string-array':'array','integer-array':'array','declare-styleable':'styleable'}.get(typ,typ)
                        if (typ,old) in resources:child.set('name',resources[(typ,old)])
                    if child.tag=='style' and ('style',child.get('parent')) in resources:
                        child.set('parent',resources[('style',child.get('parent'))])
                text=ET.tostring(tree,encoding='unicode')
            dest.write_text(text)
    # This parser regression also depends on four archived disposable-chain fixtures.
    if name == 'pandadex':
        for path in (source/'contract/reviews/0.4.4').glob('java-create-*.json'):
            dest=target/'src/test/resources/creation-evidence'/path.name
            dest.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(path,dest)
            hashes[str(path.relative_to(source))]=hashlib.sha256(path.read_bytes()).hexdigest()
    manifest=target/'src/main/AndroidManifest.xml'
    tree=ET.parse(manifest);app=tree.getroot().find('application');theme=app.get(ANDROID+'theme')
    app.attrib.clear()  # base owns backup policy, application class, icon and label
    for component in list(app):
        component_name=component.get(ANDROID+'name','')
        if component_name.startswith('.'):
            component.set(ANDROID+'name',namespace+component_name)
        parent=component.get(ANDROID+'parentActivityName','')
        if parent.startswith('.'):component.set(ANDROID+'parentActivityName',namespace+parent)
        if component.tag=='activity':
            component.set(ANDROID+'exported','false')
            if theme and not component.get(ANDROID+'theme'):component.set(ANDROID+'theme',theme)
            for filt in list(component.findall('intent-filter')):
                if any(c.get(ANDROID+'name')=='android.intent.category.LAUNCHER' for c in filt.findall('category')):component.remove(filt)
        if component.tag=='provider':
            provider=namespace+'.IntegratedFileProvider'
            component.set(ANDROID+'name',provider)
            component.set(ANDROID+'authorities','${applicationId}.'+name.replace('-','_')+'.fileprovider')
            java=target/'src/main/java'/Path(*namespace.split('.'))/'IntegratedFileProvider.java'
            java.parent.mkdir(parents=True,exist_ok=True)
            java.write_text('package '+namespace+';\npublic final class IntegratedFileProvider extends androidx.core.content.FileProvider {}\n')
        if component.get(ANDROID+'process')==':nodeipc':component.set(ANDROID+'process',':'+name.replace('-','_')+'_nodeipc')
    ET.indent(tree,space='    ');tree.write(manifest,encoding='unicode',xml_declaration=True)
    deps=[]
    for line in gradle.splitlines():
        line=line.strip()
        if re.match(r'(implementation|testImplementation|androidTestImplementation)\b',line) and 'files(' not in line:
            deps.append(line)
    if name!='pandadex':deps.append("implementation project(':minimaapi')")
    if not any('junit:' in line or 'libs.junit' in line for line in deps):deps.append("testImplementation 'junit:junit:4.13.2'")
    if not any('org.json:json' in line for line in deps):deps.append("testImplementation 'org.json:json:20240303'")
    (target/'build.gradle').write_text('''// Imported native feature; see ../sources.lock.json and scripts/import-pandamonium.py.
plugins { alias(libs.plugins.android.library) }
android {
    namespace '%s'
    compileSdk 36
    defaultConfig {
        minSdk 28
        buildConfigField 'String', 'VERSION_NAME', '"%s"'
        buildConfigField 'int', 'VERSION_CODE', '%s'
    }
    buildFeatures { buildConfig true }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
    }
}
dependencies {
    %s
}
'''%(namespace,version,code,'\n    '.join(deps)))
    for f in ['LICENSE','NOTICE']:
        if (source/f).exists():shutil.copy2(source/f,target/f)
    revision=subprocess.check_output(['git','-C',str(source),'rev-parse','HEAD'],text=True).strip()
    return {'source':'../'+name,'revision':revision,'version':version,'namespace':namespace,'files':hashes,'resource_prefix':prefix}

if __name__=='__main__':
    if sys.argv[1:] == ['--verify']:
        # Recreate in a disposable directory without touching the checked-in snapshot.
        with tempfile.TemporaryDirectory(prefix='pandamonium-import-') as directory:
            output=Path(directory)
            lock={name:import_app(name,output) for name in APPS}
            direct_adapt.adapt(output)
            failures=[]
            for path in output.rglob('*'):
                if path.is_file():
                    existing=ROOT/'integrated'/path.relative_to(output)
                    if not existing.is_file() or path.read_bytes()!=existing.read_bytes():
                        failures.append(str(path.relative_to(output)))
            for name in APPS:
                for path in (ROOT/'integrated'/name/'src').rglob('*'):
                    if path.is_file() and not (output/path.relative_to(ROOT/'integrated')).is_file():
                        failures.append('unexpected source: '+str(path.relative_to(ROOT/'integrated')))
            if lock!=json.loads((ROOT/'integrated/sources.lock.json').read_text()):failures.append('sources.lock.json')
            if failures:raise SystemExit('Snapshot differs: '+', '.join(failures))
            print('Verified all source snapshots and provenance against the importer.')
    elif sys.argv[1:]:
        raise SystemExit('Usage: import-pandamonium.py [--verify]')
    else:
        lock={name:import_app(name) for name in APPS}
        direct_adapt.adapt(ROOT/'integrated')
        (ROOT/'integrated/sources.lock.json').write_text(json.dumps(lock,indent=2)+'\n')
        print('Imported',len(lock),'native feature libraries.')
