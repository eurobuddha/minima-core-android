    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 7001) return;
        final ImagePick pick = pendingImagePick;
        pendingImagePick = null;
        if (resultCode != RESULT_OK || data == null) return;
        if (pick == null) { toast(Language.msg(l10n_atelier_image_reselect)); return; }
        if (!imageCurrent(pick)) { toast(Language.msg(l10n_atelier_image_changed)); return; }
        ArrayList<Uri> uris = selectedImageUris(data);
        if (uris.isEmpty()) return;
        final int start = pick.start, context = pick.context;
        final boolean batch = pick.batch;
        final long recoveryId = pick.recovery;
        activeImagePick = pick;
        if (context == PICK_ARTPHOTO) {
            final Uri photoUri = uris.get(0);
            toast(Language.msg(l10n_atelier_art_cartoonizing));
            executeImageWork(pick, () -> {
                android.graphics.Bitmap big = ImageTools.squareBitmap(this, photoUri, 512);
                artPhotoFromBitmap(big, pick);
            });
            return;
        }
        if (context == PICK_HOST_ICON || context == PICK_HOST_EXTERNAL) {
            hostUploadSingle(context, pick.lane, uris.get(0));
            return;
        }
        if (context == PICK_HOST_PLATES) {
            hostUploadPlates(uris);
            return;
        }
        int budget = context == PICK_NFT ? ImageTools.ARTIMAGE_BUDGET
                : (context == PICK_TOKICON || context == PICK_COLLICON) ? ImageTools.ICON_BUDGET
                : ImageTools.STATE_IMG_BUDGET;
        executeImageWork(pick, () -> {
            ArrayList<String> out = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    // SVG lane: sanitize + seal as text, skip the raster pipeline
                    if (ImageTools.isSvgUri(this, uri)) out.add(ImageTools.svgBase64FromUri(this, uri, budget));
                    // wallet icons are square-cropped so wallet tiles fill edge-to-edge
                    else if (context == PICK_TOKICON || context == PICK_COLLICON) out.add(ImageTools.iconFromUri(this, uri, budget));
                    else out.add(ImageTools.compressUri(this, uri, budget));
                } catch (Exception ignored) { out.add(""); }
            }
            runOnUiThread(() -> {
                if (!acceptImageResult(pick)) return;
                if (context == PICK_NFT) {
                    String b64 = out.isEmpty() ? "" : out.get(0);
                    if (b64 == null || b64.isEmpty()) { toast(Language.msg(l10n_atelier_image_process_failed)); renderCreateNft(); return; }
                    nftImage = b64;
                    renderCreateNft();
                    // straight into the plate room — a bad crop is sealed forever
                    // (SVG is resolution-independent; crop/tone don't apply)
                    if (!"image/svg+xml".equals(ImageTools.mimeOf(b64))) {
                        showEditor(b64, ImageTools.ARTIMAGE_BUDGET, edited -> {
                            nftImage = edited;
                            renderCreateNft();
                        });
                    }
                } else if (context == PICK_TOKICON || context == PICK_COLLICON) {
                    String b64 = out.isEmpty() ? "" : out.get(0);
                    if (b64 == null || b64.isEmpty()) toast(Language.msg(l10n_atelier_image_icon_failed));
                    else if (context == PICK_TOKICON) tokIcon = b64;
                    else createIconB64 = b64;
                    if (context == PICK_TOKICON) renderCreateToken();
                    else renderCreateCollection();
                } else if (context == PICK_RECOVERY) {
                    int added = applyRecoveryImages(recoveryId, start, batch, out);
                    toast(added == 0 ? Language.msg(l10n_atelier_image_process_failed)
                            : (batch ? Language.msg(l10n_atelier_image_restored, added) : Language.msg(l10n_atelier_image_restored, 1)));
                    loadLocalCollections();
                    openMeta = findCollectionByLocalId(recoveryId, openMeta);
                    if (openMeta != null && !"NEEDIMAGES".equals(openMeta.phase)) {
                        engageEngine();
                    }
                    if (openMeta != null) openCollection(openMeta);
                } else {
                    int added = batch ? applyPickedImages(start, out) : applyReplacementImage(start, out);
                    toast(added == 0 ? Language.msg(l10n_atelier_image_process_failed)
                            : (batch ? Language.msg(l10n_atelier_image_loaded, added) : Language.msg(l10n_atelier_image_plate_loaded, start + 1)));
                    renderCreateCollection();
                }
                saveDrafts();
            });
        });
    }


