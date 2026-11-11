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
    if plan['format'] != 1 or plan['apk_name'] != 'DocScan-Native-0.1.7-standalone-preview.apk':
        raise ValueError('Unexpected delivery manifest')
    if not re.fullmatch('[0-9a-f]{40}', plan['source_commit']):
        raise ValueError('Invalid application source')
    subprocess.run(['git', 'diff', '--exit-code', plan['source_commit'], '--', 'app', 'gradle',
                    'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties'], check=True)
    if args.publish:
        for expected in plan['validated_runs']:
            run = gh_json(f"repos/{REPOSITORY}/actions/runs/{expected['id']}")
            if run['head_sha'] != expected['head_sha'] or run['status'] != 'completed' or run['conclusion'] != 'success':
                raise ValueError('Required validation has not passed: ' + str(expected['id']))
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
    if metadata['application_id'] != 'com.docscan.rebuild.csnative.preview' or not metadata['standalone']:
        raise ValueError('This delivery must use a separate application identity')
    metadata['ci_artifact_id'] = plan['artifact_id']
    metadata['ci_archive_sha256'] = plan['artifact_zip_sha256']
    metadata['validation_runs'] = plan['validated_runs']
    build = args.output / 'BUILD.json'
    build.write_text(json.dumps(metadata, indent=2) + '\n')
    install = args.output / 'INSTALLATION_HE.txt'
    install.write_text('DocScan Native 0.1.7: יכולות 0.1.5 בממשק 0.1.6.\n'
                       'התקנה נפרדת לצד Native 0.1.5. הסריקות הקיימות נשארות באפליקציה הקודמת ואינן מועברות אוטומטית. אין להסיר אותה.\n'
                       'גרסת Debug ניסיונית, Android 8 ומעלה, ARM64.\n', encoding='utf-8')
    if not args.publish:
        return
    release = json.loads(subprocess.check_output(['gh', 'release', 'view', TAG, '--repo', REPOSITORY,
                                                 '--json', 'isDraft,targetCommitish,assets'], text=True))
    if release['targetCommitish'] != plan['source_commit']:
        raise ValueError('Release targets different application source')
    if release['isDraft']:
        subprocess.run(['gh', 'release', 'upload', TAG, str(apk), str(checksum), str(build), str(install),
                        '--repo', REPOSITORY, '--clobber'], check=True)
        subprocess.run(['gh', 'release', 'edit', TAG, '--repo', REPOSITORY, '--draft=false', '--prerelease',
                        '--latest=false', '--notes-file', 'docs/PREVIEW_0.1.7_HE.md'], check=True)
    published = gh_json(f'repos/{REPOSITORY}/releases/tags/{TAG}')
    asset = next(x for x in published['assets'] if x['name'] == plan['apk_name'])
    if published['draft'] or not published['prerelease'] or asset['size'] != plan['apk_size'] or asset.get('digest') != 'sha256:' + plan['apk_sha256']:
        raise ValueError('Published release verification failed')
    reference = gh_json(f'repos/{REPOSITORY}/git/ref/tags/{TAG}')
    if reference['object']['type'] != 'commit' or reference['object']['sha'] != plan['source_commit']:
        raise ValueError('Published tag does not match the verified application source')
    print(published['html_url'])


if __name__ == '__main__':
    main()
