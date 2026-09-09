# Six-decimal wallet summaries — 1.6.15-ui-h2 (43)

Main wallet totals, token rows, and confirmed/locked/unconfirmed summaries display
at most six decimal places. Extra places are truncated toward zero, and trailing
zeros are removed. Positive amounts smaller than one millionth show `<0.000001`.
Token and coin details retain exact amounts; underlying values and arithmetic are
unchanged. Tapping the amount now follows the existing token-row detail action.

Reused `../atomix/app/src/main/java/com/eurobuddha/atomix/Util.java` (`fmt5`) and its
`../atomix/app/src/test/java/com/eurobuddha/atomix/UtilTest.java` behavior, adapting
the precision to six and preserving visible tiny balances. Existing `Format.tidyAmount`,
`CoinsDialog`, and `CoinsAdapter` continue to serve full-precision details.

## Code Review

### Summary
Reviewed the formatter, wallet callers, row interaction/layout, detail paths, and
regression cases. The summary formatter is used only at display boundaries, with
BigDecimal truncation and a guard against expanding enormous positive exponents.

### Findings
No new correctness issues found in this change.

### Verdict
✅ Approve. Release build and lint passed; all 13 JVM tests passed, including six
formatter tests covering exact precision, truncation, tiny balances, exponents,
large balances, and placeholders. APK signature verified against the family key.
Installed on the Z Fold (RFCY71KW3LX); ADB confirmed versionName 1.6.15-ui-h2 and
versionCode 43. The user subsequently authorized publication.
