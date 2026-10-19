private void cfgSecret(LinearLayout parent, JSONObject cfg, String key, String label) {
        // Reuse Atelier's input/eye row and Backup PasswordField's selection-preserving toggle.
        EditText e = field(parent, label, "", true, "••••••");
        parent.removeView(e);
        final int masked = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        final int shown = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        e.setInputType(masked);
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        e.setMinHeight(dp(48));
        final android.text.TextWatcher secretWatcher = bindHostingSecret(e, cfg, key, true, false, false);
        LinearLayout secretRow = row();
        secretRow.setGravity(Gravity.CENTER_VERTICAL);
        secretRow.addView(e, new LinearLayout.LayoutParams(0, -2, 1));
        TextView eye = Design.text(this, "👁", 18, Design.INK(), Design.sans());
        eye.setGravity(Gravity.CENTER);
        eye.setBackground(Design.ruled(this, Design.PAPER(), Design.INK(), 1));
        eye.setClickable(true);
        eye.setFocusable(true);
        eye.setAlpha(0.5f);
        Language.description(eye, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_backup_show_password));
        eye.setOnClickListener(v -> {
            boolean hidden = (e.getInputType() & InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD;
            int start = e.getSelectionStart(), end = e.getSelectionEnd();
            // Android setInputType emits text callbacks even when the credential is unchanged.
            e.removeTextChangedListener(secretWatcher);
            try {
                e.setInputType(hidden ? shown : masked);
                e.setTypeface(Design.sans());
                e.setSelection(Math.max(0, start), Math.max(0, end));
            } finally { e.addTextChangedListener(secretWatcher); }
            eye.setAlpha(hidden ? 1f : 0.5f);
            Language.description(eye, Language.msg(hidden
                    ? com.eurobuddha.minimacore.i18n.R.string.l10n_backup_hide_password
                    : com.eurobuddha.minimacore.i18n.R.string.l10n_backup_show_password));
        });
        LinearLayout.LayoutParams eyeParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        eyeParams.leftMargin = dp(6);
        secretRow.addView(eye, eyeParams);
        parent.addView(secretRow, new LinearLayout.LayoutParams(-1, -2));
    }
