package com.eurobuddha.vestr;
import android.content.Context;
import android.widget.ArrayAdapter;
import android.view.View;
import android.view.ViewGroup;
import org.json.JSONObject;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;
final class VestrText {
    private VestrText() {}
    static CharSequence grace(VestingContract.Grace grace) {
        int[] resources={l10n_vestr_none,l10n_vestr_daily,l10n_vestr_weekly,l10n_vestr_monthly,l10n_vestr_quarterly,l10n_vestr_halfyearly,l10n_vestr_yearly};
        return Language.msg(resources[grace.ordinal()]);
    }
    // Same source checks as Util.tokenName: preserve literal metadata, translate only its absent-name fallback.
    static CharSequence token(Object token, String id) {
        if(Util.isMinima(id))return "Minima";
        if(token instanceof String)return (String)token;
        if(token instanceof JSONObject) {
            Object name=((JSONObject)token).opt("name");
            if(name instanceof JSONObject) {
                JSONObject meta=(JSONObject)name;
                if(meta.has("name") && !meta.isNull("name"))return meta.optString("name");
            } else if(name instanceof String && !((String)name).isEmpty())return (String)name;
        }
        return Language.msg(l10n_vestr_token);
    }
    static ArrayAdapter<CharSequence> choices(Context context, java.util.List<CharSequence> values) {
        return new ArrayAdapter<CharSequence>(context, android.R.layout.simple_spinner_item, values) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                View row=super.getView(position,recycled,parent);
                Language.setText(row.findViewById(android.R.id.text1),getItem(position));return row;
            }
            @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
                View row=super.getDropDownView(position,recycled,parent);
                Language.setText(row.findViewById(android.R.id.text1),getItem(position));return row;
            }
        };
    }
}
