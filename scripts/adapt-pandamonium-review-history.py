"""Keep History's existing adaptive pagination without skipping failed records."""
from pathlib import Path

def repair(s):
    def change(old, new):
        nonlocal s
        assert s.count(old) == 1, ('History source drift', old)
        s = s.replace(old, new)
    change('MAX_SKIP = 3;', 'MAX_RETRIES = 3;')
    s = s.replace('skipFails', 'pageFailures')
    change('db.getMeta("backfill_done", "")', 'db.getMeta("backfill_complete_v2", "")')
    change('if (++fetches > MAX_FETCHES) { finish(true); return; }', 'if (++fetches > MAX_FETCHES) { finish(false); return; }')
    change('JSONObject r = j.optJSONObject("response");', 'JSONObject r = j == null ? null : j.optJSONObject("response");')
    change('!j.optBoolean("status", true)', '!Boolean.TRUE.equals(j.opt("status"))')
    change('                pageFailures = 0;\n', '')
    change('                boolean hitKnown = false;\n                int pageNew = 0;', '                // Validate the entire page before storing any part of it.\n                for (int i = 0; i < got; i++) {\n                    JSONObject tx = txpows.optJSONObject(i);\n                    Object id = tx == null ? null : tx.opt("txpowid");\n                    if (!(id instanceof String) || ((String) id).trim().isEmpty()) {\n                        overLimit(offset); return;\n                    }\n                }\n                pageFailures = 0;\n                boolean hitKnown = false;\n                int pageNew = 0;')
    change('at max:1, skip one giant txpow.', 'at max:1, retry without advancing past the failed record.')
    change('++pageFailures <= MAX_SKIP', '++pageFailures <= MAX_RETRIES')
    change('// even a single txpow exceeds 256 KB — skip it so it can\'t stall the whole sync', '// A permanently unreadable record leaves the sync incomplete; never hide the gap.')
    change('fetchPage(offset + 1)', 'fetchPage(offset)')
    change('db.setMeta("backfill_done", "true")', 'db.setMeta("backfill_complete_v2", "true")')
    change('        if (db.getMeta("first_sync_ts", "").isEmpty())\n            db.setMeta("first_sync_ts", String.valueOf(System.currentTimeMillis()));\n        db.setMeta("synced_tip_block", String.valueOf(act.chainBlock()));\n        db.setMeta("last_sync_ts", String.valueOf(System.currentTimeMillis()));', '        if (ok) {\n            if (db.getMeta("first_sync_ts", "").isEmpty())\n                db.setMeta("first_sync_ts", String.valueOf(System.currentTimeMillis()));\n            db.setMeta("synced_tip_block", String.valueOf(act.chainBlock()));\n            db.setMeta("last_sync_ts", String.valueOf(System.currentTimeMillis()));\n        }')
    return s

def adapt(root):
    p = root/'history/src/main/java/com/eurobuddha/history/HistorySync.java'
    p.write_text(repair(p.read_text()))
    p = root/'history/build.gradle'
    s = p.read_text().replace('    compileOptions {', '    testOptions { unitTests.returnDefaultValues = true }\n    compileOptions {')
    s = s.replace("    testImplementation 'junit:junit:4.13.2'", "    testImplementation 'org.mockito:mockito-core:5.12.0'\n    testImplementation 'junit:junit:4.13.2'")
    p.write_text(s)
