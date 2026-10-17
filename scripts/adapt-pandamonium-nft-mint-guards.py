"""Keep delayed mint callbacks bound to the reviewed draft and selected collection."""
from pathlib import Path
import shutil

def adapt(root):
 project=Path(__file__).resolve().parents[1];folder=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
 def replace(s,old,new,n=1):
  assert s.count(old)==n,('NFT mint guard source drift',old,s.count(old),n)
  return s.replace(old,new)
 p=folder/'MintView.java';s=p.read_text()
 s=replace(s,'    private void onReviewCollection() {',(project/'scripts/pandamonium-sources/NFTwallet/mint-draft-guard.inc').read_text()+'    private void onReviewCollection() {\n        final long generation = ++collectionReviewGeneration;\n        collectionStarting = false;')
 s=replace(s,'        showConfirm("Start minting this collection?", body, "Start mint →", () ->\n                startCollection(name, size, desc, base, ext, iconF, extUrl, webv));','        final CollectionReview review = new CollectionReview();\n        showConfirm("Start minting this collection?", body, "Start mint →", () ->\n                startCollection(name, size, desc, base, ext, iconF, extUrl, webv, generation, review));')
 s=replace(s,'final String extUrl, final String webv) {\n        status(cStatus,','final String extUrl, final String webv, final long generation, final CollectionReview review) {\n        if (collectionStarting || !acceptCollectionReview(generation, review)) return;\n        collectionStarting = true;\n        status(cStatus,')
 start=s.index('    private void startCollection(');end=s.index('    // ===================== PROGRESS',start);part=s[start:end]
 part=replace(part,'            @Override public void onResult(JSONObject json) {','            @Override public void onResult(JSONObject json) {\n                if (!finishCollectionLookup(generation, review)) return;')
 part=replace(part,'if (pk.isEmpty() || addr.isEmpty())','if (!json.optBoolean("status", false) || !Util.isValidHexId(pk) || !Util.isValidHexId(addr))')
 part=part.replace('colEmbedMode ?','review.embed ?').replace('colImages.get(i)','review.images.get(i)')
 part=replace(part,'            @Override public void onError(String message) {','            @Override public void onError(String message) {\n                if (!finishCollectionLookup(generation, review)) return;')
 s=s[:start]+part+s[end:]
 # Form-owned image work must not refill a replacement form after a successful mint.
 s=replace(s,'    private void prepareIconDefault(final String first) {','    private void prepareIconDefault(final String first) {\n        final LinearLayout sourceForm = colForm;')
 s=replace(s,'                if (!first.equals(colIconDefaultSource)) return;', '                if (sourceForm != colForm || act.isDestroyed() || !first.equals(colIconDefaultSource)) return;')
 s=replace(s,'    private void pickCollectionIcon() {\n        act.pickImage(uri -> {', '    private void pickCollectionIcon() {\n        final LinearLayout sourceForm = colForm;\n        act.pickImage(uri -> {\n            if (sourceForm != colForm || act.isDestroyed()) return;')
 s=replace(s,'                    if (result.isEmpty()) { Language.setText(cIconNote,', '                    if (sourceForm != colForm || act.isDestroyed()) return;\n                    if (result.isEmpty()) { Language.setText(cIconNote,')
 s=replace(s,'    private void pickCollectionImages(final int startIdx) {\n        act.pickImages(uris -> {','    private void pickCollectionImages(final int startIdx) {\n        final LinearLayout pickedForm = colForm;\n        act.pickImages(uris -> {\n            if (pickedForm != colForm || act.isDestroyed()) return;')
 s=replace(s,'compressInto(uris, targets, (idx, b64) -> colImages.put(idx, b64),\n                    msg -> Language.setText(cImagesNote, MintMessages.message(msg)),\n                    () -> { buildImageSlots(); reportFill(targets.size(), uris.size(), size); });','compressInto(uris, targets, (idx, b64) -> { if (pickedForm == colForm && !act.isDestroyed()) colImages.put(idx, b64); },\n                    msg -> { if (pickedForm == colForm && !act.isDestroyed()) Language.setText(cImagesNote, MintMessages.message(msg)); },\n                    () -> { if (pickedForm == colForm && !act.isDestroyed()) { buildImageSlots(); reportFill(targets.size(), uris.size(), size); } });')
 s=replace(s,'boolean needImage = !"BURY".equals(phase)', 'boolean needImage = coinid.isEmpty() && !"DONE".equals(phase) && !MintDriver.isStuck(row) && !"BURY".equals(phase)')
 s=replace(s,'        final int size = row.optInt("size", 0);\n        if (size <= 0) return;','        final long selectedRowId = row.optLong("id", -1);\n        final int size = row.optInt("size", 0);\n        if (selectedRowId < 1 || size < 2 || size > 20) return;')
 s=replace(s,'            compressInto(uris, targets,\n                    (idx, b64) -> saveProgressImage(idx, b64),','            final int[] saved = {0};\n            compressInto(uris, targets,\n                    (idx, b64) -> { if (!act.isDestroyed() && MintDriver.saveProgressImage(act, selectedRowId, idx, b64)) saved[0]++; },')
 old='''                        refresh();
                        Toast.makeText(act, MintMessages.message("Images saved — stamping resumes on the next block."), Toast.LENGTH_SHORT).show();
                        act.mintEngineTick();'''
 new='''                        if (act.isDestroyed()) return;
                        if (screen == Screen.PROGRESS && progressRowId == selectedRowId) refresh();
                        String notice = saved[0] == targets.size() && saved[0] > 0
                                ? "Images saved."
                                : "Some images were not saved. The mint changed or is busy; reopen the collection and retry the missing images.";
                        Toast.makeText(act, MintMessages.message(notice), Toast.LENGTH_SHORT).show();
                        if (saved[0] > 0) act.mintEngineTick();'''
 s=replace(s,old,new)
 # A delayed re-post confirmation is bound to its original parked CREATE row.
 old='''                        JSONObject fresh = LocalStore.findById(act, progressRowId);
                        if (fresh != null) {
                            try { fresh.put("posted", 0); fresh.put("createstuck", 0); fresh.put("error", ""); }
                            catch (Exception ignored) {}
                            LocalStore.upsert(act, fresh);
                        }
                        refresh();
                        act.mintEngineTick();'''
 new='''                        long selectedId = row.optLong("id", -1);
                        if (act.isDestroyed() || act.isFinishing()) return;
                        if (!MintDriver.retryCreate(act, selectedId)) {
                            Toast.makeText(act, MintMessages.message("The mint changed or is busy. Reopen the collection and review it again."), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (screen == Screen.PROGRESS && progressRowId == selectedId) refresh();
                        act.mintEngineTick();'''
 s=replace(s,old,new)
 start=s.index('    /** Persist one item\'s image');end=s.index('    // ===================== shared',start);s=s[:start]+s[end:];p.write_text(s)
 p=folder/'MintDriver.java';s=p.read_text();s=replace(s,'    public static boolean hasPausedBurial', '''    /** Reuse the operation lock so image updates cannot be overwritten by a live engine snapshot. */
    static synchronized boolean saveProgressImage(Context ctx, long rowId, int index, String image) {
        return !inFlight() && LocalStore.saveMintImage(ctx, rowId, index, image);
    }

    static synchronized boolean retryCreate(Context ctx, long rowId) {
        return !inFlight() && LocalStore.retryMintCreate(ctx, rowId);
    }

    public static boolean hasPausedBurial''');p.write_text(s)
 p=folder/'LocalStore.java';s=p.read_text();s=replace(s,'    private static SharedPreferences prefs(', (project/'scripts/pandamonium-sources/NFTwallet/mint-image-store.inc').read_text()+(project/'scripts/pandamonium-sources/NFTwallet/mint-retry-store.inc').read_text()+'    private static SharedPreferences prefs(');p.write_text(s)
 for name in ['MintImageGuardTest','MintDraftGuardTest']:
  source=project/'scripts/pandamonium-tests/NFTwallet/src/test/java/com/eurobuddha/nftwallet'/(name+'.java')
  if source.exists():shutil.copy2(source,root/'NFTwallet/src/test/java/com/eurobuddha/nftwallet'/source.name)
