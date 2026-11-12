"""Restore the exact signed 0.1.7 APK using verified CI bytes and public signatures.

The transfer manifest contains ZIP metadata and public APK signing records only.
Application code, native libraries and private signing keys are not stored in it.
Publication runs in Actions with the repository's scoped GITHUB_TOKEN.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess

from publish_native_release import gh_json, restore


REPOSITORY = '4pdara/DocScan'
TAG = 'native-v0.1.7-preview'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input-apk', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--build-tools', required=True, type=Path)
    parser.add_argument('--publish', action='store_true')
    args = parser.parse_args()
    plan = json.loads(Path('.github/native-0.1.7-signature.json').read_text())
    if plan['format'] != 1 or plan['apk_name'] != 'DocScan-Native-0.1.7-preview.apk':
        raise ValueError('Unexpected delivery manifest')
    if not re.fullmatch('[0-9a-f]{40}', plan['source_commit']):
        raise ValueError('Invalid application source')
    subprocess.run(['git', 'diff', '--exit-code', plan['source_commit'], '--', 'app', 'gradle',
                    'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties'], check=True)
    if len(plan['validated_runs']) != 3 or any(not re.fullmatch('[0-9a-f]{40}', run['head_sha']) for run in plan['validated_runs']):
        raise ValueError('Three pinned application validations are required')
    # A collector-only correction may have a later commit; prove that its
    # application, tests and build inputs are identical to the packaged source.
    for expected in plan['validated_runs']:
        subprocess.run(['git', 'diff', '--exit-code', expected['head_sha'], plan['source_commit'], '--',
                        'app', 'gradle', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties'], check=True)
    if args.publish:
        validated_names = set()
        for expected in plan['validated_runs']:
            run = gh_json(f"repos/{REPOSITORY}/actions/runs/{expected['id']}")
            if run['head_sha'] != expected['head_sha'] or run['status'] != 'completed' or run['conclusion'] != 'success':
                raise ValueError('Required validation has not passed: ' + str(expected['id']))
            validated_names.add(run['name'])
        if validated_names != {'Native preview build input', 'Independent rebuild checks', 'Isolated native filter matrix and Frida'}:
            raise ValueError('Required application, interface and Native validations are incomplete')
    payload = base64.b64decode(plan['payload_gzip_base64'], validate=True)
    if len(payload) > 128 * 1024 or plan['literal_size'] > 512 * 1024:
        raise ValueError('Signature metadata exceeds the transfer limit')
    args.output.mkdir(parents=True, exist_ok=True)
    transfer = args.output / 'public-signatures.gz'
    try:
        transfer.write_bytes(payload)
        apk, checksum = restore(plan, args.input_apk, transfer, args.output)
    finally:
        transfer.unlink(missing_ok=True)
    certs = subprocess.check_output([str(args.build_tools / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
    signer = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})', certs)
    metadata = plan['build_metadata']
    if signer != [metadata['certificate_sha256']] or metadata['apk_sha256'] != plan['apk_sha256']:
        raise ValueError('Signed APK does not match the verified delivery identity')
    if metadata['application_id'] != 'com.docscan.rebuild.csnative' or metadata['standalone'] or signer != [metadata['legacy_certificate_sha256']]:
        raise ValueError('The update must match the original Native package and signing certificate')
    release_commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    metadata['release_commit'] = release_commit
    metadata['ci_artifact_id'] = plan['artifact_id']
    metadata['ci_archive_sha256'] = plan['artifact_zip_sha256']
    metadata['validation_runs'] = plan['validated_runs']
    build = args.output / 'BUILD.json'
    build.write_text(json.dumps(metadata, indent=2) + '\n')
    install = args.output / 'INSTALLATION_HE.txt'
    install.write_text('DocScan Native 0.1.7: יכולות 0.1.5 בממשק 0.1.6.\n'
                       'עדכון Native 0.1.5 באותה חתימה ובאותו מזהה חבילה. אין להסיר את ההתקנה הקיימת; המקורות והעריכות נשמרים.\n'
                       'גרסת Debug ניסיונית, Android 8 ומעלה, ARM64.\n', encoding='utf-8')
    if not args.publish:
        return
    release = gh_json(f'repos/{REPOSITORY}/releases/tags/{TAG}')
    files = [apk, checksum, build, install]
    expected_assets = {path.name: (path.stat().st_size, 'sha256:' + hashlib.sha256(path.read_bytes()).hexdigest()) for path in files}
    # The user's last 0.1.7 prerelease was published without files. Complete
    # that release, but never replace an already published different binary.
    for asset in release['assets']:
        expected = expected_assets.get(asset['name'])
        if expected is None or (asset['size'], asset.get('digest')) != expected:
            raise ValueError('Existing release contains a different asset: ' + asset['name'])
    ref_endpoint = f'repos/{REPOSITORY}/git/refs/tags/{TAG}'
    reference = gh_json(f'repos/{REPOSITORY}/git/ref/tags/{TAG}')
    if reference['object']['type'] != 'commit':
        raise ValueError('Expected a lightweight prerelease tag')
    if reference['object']['sha'] != release_commit:
        if reference['object']['sha'] != plan['previous_tag_sha']:
            raise ValueError('Release tag changed concurrently')
        subprocess.run(['git', 'merge-base', '--is-ancestor', plan['previous_tag_sha'], release_commit], check=True)
        subprocess.run(['gh', 'api', '--method', 'PATCH', ref_endpoint,
                        '-f', 'sha=' + release_commit, '-F', 'force=false'], check=True)
    existing_names = {asset['name'] for asset in release['assets']}
    missing = [str(path) for path in files if path.name not in existing_names]
    if missing:
        subprocess.run(['gh', 'release', 'upload', TAG, *missing, '--repo', REPOSITORY], check=True)
    subprocess.run(['gh', 'release', 'edit', TAG, '--repo', REPOSITORY, '--draft=false', '--prerelease',
                    '--latest=false', '--target', release_commit,
                    '--title', 'DocScan Native 0.1.7 — יכולות 0.1.5 בעיצוב 0.1.6',
                    '--notes-file', 'docs/PREVIEW_0.1.7_HE.md'], check=True)
    published = gh_json(f'repos/{REPOSITORY}/releases/tags/{TAG}')
    if published['draft'] or not published['prerelease'] or published['target_commitish'] != release_commit:
        raise ValueError('Published release metadata differs from the verified source')
    for name, expected in expected_assets.items():
        asset = next(item for item in published['assets'] if item['name'] == name)
        if (asset['size'], asset.get('digest')) != expected:
            raise ValueError('Published asset verification failed: ' + name)
    reference = gh_json(f'repos/{REPOSITORY}/git/ref/tags/{TAG}')
    if reference['object']['type'] != 'commit' or reference['object']['sha'] != release_commit:
        raise ValueError('Published tag does not match the verified application source')
    print(published['html_url'])


if __name__ == '__main__':
    main()
