#!/usr/bin/env python3
"""Verify the actual APK and stage only public release artifacts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--variant', choices=['debug', 'release'], required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
metadata = json.loads((root / 'build/version.json').read_text())
expected_name = metadata['versionName'] + ('-debug' if args.variant == 'debug' else '')
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT']) / 'build-tools/36.0.0'
apk = root / f'app/build/outputs/apk/{args.variant}/app-{args.variant}.apk'
badging = subprocess.check_output([str(sdk / 'aapt2'), 'dump', 'badging', str(apk)], text=True)
match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
assert match, 'Cannot inspect APK manifest'
assert match.group(1) == 'me.seasonyuu.displaylauncher', 'Unexpected package'
assert int(match.group(2)) == metadata['versionCode'], 'versionCode mismatch'
assert match.group(3) == expected_name, 'versionName mismatch'
assert ('application-debuggable' in badging) == (args.variant == 'debug'), 'Wrong debuggable state'
signature = subprocess.check_output([str(sdk / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
fingerprint = re.search(r'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)', signature)
assert fingerprint, 'Missing signing certificate'
metadata.update(versionName=expected_name, variant=args.variant, certificateSha256=fingerprint.group(1).lower())
out = root / 'dist'
if out.exists():
    shutil.rmtree(out)
out.mkdir()
filename = f"DisplayLauncher-{json.loads((root / 'build/version.json').read_text())['versionName']}-{args.variant}.apk"
shutil.copy2(apk, out / filename)
(out / 'version.json').write_text(json.dumps(metadata, indent=2) + '\n')
(out / 'SHA256SUMS').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in [out / filename, out / 'version.json']))
print(f'Verified {filename}: code={metadata["versionCode"]}, certificate SHA-256={metadata["certificateSha256"]}')
