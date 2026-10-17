package com.eurobuddha.nftwallet;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Display-only name selection; raw metadata and stored transaction names remain unchanged. */
final class NftText {
    private NftText() {}
    static CharSequence token(Coin coin) {
        return coin.tokenNameProvided ? (coin.tokenName == null ? "" : coin.tokenName) : Language.msg(l10n_wallet_token);
    }
    static CharSequence token(TokenBalance balance) {
        return balance.meta != null && balance.meta.nameProvided ? balance.name : Language.msg(l10n_wallet_token);
    }
    static CharSequence collection(StateNft.Meta meta, org.json.JSONObject record) {
        Object token = record != null && record.opt("token") instanceof org.json.JSONObject
                ? record.opt("token") : record;
        return meta != null && Util.tokenNameProvided(token, meta.tokenid)
                ? meta.name : Language.msg(l10n_nft_collection);
    }
    static CharSequence galleryName(GalleryView.GItem item) {
        return item.displayName != null && item.displayName.length() > 0 ? item.displayName
                : item.name == null || item.name.isEmpty() ? "NFT" : item.name;
    }
}
