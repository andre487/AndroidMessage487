import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).parents[2]
LANE = (
    (ROOT / 'fastlane/Fastfile')
    .read_text()
    .split('  lane :device_tests do\n', 1)[1]
    .split('\n  end', 1)[0]
)


class DeviceLaneTest(unittest.TestCase):
    def test_only_one_explicit_disposable_emulator_can_run_tests(self):
        with tempfile.TemporaryDirectory() as directory:
            adb = Path(directory) / 'platform-tools/adb'
            adb.parent.mkdir()
            adb.write_text(
                '#!/bin/sh\n'
                'if [ "$1" = devices ]; then printf "%b" "$TEST_DEVICES"; '
                'else printf "%s\\n" "$TEST_AVD"; fi\n'
            )
            adb.chmod(0o755)
            for serial, devices, avd, allowed in [
                ('emulator-5554', 'emulator-5554\tdevice\n', 'message487-tests', True),
                (
                    'emulator-5554',
                    'emulator-5554\tdevice\n',
                    'message487-tests-26',
                    True,
                ),
                ('phone', 'phone\tdevice\n', 'message487-tests', False),
                ('', '', 'message487-tests', False),
                ('emulator-5554', '', 'message487-tests', False),
                (
                    'emulator-5554',
                    'emulator-5554\tdevice\nphone\tdevice\n',
                    'message487-tests',
                    False,
                ),
                (
                    'emulator-5554',
                    'emulator-5554\tdevice\n',
                    'Message487_API_35',
                    False,
                ),
            ]:
                with self.subTest(serial=serial, devices=devices, avd=avd):
                    result = subprocess.run(
                        [
                            'ruby',
                            '-e',
                            'module UI; def self.user_error!(s); abort(s); end; end\n'
                            'def gradle(**args); puts "RUN_TESTS"; end\n'
                            'project_root = "."\n' + LANE,
                        ],
                        env=dict(
                            os.environ,
                            ANDROID_HOME=directory,
                            ANDROID_SERIAL=serial,
                            TEST_DEVICES=devices,
                            TEST_AVD=avd,
                        ),
                        capture_output=True,
                        text=True,
                        check=False,
                    )
                    self.assertEqual(result.returncode == 0, allowed, result.stderr)
                    self.assertEqual('RUN_TESTS' in result.stdout, allowed)
