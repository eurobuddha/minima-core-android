    /** Runs the upload off the main thread, fills the target field on success,
     *  reports honestly on failure. Single-file lanes only. */
    private void hostUploadSingle(int context, String lane, Uri uri) {
        final ImagePick pick = activeImagePick;
        if (!imageCurrent(pick)) { if (pick != null) acceptImageResult(pick); return; }
        final Hosting.Profile p = pick.host;
        if (p == null) { acceptImageResult(pick); toast(Language.msg(l10n_atelier_image_no_host)); return; }
        toast(Language.msg(l10n_atelier_image_uploading, p.name()));
        executeImageWork(pick, () -> {
            String result = null, url = null;
            Hosting.Uploader u = null;
            try {
                byte[] bytes; String mime;
                if (context == PICK_HOST_ICON) {
                    android.graphics.Bitmap sq = ImageTools.squareBitmap(this, uri, 512);
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    android.graphics.Bitmap.CompressFormat format = android.os.Build.VERSION.SDK_INT >= 30
                            ? android.graphics.Bitmap.CompressFormat.WEBP_LOSSY
                            : android.graphics.Bitmap.CompressFormat.WEBP;
                    sq.compress(format, 85, bos);
                    bytes = bos.toByteArray();
                    mime = "image/webp";
                } else {
                    bytes = readUri(uri);
                    mime = getContentResolver().getType(uri);
                    if (mime == null) mime = "application/octet-stream";
                }
                if (bytes.length == 0) throw new Hosting.HostingException("could not open the image");
                String ext = extFromMime(mime);
                String coll = pick.slug;
                String rel = Hosting.fillTemplate(p.pathTemplate(), mapOf(
                        "collection", coll, "name", lane, "ext", ext,
                        "ts", Hosting.ts36(), "rand", Hosting.rand4(), "idx", ""));
                if (Hosting.TYPE_ARWEAVE.equals(p.type()) && bytes.length > ArweaveUploader.FREE_TIER_BYTES
                        && !confirmArweaveSpend(pick, bytes.length, 1)) {
                    main.post(() -> { if (acceptImageResult(pick)) toast(Language.msg(l10n_atelier_image_cancelled)); });
                    return;
                }
                if (!allowImageUpload(pick)) return;
                u = Hosting.forProfile(p);
                if (u.exists(rel)) throw new Hosting.HostingException("Remote file already exists: " + rel);
                url = u.putFile(bytes, rel, mime);
                if (url == null || url.isEmpty()) throw new Hosting.HostingException("Empty upload reference");
                // arweave: Turbo's 200 + txid is the receipt; the URL only starts
                // serving after gateway indexing (~5-10 min) — don't block on it.
                if (!Hosting.TYPE_ARWEAVE.equals(p.type())) Hosting.verifyUrl(url, p);
            } catch (SftpUploader.HostKeyUnverified hk) {
                main.post(() -> { if (acceptImageResult(pick)) confirmHostKey(p, hk.fingerprint); });
                return;
            } catch (Hosting.HostingException e) {
                result = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            } catch (Throwable t) {
                result = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : " — " + t.getMessage());
            } finally {
                if (u instanceof AutoCloseable) { try { ((AutoCloseable) u).close(); } catch (Exception ignored) { } }
            }
            final String err = result, okUrl = url;
            main.post(() -> {
                if (err != null) {
                    LocalStore.logEvent(this, "HOSTING " + lane + " FAILED: " + err);
                    android.util.Log.e("AtelierHost", lane + " failed: " + err);
                    if (acceptImageResult(pick)) toast(Language.msg(l10n_atelier_image_failed, imageError(err)));
                    return;
                }
                LocalStore.logEvent(this, "HOSTING " + lane + " OK — " + okUrl);
                if (!acceptImageResult(pick)) return;
                fillHostLane(lane, okUrl, null);
                saveDrafts();
                toast(Hosting.TYPE_ARWEAVE.equals(p.type())
                        ? Language.msg(l10n_atelier_image_uploaded_slow) : Language.msg(l10n_atelier_image_uploaded));
            });
        });
    }

    /** N plates → one directory (IPFS) or sequence (sftp/webdav/github) named
     *  1..N + ext; fills base + ext. */
    private void hostUploadPlates(ArrayList<Uri> uris) {
        final ImagePick pick = activeImagePick;
        if (!imageCurrent(pick)) { if (pick != null) acceptImageResult(pick); return; }
        final Hosting.Profile p = pick.host;
        if (p == null) { acceptImageResult(pick); toast(Language.msg(l10n_atelier_image_no_host)); return; }
        if (uris.size() != pick.count) {
            acceptImageResult(pick);
            LocalStore.logEvent(this, "HOSTING plates: picked " + uris.size() + " but need " + pick.count);
            toast(Language.msg(l10n_atelier_image_exact_count, pick.count, uris.size()));
            return;
        }
        LocalStore.logEvent(this, "HOSTING plates start: type=" + p.type()
                + " root=" + p.cfgStr("remoteRoot") + " prefix=" + p.cfgStr("urlPrefix")
                + " dirTpl=" + p.dirTemplate());
        toast(Language.msg(l10n_atelier_image_uploading_plates, uris.size(), p.name()));
        executeImageWork(pick, () -> {
            String result = null, base = null, ext = null;
            Hosting.Uploader u = null;
            try {
                java.util.List<Hosting.Entry> files = new java.util.ArrayList<>();
                String coll = pick.slug;
                String dirRel = Hosting.fillTemplate(p.dirTemplate(), mapOf(
                        "collection", coll, "ts", Hosting.ts36(), "rand", Hosting.rand4()));
                // every plate → display-grade JPEG, so the set is homogeneous
                // (.jpg) by construction and bounded in size
                ext = ".jpg";
                for (int i = 0; i < uris.size(); i++) {
                    byte[] bytes = plateBytes(uris.get(i));
                    files.add(new Hosting.Entry(bytes, dirRel + "/" + (i + 1) + ext, "image/jpeg"));
                }
                if (Hosting.TYPE_ARWEAVE.equals(p.type())) {
                    long total = 0;
                    for (Hosting.Entry e : files) total += e.bytes.length;
                    if (total > ArweaveUploader.FREE_TIER_BYTES && !confirmArweaveSpend(pick, total, files.size())) {
                        main.post(() -> { if (acceptImageResult(pick)) toast(Language.msg(l10n_atelier_image_cancelled)); });
                        return;
                    }
                }
                if (!allowImageUpload(pick)) return;
                u = Hosting.forProfile(p);
                if (u instanceof Hosting.DirUploader) {
                    base = ((Hosting.DirUploader) u).putDirectory(files, null);
                } else {
                    base = Hosting.putSequence(u, p, dirRel, files, (done,total,path) -> { if (pick.cancelled) throw new IllegalStateException("Upload stopped after navigation"); });
                }
                if (base == null || base.isEmpty()) throw new Hosting.HostingException("Empty upload reference");
                // verify plate 1 and plate N — except arweave, where the Turbo
                // receipt is trusted and the pre-mint gate retry-verifies later
                // (fresh txids take ~5-10 min to serve)
                if (!Hosting.TYPE_ARWEAVE.equals(p.type())) {
                    Hosting.verifyUrl(base + "1" + ext, p);
                    Hosting.verifyUrl(base + uris.size() + ext, p);
                }
            } catch (SftpUploader.HostKeyUnverified hk) {
                main.post(() -> { if (acceptImageResult(pick)) confirmHostKey(p, hk.fingerprint); });
                return;
            } catch (Hosting.HostingException e) {
                result = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            } catch (Throwable t) {
                result = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : " — " + t.getMessage());
            } finally {
                if (u instanceof AutoCloseable) { try { ((AutoCloseable) u).close(); } catch (Exception ignored) { } }
            }
            final String err = result, okBase = base, okExt = ext;
            main.post(() -> {
                if (err != null) {
                    LocalStore.logEvent(this, "HOSTING plates FAILED: " + err);
                    android.util.Log.e("AtelierHost", "plates failed: " + err);
                    if (acceptImageResult(pick)) toast(Language.msg(l10n_atelier_image_failed, imageError(err)));
                    return;
                }
                LocalStore.logEvent(this, "HOSTING plates OK — base " + okBase);
                if (!acceptImageResult(pick)) return;
                createBase = okBase;
                createExt = okExt;
                createUploadedBase = okBase;
                createUploadedExt = okExt;
                createMode = "url";
                toast(Hosting.TYPE_ARWEAVE.equals(p.type())
                        ? Language.msg(l10n_atelier_image_plates_slow) : Language.msg(l10n_atelier_image_plates_uploaded));
                renderCreateCollection();
                saveDrafts();
            });
        });
    }

