"""Restore, verify and publish the exact signed universal installation hotfix."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import zipfile
from publish_native_release import gh_json, restore

REPO = '4pdara/DocScan'
TAG = 'native-v0.1.8-preview'
APP = 'com.docscan.rebuild.csnative'
CERT = '69dce468c27a15728cf501feb9bf76d67263031f5ee34604f78b6be55465e89c'
APP_NAME = 'DocScan-0.1.8-universal.apk'
ABIS = {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}
# Room schema exports are generated documentation, independent of the packaged source.
SOURCE_PATHS = ['app', ':(exclude)app/schemas/**', 'gradle', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties']


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_run(expected, source):
    run = gh_json(f"repos/{REPO}/actions/runs/{expected['id']}")
    if run['head_sha'] != expected['head_sha']:
        raise ValueError('Validation commit changed')
    subprocess.run(['git', 'diff', '--exit-code', source, expected['head_sha'], '--', *SOURCE_PATHS], check=True)
    return run


def prepare(plan, output, tools):
    if plan['format'] != 2 or not re.fullmatch('[0-9a-f]{40}', plan['source_commit']):
        raise ValueError('Unsupported universal delivery plan')
    subprocess.run(['git', 'diff', '--exit-code', plan['source_commit'], '--', *SOURCE_PATHS], check=True)
    run = verify_run(plan['build_run'], plan['source_commit'])
    if run['status'] != 'completed' or run['conclusion'] != 'success' or run['name'] != 'Universal 0.1.8 compatibility build':
        raise ValueError('Universal build has not passed')
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary); archive = root / 'input.zip'
        with archive.open('wb') as stream:
            subprocess.run(['gh', 'api', f"repos/{REPO}/actions/artifacts/{plan['artifact_id']}/zip"], stdout=stream, check=True)
        if sha(archive) != plan['artifact_zip_sha256']:
            raise ValueError('CI archive digest mismatch')
        with zipfile.ZipFile(archive) as inputs:
            for key, entry, package in [('app', 'debug/app-debug.apk', APP), ('tests', 'androidTest/debug/app-debug-androidTest.apk', APP + '.test')]:
                item = plan[key]
                expected_name = APP_NAME if key == 'app' else 'DocScan-0.1.8-tests.apk'
                if item['apk_name'] != expected_name:
                    raise ValueError('Unexpected signed artifact name')
                baseline = root / (key + '.apk'); baseline.write_bytes(inputs.read(entry))
                payload = root / (key + '.gz'); payload.write_bytes(base64.b64decode(item['payload_gzip_base64'], validate=True))
                if payload.stat().st_size > 128 * 1024 or item['literal_size'] > 512 * 1024:
                    raise ValueError('Unexpected non-CI payload')
                apk, _ = restore(item, baseline, payload, output / key)
                cert = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
                if re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})', cert) != [CERT]:
                    raise ValueError('Update signing certificate differs')
                badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
                if f"package: name='{package}'" not in badging or (key == 'app' and "versionCode='9'" not in badging):
                    raise ValueError('Signed package identity differs')
                if key == 'app':
                    with zipfile.ZipFile(apk) as z:
                        abis = {name.split('/')[1] for name in z.namelist() if name.startswith('lib/') and name.endswith('.so')}
                        if abis != ABIS or z.testzip() is not None:
                            raise ValueError('Universal package is incomplete')
    (output / 'BUILD.json').write_text(json.dumps(dict(source_commit=plan['source_commit'], release_commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
        application_id=APP, version_code=9, version_name='0.1.8-universal-preview', minimum_android_api=26,
        architectures=sorted(ABIS), optional_native_architecture='arm64-v8a', certificate_sha256=CERT,
        apk_sha256=plan['app']['apk_sha256'], apk_size=plan['app']['apk_size'], build_run=plan['build_run'],
        source_validation_runs=plan['validation_runs'], signed_installation_run=os.environ.get('GITHUB_RUN_ID')), indent=2) + '\n')
    (output / 'SHA256SUMS.txt').write_text(plan['app']['apk_sha256'] + '  ' + APP_NAME + '\n')
    (output / 'INSTALLATION_HE.txt').write_text('DocScan 0.1.8: חבילת התקנה מתוקנת ל־Android 8 ומעלה, 32 ו־64 ביט.\nעדכון באותו מזהה חבילה ובאותה חתימה, ללא הסרת ההתקנה הקיימת.\nבמכשיר ללא מנוע ARM64 זמין פועל העיבוד המקומי; אין הבטחה שאותם מסנני Native זמינים בכל מעבד.\n', encoding='utf-8')


def publish(plan, output):
    deadline = time.monotonic() + 15 * 60
    while True:
        runs = [verify_run(item, plan['source_commit']) for item in plan['validation_runs']]
        if any(run['status'] == 'completed' and run['conclusion'] != 'success' for run in runs):
            raise ValueError('A required source validation failed')
        if all(run['status'] == 'completed' and run['conclusion'] == 'success' for run in runs):
            break
        if time.monotonic() > deadline:
            raise ValueError('Source validations did not finish in time')
        print('Waiting for remaining source validations', flush=True); time.sleep(30)
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    notes = output / 'RELEASE_NOTES.md'
    notes.write_text(Path('docs/PREVIEW_0.1.8_HE.md').read_text() + '\n\n[בדיקות התקנת הקובץ החתום](https://github.com/' + REPO + '/actions/runs/' + os.environ['GITHUB_RUN_ID'] + ')\n')
    releases = gh_json(f'repos/{REPO}/releases?per_page=100')
    existing_release = next((item for item in releases if item['tag_name'] == TAG), None)
    if existing_release is None:
        subprocess.run(['gh', 'release', 'create', TAG, '--repo', REPO, '--target', commit, '--draft', '--prerelease', '--latest=false',
            '--title', 'DocScan 0.1.8 — כריכות וכלי ספר', '--notes-file', str(notes)], check=True)
    release = gh_json(f'repos/{REPO}/releases/tags/{TAG}')
    if not release['prerelease']:
        raise ValueError('Refusing to replace a final release')
    apk = output / 'app' / APP_NAME
    files = [apk] + [output / name for name in ['SHA256SUMS.txt', 'BUILD.json', 'INSTALLATION_HE.txt']]
    for path in files:
        existing = next((asset for asset in release['assets'] if asset['name'] == path.name), None)
        if existing and existing.get('digest') != 'sha256:' + sha(path):
            raise ValueError('Different asset already exists: ' + path.name)
        if existing is None:
            subprocess.run(['gh', 'release', 'upload', TAG, str(path), '--repo', REPO], check=True)
    published = gh_json(f'repos/{REPO}/releases/tags/{TAG}')
    for path in files:
        asset = next(item for item in published['assets'] if item['name'] == path.name)
        if asset.get('digest') != 'sha256:' + sha(path) or asset['size'] != path.stat().st_size:
            raise ValueError('Uploaded asset differs: ' + path.name)
    subprocess.run(['gh', 'release', 'edit', TAG, '--repo', REPO, '--draft=false', '--prerelease', '--latest=false',
        '--title', 'DocScan 0.1.8 — כריכות וכלי ספר', '--notes-file', str(notes)], check=True)
    ref = gh_json(f'repos/{REPO}/git/ref/tags/{TAG}')
    if ref['object']['type'] != 'commit' or ref['object']['sha'] != commit:
        raise ValueError('Published release tag differs')
    print(published['html_url'])



if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=Path('.github/universal-signatures-0.1.8.json'))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--publish', action='store_true')
    args = parser.parse_args(); plan = json.loads(args.manifest.read_text())
    prepare(plan, args.output, args.build_tools)
    if args.publish: publish(plan, args.output)
