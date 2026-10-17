        final ShopExport snapshot;
        try { snapshot = captureExport(sel); }
        catch (ArithmeticException unsupported) { toast(Language.msg(l10n_mall_studio_ui_count_limit)); return; }
        catch (Exception e) { toast(Language.msg(l10n_mall_inbox_export_error, StudioMessages.error(e))); return; }
        exporting = true;
        refreshTop(buildPreview());
        io.execute(() -> {
            try {
                File f = snapshot.write(getCacheDir(), MainActivity::resolveExportImage);
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".merchstudio.fileprovider", f);
                ui.post(() -> {
                    finishExport();
                    if (isFinishing() || isDestroyed()) return;
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("application/octet-stream");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_SUBJECT, snapshot.name + " — miniMall shop");
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { startActivity(Intent.createChooser(send, Language.msg(l10n_mall_studio_ui_share))); }
                    catch (RuntimeException e) { toast(Language.msg(l10n_mall_inbox_export_error, StudioMessages.error(e))); }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    finishExport();
                    if (!isFinishing() && !isDestroyed()) toast(Language.msg(l10n_mall_inbox_export_error, StudioMessages.error(e)));
                });
            }
        });
    }

    /** Completion updates only the export controls, preserving any current editor and cursor. */
    private void finishExport() {
        exporting = false;
        if (isFinishing() || isDestroyed()) return;
        for (View page : stack) {
            TextView button = page.findViewWithTag("exportShop");
            if (button != null) Language.setText(button, Language.msg(l10n_mall_studio_ui_export));
        }
    }

    /** Called on the UI thread. All prices, shipping, metadata and image sources become literals. */
    private ShopExport captureExport(List<NftListing> sel) throws Exception {
        List<ShopExport.Image> images = new ArrayList<>();
@@SERIALIZE@@
        return new ShopExport(o, slug(shopName), images);
    }

    /** Same image resolution, fallbacks and byte budgets as the original Studio exporter. */
    private static String resolveExportImage(ShopExport.Image image) {
        Bitmap bmp = null;
        if (image.url != null && !image.url.isEmpty()) bmp = ImageLoader.decodeSync(image.url, image.decodeSize);
        if (bmp == null) bmp = Identicon.forToken(image.identity, image.fallbackSize);
        byte[] jpeg = Images.compressToFit(bmp, image.budget);
        return jpeg == null ? "" : android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP);
    }

