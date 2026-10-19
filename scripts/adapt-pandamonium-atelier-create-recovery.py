"""Atelier CREATE discovery and durable no-repost recovery; preserve the existing mint/burial engines."""
from pathlib import Path
import shutil

def adapt(root):
    project=Path(__file__).resolve().parents[1]
    src=project/'scripts/pandamonium-sources/atelier'
    folder=root/'atelier/src/main/java/com/eurobuddha/statenft'
    def replace(s,old,new):
        assert s.count(old)==1,('Atelier create drift',old,s.count(old))
        return s.replace(old,new)
    p=folder/'MintEngine.java';s=p.read_text()
    a=s.index('    private static void phaseCreate(');b=s.index('    private static void postTokenCreate(',a)
    s=s[:a]+(src/'create-discovery.inc').read_text()+'\n'+s[b:]
    a=s.index('        if (row.optInt("posted", 0) == 1) {',s.index('    private static void postTokenCreate('));b=s.index('        StateNft.Meta m = metaFromRow(row);',a)
    s=s[:a]+'''        if (LocalStore.createAttempted(ctx,row)) {
            setError(ctx,row,"A collection submission is already recorded. Checking for its confirmation; recovery will not mint another copy.",done);
            return;
        }
'''+s[b:]
    target='        cmd(node, cmd, new Cb() {'
    start=s.index('    private static void postTokenCreate(');end=s.index('    private static void phaseMove(',start)
    part=s[start:end]
    part=replace(part,target,'''        if (!LocalStore.reserveCreate(ctx,row,tip)) {
            done.done("Submission could not be safely recorded, or is already pending. No new collection was submitted."); return;
        }
        put(row,"posted",1); put(row,"postedat",tip);
'''+target)
    s=s[:start]+part+s[end:]
    s=replace(s,'            put(row, "posted", 0);\n            setPhase(ctx, row, "CREATE", done);','            setPhase(ctx, row, "CREATE", done);')
    s=replace(s,'if ("SPLIT".equals(row.optString("phase"))) return false;','if ("SPLIT".equals(row.optString("phase")) || "CREATE".equals(row.optString("phase"))) return false;')
    s=replace(s,'    private static final int CREATE_TIMEOUT = 20;\n','')
    p.write_text(s)
    p=folder/'LocalStore.java';s=p.read_text();s=replace(s,'    private LocalStore() {}','    private LocalStore() {}\n'+(src/'create-store.inc').read_text());s=replace(s,'                String phase = cur.optString("phase");','                String phase = cur.optString("phase");\n                if ("CREATE".equals(row.optString("phase"))) {\n                    if (!cur.optString("tokenid").isEmpty()) { sCollections=arr; return; }\n                    if (cur.has("createchoice")) {\n                        try { row.put("createchoice",cur.get("createchoice")); } catch (Exception ignored) { }\n                    }\n                }');p.write_text(s)
    p=folder/'MainActivity.java';s=p.read_text()
    s=replace(s,'    private void renderDetail() {',(src/'create-choice-ui.inc').read_text()+'    private void renderDetail() {')
    s=replace(s,'        if ("NEEDIMAGES".equals(m.phase)) body.addView(recoveryPanel(m), lpm(0, 0, 0, 12));','        showCreateChoices(m);\n        if ("NEEDIMAGES".equals(m.phase)) body.addView(recoveryPanel(m), lpm(0, 0, 0, 12));')
    s=replace(s,'        EditText confirm = input(m.name);','        TextView tokenIdentity = Design.hash(this,m.tokenid);\n        tokenIdentity.setTextIsSelectable(true);\n        body.addView(tokenIdentity,lpm(0,0,0,12));\n        if (!Util.isValidHexId(m.tokenid) || Util.MINIMA_TOKENID.equals(m.tokenid)) {\n            body.addView(Design.note(this,"This mint has no linked token yet. Return to the collection and select an existing token before burying anything.")); return;\n        }\n        EditText confirm = input(m.name);\n        confirm.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);')
    s=replace(s,'if (!m.name.equals(confirm.getText().toString().trim())) {','if (!m.name.trim().equalsIgnoreCase(confirm.getText().toString().trim())) {')
    p.write_text(s)
    p=root/'atelier/build.gradle';s=p.read_text();s=replace(s,"dependencies {","dependencies {\n    testImplementation files('../../app/libs/minima.jar')");p.write_text(s)
    tests=project/'scripts/pandamonium-tests/atelier'
    shutil.copytree(tests,root/'atelier',dirs_exist_ok=True)
