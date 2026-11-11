"""Package a checked Native preview, rejecting incompatible in-place updates.

Native libraries and signing keys stay outside Git. This reads the completed
APK and the published 0.1.3 baseline; it never changes their contents or signs.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile


BASELINE_SHA256 = '6b9ad458d620295a28bdabc0e7db87829dda7bec16fbb73862de09605fc16a5a'
LEGACY_PACKAGE = 'com.docscan.rebuild.csnative'
PREVIEW_PACKAGE = LEGACY_PACKAGE + '.preview'
LIBRARIES = ('opencv', 'ncnn', 'MNN', 'CVNN', 'magicenhancer', 'imageprocessor')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def certificate(tool, apk):
    output = subprocess.check_output([str(tool), 'verify', '--print-certs', str(apk)], text=True)
    signers = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})', output)
    if len(signers) != 1:
        raise ValueError('Expected one verified APK signer')
    return signers[0]


def package(apk, baseline, tools, output):
    if digest(baseline) != BASELINE_SHA256:
        raise ValueError('Baseline APK does not match the published research release')
    badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not match:
        raise ValueError('Cannot read APK identity')
    app_id, version_code, version_name = match.groups()
    if app_id not in (LEGACY_PACKAGE, PREVIEW_PACKAGE) or version_code != '7' or version_name != '0.1.7-native-preview':
        raise ValueError('Expected the Native 0.1.7 application, including its reference engine')
    signer = certificate(tools / 'apksigner', apk)
    previous_signer = certificate(tools / 'apksigner', baseline)
    standalone = app_id == PREVIEW_PACKAGE
    if not standalone and signer != previous_signer:
        raise ValueError('Signing key differs from Native 0.1.5: use the original key or build with -PstandaloneNativePreview=true. Never uninstall the existing app to install this APK.')
    libraries = {}
    with zipfile.ZipFile(apk) as current, zipfile.ZipFile(baseline) as previous:
        for library in LIBRARIES:
            name = f'lib/arm64-v8a/lib{library}.so'
            current_hash = hashlib.sha256(current.read(name)).hexdigest()
            if current_hash != hashlib.sha256(previous.read(name)).hexdigest():
                raise ValueError('Native engine changed: ' + name)
            libraries[name] = current_hash
    output.mkdir(parents=True, exist_ok=True)
    filename = 'DocScan-Native-0.1.7-' + ('standalone-preview' if standalone else 'preview') + '.apk'
    destination = output / filename
    if apk.resolve() != destination.resolve():
        shutil.copyfile(apk, destination)
    sha256 = digest(destination)
    source = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    metadata = dict(source_commit=source, application_id=app_id, version_code=int(version_code),
                    version_name=version_name, standalone=standalone, certificate_sha256=signer,
                    legacy_certificate_sha256=previous_signer, apk_sha256=sha256,
                    apk_size=destination.stat().st_size, libraries=libraries)
    (output / 'BUILD.json').write_text(json.dumps(metadata, indent=2) + '\n')
    (output / 'SHA256SUMS.txt').write_text(sha256 + '  ' + filename + '\n')
    instruction = ('התקנה נפרדת לצד Native 0.1.5. הסריקות הקיימות נשארות באפליקציה הישנה ואינן מועברות אוטומטית. אין להסיר אותה.\n'
                   if standalone else 'עדכון Native 0.1.5 באותה חתימה ובאותו מזהה חבילה. אין להסיר את ההתקנה הקיימת.\n')
    (output / 'INSTALLATION_HE.txt').write_text(instruction + 'גרסת Debug ניסיונית: יכולות Native 0.1.5 עם ממשק 0.1.6.\n', encoding='utf-8')
    print(json.dumps(metadata, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--baseline', required=True, type=Path)
    parser.add_argument('--build-tools', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    package(args.apk, args.baseline, args.build_tools, args.output)
