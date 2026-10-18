#!/usr/bin/env python3
"""Regression coverage for missing Keets packaging inputs and nested addon versions."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('guard', Path(__file__).with_name('verify-keets-artifacts.py'))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class KeetsArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bundle = self.root / 'app/src/main/assets/keets.bundle'
        self.bundle.parent.mkdir(parents=True)
        header = {'version': 0, 'main': '/index.js',
                  'files': {'/index.js': {'offset': 0, 'length': 2}},
                  'addons': ['linked:libbare-signals.4.2.0.so', 'linked:libbare-signals.5.0.0.so']}
        framed = ('\n' + json.dumps(header) + '\n').encode()
        self.bundle.write_bytes(str(len(framed)).encode() + framed + b'//')
        self.libs = self.root / 'app/src/main/jniLibs/arm64-v8a'
        self.libs.mkdir(parents=True)
        for addon in header['addons']:
            (self.libs / addon.removeprefix('linked:')).write_bytes(b'\x7fELFtest')
        self.runtime = self.root / 'app/libs/bare-kit.aar'
        self.runtime.parent.mkdir(parents=True)
        self.write_runtime(('libbare-kit.so', 'libc++_shared.so'))

    def write_runtime(self, names):
        with zipfile.ZipFile(self.runtime, 'w') as archive:
            for name in names:
                archive.writestr('jni/arm64-v8a/' + name, b'\x7fELFtest')

    def verify(self):
        return guard.verify(self.root, ('arm64-v8a',))

    def test_complete_inputs(self):
        self.assertEqual(2, self.verify())

    def test_missing_bundle(self):
        self.bundle.unlink()
        with self.assertRaises(FileNotFoundError): self.verify()

    def test_missing_nested_addon_version(self):
        (self.libs / 'libbare-signals.5.0.0.so').unlink()
        with self.assertRaises(FileNotFoundError): self.verify()

    def test_corrupt_addon(self):
        (self.libs / 'libbare-signals.5.0.0.so').write_bytes(b'')
        with self.assertRaises(ValueError): self.verify()

    def test_truncated_bundle_payload(self):
        self.bundle.write_bytes(self.bundle.read_bytes()[:-1])
        with self.assertRaises(ValueError): self.verify()

    def test_corrupt_bundle_header(self):
        self.bundle.write_bytes(b'12\n{}')
        with self.assertRaises(ValueError): self.verify()

    def test_missing_runtime_library(self):
        self.write_runtime(('libbare-kit.so',))
        with self.assertRaises(KeyError): self.verify()


if __name__ == '__main__':
    unittest.main()
