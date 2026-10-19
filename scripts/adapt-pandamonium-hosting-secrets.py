"""Fail closed on encrypted hosting credentials; preserve unfinished edits and restore inputs."""
from pathlib import Path
import shutil

def adapt(root):
    project=Path(__file__).resolve().parents[1]
    for app,package in [('salon','salon'),('atelier','statenft')]:
        java=root/app/('src/main/java/com/eurobuddha/'+package)
        for name in ['Crypt.java','HostingSecrets.java']:
            shutil.copy2(project/'scripts/pandamonium-sources'/app/name,java/name)
        p=java/'Hosting.java';s=p.read_text();old='    static Uploader forProfile(Profile p) throws HostingException {\n'
        assert old in s;s=s.replace(old,old+'        HostingSecrets.requireReadable(p);\n');p.write_text(s)
        p=java/'MainActivity.java';s=p.read_text()
        def change(old,new):
            nonlocal s
            assert old in s, (app,'hosting secret caller drift',old)
            s=s.replace(old,new)
        marker='    private void cfgSecret(' if app=='salon' else '    private void hostField('
        helpers='''    private final HostingSecrets hostSecrets = new HostingSecrets();

    private void hostingSecretUnavailable() {
        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_secret_unavailable).toString());
    }
    private boolean hostingSecretsReady(Hosting.Profile profile) {
        if (hostSecrets.retry(profile)) return true;
        toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_secret_not_saved).toString());
        return false;
    }
    private void bindHostingSecret(EditText input, JSONObject cfg, String key,
            boolean revealStored, boolean trim, boolean keepBlank) {
        hostSecrets.bind(input, cfg, key);
        try { input.setText(hostSecrets.value(cfg, key, revealStored)); }
        catch (Crypt.Unavailable unavailable) {
            Language.error(input, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_secret_unavailable));
        }
        if (hostSecrets.pending(cfg, key))
            Language.error(input, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_secret_not_saved));
        input.addTextChangedListener(watch(value -> {
            String next = trim ? value.trim() : value;
            if (keepBlank && next.isEmpty()) {
                hostSecrets.discard(cfg, key); Language.error(input, null); return;
            }
            boolean saved = hostSecrets.write(cfg, key, next);
            Language.error(input, saved ? null : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_secret_not_saved));
        }));
    }

'''
        if app == 'salon':
            helpers=helpers.replace('private void bindHostingSecret(', 'private android.text.TextWatcher bindHostingSecret(')
            helpers=helpers.replace('input.addTextChangedListener(watch(value -> {', 'android.text.TextWatcher watcher = watch(value -> {')
            helpers=helpers.replace('        }));\n    }', '        });\n        input.addTextChangedListener(watcher);\n        return watcher;\n    }')
        index=s.index(marker);s=s[:index]+helpers+s[index:]
        if app=='salon':
            change('private void cfgSecret(LinearLayout parent, JSONObject cfg, String key, String label) { EditText e = field(parent, label, Crypt.decrypt(cfg.optString(key, "")), true, "••••••"); e.addTextChangedListener(watch(s -> Hosting.put(cfg, key, Crypt.encrypt(s)))); }',
                   (project/'scripts/pandamonium-sources/salon/HostingSecretField.java.inc').read_text().strip())
            change('EditText e = new EditText(this); e.setText(Crypt.decrypt(cfg.optString(key, "")));','EditText e = new EditText(this);')
            change('e.addTextChangedListener(watch(s -> Hosting.put(cfg, key, Crypt.encrypt(s))));','bindHostingSecret(e, cfg, key, true, false, false);')
            change('saveHost(p); go(Screen.HOSTING);','if (saveHost(p)) go(Screen.HOSTING);')
            change('saveHost(p); testProfile(p, status);','if (saveHost(p)) testProfile(p, status);')
            change('    private void saveHost(Hosting.Profile p) {','    private boolean saveHost(Hosting.Profile p) {\n        if (!hostingSecretsReady(p)) return false;')
            change('        if (HostingStore.getDefault(this) == null || HostingStore.list(this).size() == 1) HostingStore.setDefault(this, p.id());\n    }',
                   '        if (HostingStore.getDefault(this) == null || HostingStore.list(this).size() == 1) HostingStore.setDefault(this, p.id());\n        return true;\n    }')
            start=s.index('    private String buildBackupJson()');end=s.index('    private void restoreFromFile()',start)
            part=s[start:end];a=part.index('                JSONObject copy = new JSONObject(p.j.toString());');b=part.index('                hosts.put(copy);',a)
            part=part[:a]+'                JSONObject copy = HostingSecrets.copySecrets(p.j, false);\n'+part[b:]
            part=part.replace('} catch (Exception unavailable)', '} catch (Crypt.Unavailable unavailable) { throw unavailable; }\n        catch (Exception unavailable)')
            s=s[:start]+part+s[end:]
            start=s.index('    private void backupAccount()');end=s.index('    private void backupMsgKey()',start)
            part=s[start:end].replace('} catch (SalonComms.IdentityUnavailable unavailable)',
                    '} catch (Crypt.Unavailable unavailable) { runOnUiThread(this::hostingSecretUnavailable); }\n            catch (SalonComms.IdentityUnavailable unavailable)')
            s=s[:start]+part+s[end:]
            start=s.index('    private void restoreFromBackup(');end=s.index('    private void restoreMsgKey()',start)
            part=s[start:end]
            part=part.replace('            JSONObject me = b.optJSONObject("me");','            JSONArray preparedHosts = HostingSecrets.prepareRestore(b.optJSONArray("hosting"));\n            JSONObject me = b.optJSONObject("me");')
            a=part.index('            JSONArray hosts = b.optJSONArray("hosting");');b=part.index('            boolean msgOk = false',a)
            part=part[:a]+'''            for (int i = 0; i < preparedHosts.length(); i++) {
                HostingStore.upsert(this, new Hosting.Profile(preparedHosts.getJSONObject(i)));
                hostN++;
            }
'''+part[b:]
            part=part.replace('toast("Restore failed: " + e.getMessage())','toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_secret_restore_failed))')
            s=s[:start]+part+s[end:]
        else:
            change('e.addTextChangedListener(watch(sv -> { if (!sv.isEmpty()) Hosting.put(cfg, key, Crypt.encrypt(sv)); }));',
                   'bindHostingSecret(e, cfg, key, false, false, true);')
            change('e.addTextChangedListener(watch(sv -> { if (!sv.trim().isEmpty()) Hosting.put(cfg, key, Crypt.encrypt(sv.trim())); }));',
                   'bindHostingSecret(e, cfg, key, false, true, true);')
            change('            if (hostEditProfile.name().isEmpty()) { toast("Give the destination a name"); return; }',
                   '            if (!hostingSecretsReady(hostEditProfile)) return;\n            if (hostEditProfile.name().isEmpty()) { toast("Give the destination a name"); return; }')
            change('                    final org.json.JSONObject arCfg = cfg;',
                   '                    final org.json.JSONObject arCfg = cfg;\n                    final Hosting.Profile generatedProfile = hostEditProfile;')
            change('                                String jwk = Ans104.generateJwkJson();\n                                main.post(() -> {\n                                    Hosting.put(arCfg, "jwk", Crypt.encrypt(jwk));',
                   '                                String jwk = Ans104.generateJwkJson();\n                                String wrapped = Crypt.encrypt(jwk);\n                                main.post(() -> {\n                                    Hosting.put(arCfg, "jwk", wrapped);')
            start=s.index('                    final Hosting.Profile generatedProfile');end=s.index('                    body.addView(gen,',start)
            part=s[start:end].replace('hostEditProfile.name()', 'generatedProfile.name()').replace('Hosting.put(hostEditProfile.j,', 'Hosting.put(generatedProfile.j,').replace('HostingStore.upsert(this, hostEditProfile)', 'HostingStore.upsert(this, generatedProfile)')
            part=part.replace('if (screen == Screen.HOSTING_EDIT) renderHostingEdit(hostEditProfile);','if (screen == Screen.HOSTING_EDIT && hostEditProfile == generatedProfile) renderHostingEdit(generatedProfile);')
            part=part.replace('                            } catch (Exception e) {','                            } catch (Crypt.Unavailable unavailable) {\n                                main.post(() -> { hostingSecretUnavailable(); gen.setEnabled(true); gen.setAlpha(1f); gen.setText("Generate wallet"); });\n                            } catch (Exception e) {')
            s=s[:start]+part+s[end:]
            change('                    catch (Exception e) { addr = "(wallet unreadable — paste a valid JWK below)"; }',
                   '                    catch (Crypt.Unavailable unavailable) { addr = unavailable.getMessage(); }\n                    catch (Exception e) { addr = "(wallet unreadable — paste a valid JWK below)"; }')
            change('                    addrV.setOnClickListener(v -> copyText(addrF));',
                   '                    addrV.setOnClickListener(v -> { if (addrF.matches("[A-Za-z0-9_-]{43}")) copyText(addrF); });')
        p.write_text(s)

    p=root/'atelier/src/test/java/com/eurobuddha/statenft/HostingTest.java';s=p.read_text()
    s=s.replace('profileRoundTripAndSecretWrap()', 'profileRoundTripWithLegacySecret()')
    s=s.replace('Crypt.encrypt("s3cret")', '"plain:s3cret"')
    s=s.replace('// JVM has no AndroidKeyStore -> Crypt falls back to the visible\n        // "plain:" form, and decrypt round-trips it', '// Existing legacy profiles remain readable; HostingCryptTest covers secure new writes.')
    p.write_text(s)
