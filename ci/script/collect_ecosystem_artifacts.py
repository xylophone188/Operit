#!/usr/bin/env python3
"""Create a local evidence bundle; never copies credentials or private baseline patches."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--destination', required=True)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
dest = Path(args.destination).expanduser().resolve()
dest.mkdir(parents=True, exist_ok=True)
commit = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True).strip()
tracked_dirty = subprocess.check_output(['git', '-C', str(repo), 'status', '--porcelain', '--untracked-files=no'], text=True).strip()
if tracked_dirty:
    raise SystemExit('Refusing artifact attribution: tracked source is dirty')
manifest = {'task_id': 'operit-cross-device-20261110-1330', 'source_commit': commit,
            'source_url': 'https://github.com/xylophone188/Operit/commit/' + commit, 'artifacts': []}
for variant in ('debug', 'release'):
    for source in sorted((repo / 'app/build/outputs/apk' / variant).glob('*.apk')):
        target = dest / ('operit-' + commit[:12] + '-' + variant + '.apk')
        shutil.copy2(source, target)
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        assert digest == hashlib.sha256(source.read_bytes()).hexdigest()
        manifest['artifacts'].append({'variant': variant, 'path': str(target), 'bytes': target.stat().st_size, 'sha256': digest})
suites = [ET.parse(p).getroot() for p in (repo / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
manifest['tests'] = {key: sum(int(s.attrib.get(key, 0)) for s in suites) for key in ('tests', 'failures', 'errors', 'skipped')}
if not manifest['artifacts'] or manifest['tests']['failures'] or manifest['tests']['errors']:
    raise SystemExit('Missing artifacts or failing tests')
(repo / '.hermes/tasks/operit-cross-device-20261110-1330').mkdir(parents=True, exist_ok=True)
(dest / 'artifact-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps(manifest, indent=2))
