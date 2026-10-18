    private void renderBury() {
        StateNft.Meta m = openMeta;
        if (m == null) return;
        setScreen(Screen.BURY, () -> openCollection(m));
        final long generation = screenGeneration;
        appbar(Language.msg(l10n_atelier_burial_title), true, false);
        hideNav();
        body.addView(Design.lot(this, Language.msg(l10n_atelier_burial_final)));
        body.addView(Design.display(this, m.name, 22), lpm(0, 4, 0, 14));
        LinearLayout facts = lotCard();
        facts.addView(kvRow(Language.msg(l10n_atelier_burial_owned), String.valueOf(m.owned)));
        facts.addView(kvRow(Language.msg(l10n_atelier_burial_destination), Util.shorten(StateNft.GRAVEYARD)));
        facts.addView(kvRow(Language.msg(l10n_atelier_burial_contract), Language.msg(l10n_atelier_burial_no_exit)));
        body.addView(facts, lpm(0, 0, 0, 12));
        LinearLayout warn = vertical();
        warn.setBackground(Design.ruled(this, Design.CARD(), Design.ACCENT(), 2f));
        warn.setPadding(dp(12), dp(10), dp(12), dp(12));
        warn.addView(Design.text(this, Language.msg(l10n_atelier_burial_permanent), 13, Design.ACCENT(), Design.sansBold()));
        warn.addView(Design.note(this, Language.msg(l10n_atelier_burial_custody)), lpm(0, 4, 0, 0));
        body.addView(warn, lpm(0, 0, 0, 14));
        body.addView(Design.kicker(this, Language.msg(l10n_atelier_burial_type_name)));
        EditText confirm = input(m.name);
        confirm.setMinHeight(dp(50));
        body.addView(confirm, lpm(0, 6, 0, 8));
        TextView msg = Design.note(this, Language.msg("BURY".equals(m.phase) ? l10n_nft_burial_paused : l10n_atelier_burial_arm));
        body.addView(msg, lpm(0, 0, 0, 10));
        TextView go = Design.button(this, Language.msg("BURY".equals(m.phase) ? l10n_nft_burial_check_retry : l10n_atelier_burial_submit), true);
        go.setOnClickListener(v -> {
            if (!burialScreenActive(generation) || !go.isEnabled()) return;
            if (!m.name.equals(confirm.getText().toString().trim())) {
                Language.setText(msg, Language.msg(l10n_atelier_burial_wrong_name)); return;
            }
            go.setEnabled(false); confirm.setEnabled(false);
            buryOwnedCoins(m, msg, generation);
        });
        go.setMinHeight(dp(54));
        body.addView(go, lpm(0, 0, 0, 0));
    }

    private boolean burialScreenActive(long generation) {
        return !isFinishing() && !isDestroyed() && screen == Screen.BURY && generation == screenGeneration;
    }
