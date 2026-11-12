"""Describe an APK re-signing using public signatures and exact CI archive bytes.

This manifest cannot contain changed application or engine data. Only ZIP
metadata and META-INF signing records may differ from the validated CI APK.
"""
import argparse
import base64
import gzip
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import zipfile

from publish_native_release import compressed_entry, restore


def build(ci_apk, signed_apk, artifact_zip, artifact_id, metadata, runs, previous_tag_sha, output):
    data = signed_apk.read_bytes()
    literals = bytearray()
    segments = []
    def literal(start, end):
        if end > start:
            segments.append(dict(literal_offset=len(literals), length=end-start))
            literals.extend(data[start:end])
    with zipfile.ZipFile(ci_apk) as baseline, zipfile.ZipFile(signed_apk) as signed:
        signature = lambda name: name.upper().startswith('META-INF/') and name.upper().endswith(('.RSA', '.DSA', '.EC', '.SF', '/MANIFEST.MF'))
        original = {item.filename for item in baseline.infolist() if not signature(item.filename)}
        current = {item.filename for item in signed.infolist() if not signature(item.filename)}
        if original != current:
            raise ValueError('Signing changed the application entry set')
        cursor = 0
        for item in sorted(signed.infolist(), key=lambda item: item.header_offset):
            name_len, extra_len = struct.unpack_from('<HH', data, item.header_offset + 26)
            start = item.header_offset + 30 + name_len + extra_len
            end = start + item.compress_size
            literal(cursor, start)
            payload = data[start:end]
            if signature(item.filename):
                literal(start, end)
            else:
                reference = compressed_entry(ci_apk, item.filename)
                if reference != payload or signed.read(item.filename) != baseline.read(item.filename):
                    raise ValueError('Signing changed application bytes: ' + item.filename)
                segments.append(dict(base_entry=item.filename, length=len(payload), sha256=hashlib.sha256(payload).hexdigest()))
            cursor = end
        literal(cursor, len(data))
    payload = gzip.compress(bytes(literals), mtime=0)
    plan = dict(format=1, apk_name=signed_apk.name, source_commit=metadata['source_commit'],
                artifact_id=artifact_id, artifact_zip_sha256=hashlib.sha256(artifact_zip.read_bytes()).hexdigest(),
                baseline_sha256=hashlib.sha256(ci_apk.read_bytes()).hexdigest(),
                apk_size=len(data), apk_sha256=hashlib.sha256(data).hexdigest(),
                literal_size=len(literals), payload_size=len(payload), payload_sha256=hashlib.sha256(payload).hexdigest(),
                payload_gzip_base64=base64.b64encode(payload).decode(), segments=segments,
                build_metadata=metadata, validated_runs=runs, previous_tag_sha=previous_tag_sha)
    if len(payload) > 128*1024 or len(literals) > 512*1024:
        raise ValueError('Unexpected amount of non-CI data in signing manifest')
    with tempfile.TemporaryDirectory(prefix='native-signature-') as temporary:
        payload_path = Path(temporary) / 'signatures.gz'
        payload_path.write_bytes(payload)
        restored, _ = restore(plan, ci_apk, payload_path, Path(temporary) / 'restored')
        if restored.read_bytes() != data:
            raise ValueError('Signature reconstruction is not byte-exact')
    output.write_text(json.dumps(plan, indent=2) + '\n')
    print(f'Public signature manifest verified: {len(payload)} compressed bytes; {len(data)} APK bytes')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('ci-apk', 'signed-apk', 'artifact-zip', 'metadata', 'runs', 'output'):
        parser.add_argument('--' + option, required=True, type=Path)
    parser.add_argument('--artifact-id', required=True, type=int)
    parser.add_argument('--previous-tag-sha', required=True)
    args = parser.parse_args()
    build(args.ci_apk, args.signed_apk, args.artifact_zip, args.artifact_id,
          json.loads(args.metadata.read_text()), json.loads(args.runs.read_text()), args.previous_tag_sha, args.output)
