"""Reuse Salon media request ownership and retain Atelier's codecs and hosting backends."""
from pathlib import Path

def adapt(root):
    sources=Path(__file__).parent/'pandamonium-sources/atelier'
    p=root/'atelier/src/main/java/com/eurobuddha/statenft/MainActivity.java';s=p.read_text()
    def change(old,new,n=1):
        nonlocal s
        assert s.count(old)==n,('Atelier image source drift',old,s.count(old),n)
        s=s.replace(old,new)
    change('    private void launchPicker(boolean multiple) {\n        Intent i = new Intent(Intent.ACTION_GET_CONTENT);\n        i.setType("image/*");\n        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);\n        startActivityForResult(Intent.createChooser(i, "Choose image"), 7001);\n    }', '''    private void launchPicker(boolean multiple) {
        if (pendingImagePick != null || activeImagePick != null) { toast(Language.msg(l10n_atelier_image_busy)); return; }
        if (isFinishing() || isDestroyed()) return;
        ImagePick pick = new ImagePick();
        pick.context = pendingPickContext; pick.batch = pendingBatchImages;
        pick.start = pick.context == PICK_CREATE && pick.batch ? firstEmptyImageSlot() : pendingImageIndex;
        pick.recovery = pendingRecoveryId; pick.lane = pendingHostLane; pick.count = pendingHostPlateCount;
        Screen expected = pick.context == PICK_RECOVERY ? Screen.COLLECTION
                : pick.context == PICK_ARTPHOTO ? Screen.CREATE_GENERATIVE
                : pick.context == PICK_NFT ? Screen.CREATE_NFT
                : pick.context == PICK_TOKICON ? Screen.CREATE_TOKEN : Screen.CREATE_COLLECTION;
        if (pick.context == PICK_HOST_ICON || pick.context == PICK_HOST_EXTERNAL) {
            if ("nfturl".equals(pick.lane) || "nftext".equals(pick.lane)) expected = Screen.CREATE_NFT;
            else if ("tokicon".equals(pick.lane)) expected = Screen.CREATE_TOKEN;
        }
        if (screen != expected) return;
        pick.screen = screen; pick.generation = screenGeneration; pick.sequence = ++imageSequence;
        pick.target = imageTarget(pick.context,pick.recovery);
        pick.slug = Hosting.slug(screen == Screen.CREATE_NFT ? nftName : screen == Screen.CREATE_TOKEN ? tokName : createName);
        if (pick.context >= PICK_HOST_ICON) {
            Hosting.Profile host = HostingStore.getDefault(this);
            if (host == null) { toast(Language.msg(l10n_atelier_image_no_host)); return; }
            try { pick.hostJson = host.j.toString(); pick.host = new Hosting.Profile(new JSONObject(pick.hostJson)); }
            catch (org.json.JSONException invalid) { return; }
        }
        if (!imageCurrent(pick)) { toast(Language.msg(l10n_atelier_image_changed)); return; }
        pendingImagePick = pick;
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.setType("image/*");
            if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(Intent.createChooser(i, Language.msg(l10n_atelier_image_choose)), 7001);
        } catch (RuntimeException unavailable) {
            pendingImagePick = null; toast(Language.msg(l10n_atelier_image_picker_failed));
        }
    }

'''+(sources/'image-pick-session.inc').read_text())
    change('    private void setScreen(Screen s, Runnable back) {','    private void setScreen(Screen s, Runnable back) {\n        cancelImageRequests();')
    change('    @Override protected void onDestroy() {','    @Override protected void onDestroy() {\n        cancelImageRequests();\n        imageIo.shutdownNow();')
    a=s.index('    /** Runs the upload off the main thread');b=s.index('    private void fillHostLane(',a)
    s=s[:a]+(sources/'image-host-uploads.inc').read_text()+(sources/'image-spend-confirm.inc').read_text()+'\n'+s[b:]
    a=s.index('    @Override protected void onActivityResult(');b=s.index('    private ArrayList<Uri> selectedImageUris',a)
    s=s[:a]+(sources/'image-pick-result.inc').read_text()+s[b:]
    # A JPEG extension must never conceal undecoded PNG/SVG/invalid raw bytes.
    a=s.index('    private byte[] plateBytes(');b=s.index('    private static String extFromMime',a)
    part=s[a:b].replace('        return readUri(uri);','        throw new Exception("Could not encode the image as JPEG");')
    s=s[:a]+part+s[b:]
    change('crawl. Falls back to raw bytes if decoding fails.','crawl. Refuses undecodable files rather than labelling raw bytes as JPEG.')
    change('    private void artPhotoFromBitmap(android.graphics.Bitmap big) {','''    private void artPhotoFromBitmap(android.graphics.Bitmap big) { artPhotoFromBitmap(big, null); }
    private void artPhotoFromBitmap(android.graphics.Bitmap big, ImagePick pick) {''')
    change('''        runOnUiThread(() -> {
            if (rgba == null)''','''        runOnUiThread(() -> {
            if (pick != null && !acceptImageResult(pick)) return;
            if (rgba == null)''')
    change('''            ArtStudio.with(this, s -> s.setPhoto(rgba, ai ? 10 : 8, paint, k -> {
                artPhotoLoaded''','''            ArtStudio.with(this, s -> {
                if (pick != null && !imageCurrent(pick)) return;
                s.setPhoto(rgba, ai ? 10 : 8, paint, k -> {
                if (pick != null && !imageCurrent(pick)) return;
                artPhotoLoaded''')
    change('''                if (screen == Screen.CREATE_GENERATIVE) renderGenerative();
            }));''','''                if (screen == Screen.CREATE_GENERATIVE) renderGenerative();
            }); });''')
    # Reuse the same screen/draft guard for editor results and overlay ownership.
    change('''        overlay.removeAllViews();
        View v = ImageEditor.build(this, b64, budget, this::onCloseViewer, edited -> {
            onCloseViewer();
            done.edited(edited);
        });''','''        final long generation = screenGeneration;
        final int context = screen == Screen.COLLECTION ? PICK_RECOVERY : PICK_CREATE;
        final long recovery = openMeta == null ? 0 : openMeta.localId;
        final String target = imageTarget(context,recovery);
        final View[] editor = {null};
        final java.util.function.BooleanSupplier current = () -> !isFinishing() && !isDestroyed()
                && generation == screenGeneration && target.equals(imageTarget(context,recovery))
                && overlay.getChildCount() == 1 && overlay.getChildAt(0) == editor[0];
        overlay.removeAllViews();
        View v = ImageEditor.build(this, b64, budget, () -> { if (current.getAsBoolean()) onCloseViewer(); }, edited -> {
            if (!current.getAsBoolean()) return;
            onCloseViewer(); done.edited(edited); saveDrafts();
        });
        editor[0] = v;''')
    a=s.index('    private LinearLayout recoveryPanel(');b=s.index('    private LinearLayout recoveryImageGrid(',a)
    part=s[a:b]
    labels={
        '"MISSING EMBEDDED IMAGES"':'Language.msg(l10n_atelier_image_missing_title)',
        '"These exact bytes get sealed. Re-supply the missing plates to finish the mint."':'Language.msg(l10n_atelier_image_missing_help)',
        '"Choose missing images"':'Language.msg(l10n_atelier_image_choose_missing)',
        '"All plates present — ready to resume."':'Language.msg(l10n_atelier_image_all_present)',
        'missing + " plates still missing."':'Language.msg(l10n_atelier_image_missing_count, missing)',
        '"Resume stamping"':'Language.msg(l10n_atelier_image_resume)'}
    for old,new in labels.items():
        assert old in part,old;part=part.replace(old,new)
    part=part.replace('        LinearLayout panel = vertical();','        final long generation = screenGeneration;\n        LinearLayout panel = vertical();')
    part=part.replace('pick.setOnClickListener(v -> pickRecoveryImages(m));','pick.setOnClickListener(v -> { if (collectionCurrent(m,generation)) pickRecoveryImages(m); });')
    part=part.replace('panel.addView(pick, lph(46, 0, 0, 0, 10));','pick.setMinHeight(dp(48)); panel.addView(pick, lpm(0, 0, 0, 10));')
    part=part.replace('                JSONObject row = LocalStore.findById(this, m.localId);','                if (!collectionCurrent(m,generation)) return;\n                JSONObject row = LocalStore.findById(this, m.localId);')
    part=part.replace('if (row != null) {','if (row != null && "NEEDIMAGES".equals(row.optString("phase")) && missingLocalImagesInRow(row) == 0) {')
    part=part.replace('panel.addView(resume, lph(46, 0, 10, 0, 0));','resume.setMinHeight(dp(48)); panel.addView(resume, lpm(0, 10, 0, 0));')
    s=s[:a]+part+s[b:]
    # A slot from a previous render must not address a resized or replaced draft.
    a=s.index('    private View imageSlotCell(');b=s.index('    /* ---- recovery',a)
    part=s[a:b].replace('        boolean ready =','        final long generation = screenGeneration;\n        boolean ready =',1)
    part=part.replace('        cell.setOnClickListener(v -> {','        cell.setOnClickListener(v -> {\n            if (isFinishing() || isDestroyed() || generation != screenGeneration || screen != Screen.CREATE_COLLECTION || idx >= createImages.length) return;',1)
    s=s[:a]+part+s[b:]
    a=s.index('    private View recoverySlotCell(');b=s.index('    private int missingLocalImages(',a)
    part=s[a:b].replace('        String image =','        final long generation = screenGeneration;\n        final String expected = storeRow == null ? "" : storeRow.toString();\n        String image =',1)
    part=part.replace('        cell.setOnClickListener(v -> {','''        cell.setOnClickListener(v -> {
            JSONObject current = LocalStore.findById(this,m.localId);
            if (!collectionCurrent(m,generation) || current == null || !expected.equals(current.toString())
                    || !"NEEDIMAGES".equals(current.optString("phase"))) return;''',1)
    s=s[:a]+part+s[b:]
    p.write_text(s)
