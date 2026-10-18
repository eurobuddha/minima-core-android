#!/usr/bin/env python3
"""Reject APK inputs missing the generated Keets worklet or its linked addons.

The header framing follows bare-bundle.fromBuffer: decimal byte count followed
by a JSON header (including both newlines), then the concatenated source files.
"""
import argparse
import json
from pathlib import Path
import re
import zipfile

ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86_64')


def required_addons(data):
    prefix = re.match(rb'(?:#![^\n]*\n)?([0-9]+)(?=\n)', data)
    if not prefix:
        raise ValueError('invalid Keets bundle header')
    start = prefix.end()
    end = start + int(prefix.group(1))
    if end > len(data):
        raise ValueError('truncated Keets bundle header')
    header = json.loads(data[start:end])
    if header.get('version') != 0:
        raise ValueError('unsupported Keets bundle version')
    files = header.get('files', {})
    if not files or header.get('main') not in files:
        raise ValueError('Keets bundle entry point is missing')
    offset = 0
    for info in files.values():
        length = info.get('length')
        if type(length) is not int or length < 0 or info.get('offset') != offset:
            raise ValueError('invalid Keets bundle file table')
        offset += length
    if offset != len(data) - end:
        raise ValueError('truncated or invalid Keets bundle payload')
    addons = header.get('addons', [])
    if not addons or any(not isinstance(a, str) or not re.fullmatch(
            r'linked:lib[A-Za-z0-9_.-]+\.so', a) for a in addons):
        raise ValueError('Keets bundle must declare linked Android addons')
    return {a.removeprefix('linked:') for a in addons}


def verify(root, abis):
    bundle = root / 'app/src/main/assets/keets.bundle'
    addons = required_addons(bundle.read_bytes())
    for abi in abis:
        for name in sorted(addons):
            path = root / 'app/src/main/jniLibs' / abi / name
            with path.open('rb') as library:
                if library.read(4) != b'\x7fELF':
                    raise ValueError(f'invalid native addon: {abi}/{name}')
    with zipfile.ZipFile(root / 'app/libs/bare-kit.aar') as runtime:
        for abi in abis:
            for name in ('libbare-kit.so', 'libc++_shared.so'):
                with runtime.open(f'jni/{abi}/{name}') as library:
                    if library.read(4) != b'\x7fELF':
                        raise ValueError(f'invalid Bare runtime: {abi}/{name}')
    return len(addons)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--abi', choices=ABIS, action='append')
    args = parser.parse_args()
    try:
        count = verify(args.root, args.abi or ABIS)
    except (OSError, ValueError, KeyError, TypeError, AttributeError, zipfile.BadZipFile) as error:
        parser.exit(1, f'Keets artifacts are incomplete: {error}\n'
                    'Run scripts/fetch-bare-kit.sh and scripts/pack-keets-worklet.sh '
                    '(set KEETS_SIDECAR_DIR when building from a worktree).\n')
    print(f'Keets bundle, {count} linked addons per ABI, and Bare runtime verified.')
