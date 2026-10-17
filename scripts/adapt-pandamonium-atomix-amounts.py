"""Reuse AtomiX decimal/finite/grain guards at OTC input and execution boundaries."""
def adapt(root):
 folder=root/'atomix/src/main/java/com/eurobuddha/atomix'
 def patch(name,changes):
  p=folder/name;s=p.read_text()
  for old,new in changes:assert old in s,('AtomiX amount drift',name,old);s=s.replace(old,new)
  p.write_text(s)
 patch('swap/OtcOffer.java',[(
 '    public boolean hasLiquidity() { return enable && (sellSize > 0 || buySize > 0); }',
 '''    // Same finite-positive boundary as Order.finite; malformed peer sizes supply no liquidity.
    public static double finite(double value) { return Double.isFinite(value) && value > 0 ? value : 0; }
    public boolean hasLiquidity() { return sells() || buys(); }'''),(
 'return enable && sellSize > 0;', 'return enable && finite(sellSize) > 0;'),(
 'return enable && buySize > 0;', 'return enable && finite(buySize) > 0;'),(
 'return LP_SELLS_MINIMA.equals(dealSide) ? sellSize : buySize;', 'return LP_SELLS_MINIMA.equals(dealSide) ? finite(sellSize) : LP_BUYS_MINIMA.equals(dealSide) ? finite(buySize) : 0;'),(
 '        return f;', '        f.sellSize = finite(f.sellSize); f.buySize = finite(f.buySize);\n        return f;')])
 patch('swap/OtcController.java',[(
 '        this.myOfferEnabled = enabled; this.myOfferSellSize = sellSize; this.myOfferBuySize = buySize;',
 '        this.myOfferEnabled = enabled; this.myOfferSellSize = OtcOffer.finite(sellSize); this.myOfferBuySize = OtcOffer.finite(buySize);'),(
 '    private static double parseD(String s) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; } }',
 '''    private static double parseD(String s) { try { return OtcOffer.finite(Double.parseDouble(s.trim())); } catch (Exception e) { return 0; } }
    public static boolean validSide(String side) { return OtcOffer.LP_SELLS_MINIMA.equals(side) || OtcOffer.LP_BUYS_MINIMA.equals(side); }
    /** Use the existing command decimal guard and 6dp settlement grain; neither leg may round to zero. */
    public static boolean validTerms(String amount, String price) {
        if (!MinimaHtlc.isDecimal(amount) || !MinimaHtlc.isDecimal(price)
                || !amount.equals(amount.trim()) || !price.equals(price.trim())) return false;
        double a = parseD(amount), p = parseD(price);
        if (a <= 0 || p <= 0 || !Double.isFinite(a * p)) return false;
        return MinimaHtlc.positive(MinimaHtlc.grain(amount))
                && new java.math.BigDecimal(amount.trim()).multiply(new java.math.BigDecimal(price.trim()))
                    .setScale(6, java.math.RoundingMode.DOWN).signum() > 0;
    }
    static boolean validDeal(OtcDb.Deal deal) { return deal != null && validSide(deal.side) && validTerms(deal.amount, deal.price); }'''),(
 '        if (!myOfferEnabled || cap <= 0) return false;',
 '        if (!validSide(m.side) || !myOfferEnabled || cap <= 0) return false;'),(
 'if (parseD(m.amount) <= 0 || parseD(m.amount) > cap + 1e-9 || parseD(m.price) <= 0) return false;',
 'if (!validTerms(m.amount, m.price) || parseD(m.amount) > cap + 1e-9) return false;'),(
 'if (isTerminal(d.status) || parseD(m.amount) <= 0 || parseD(m.price) <= 0) return;',
 'if (isTerminal(d.status) || !validTerms(m.amount, m.price)) return;'),(
 'if (parseD(amount) <= 0 || parseD(price) <= 0) { res.err("enter amount + price"); return; }',
 'if (!validTerms(amount, price)) { res.err("enter amount + price"); return; }'),(
 '    public void propose(OtcOffer lp, String dealSide, String amount, String price, SendResult res) {',
 '''    public void propose(OtcOffer lp, String dealSide, String amount, String price, SendResult res) {
        if (lp == null || !validSide(dealSide) || lp.capFor(dealSide) <= 0) { res.err("enter amount + price"); return; }'''),(
 '    public void accept(OtcDb.Deal d, SendResult res) {',
 '    public void accept(OtcDb.Deal d, SendResult res) {\n        if (!validDeal(d)) { res.err("enter amount + price"); return; }'),(
 '        } else if (OtcMessage.ACCEPT.equals(m.type)) {',
 '        } else if (OtcMessage.ACCEPT.equals(m.type)) {\n            if (!validDeal(d)) return;'),(
 '        if (!sameMarket(cur) || !OtcDb.ST_AGREED.equals(cur.status)) return;',
 '        if (!sameMarket(cur) || !validDeal(cur) || !OtcDb.ST_AGREED.equals(cur.status)) return;')])
 patch('swap/SwapEngine.java',[(
 '    public void executeOtcDeal(OtcDb.Deal deal, StartCb cb) {',
 '    public void executeOtcDeal(OtcDb.Deal deal, StartCb cb) {\n        if (!OtcController.validDeal(deal)) { cb.err("enter amount + price"); return; }'),(
 '    boolean otcVerifyBuy(EthHtlc.Contract c, OtcDb.Deal d) {',
 '    boolean otcVerifyBuy(EthHtlc.Contract c, OtcDb.Deal d) {\n        if (!OtcController.validDeal(d)) return false;'),(
 '    boolean otcVerifySell(JSONObject coin, OtcDb.Deal d, String reqTokenAddr) {',
 '    boolean otcVerifySell(JSONObject coin, OtcDb.Deal d, String reqTokenAddr) {\n        if (!OtcController.validDeal(d)) return false;'),(
 '        return approxEq(gotMinima, wantMinima) && approxEq(gotUsdt, wantUsdt);',
 '        return gotMinima.signum() > 0 && gotUsdt.signum() > 0 && approxEq(gotMinima, wantMinima) && approxEq(gotUsdt, wantUsdt);')])
 # Restrict UI parsing changes to OTC methods. Other trading fields retain their existing behavior.
 p=folder/'MainActivity.java';s=p.read_text();start=s.index('    private void renderOtcAvailability(');end=s.index('    private int dp(',start);part=s[start:end]
 part=part.replace('parseD(', 'otcNumber(')
 old='            if (sell <= 0 && buy <= 0) {';assert old in part
 part=part.replace('otcNumber(sellE.getText().toString(), 0), buy = otcNumber(buyE.getText().toString(), 0)', 'otcNumber(sellE.length() == 0 ? "0" : sellE.getText().toString(), Double.NaN), buy = otcNumber(buyE.length() == 0 ? "0" : buyE.getText().toString(), Double.NaN)')
 part=part.replace(old,'            if (!Double.isFinite(sell) || !Double.isFinite(buy) || sell < 0 || buy < 0 || (sell <= 0 && buy <= 0)) {')
 old='                    if (a <= 0 || p <= 0) {';assert part.count(old)==2
 part=part.replace(old,'                    if (a <= 0 || p <= 0 || !Double.isFinite(a) || !Double.isFinite(p) || !OtcController.validTerms(trim(a), trimSig(p))) {')
 old='    private CharSequence otcCheckFunds(String role, String side, String amount, String price) {';assert old in part
 part=part.replace(old,old+'\n        if (!OtcController.validTerms(amount, price)) return Language.msg(l10n_atomix_otc_enter_terms);')
 old='    private void publishOtcOffer(double sellSize, double buySize) {';assert old in part
 part=part.replace(old,old+'\n        if (!Double.isFinite(sellSize) || !Double.isFinite(buySize) || sellSize < 0 || buySize < 0) { toast(Language.msg(l10n_atomix_otc_enter_size)); return; }')
 helper='''    private static double otcNumber(String value, double fallback) {
        double parsed = parseD(value, fallback);
        return Double.isFinite(parsed) ? parsed : fallback;
    }

'''
 s=s[:start]+helper+part+s[end:];p.write_text(s)
