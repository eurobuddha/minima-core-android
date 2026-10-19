package com.eurobuddha.merchstudio;

import org.json.JSONObject;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** A literal schema-v3 snapshot. Background work never owns editable Activity models. */
final class ShopExport {
    final String name, slug;
    private final String json;
    private final List<Image> images;

    static final class Image {
        final String array, field, url, identity;
        final int index, decodeSize, fallbackSize, budget;
        Image(String array, int index, String field, String url, String identity,
                int decodeSize, int fallbackSize, int budget) {
            this.array=array;this.index=index;this.field=field;this.url=url;this.identity=identity;
            this.decodeSize=decodeSize;this.fallbackSize=fallbackSize;this.budget=budget;
        }
    }
    interface Resolver { String resolve(Image image) throws Exception; }

    ShopExport(JSONObject shop, String slug, List<Image> images) throws Exception {
        this.name=shop.getString("shopName");
        if (slug == null || !slug.matches("[a-zA-Z0-9]+(?:-[a-zA-Z0-9]+)*"))
            throw new IllegalArgumentException("Invalid shop filename");
        this.slug=slug;
        this.json=shop.toString();
        this.images=Collections.unmodifiableList(new ArrayList<>(images));
    }

    JSONObject render(Resolver resolver) throws Exception {
        JSONObject result=new JSONObject(json);
        for (Image image:images) {
            String encoded=resolver.resolve(image);
            if (encoded != null && !encoded.isEmpty())
                result.getJSONArray(image.array).getJSONObject(image.index).put(image.field,encoded);
        }
        return result;
    }

    /** miniMall's v3 reader and quantity controls use positive signed 32-bit counts. */
    static int count(BigInteger value) {
        int n=new java.math.BigDecimal(value).intValueExact();
        if (n<=0) throw new ArithmeticException("Non-positive shop count");
        return n;
    }

    /** Reuse NodeFiles.replaceFrom's temporary-write, sync and atomic-rename ordering.
     * Each export has its own directory, so an already shared URI never changes contents. */
    File write(File cache, Resolver resolver) throws Exception {
        byte[] bytes=render(resolver).toString().getBytes(StandardCharsets.UTF_8);
        File root=new File(cache,"shops");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create export folder");
        File dir=Files.createTempDirectory(root.toPath(),"export-").toFile();
        File part=new File(dir,"export.part"), destination=new File(dir,slug+".shop");
        boolean complete=false;
        try {
            try (FileOutputStream output=new FileOutputStream(part)) {
                output.write(bytes);
                output.getFD().sync();
            }
            Files.move(part.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE);
            complete=true;
            return destination;
        } finally {
            if (!complete) { part.delete(); destination.delete(); dir.delete(); }
        }
    }
}
