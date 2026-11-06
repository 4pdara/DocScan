"""Reconstruct the exact verified APK from this rebuild's CI artifact and private transfer data.

The app, reference APKs, raw libraries and signing key are never added to the source tree.
Only the completed DocScan APK and its checksum are attached to the prerelease.
"""
import argparse
import base64
import concurrent.futures
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile


def gh_json(endpoint):
    result = subprocess.run(['gh', 'api', endpoint], check=True, capture_output=True, text=True)
    return json.loads(result.stdout)


def compressed_entry(apk, name):
    with zipfile.ZipFile(apk) as archive:
        item = archive.getinfo(name)
    with apk.open('rb') as stream:
        stream.seek(item.header_offset)
        header = stream.read(30)
        if header[:4] != b'PK\x03\x04':
            raise ValueError('Invalid baseline local header')
        name_length, extra_length = struct.unpack_from('<HH', header, 26)
        stream.seek(name_length + extra_length, 1)
        data = stream.read(item.compress_size)
    if len(data) != item.compress_size:
        raise ValueError('Incomplete baseline entry')
    return data


def fallback_literals(plan, data):
    spec = plan['fallback']
    if len(data) != spec['size'] or hashlib.sha256(data).hexdigest() != spec['sha256']:
        raise ValueError('Fallback transfer digest mismatch')
    import io
    with gzip.GzipFile(fileobj=io.BytesIO(data), mode='rb') as stream:
        result = stream.read(spec['literal_size'] + 1)
    if len(result) != spec['literal_size']:
        raise ValueError('Fallback size mismatch')
    return result


def restore(plan, baseline, payload, destination, fallback=None):
    if payload.stat().st_size != plan['payload_size']:
        raise ValueError('Transfer size mismatch')
    if hashlib.sha256(payload.read_bytes()).hexdigest() != plan['payload_sha256']:
        raise ValueError('Transfer digest mismatch')
    with gzip.open(payload, 'rb') as stream:
        literals = stream.read(plan['literal_size'] + 1)
    if len(literals) != plan['literal_size']:
        raise ValueError('Literal size mismatch')
    destination.mkdir(parents=True, exist_ok=True)
    apk = destination / plan['apk_name']
    digest = hashlib.sha256()
    total = 0
    with apk.open('wb') as output:
        for segment in plan['segments']:
            if 'base_entry' in segment:
                data = compressed_entry(baseline, segment['base_entry'])
                if hashlib.sha256(data).hexdigest() != segment['sha256']:
                    if fallback is None or 'fallback_offset' not in segment:
                        raise ValueError('Baseline differs for ' + segment['base_entry'])
                    start = segment['fallback_offset']
                    data = fallback[start:start + segment['length']]
                    if hashlib.sha256(data).hexdigest() != segment['sha256']:
                        raise ValueError('Fallback differs for ' + segment['base_entry'])
            else:
                start = segment['literal_offset']
                if start < 0:
                    raise ValueError('Invalid literal offset')
                data = literals[start:start + segment['length']]
            if len(data) != segment['length']:
                raise ValueError('Segment size mismatch')
            total += len(data)
            if total > plan['apk_size']:
                raise ValueError('APK exceeds verified size')
            output.write(data)
            digest.update(data)
    if total != plan['apk_size'] or digest.hexdigest() != plan['apk_sha256']:
        raise ValueError('APK does not match the signed, locally verified artifact')
    checksum = destination / 'SHA256SUMS.txt'
    checksum.write_text(plan['apk_sha256'] + '  ' + plan['apk_name'] + '\n')
    print('Verified exact APK:', apk.name, 'bytes:', total, 'SHA256:', digest.hexdigest(), flush=True)
    return apk, checksum


def download_payload(plan, repository, destination):
    rows = plan['chunks']
    if [row['index'] for row in rows] != list(range(len(rows))) or not rows:
        raise ValueError('Incomplete transfer manifest')

    def fetch(row):
        if not re.fullmatch('[0-9a-f]{40}', row['sha']):
            raise ValueError('Invalid transfer object')
        result = gh_json(f"repos/{repository}/git/blobs/{row['sha']}")
        if result['encoding'] != 'base64':
            raise ValueError('Unexpected transfer encoding')
        data = base64.b64decode(''.join(result['content'].split()), validate=True)
        git_digest = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        if len(data) != row['size'] or git_digest != row['sha']:
            raise ValueError('Transfer object failed verification')
        return data

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        with destination.open('wb') as output:
            for data in executor.map(fetch, rows):
                output.write(data)


