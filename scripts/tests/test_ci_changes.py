import contextlib
import pathlib
import subprocess
import sys
import tempfile
import unittest
import unittest.mock

sys.path.insert(0, str(pathlib.Path(__file__).parents[1]))
import ci_changes


class ChangeScopeTest(unittest.TestCase):
    def test_suite_selection(self):
        cases = {
            'PRIVACY.md': set(),
            'DevServer/README.md': set(),
            'app/src/main/assets/help.md': ci_changes.ANDROID,
            'app/build.gradle.kts': ci_changes.ANDROID,
            'scripts/virus_total.py': {'python'},
            'assets/master.svg': {'android', 'python'},
            'DevServer/tests/smoke.py': {'dev-server', 'python'},
            'examples/n8n/workflows/example.json': {'dev-server', 'python'},
            'manage.py': set(ci_changes.SUITES),
            '.github/workflows/ci.yml': set(ci_changes.SUITES),
            'unknown': set(ci_changes.SUITES),
        }
        for path, expected in cases.items():
            with self.subTest(path=path):
                self.assertEqual(ci_changes.classify(path), expected)

    def test_forced_runs_need_no_comparison(self):
        for event, attempt, branch in [
            ('push', '1', ''),
            ('workflow_dispatch', '1', ''),
            ('pull_request', '2', 'docs'),
            ('pull_request', '1', 'release/v1.2.3'),
        ]:
            env = dict(EVENT_NAME=event, GITHUB_RUN_ATTEMPT=attempt, PR_BRANCH=branch)
            self.assertEqual(ci_changes.select(env), set(ci_changes.SUITES))

    def test_reuse_only_successful_suite_on_same_base(self):
        env = dict(
            EVENT_NAME='pull_request',
            GITHUB_RUN_ATTEMPT='1',
            PR_BRANCH='feature',
            BASE_SHA='base',
            HEAD_SHA='head',
            PR_NUMBER='7',
            GITHUB_RUN_ID='10',
            PR_REPOSITORY='owner/repo',
            GITHUB_REPOSITORY='owner/repo',
        )
        diffs = {'base...head': ci_changes.ANDROID, 'previous..head': set()}
        with (
            unittest.mock.patch.object(
                ci_changes.ci_history,
                'successful_baselines',
                return_value={'android': {'sha': 'previous'}},
            ) as history,
            unittest.mock.patch.object(
                ci_changes, 'changed_suites', side_effect=diffs.__getitem__
            ),
        ):
            self.assertEqual(ci_changes.select(env), {'emulator26', 'emulator35'})
            history.side_effect = RuntimeError()
            self.assertEqual(ci_changes.select(env), ci_changes.ANDROID)
            history.reset_mock()
            self.assertEqual(
                ci_changes.select(dict(env, PR_REPOSITORY='fork/repo')),
                ci_changes.ANDROID,
            )
            history.assert_not_called()

    def test_move_to_markdown_still_tests_deleted_code(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.chdir(tmp):

            def git(*args):
                subprocess.run(['git', *args], check=True, capture_output=True)

            git('init')
            code = pathlib.Path(tmp, 'app', 'main.kt')
            code.parent.mkdir()
            code.write_text('code')
            git('add', '.')
            git(
                '-c',
                'user.name=Test',
                '-c',
                'user.email=test@example.invalid',
                '-c',
                'commit.gpgsign=false',
                'commit',
                '-m',
                'base',
            )
            git('mv', 'app/main.kt', 'README.md')
            self.assertEqual(ci_changes.changed_suites('HEAD'), ci_changes.ANDROID)
