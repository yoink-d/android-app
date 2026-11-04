#!/usr/bin/env python3
"""Prepare cached runtime packages and signed APKs for focused Android checks."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from urllib.parse import urlsplit
import zipfile


ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
LAUNCHERS = ("libpython.so", "libqjs.so", "libffmpeg.so", "libffprobe.so")
APPLICATION_ID = "com.maynkudu.yoinkd"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def signed_fixture(args, temporary, name, package, code, abi, key, password):
    tools = args.sdk / "build-tools/35.0.0"
    manifest = temporary / f"{name}.xml"
    manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
        package="{package}" android:versionCode="{code}" android:versionName="0.2.0-verification">
        <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="35" />
        <application android:label="yoink'd verification" android:hasCode="false" />
    </manifest>''')
    unsigned = temporary / f"{name}-unsigned.apk"
    subprocess.run([str(tools / "aapt2"), "link", "-I", str(args.sdk / "platforms/android-35/android.jar"),
        "--manifest", str(manifest), "-o", str(unsigned)], check=True)
    with zipfile.ZipFile(unsigned, "a") as archive:
        for launcher in LAUNCHERS:
            archive.write(Path("app/build/media-runtime/generated/jniLibs") / abi / launcher, f"lib/{abi}/{launcher}")
    aligned = temporary / f"{name}-aligned.apk"
    subprocess.run([str(tools / "zipalign"), "-f", "4", str(unsigned), str(aligned)], check=True)
    destination = args.output / f"{name}.apk"
    subprocess.run([str(tools / "apksigner"), "sign", "--ks", str(key), "--ks-key-alias", "androiddebugkey",
        "--ks-pass", f"pass:{password}", "--key-pass", f"pass:{password}", "--out", str(destination), str(aligned)], check=True)
    subprocess.run([str(tools / "apksigner"), "verify", str(destination)], check=True)


def prepare(args):
    args.output.mkdir(parents=True, exist_ok=True)
    versions = {element["versionCode"] for element in json.loads(Path("app/build/outputs/apk/debug/output-metadata.json").read_text())["elements"]}
    if len(versions) != 1:
        raise ValueError("The debug architecture APKs must have the same version code.")
    installed_code = versions.pop()
    manifest = json.loads(Path("app/build/media-runtime/releases/runtime-manifest.json").read_text())
    for name, artifact in manifest["architectures"][args.abi].items():
        source = Path("app/build/media-runtime/releases") / Path(urlsplit(artifact["url"]).path).name
        if source.stat().st_size != artifact["bytes"] or digest(source) != artifact["sha256"]:
            raise ValueError("The cached runtime package failed verification.")
        shutil.copyfile(source, args.output / f"{name}.zip")
    extractor = manifest["extractor"]
    if args.extractor.stat().st_size != extractor["bytes"] or digest(args.extractor) != extractor["sha256"]:
        raise ValueError("Supply the cached, verified extractor specified by media-runtime.json.")
    shutil.copyfile(args.extractor, args.output / "yt-dlp")
    with tempfile.TemporaryDirectory(prefix="yoinkd-apk-fixtures-") as directory:
        temporary = Path(directory)
        wrong_key = temporary / "test-only.jks"
        subprocess.run(["keytool", "-genkeypair", "-keystore", str(wrong_key), "-alias", "androiddebugkey",
            "-storepass", "test-fixtures", "-keypass", "test-fixtures", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "2", "-dname", "CN=yoinkd verification only", "-noprompt"], check=True, capture_output=True)
        other_abi = "arm64-v8a" if args.abi != "arm64-v8a" else "x86_64"
        for name, package, code, abi, key, password in (
            ("same-key", APPLICATION_ID, installed_code + 1, args.abi, args.debug_key, "android"),
            ("wrong-package", "com.maynkudu.yoinkd.verification", installed_code + 1, args.abi, args.debug_key, "android"),
            ("older", APPLICATION_ID, installed_code, args.abi, args.debug_key, "android"),
            ("wrong-architecture", APPLICATION_ID, installed_code + 1, other_abi, args.debug_key, "android"),
            ("wrong-key", APPLICATION_ID, installed_code + 1, args.abi, wrong_key, "test-fixtures"),
        ):
            signed_fixture(args, temporary, name, package, code, abi, key, password)
    if args.device:
        prefix = ["adb", "-s", args.device, "exec-in", "run-as", APPLICATION_ID]
        subprocess.run(prefix + ["mkdir", "-p", "files/device-fixtures"], check=True)
        for name in ("python.zip", "ffmpeg.zip", "yt-dlp", "same-key.apk", "wrong-package.apk", "older.apk",
            "wrong-architecture.apk", "wrong-key.apk"):
            with (args.output / name).open("rb") as source:
                subprocess.run(prefix + ["sh", "-c", f"cat > files/device-fixtures/{name}"], stdin=source, check=True)
        if args.seed_runtime:
            specification = manifest["architectures"][args.abi]
            identity = "\n".join((manifest["version"], args.abi, specification["python"]["sha256"],
                specification["ffmpeg"]["sha256"], extractor["sha256"]))
            generation = f"{manifest['version']}-{args.abi}-{hashlib.sha256(identity.encode()).hexdigest()[:12]}"
            incoming = f"no_backup/media-tools/downloads/{generation}"
            subprocess.run(prefix + ["mkdir", "-p", incoming], check=True)
            for source, destination in (("python.zip", "python"), ("ffmpeg.zip", "ffmpeg"), ("yt-dlp", "yt-dlp")):
                subprocess.run(prefix + ["cp", f"files/device-fixtures/{source}", f"{incoming}/{destination}"], check=True)
    print(f"Prepared {args.abi} fixtures in {args.output}. No runtime packages were downloaded.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=ABIS, default="x86_64")
    parser.add_argument("--extractor", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("app/build/device-fixtures"))
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", "/opt/android-sdk")))
    parser.add_argument("--debug-key", type=Path, default=Path.home() / ".android/debug.keystore")
    parser.add_argument("--device", help="Copy fixtures into this installed debug app using run-as.")
    parser.add_argument("--seed-runtime", action="store_true", help="Also seed setup's download cache for native conversion checks.")
    prepare(parser.parse_args())


if __name__ == "__main__":
    main()
