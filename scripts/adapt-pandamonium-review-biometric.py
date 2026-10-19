"""Extend the existing secure-key error boundary to persisted biometric decoding."""
from pathlib import Path

def repair(s):
    start=s.index('        SharedPreferences p = prefs(zActivity);')
    end=s.index('            SecretKey key = loadKey();',start)
    old=s[start:end]
    assert old.count('Base64.decode') == 2
    prefix=old[:old.index('        final Cipher cipher;')].rstrip()
    prefix=prefix.replace('final byte[] ct =', 'ct =')
    prefix='\n'.join('    '+line if line else line for line in prefix.splitlines())
    return s[:start]+'        final byte[] ct;\n        final Cipher cipher;\n        try {\n'+prefix+'\n'+s[end:]

def adapt(root):
    p=root/'wallet/src/main/java/com/eurobuddha/wallet/BiometricUnlock.java'
    p.write_text(repair(p.read_text()))
