    private PaymentJournal paymentJournal;
    private final java.util.Set<String> activePayments = new java.util.HashSet<>();
    private synchronized PaymentJournal paymentJournal() {
        if (paymentJournal == null) paymentJournal = new PaymentJournal(db);
        return paymentJournal;
    }
    private boolean paymentUiLive() { return !isFinishing() && !isDestroyed(); }
    private void refreshPaymentOrder(String ref) {
        if (!paymentUiLive()) return;
        View top = stack.peek();
        if (top == null || !("order:" + ref).equals(top.getTag())) return;
        View panel = top.findViewWithTag("payment:" + ref);
        if (!(panel instanceof LinearLayout)) return;
        MerchDb.Order order = db.order(ref);
        if (order != null) { ((LinearLayout) panel).removeAllViews(); fillPaymentStatus((LinearLayout) panel, order); }
    }
    private void startPayment(String ref, AlertDialog progress, View checkout, Catalog shop) {
        if (!activePayments.add(ref)) { dismiss(progress); return; }
        io.execute(() -> {
            final MerchDb.Order payment;
            try { payment = paymentJournal().paymentOrder(ref); }
            catch (RuntimeException failed) {
                ui.post(() -> { activePayments.remove(ref); dismiss(progress); if (paymentUiLive()) toast(Language.msg(l10n_mall_shop_payment_unavailable)); });
                return;
            }
            ui.post(() -> {
                if (!paymentUiLive()) { activePayments.remove(ref); return; }
                new PaymentFlow(node, paymentJournal(), io, ui, this::paymentUiLive, new PaymentFlow.Listener() {
                    public void posted() {
                        if (!paymentUiLive()) return;
                        dismiss(progress);
                        if (checkout != null && stack.peek() == checkout && catalog == shop) {
                            cart.clear(); pop(); push(buildOrderDetail(ref));
                        } else refreshPaymentOrder(ref);
                    }
                    public void confirmed() { activePayments.remove(ref); refreshPaymentOrder(ref); }
                    public void unconfirmed() { activePayments.remove(ref); refreshPaymentOrder(ref); }
                }).start(payment);
            });
        });
    }
    private void addPaymentStatus(LinearLayout body, MerchDb.Order original) {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setTag("payment:" + original.ref); body.addView(panel); fillPaymentStatus(panel, original);
    }
    private void fillPaymentStatus(LinearLayout body, MerchDb.Order original) {
        try {
            MerchDb.Order payment = paymentJournal().paymentOrder(original.ref);
            JSONObject record = paymentJournal().load(payment.ref, payment);
            boolean available = paymentJournal().available(payment);
            boolean retry = PaymentJournal.retryable(record);
            boolean maySubmit = !payment.paid && MerchDb.PENDING.equals(payment.status);
            int label = !available || (retry && !maySubmit) ? l10n_mall_shop_payment_unavailable
                    : PaymentJournal.complete(record) ? l10n_mall_shop_payment_confirmed
                    : activePayments.contains(payment.ref) ? l10n_mall_shop_payment_checking
                    : PaymentJournal.retryable(record) ? l10n_mall_shop_payment_not_sent : l10n_mall_shop_payment_unknown;
            body.addView(card(Language.msg(label)));
            if (available && (!retry || maySubmit) && !PaymentJournal.complete(record) && !activePayments.contains(payment.ref)) {
                TextView action = button(Language.msg(PaymentJournal.retryable(record)
                        ? l10n_mall_shop_retry_payment : l10n_mall_shop_payment_check), true);
                action.setOnClickListener(v -> retryPayment(original)); body.addView(action);
            }
        } catch (RuntimeException unavailable) { body.addView(card(Language.msg(l10n_mall_shop_payment_unavailable))); }
    }