def publish(plan, apk, checksum, repository):
    tag = 'native-v0.1.3-research'
    endpoint = f'repos/{repository}/releases/tags/{tag}'
    previous = subprocess.run(['gh', 'api', endpoint], capture_output=True, text=True)
    if previous.returncode == 0:
        existing = json.loads(previous.stdout)
        if not existing['draft']:
            asset = next((x for x in existing['assets'] if x['name'] == apk.name), None)
            if not asset or asset['size'] != plan['apk_size'] or asset.get('digest') != 'sha256:' + plan['apk_sha256']:
                raise ValueError('A different published release already exists; refusing overwrite')
            print(existing['html_url'])
            return
        if existing['target_commitish'] != plan['source_commit']:
            raise ValueError('Unexpected draft release target')
        subprocess.run(['gh', 'release', 'upload', tag, str(apk), str(checksum), '--repo', repository, '--clobber'], check=True)
        subprocess.run(['gh', 'release', 'edit', tag, '--repo', repository, '--draft=false', '--prerelease', '--latest=false', '--notes-file', 'docs/NATIVE_0.1.3_RELEASE_HE.md'], check=True)
    elif '404' in previous.stderr:
        subprocess.run(['gh', 'release', 'create', tag, str(apk), str(checksum), '--repo', repository,
                        '--target', plan['source_commit'], '--prerelease', '--latest=false',
                        '--title', 'DocScan Native 0.1.3 — מסננים מקוריים ושמירה לגלריה',
                        '--notes-file', 'docs/NATIVE_0.1.3_RELEASE_HE.md'], check=True)
    else:
        raise RuntimeError('Cannot inspect the target release')
    published = gh_json(endpoint)
    asset = next(x for x in published['assets'] if x['name'] == apk.name)
    if published['draft'] or not published['prerelease'] or asset['size'] != plan['apk_size']:
        raise ValueError('Release publication verification failed')
    if asset.get('digest') != 'sha256:' + plan['apk_sha256']:
        raise ValueError('Release asset digest mismatch')
    print(published['html_url'])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--manifest', type=Path, default=Path('.github/native-release-0.1.3.json'))
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--local-payload', type=Path)
    parser.add_argument('--local-fallback', type=Path)
    parser.add_argument('--output', type=Path, default=Path('release-output'))
    args = parser.parse_args()
    plan = json.loads(args.manifest.read_text())
    if plan['apk_name'] != 'DocScan-Native-0.1.3-debug.apk' or not re.fullmatch('[0-9a-f]{64}', plan['apk_sha256']):
        raise ValueError('Unexpected release artifact')
    args.output.mkdir(parents=True, exist_ok=True)
    if args.local_payload:
        fallback = fallback_literals(plan, args.local_fallback.read_bytes()) if args.local_fallback else None
        restore(plan, args.baseline, args.local_payload, args.output, fallback)
        return
    repository = os.environ['GITHUB_REPOSITORY']
    if repository != '4pdara/DocScan':
        raise ValueError('Unexpected target repository')
    run = gh_json(f"repos/{repository}/actions/runs/{plan['validated_run_id']}")
    if run['head_sha'] != plan['source_commit'] or run['status'] != 'completed' or run['conclusion'] != 'success':
        raise ValueError('The exact app source has not passed its validation run')
    payload = args.output / 'native-transfer.gz'
    download_payload(plan, repository, payload)
    result = gh_json(f"repos/{repository}/git/blobs/{plan['fallback']['blob']}")
    fallback = fallback_literals(plan, base64.b64decode(''.join(result['content'].split()), validate=True))
    apk, checksum = restore(plan, args.baseline, payload, args.output, fallback)
    publish(plan, apk, checksum, repository)


if __name__ == '__main__':
    main()
