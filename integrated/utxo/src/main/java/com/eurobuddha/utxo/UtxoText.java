package com.eurobuddha.utxo;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Display-only name selection; raw metadata and stored transaction names remain unchanged. */
final class UtxoText {
    private UtxoText() {}
    static CharSequence token(Coin coin) {
        return coin.tokenNameProvided ? (coin.tokenName == null ? "" : coin.tokenName) : Language.msg(l10n_wallet_token);
    }
    static CharSequence token(TokenBalance balance) {
        return balance.meta != null && balance.meta.nameProvided ? balance.name : Language.msg(l10n_wallet_token);
    }
}
