"""Reuse retained dialogs and original consent assembly; separate the funding list from its warning."""
from pathlib import Path
import re

def adapt(root):
 p=root/'pandadex/src/main/java/com/eurobuddha/pandadex/MainActivity.java';s=p.read_text()
 def change(text,old,new,count=1):
  assert text.count(old)==count,('PandaDEX maker dialog source drift',old,text.count(old));return text.replace(old,new)
 a=s.index('    public void publishMaker(');b=s.index('    private String makerFundingHint(',a);part=s[a:b]
 x=part.index('        StringBuilder lack =');z=part.index('        if (lack.length() > 0)',x)
 lack=part[x:z].replace('minimaLockedNode()','minimaLocked').replace('usdtLockedNode()','usdtLocked')
 helpers='''    static String makerFundingLack(MakerLadder.Commitments c, BigDecimal minimaSendable, BigDecimal minimaLocked,
            BigDecimal minimaUnconfirmed, BigDecimal usdtSendable, BigDecimal usdtLocked, BigDecimal usdtUnconfirmed) {
'''+lack+'        return lack.toString();\n    }\n\n'
 part=part[:x]+'''        String lack = makerFundingLack(c, minimaSendable, minimaLockedNode(), minimaUnconfirmed,
                usdtSendable, usdtLockedNode(), usdtUnconfirmed);
'''+part[z:]
 x=part.index('        String sides =');z=part.index('        String fundingHint =',x);sides=part[x:z];part=part[:x]+part[z:]
 x=part.index('.setMessage("This puts ');z=part.index('\n                .setPositiveButton',x)
 expr=part[x+len('.setMessage('):z].strip();assert expr.endswith(')');expr=expr[:-1]
 helpers+='''    static CharSequence makerPublishMessage(int nAsks, int nBids, MakerLadder.Commitments c, boolean pegged, String fundingHint) {
'''+sides+'        return DexMessages.quote('+expr+');\n    }\n\n'
 part=part[:x]+'.setMessage(makerPublishMessage(nAsks, nBids, c, pegged, fundingHint))'+part[z:]
 part=change(part,'.setMessage(lack + "\\nShrink the rung sizes or free up funds, then publish.")','.setMessage(DexMessages.quote(lack + "\\nShrink the rung sizes or free up funds, then publish."))')
 part=change(part,'''.setMessage("Wait for the next block and balance refresh before publishing. "
                            + "The split outputs are not spendable yet.")''','''.setMessage(DexMessages.message("Wait for the next block and balance refresh before publishing. "
                            + "The split outputs are not spendable yet."))''')
 s=s[:a]+part+helpers+s[b:]
 a=s.index('    private String makerFundingHint(');b=s.index('    /** Split either side',a);part=s[a:b]
 part=change(part,'    private String makerFundingHint(int nAsks, int nBids) {','''    private String makerFundingHint(int nAsks, int nBids) {
        return makerFundingHint(nAsks, nBids, minimaCoins, usdtCoins);
    }

    static String makerFundingHint(int nAsks, int nBids, int minimaCoins, int usdtCoins) {''')
 s=s[:a]+part+s[b:]
 a=s.index('    public void prepareMakerFundingUtxos()');b=s.index('    private void splitMakerFunding(',a);part=s[a:b]
 x=part.index('        String[] items =')
 part=part[:x]+'''        showMakerFundingChoices(minimaSendable, usdtSendable, minimaCoins, usdtCoins);
    }

    // A message and a list cannot share AlertDialog's content panel. Keep the list visible,
    // then review the selected immutable amount and warning before any node write.
    void showMakerFundingChoices(BigDecimal minima, BigDecimal mxusd, int minimaCount, int mxusdCount) {
        CharSequence[] items = {makerFundingItem("MINIMA", minima, minimaCount), makerFundingItem("MxUSD", mxusd, mxusdCount)};
        new LanguageDialog(this, Design.dialogTheme())
                .setTitle(DexMessages.message("Split maker funding"))
                .setItems(items, (d, which) -> {
                    if (which == 0) confirmMakerFunding(Util.MINIMA_TOKENID, "MINIMA", minima, minimaCount);
                    else if (which == 1) confirmMakerFunding(DexContract.USDT_ID, "MxUSD", mxusd, mxusdCount);
                })
                .setNegativeButton(DexMessages.message("Cancel"), null)
                .show();
    }

    static CharSequence makerFundingItem(String label, BigDecimal amount, int coins) {
        return DexMessages.message(label + " - " + PriceMath.fmt(amount) + " sendable, " + coins + " coin" + (coins == 1 ? "" : "s"));
    }

    private void confirmMakerFunding(String tokenid, String label, BigDecimal amount, int coins) {
        new LanguageDialog(this, Design.dialogTheme())
                .setTitle(DexMessages.message("Split maker funding"))
                .setMessage(Language.join(makerFundingItem(label, amount, coins), "\\n\\n", Language.msg(l10n_dex_maker_split_body, SelfSplit.COUNT)))
                .setPositiveButton(DexMessages.message("Split funds"), (d, w) -> {
                    if (!ready()) return;
                    if (makerCfg != null && makerCfg.armed) {
                        toast("Withdraw the live ladder before splitting maker funding coins"); return;
                    }
                    splitMakerFunding(tokenid, label, amount);
                })
                .setNegativeButton(DexMessages.message("Cancel"), null)
                .show();
    }

'''
 s=s[:a]+part+s[b:]
 a=s.index('    public void applyMakerEdits(');b=s.index('    /** Withdraw the ladder:',a);part=s[a:b]
 x=part.index('        StringBuilder sb =');z=part.index('        new AlertDialog.Builder',x);body=part[x:z]
 part=part[:x]+'        CharSequence message = makerEditMessage(relocks, reposts, creates, cancels);\n\n'+part[z:]
 part=change(part,'.setMessage(sb.toString())','.setMessage(message)')
 part+='''    static CharSequence makerEditMessage(int relocks, int reposts, int creates, int cancels) {
'''+body+'        return DexMessages.quote(sb.toString());\n    }\n\n'
 s=s[:a]+part+s[b:]
 # Bind only these dialog labels; action bodies and original engine requests remain intact.
 a=s.index('    public void publishMaker(');b=s.index('    /** Withdraw the ladder:',a);part=s[a:b]
 part=part.replace('new AlertDialog.Builder(this, Design.dialogTheme())','new LanguageDialog(this, Design.dialogTheme())')
 part=re.sub(r'\.(setTitle|setPositiveButton|setNegativeButton|setNeutralButton)\(("(?:\\.|[^"\\])*")',r'.\1(DexMessages.message(\2)',part)
 s=s[:a]+part+s[b:];p.write_text(s)
