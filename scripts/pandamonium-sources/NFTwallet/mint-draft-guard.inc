    private long collectionReviewGeneration;
    private boolean collectionStarting;

    /** Same captured-fields boundary as SendView; language changes do not change a draft. */
    private final class CollectionReview {
        final LinearLayout form = colForm;
        final String[] fields = collectionFields();
        final boolean embed = colEmbedMode, iconEmbed = colIconEmbed;
        final String icon = colIconB64, defaultIcon = colIconDefault, defaultSource = colIconDefaultSource;
        final java.util.Map<Integer, String> images = new java.util.HashMap<>(colImages);
        boolean matches() {
            return form == colForm && java.util.Arrays.equals(fields, collectionFields())
                    && embed == colEmbedMode && iconEmbed == colIconEmbed
                    && java.util.Objects.equals(icon, colIconB64)
                    && java.util.Objects.equals(defaultIcon, colIconDefault)
                    && java.util.Objects.equals(defaultSource, colIconDefaultSource)
                    && images.equals(colImages);
        }
    }

    private String[] collectionFields() {
        EditText[] inputs = {cName, cSize, cDesc, cBase, cExt, cIconUrl, cExtUrl, cWebv};
        String[] values = new String[inputs.length];
        for (int i = 0; i < inputs.length; i++) values[i] = inputs[i].getText().toString();
        return values;
    }

    private boolean acceptCollectionReview(long generation, CollectionReview review) {
        if (generation != collectionReviewGeneration || act.isFinishing() || act.isDestroyed()) return false;
        if (screen != Screen.COLLECTION || act.currentTab() != MainActivity.TAB_MINT || !review.matches()) {
            status(cStatus, "Collection details changed. Review the collection again before minting.", false);
            return false;
        }
        return true;
    }

    private boolean finishCollectionLookup(long generation, CollectionReview review) {
        if (generation != collectionReviewGeneration || !collectionStarting) return false;
        collectionStarting = false;
        return acceptCollectionReview(generation, review);
    }

