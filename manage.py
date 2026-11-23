"""Message487 project commands. Run with uv run manage.py --help."""

import os
import pathlib
import platform
import re
import shutil
import subprocess as sp
import sys

import click

ROOT = pathlib.Path(__file__).resolve().parent


@click.group()
def cli():
    """Build, test and release Message487."""
    os.chdir(ROOT)


@cli.group()
def android():
    """Build APKs and test Android."""


@android.command()
def checks():
    """Run JVM tests and lint, then build debug and unsigned release APKs."""
    for key in (
        "KEYSTORE_PATH",
        "KEYSTORE_PASSWORD",
        "KEY_ALIAS",
        "KEY_PASSWORD",
        "KEY_PASSWORD_FILE",
    ):
        if os.environ.get("MESSAGE487_" + key):
            raise click.ClickException(
                "Signing configuration must not be available to PR checks"
            )
    sp.run(
        [
            "./gradlew",
            "--no-daemon",
            "testDebugUnitTest",
            "lintDebug",
            "lintRelease",
            "assembleDebug",
            "assembleRelease",
        ],
        check=True,
    )
    apk = pathlib.Path("app/build/outputs/apk/release/app-release-unsigned.apk")
    if not apk.is_file():
        raise click.ClickException("Expected unsigned release APK was not produced")
    sdk = os.getenv("ANDROID_HOME") or os.getenv("ANDROID_SDK_ROOT")
    local = pathlib.Path("local.properties")
    if not sdk and local.is_file():
        sdk = next(
            (
                line.removeprefix("sdk.dir=").strip()
                for line in local.read_text().splitlines()
                if line.startswith("sdk.dir=")
            ),
            None,
        )
    if not sdk:
        raise click.ClickException(
            "Android SDK location is required to verify the unsigned APK"
        )
    signer = pathlib.Path(sdk) / "build-tools/36.0.0/apksigner"
    if sp.run([signer, "verify", apk], capture_output=True).returncode == 0:
        raise click.ClickException("CI release APK is unexpectedly signed")
    click.echo("Verified unsigned release APK")


@android.command()
def debug():
    """Build the debug APK."""
    sp.run(["./gradlew", "assembleDebug"], check=True)


@android.command()
def install():
    """Install the debug APK on a connected device."""
    sp.run(["./gradlew", "installDebug"], check=True)


@android.command()
def emulator_abi():
    """Print the emulator image ABI for the host CPU."""
    machine = platform.machine().lower()
    if machine in ("aarch64", "arm64"):
        click.echo("arm64-v8a")
    elif machine in ("x86_64", "amd64"):
        click.echo("x86_64")
    else:
        raise click.ClickException(f"Unsupported host architecture: {machine}")


@android.command()
@click.option("--serial", envvar="ANDROID_SERIAL", required=True)
def device_tests(serial):
    """Run instrumentation on one explicitly selected disposable emulator."""
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise click.ClickException("Select one disposable emulator with ANDROID_SERIAL")
    sdk = os.getenv("ANDROID_HOME")
    if not sdk:
        raise click.ClickException("Set ANDROID_HOME to the Android SDK")
    adb = pathlib.Path(sdk) / "platform-tools/adb"
    devices = sp.check_output([adb, "devices"], text=True)
    if re.findall(r"^(\S+)\tdevice$", devices, re.MULTILINE) != [serial]:
        raise click.ClickException("Connect only the selected disposable emulator")
    avd = sp.check_output([adb, "-s", serial, "emu", "avd", "name"], text=True)
    if not re.fullmatch(
        r"message487-tests(?:-[0-9]+)?", avd.partition("\n")[0].strip()
    ):
        raise click.ClickException(
            "Use an AVD named message487-tests (or message487-tests-API)"
        )
    sp.run(
        ["./gradlew", "--no-daemon", "connectedDebugAndroidTest"],
        check=True,
        env={**os.environ, "ANDROID_SERIAL": serial},
    )


