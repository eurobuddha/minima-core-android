package com.eurobuddha.terminalide.ide;

import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Localized prose is captured when a new editable script is created. */
public final class TemplateText {
    private TemplateText() {}
    private static final int[] NAMES = {
        l10n_terminal_template_untitled, l10n_terminal_editor_signature,
        l10n_terminal_editor_timelock, l10n_terminal_editor_hashlock,
        l10n_terminal_editor_multisig, l10n_terminal_editor_random,
        l10n_terminal_editor_covenant
    };
    private static final int[] COMMENTS = {
        0, l10n_terminal_template_comment1, l10n_terminal_template_comment2,
        l10n_terminal_template_comment3, l10n_terminal_template_comment4,
        l10n_terminal_template_comment5, l10n_terminal_template_comment6
    };
    public static String name(int index) { return Language.msg(NAMES[index]).toString(); }
    public static String source(int index) {
        String original = Templates.ALL[index][1];
        if (index == 0) return original;
        return "/*" + Language.msg(COMMENTS[index]) + "*/" + original.substring(original.indexOf("*/") + 2);
    }
}
