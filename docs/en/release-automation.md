# Release automation

[English](release-automation.md) | [Русский](../ru/release-automation.md)

Once the implementation is merged into `main`, open **Actions → Prepare and merge release →
Run workflow**, choose `main` and enter a new version without `v`. This authorizes a release PR,
squash merge after full CI, and a tag on the verified merged commit. **Release Android artifacts**
then signs and publishes the APK. Merging the implementation does not itself release a version.

## One-time manual setup

In [Settings → Secrets and variables → Actions](https://github.com/andre487/AndroidMessage487/settings/secrets/actions), add:

| Type | Name | Value |
| --- | --- | --- |
| Secret | `OPENAI_API_KEY` | API-project key for changelog generation; requests are billed to that project. |
| Variable or Secret | `OPENAI_RELEASE_MODEL` | A model available to that project supporting Responses API Structured Outputs, for example `gpt-4o-mini`. Variables take precedence; no model substitution. |
| Secret | `RELEASE_BOT_TOKEN` | Expiring fine-grained PAT scoped only to this repository: Contents read/write, Pull requests read/write, Actions read. Renew before expiration. |

A separate token lets PR CI and the tag workflow run automatically; see
[GitHub token behavior](https://docs.github.com/en/actions/concepts/security/github_token).
For this personally owned repository, create the fine-grained PAT as its owner, `andre487`.
A separate bot cannot use a fine-grained PAT to write to another user's public repository.
For a separate bot, invite it as a collaborator and use its classic PAT with `public_repo`;
that token is not restricted to one repository. A bot fine-grained PAT requires an organization-owned
repository and organization membership. See [PAT limitations](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens).
Never put tokens in PRs or logs.

Keep the existing `ANDROID_SIGNING_KEY_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` secrets. Preparation and PR CI never receive them.
The tag build keeps the same signing key so existing installations can upgrade.

Enable squash merging in **Settings → General → Pull Requests**. Protect `main` with required
**Release CI identity**, **Android tests and checks**, **Python tests and style** and
**Development server tests**; do not grant the bot a bypass. Require human review if you want to
review notes before merge. The workflow cannot approve itself. After a rejected merge, approve
and rerun the failed finalize job. Required merge queues are unsupported. Tag rules must allow
the bot to create `v*` tags.

## Each release

1. Merge intended changes and wait for CI. On a device, check SMS/notification delivery, queue and
   settings preservation during upgrade, and update checks. CI does not replace device testing.
2. Run [Prepare and merge release](https://github.com/andre487/AndroidMessage487/actions/workflows/prepare-release.yml)
   from `main` with a version higher than the app and latest stable tag; for example `0.0.6` after
   `0.0.5`. Running it authorizes automatic merge and publication after checks pass.
3. Read the EN/RU notes in the generated PR linked from the Actions summary. Approve if required.
   If merge was rejected, approve and choose **Re-run failed jobs**. Do not start preparation again
   for the same version: existing branches are deliberately not overwritten.
4. Wait for **Release Android artifacts**. Check both language sections, `message487.apk`, the
   versioned APK, `mapping.txt` and `SHA256SUMS`. Install the published APK over the previous version
   and confirm data preservation.
5. Check F-Droid publication; update the external `fdroid/fdroiddata` recipe if needed with the
   release commit SHA, versionName/versionCode and APK source. This workflow does not update the
   recipe or control F-Droid publication timing. See [signed releases](releases.md) for reproducibility.

## Dry run before publication

In **Actions → CI → Run workflow**, select the implementation branch and enter
`release_dry_run_version`, for example `0.0.6`. The check reads the repository and
Actions through `RELEASE_BOT_TOKEN`, checks squash merge availability and generates
real EN/RU notes through OpenAI. This is a billed API request. Results appear in the
Actions summary. It does not write changelogs, create branches/PRs, merge, tag, sign
or publish. It does not prove write permissions or satisfaction of branch protection.
Leaving the field empty runs normal CI. Local equivalent from a clean checkout:
`bundle exec fastlane android release_prepare version:0.0.6 dry_run:true`.

## Behavior and recovery

Generation sends commit messages and diff statistics since the highest stable `vX.Y.Z` tag
reachable from the selected `main` commit to OpenAI, not source code or signing secrets.
History over 100,000 characters, API errors, refusals and invalid responses stop before file writes.
[Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs) validates
format, not factual accuracy; notes remain visible in the PR.

The `release/vX.Y.Z` commit changes only `versionName`, `versionCode` incremented by one, and two
new `fastlane/metadata/android/{en-US,ru-RU}/changelogs/<versionCode>.txt` files, each 1–500 characters.
Historical notes remain unchanged. GitHub Release uses the same texts. You choose the version;
prereleases are unsupported.

Finalize waits up to 60 minutes for successful full CI for that PR, head SHA and comparison base.
Skipped/neutral jobs are not success. Changed PRs or an advanced `main` stop merge. The merged
tree must equal the checked head tree before a tag is created on the actual merged commit.
Finalize runs trusted workflow code, never code from the release PR. Retries never force-push,
move tags or overwrite published Releases.

For failed CI, fix the cause and rerun checks, then rerun failed preparation jobs if head/base
are unchanged. Generation is not repeated. If merged but untagged, retry finalize. For a failed
tag build, retry that build without moving the tag.

If head/base changed, deliberately update the release branch so its single release commit is
based on current `main`, then wait for full CI. From a trusted `main` checkout with authorized
`gh` and Fastlane, finish with:

```sh
export GITHUB_REPOSITORY=andre487/AndroidMessage487
bundle exec fastlane android release_finish version:0.0.6 pr:123 head:FULL_40_CHARACTER_SHA
```

This merges and tags; it is not a dry run. If branch push succeeded but PR creation failed,
manually create and inspect the PR before using this command. Local preparation from a clean
current `main` checkout with the same API/token/model configuration:
`bundle exec fastlane android release_prepare version:0.0.6`.
