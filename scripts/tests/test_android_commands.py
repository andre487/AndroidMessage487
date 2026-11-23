import unittest
import unittest.mock

import click.testing

import manage


class AndroidCommandTest(unittest.TestCase):
    def test_pr_checks_reject_signing_before_gradle(self):
        with unittest.mock.patch.object(manage.sp, 'run') as gradle:
            result = click.testing.CliRunner().invoke(
                manage.cli,
                ['android', 'checks'],
                env={'MESSAGE487_KEY_ALIAS': 'fixture'},
            )
            self.assertNotEqual(result.exit_code, 0)
            self.assertIn('Signing configuration', result.output)
            gradle.assert_not_called()

    def test_disposable_emulator_guard(self):
        for serial, devices, avd, allowed in [
            ('emulator-5554', 'emulator-5554\tdevice', 'message487-tests', True),
            ('emulator-5554', 'emulator-5554\tdevice', 'message487-tests-26', True),
            ('phone', 'phone\tdevice', 'message487-tests', False),
            ('', '', 'message487-tests', False),
            ('emulator-5554', '', 'message487-tests', False),
            (
                'emulator-5554',
                'emulator-5554\tdevice\nphone\tdevice',
                'message487-tests',
                False,
            ),
            ('emulator-5554', 'emulator-5554\tdevice', 'Message487_API_35', False),
            ('emulator-5554', 'emulator-5554\tdevice', '', False),
        ]:
            with (
                self.subTest(serial=serial, devices=devices, avd=avd),
                unittest.mock.patch.object(
                    manage.sp, 'check_output', side_effect=[devices, avd]
                ),
                unittest.mock.patch.object(manage.sp, 'run') as gradle,
            ):
                env = {'ANDROID_HOME': '/sdk', 'ANDROID_SERIAL': serial}
                result = click.testing.CliRunner().invoke(
                    manage.cli, ['android', 'device-tests'], env=env
                )
                self.assertEqual(result.exit_code == 0, allowed, result.output)
                self.assertEqual(gradle.called, allowed)
                if allowed:
                    self.assertIn('connectedDebugAndroidTest', gradle.call_args.args[0])
                    self.assertEqual(
                        gradle.call_args.kwargs['env']['ANDROID_SERIAL'], serial
                    )

    def test_emulator_image_matches_host(self):
        for machine, abi in [
            ('arm64', 'arm64-v8a'),
            ('aarch64', 'arm64-v8a'),
            ('x86_64', 'x86_64'),
            ('AMD64', 'x86_64'),
            ('riscv64', None),
        ]:
            with (
                self.subTest(machine=machine),
                unittest.mock.patch.object(
                    manage.platform, 'machine', return_value=machine
                ),
            ):
                result = click.testing.CliRunner().invoke(
                    manage.cli, ['android', 'emulator-abi']
                )
                self.assertEqual(result.exit_code == 0, abi is not None)
                if abi:
                    self.assertEqual(result.output.strip(), abi)