def fresh_reports(suite):
    directory = pathlib.Path("test-results") / suite
    if directory.exists():
        shutil.rmtree(directory)
    directory.mkdir(parents=True)
    return directory


@cli.command()
def python_checks():
    """Check style and generated examples, then write Python JUnit reports."""
    files = [
        "manage.py",
        "DevServer/tests",
        "scripts/ci_changes.py",
        "scripts/ci_history.py",
        "scripts/release_automation.py",
        "scripts/virus_total.py",
        "scripts/generate_n8n_examples.py",
        "scripts/tests",
    ]
    sp.run([sys.executable, "-m", "isort", "--check-only", *files], check=True)
    sp.run([sys.executable, "-m", "black", "--check", *files], check=True)
    sp.run([sys.executable, "scripts/generate_n8n_examples.py", "--check"], check=True)
    output = fresh_reports("python")
    for source, report in [
        ("DevServer/tests", "dev-server"),
        ("scripts/tests", "scripts"),
    ]:
        sp.run(
            [
                sys.executable,
                "-m",
                "xmlrunner",
                "discover",
                "-s",
                source,
                "-o",
                output / report,
            ],
            check=True,
        )


@cli.command()
def server_tests():
    """Exercise the running development n8n server and write JUnit reports."""
    output = fresh_reports("server")
    with (output / "telegram.xml").open("w") as report:
        sp.run(
            [
                "docker",
                "compose",
                "-f",
                "DevServer/compose.yaml",
                "exec",
                "-T",
                "n8n",
                "node",
                "--test",
                "--test-reporter=junit",
                "/bootstrap/tests/telegram-format.test.cjs",
                "/bootstrap/tests/telegram-images.test.cjs",
                "/bootstrap/tests/n8n-examples.test.cjs",
            ],
            stdout=report,
            check=True,
        )
    sp.run(
        [
            sys.executable,
            "-m",
            "xmlrunner",
            "discover",
            "-s",
            "DevServer/tests",
            "-p",
            "smoke.py",
            "-o",
            output,
        ],
        check=True,
    )


@cli.group()
def release():
    """Prepare, verify and publish releases."""


@release.command()
def artifacts():
    """Build, sign and verify release artifacts."""
    sp.run(["scripts/build-release-apk.sh"], check=True)


@release.command()
def virus_total():
    """Scan signed APKs before publication."""
    sp.run(
        [sys.executable, "scripts/virus_total.py", "--artifacts", "dist/release"],
        check=True,
    )


def release_automation(*args):
    sp.run([sys.executable, "scripts/release_automation.py", *args], check=True)


@release.command()
@click.option("--version", envvar="RELEASE_VERSION", required=True)
@click.option("--dry-run", is_flag=True)
def prepare(version, dry_run):
    """Prepare bilingual release notes and the release PR."""
    release_automation(
        "prepare", "--version", version, *(["--dry-run"] if dry_run else [])
    )


@release.command()
@click.option("--version", required=True)
@click.option("--ref", required=True)
@click.option("--previous")
def preview(version, ref, previous):
    """Preview bilingual notes for a revision."""
    release_automation(
        "preview",
        "--version",
        version,
        "--ref",
        ref,
        *(["--previous", previous] if previous else []),
    )


@release.command()
@click.option("--version", envvar="RELEASE_VERSION", required=True)
@click.option("--pr", envvar="RELEASE_PR", type=int, required=True)
@click.option("--head", envvar="RELEASE_HEAD", required=True)
def finish(version, pr, head):
    """Verify CI, merge the release PR and tag its commit."""
    release_automation("finish", "--version", version, "--pr", str(pr), "--head", head)


if __name__ == "__main__":
    try:
        cli()
    except sp.CalledProcessError as error:
        sys.exit(error.returncode)
    except OSError as error:
        sys.exit(str(error))
