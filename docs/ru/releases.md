# Выпуск подписанного APK

[English](../en/releases.md) | [Русский](../ru/releases.md)

Запустите `bundle exec fastlane android release_artifacts` с JDK 21 и Android SDK 36.
Lane выполняет JVM/Compose-тесты, debug/release lint и подписанную release-сборку,
затем проверяет сертификат, package ID, версию и отсутствие debug-флага. Результаты:
`dist/release/message487-<version>.apk`, файл R8 `mapping.txt` и `SHA256SUMS`.
При разборе крешей используйте mapping от конкретного APK.

Подпись следует контракту окружения MegaProxy с префиксом `MESSAGE487_`:

| Переменная | Источник / значение по умолчанию |
| --- | --- |
| `MESSAGE487_KEYSTORE_PATH` | Локально `~/AndroidApkKey`; в CI — восстановленный временный файл |
| `MESSAGE487_KEY_PASSWORD_FILE` | Локально `~/.my-tokens/android-key-password`; в CI — временный файл |
| `MESSAGE487_KEYSTORE_PASSWORD` | Экспортируется release-скриптом из файла пароля |
| `MESSAGE487_KEY_ALIAS` | Локально `key0`; в CI — секрет `ANDROID_KEY_ALIAS` |
| `MESSAGE487_KEY_PASSWORD` | Содержимое файла пароля, если не задан отдельно |
| `MESSAGE487_EXPECTED_CERT_SHA256` | Ожидаемый публичный отпечаток сертификата, закреплённый в скрипте |
| `MESSAGE487_RELEASE_DIR` | `dist/release` |

Gradle читает только четыре signing-переменные: путь, пароль хранилища, alias и пароль
ключа. Частичная конфигурация приводит к ошибке. PR-lane `checks` отвергает signing-параметры
и проверяет неподписанный release APK. Пароли не передаются аргументами командной строки;
их нельзя печатать, коммитить или передавать через Gradle `-P`.

Имена GitHub Secrets совпадают с MegaProxy: `ANDROID_SIGNING_KEY_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
Workflow восстанавливает ключ и пароль с приватными правами и удаляет временные файлы
даже при сбое. Job сборки имеет только чтение репозитория; GitHub Release может записывать
лишь отдельный job публикации. PR-workflow не использует signing-секреты.

## Проверка и публикация

- Тег `release-check/*` запускает сборку и проверку подписанного APK в GitHub Actions
  без публикации Release. APK, mapping, контрольные суммы и отчёты доступны как артефакты.
- После слияния workflow в основную ветку ручной запуск также только собирает артефакты.
- Для публикации увеличьте `versionCode`, задайте нужный `versionName` в `app/build.gradle.kts`
  и слейте проверенный PR после обязательных проверок. Отправьте тег `v<versionName>`.
  Workflow требует наличия коммита в `main` и совпадения версии с тегом. Проверенные APK,
  mapping и контрольные суммы публикуются в GitHub Releases. Повторный запуск не перезаписывает
  существующий Release.

Первая настроенная версия — `0.0.1`, `versionCode = 1`. Для последующих версий увеличивайте
`versionCode`, чтобы Android мог обновить приложение. Сохраняйте тот же ключ подписи.
