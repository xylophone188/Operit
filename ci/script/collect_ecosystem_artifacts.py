#!/usr/bin/env python3
"""Collect artifacts only from an attested successful build; no credentials are copied."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--destination', required=True)
parser.add_argument('--build-attestation', required=True)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
dest = Path(args.destination).expanduser().resolve()
commit = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True).strip()
if subprocess.check_output(['git', '-C', str(repo), 'status', '--porcelain', '--untracked-files=no'], text=True).strip():
    raise SystemExit('Refusing artifact attribution: tracked source is dirty')
attestation = json.loads(Path(args.build_attestation).read_text())
if attestation['source_commit'] != commit or attestation['exit_code'] != 0:
    raise SystemExit('Build attestation does not match successful HEAD build')

def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

suites = [ET.parse(p).getroot() for p in (repo / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
tests = {key: sum(int(s.attrib.get(key, 0)) for s in suites) for key in ('tests', 'failures', 'errors', 'skipped')}
if not tests['tests'] or tests['failures'] or tests['errors']:
    raise SystemExit('Missing tests or failing tests')
manifest = {'task_id': 'operit-cross-device-20261110-1330', 'source_commit': commit,
            'source_url': 'https://github.com/xylophone188/Operit/commit/' + commit,
            'build': attestation, 'tests': tests, 'artifacts': []}
dest.mkdir(parents=True, exist_ok=True)
for item in attestation['artifacts']:
    source = repo / item['source']
    if digest(source) != item['sha256']:
        raise SystemExit('Artifact changed after build attestation')
    target = dest / ('operit-' + commit[:12] + '-' + item['variant'] + '-' + source.name)
    shutil.copy2(source, target)
    if digest(target) != item['sha256']:
        raise SystemExit('Copy checksum mismatch')
    manifest['artifacts'].append({**item, 'path': str(target), 'bytes': target.stat().st_size})
if not manifest['artifacts']:
    raise SystemExit('Missing artifacts')
(dest / 'artifact-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps(manifest, indent=2))
