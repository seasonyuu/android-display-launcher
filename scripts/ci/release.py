#!/usr/bin/env python3
"""Gate a version tag and publish verified assets via an unpublished draft."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('mode', choices=['validate', 'publish'])
args = parser.parse_args()
repo = os.environ['GITHUB_REPOSITORY']
tag = os.environ['GITHUB_REF_NAME']
root = Path(__file__).resolve().parents[2]

def command(*parts):
    return subprocess.check_output(parts, text=True).strip()

def version(value):
    assert re.fullmatch(r'v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', value), f'Invalid release tag: {value}'
    return tuple(map(int, value[1:].split('.')))

version(tag)
metadata = json.loads((root / 'build/version.json').read_text())
assert metadata['tag'] == tag and metadata['versionName'] == tag[1:] and not metadata['dirty'], 'Release requires a clean exact version tag'
assert command('git', 'rev-parse', 'HEAD') == command('git', 'rev-parse', tag + '^{commit}') == metadata['commit']
subprocess.run(['git', 'merge-base', '--is-ancestor', metadata['commit'], 'origin/main'], check=True)
pages = json.loads(command('gh', 'api', f'repos/{repo}/releases', '--paginate', '--slurp'))
releases = [release for page in pages for release in page]
existing = next((r for r in releases if r['tag_name'] == tag), None)
assert existing is None or existing['draft'], 'This Release is already published; refusing to replace assets'
if existing:
    assert existing['target_commitish'] == metadata['commit'], 'Draft points to a different commit'
previous_certificates = set()
for release in releases:
    if release['draft'] or release['prerelease']:
        continue
    assert version(tag) > version(release['tag_name']), 'Version must increase over published releases'
    asset = next((a for a in release['assets'] if a['name'] == 'version.json'), None)
    assert asset, 'Published release has no version.json; cannot validate versionCode'
    previous = json.loads(command('gh', 'api', f'repos/{repo}/releases/assets/{asset["id"]}', '-H', 'Accept: application/octet-stream'))
    previous_certificates.add(previous['certificateSha256'])
    assert metadata['versionCode'] > previous['versionCode'], 'versionCode must increase over published releases'
if args.mode == 'validate':
    print(f'Validated {tag}, versionCode={metadata["versionCode"]}, commit={metadata["commit"]}')
else:
    packaged = json.loads((root / 'dist/version.json').read_text())
    assert all(packaged[k] == metadata[k] for k in ['versionCode', 'versionName', 'commit', 'tag', 'dirty'])
    assert packaged['variant'] == 'release' and packaged['certificateSha256']
    assert not previous_certificates or previous_certificates == {packaged['certificateSha256']}, 'Signing certificate differs from published releases'
    if existing is None:
        subprocess.run(['gh', 'release', 'create', tag, '--repo', repo, '--verify-tag', '--target', metadata['commit'], '--title', f'Display Launcher {tag}', '--generate-notes', '--draft'], check=True)
    assets = [str(p) for p in sorted((root / 'dist').iterdir()) if p.is_file()]
    subprocess.run(['gh', 'release', 'upload', tag, *assets, '--repo', repo, '--clobber'], check=True)
    subprocess.run(['gh', 'release', 'edit', tag, '--repo', repo, '--draft=false', '--latest'], check=True)
