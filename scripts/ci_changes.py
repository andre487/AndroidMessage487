"""Select suites from a PR diff or from each suite's last successful check."""

import os
import pathlib
import subprocess
import sys

import ci_history
import release_automation

SUITES = tuple(release_automation.CHECKS)
ANDROID = {"android", "emulator26", "emulator35"}


def classify(path):
    if path.startswith(("app/src/main/assets/", "app/src/main/res/")):
        return ANDROID
    if path.lower().endswith(".md") or path.startswith(("docs/", ".vscode/")):
        return set()
    if (
        path.startswith(("DevServer/", "examples/n8n/"))
        or path == "scripts/generate_n8n_examples.py"
    ):
        return {"python", "dev-server"}
    if path.startswith("assets/") or path in (
        "scripts/branding.py",
        "scripts/generate-branding.py",
        "scripts/tests/test_generate_branding.py",
    ):
        return {"python", "android"}
    if (
        path.startswith(("app/", "gradle/"))
        or path.endswith(".gradle.kts")
        or path
        in (
            "gradle.properties",
            "gradlew",
            "gradlew.bat",
        )
    ):
        return ANDROID
    if (
        path.startswith("scripts/")
        and path.endswith(".py")
        and path
        not in (
            "scripts/ci_changes.py",
            "scripts/ci_history.py",
            "scripts/release_automation.py",
        )
    ):
        return {"python"}
    return set(SUITES)


def changed_suites(revision):
    paths = subprocess.check_output(
        ["git", "diff", "--name-only", "--no-renames", "-z", revision, "--"]
    )
    return set().union(
        *(classify(os.fsdecode(path)) for path in paths.split(b"\0") if path)
    )


def select(env):
    if (
        env["EVENT_NAME"] != "pull_request"
        or int(env["GITHUB_RUN_ATTEMPT"]) > 1
        or env["PR_BRANCH"].startswith("release/v")
    ):
        return set(SUITES)

    base, head = env["BASE_SHA"], env["HEAD_SHA"]
    baselines = {}
    if env["PR_REPOSITORY"] == env["GITHUB_REPOSITORY"]:
        try:
            baselines = ci_history.successful_baselines(
                env["GITHUB_REPOSITORY"],
                env["PR_BRANCH"],
                int(env["PR_NUMBER"]),
                base,
                head,
                int(env["GITHUB_RUN_ID"]),
            )
        except (
            OSError,
            RuntimeError,
            ValueError,
            KeyError,
            TypeError,
            subprocess.TimeoutExpired,
        ):
            print("CI history unavailable. Using the full PR diff.", file=sys.stderr)

    comparisons = {}
    selected = set()
    for suite in SUITES:
        revision = (
            baselines[suite]["sha"] + ".." + head
            if suite in baselines
            else base + "..." + head
        )
        if revision not in comparisons:
            comparisons[revision] = changed_suites(revision)
        if suite in comparisons[revision]:
            selected.add(suite)
    return selected


if __name__ == "__main__":
    selected = select(os.environ)
    with pathlib.Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
        for suite in SUITES:
            print(f"{suite}={str(suite in selected).lower()}", file=output)
    with pathlib.Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as summary:
        print(
            "Selected test suites: " + (", ".join(sorted(selected)) or "none"),
            file=summary,
        )
