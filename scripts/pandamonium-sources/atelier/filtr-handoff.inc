    private void launchFiltr() {
        if (screen != Screen.STUDIO || isFinishing() || isDestroyed()) return;
        if (pendingImagePick != null || activeImagePick != null) { toast(Language.msg(l10n_atelier_image_busy)); return; }
        if (!artDraftLoaded) { artDraftLoaded = true; artLoadDraft(); }
        ImagePick pick = new ImagePick();
        pick.context = -1; pick.screen = screen; pick.generation = screenGeneration;
        pick.sequence = ++imageSequence; pick.target = imageTarget(-1, 0);
        pick.filtrId = java.util.UUID.randomUUID().toString();
        pendingImagePick = pick;
        try {
            startActivityForResult(new Intent(this, FiltrActivity.class).putExtra(FiltrHandoff.ID, pick.filtrId), FiltrHandoff.REQUEST);
        } catch (RuntimeException unavailable) {
            pendingImagePick = null; toast(Language.msg(l10n_atelier_image_picker_failed));
        }
    }

    private void receiveFiltr(int resultCode, Intent data) {
        ImagePick pick = pendingImagePick;
        if (pick == null || pick.filtrId == null) {
            if (resultCode == RESULT_OK) {
                if (data != null) {
                    String id = data.getStringExtra(FiltrHandoff.ID);
                    if (activeImagePick != null && id != null && id.equals(activeImagePick.filtrId)) return;
                    FiltrHandoff.discard(this,id);
                }
                toast(Language.msg(l10n_atelier_image_reselect));
            }
            return;
        }
        pendingImagePick = null;
        if (resultCode != RESULT_OK || data == null) { FiltrHandoff.discard(this,pick.filtrId); return; }
        String id = data.getStringExtra(FiltrHandoff.ID), mode = data.getStringExtra(FiltrHandoff.MODE);
        if (!pick.filtrId.equals(id) || !imageCurrent(pick)) {
            FiltrHandoff.discard(this,pick.filtrId); toast(Language.msg(l10n_atelier_image_changed)); return;
        }
        activeImagePick = pick;
        executeImageWork(pick, () -> {
            final String image;
            try { image = FiltrHandoff.take(this,pick.filtrId,mode); }
            catch (java.io.IOException failure) {
                runOnUiThread(() -> { if (acceptImageResult(pick)) toast(Language.msg(l10n_atelier_art_filtr_failed)); }); return;
            }
            runOnUiThread(() -> {
                if (!acceptImageResult(pick)) return;
                if (FiltrHandoff.MINT.equals(mode)) {
                    nftImage = image; saveDrafts(); renderCreateNft();
                    toast(Language.msg(l10n_atelier_art_filtr_loaded));
                } else {
                    renderGenerative();
                    ImagePick photo = new ImagePick();
                    photo.context = PICK_ARTPHOTO; photo.screen = screen; photo.generation = screenGeneration;
                    photo.sequence = ++imageSequence; photo.target = imageTarget(PICK_ARTPHOTO,0);
                    activeImagePick = photo;
                    toast(Language.msg(l10n_atelier_art_cartoonizing));
                    executeImageWork(photo, () -> {
                        byte[] bytes = android.util.Base64.decode(image, android.util.Base64.DEFAULT);
                        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length);
                        artPhotoFromBitmap(bitmap,photo);
                    });
                }
            });
        });
    }

