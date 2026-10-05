# Автоматизация релиза

[English](../en/release-automation.md) | [Русский](release-automation.md)

После слияния реализации в `main` откройте **Actions → Prepare and merge release → Run workflow**,
выберите `main` и введите новую версию без `v`. Запуск создаёт релизный PR, после полного CI
делает squash merge и ставит тег на слитый коммит. **Release Android artifacts** собирает,
подписывает и публикует APK. Само слияние реализации не выпускает новую версию.

## Что настроить вручную один раз

1. В [Settings → Secrets and variables → Actions](https://github.com/andre487/AndroidMessage487/settings/secrets/actions)
   добавьте следующие настройки. Значения ключей не размещайте в PR, логах или документации.

   | Тип | Имя | Значение |
   | --- | --- | --- |
   | Secret | `OPENAI_API_KEY` | Ключ API-проекта OpenAI для генерации changelog. Запросы оплачиваются этим проектом. |
   | Variable или Secret | `OPENAI_RELEASE_MODEL` | Модель, доступная этому API-проекту и поддерживающая Responses API Structured Outputs, например `gpt-4o-mini`. Variable имеет приоритет над Secret; подмены модели нет. |
   | Secret | `RELEASE_BOT_TOKEN` | Fine-grained PAT с доступом только к `andre487/AndroidMessage487`: **Contents: Read and write**, **Pull requests: Read and write**, **Actions: Read-only**. Установите срок действия и обновляйте токен до истечения. |

   Отдельный токен нужен для автоматического запуска PR CI и сборки по созданному тегу:
   [ограничения GITHUB_TOKEN](https://docs.github.com/en/actions/concepts/security/github_token).
   Для нынешнего личного репозитория такой fine-grained PAT создайте от владельца `andre487`.
   У отдельного аккаунта бота fine-grained PAT не даёт запись в публичный репозиторий,
   принадлежащий другому пользователю. Если нужен отдельный бот, добавьте его как collaborator
   и используйте его classic PAT с `public_repo`; такой токен не ограничивается одним
   репозиторием. Для fine-grained PAT отдельного бота потребуется репозиторий организации,
   членом которой является бот. [Ограничения PAT](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens).

2. Сохраните уже настроенные signing-секреты: `ANDROID_SIGNING_KEY_BASE64`,
   `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
   PR CI и подготовка changelog их не получают. Сборка по тегу использует прежний ключ,
   чтобы установленное приложение могло обновиться.
3. В **Settings → General → Pull Requests** разрешите **Squash merging**.
   В **Settings → Rules / Branches** защитите `main`, сделайте обязательными проверки
   **Release CI identity**, **Android tests and checks**, **Python tests and style**,
   **Development server tests** и не давайте боту обходить правила.
   Если хотите проверить changelog до автоматического merge, сделайте человеческое ревью
   обязательным и одобрите релизный PR после чтения текстов. Бот не одобряет себя.
   Этот флоу использует прямой squash merge и не поддерживает обязательную merge queue.
   Если теги защищены, разрешите боту создавать `v*`.

## Что сделать для каждого релиза

1. Слейте нужные изменения и дождитесь зелёного CI. На устройстве проверьте пересылку
   SMS/уведомлений, сохранение очереди и настроек при обновлении APK, экран обновлений.
   Автоматический CI не заменяет эту проверку.
2. Откройте [Prepare and merge release](https://github.com/andre487/AndroidMessage487/actions/workflows/prepare-release.yml),
   выберите `main`, задайте версию выше текущей и последнего стабильного тега и нажмите
   **Run workflow**. Например, после `0.0.5` можно выпустить `0.0.6`.
   Этот запуск разрешает автоматический merge и публикацию после успешных проверок.
3. Откройте ссылку на созданный PR из Actions summary и прочитайте EN/RU changelog.
   Если требуется ревью, одобрите PR. Если merge уже отклонён правилами, после одобрения
   выберите **Re-run failed jobs** у запуска подготовки. Не запускайте подготовку заново
   для той же версии: существующая ветка намеренно не перезаписывается.
4. Дождитесь **Release Android artifacts** и проверьте новый GitHub Release:
   EN/RU тексты, `message487.apk`, APK с версией в имени, `mapping.txt` и `SHA256SUMS`.
   Проверьте установку опубликованного APK поверх прежней версии без потери данных.
5. Проверьте появление версии в F-Droid. При необходимости обновите внешний рецепт
   `fdroid/fdroiddata`: SHA релизного коммита, versionName/versionCode и источник APK.
   Эта автоматизация не меняет рецепт и не гарантирует срок публикации F-Droid.
   Детали подписи и воспроизводимости — в [инструкции релизов](releases.md).

## Что делает автоматизация

Из истории после максимального стабильного тега `vX.Y.Z`, достижимого из выбранного
коммита `main`, в OpenAI отправляются сообщения коммитов и статистика diff.
Исходный код и signing-секреты не отправляются. При истории больше 100 000 символов,
ошибке API, отказе модели или некорректном ответе процесс останавливается до записи файлов.
Используется [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs);
формат ответа не гарантирует правильность текста, поэтому changelog доступен в PR.

В `release/vX.Y.Z` меняются только `versionName`, увеличенный на единицу `versionCode`
и два новых файла `fastlane/metadata/android/{en-US,ru-RU}/changelogs/<versionCode>.txt`
по 1–500 символов. Исторические записи не переписываются. GitHub Release использует
эти же тексты. Номер версии задаёте вы; prerelease не поддерживается.

Finalize ждёт до 60 минут успешного CI именно этого PR, head SHA и базы сравнения.
Skipped, neutral, failure и cancelled не считаются успехом. Если `main` или PR изменились,
merge останавливается. После merge проверяется точное совпадение дерева с проверенным head;
тег создаётся на фактическом слитом коммите. Код из релизного PR не исполняется в finalize.
Повтор не двигает теги, не делает force-push и не переписывает опубликованный Release.

## Dry-run перед выпуском

В **Actions → CI → Run workflow** выберите ветку с реализацией и задайте
`release_dry_run_version`, например `0.0.6`. Проверка читает репозиторий и Actions
через `RELEASE_BOT_TOKEN`, проверяет доступность squash merge и генерирует настоящие
EN/RU тексты через OpenAI. Это оплачиваемый API-запрос. Результат будет в Actions summary.
Подготовка ветки, запись changelog, PR, merge, тег, подпись и публикация не выполняются.
Проверка не доказывает права записи токена или возможность пройти защиту `main`.
Пустое поле оставляет обычный CI. Локальный эквивалент из чистого checkout:
`bundle exec fastlane android release_prepare version:0.0.6 dry_run:true`.

## Восстановление после ошибки

При ошибке CI исправьте причину и повторите проверки, затем **Re-run failed jobs**
у подготовки, если head и база PR не изменились. Это не вызывает генератор второй раз.
Если merge состоялся, но тег не создан, повтор finalize проверит CI и поставит тег.
Если сборка по тегу упала, повторите её, сохранив тег.

Если head или `main` изменились, осознанно обновите релизную ветку так, чтобы единственный
релизный коммит был основан на актуальном `main`, и дождитесь полного CI. Затем из
доверенного checkout `main`, с авторизованным `gh` и Fastlane, можно завершить релиз:

```sh
export GITHUB_REPOSITORY=andre487/AndroidMessage487
bundle exec fastlane android release_finish version:0.0.6 pr:123 head:FULL_40_CHARACTER_SHA
```

Это реальная команда merge и создания тега, не dry run. Если ветка отправлена,
но PR не создался, сначала создайте PR вручную, проверьте его и используйте эту команду.

Локальная подготовка из чистого актуального checkout `main` с теми же настройками API/токена:
`bundle exec fastlane android release_prepare version:0.0.6`.
