# Тесты и CI

[English](../en/testing.md) | [Русский](../ru/testing.md)

| Набор | Команда | Покрытие |
| --- | --- | --- |
| Android | `bundle exec fastlane android checks` | JVM/MockWebServer, Robolectric БД/provider, Compose, локализация/безопасность/контраст, debug/release lint, debug и неподписанный release APK |
| Эмулятор | `ANDROID_SERIAL=emulator-5554 bundle exec fastlane android device_tests` | Настоящий Keystore, зашифрованные настройки/очередь, HTTP/ACK/redirect/отмена, восстановление изображений и FileProvider на API 26/35 |
| Python | `PYTHON=.venv/bin/python bundle exec fastlane android python_checks` | Локальный HTTP-клиент, релизная автоматизация с mock API/локальным Git, брендинг, Black/isort |
| n8n | `PYTHON=.venv/bin/python bundle exec fastlane android server_tests` | Живая валидация text/image/test/SMS, маршрутизация/ключи прогресса, HTTP-ошибки, неверный ACK и таймаут |

Нужны JDK 21, Ruby/Bundler и Android SDK (`ANDROID_HOME`). Для Python/server lane:

```sh
bundle install
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-dev.txt
```

Перед server tests запустите [DevServer](../../DevServer/README.md):
`docker compose -f DevServer/compose.yaml up -d --wait --wait-timeout 300`.
Используйте синтетические данные: n8n сохраняет тела запросов в истории execution.

## CI и отчёты

[ci.yml](../../.github/workflows/ci.yml) запускает наборы на PR, push в main и вручную;
`release_dry_run_version` оставляет только dry-run релиза. PR-проверки отвергают signing-переменные.
[Подписанные сборки](releases.md) отдельно запускают Android-тесты/lint с параметрами подписи.

CI загружает XML/HTML тестов/lint и после ошибок; APK и HTML/Logcat эмуляторов хранятся 14 дней.
Python XML: `test-results/python/`, n8n XML: `test-results/server/`, device XML:
`app/build/outputs/androidTest-results/connected/`, Android JVM XML: `app/build/test-results/`.
Python использует `unittest-xml-reporting`, Node — встроенный reporter, Android — Gradle JUnit XML.

[test-reports.yml](../../.github/workflows/test-reports.yml) публикует завершённые наборы в GitHub
Checks и сворачиваемых summary. Внутренние job вызывают его напрямую, fork PR — через доверенный
`workflow_run`. Reporter разбирает артефакты без запуска кода PR; отсутствие XML не превращает
ошибку job в успех. JVM-результаты подписанных сборок публикуются отдельно.
[pr-artifacts-description.yml](../../.github/workflows/pr-artifacts-description.yml) из основной
ветки обновляет только отмеченный блок APK-ссылок после успешного CI, сохраняет остальной текст
и отвергает устаревшие head/run. Он не исполняет код PR и не пишет комментарии.
Локальный успех не подтверждает результат GitHub CI.

## Одноразовые эмуляторы

Используйте Google APIs AVD `message487-tests`, `message487-tests-26` или `message487-tests-35`.
**Тесты очищают настройки и очередь Message487.** Подключите только этот эмулятор: lane отвергает
физические устройства, другие имена AVD и лишние подключения. CI создаёт чистые AVD API 26/35
без signing-секретов, требует KVM и не повторяет упавшие assertions автоматически.

На Apple Silicon используйте `arm64-v8a`, на Intel/Linux — `x86_64`:

```sh
sdkmanager "system-images;android-35;google_apis;arm64-v8a"
avdmanager create avd --name message487-tests-35 --package "system-images;android-35;google_apis;arm64-v8a" --device pixel_5
emulator -avd message487-tests-35 -no-snapshot
# В другом терминале; serial возьмите из adb devices:
ANDROID_SERIAL=emulator-5554 bundle exec fastlane android device_tests
```

Повторите с API 26. Успех debug-эмулятора не сертифицирует подписанный релизный кандидат.

## Границы покрытия

Compose-тесты используют настоящие экраны/ViewModel/настройки с отдельными тестовыми Application,
без startup Worker и глобального crash handler, с ожиданием фоновых операций. Регрессии покрывают
маршрутизацию/отсрочку/повторы/отмену обновлений, повреждённые настройки, ограниченные очереди,
постраничное восстановление, лимиты метаданных, очистку APK и передачу текста при ошибке выделения
памяти для изображения. Эмуляторы проверяют настоящую отмену HTTP/таймауты и восстановление
изображений; искусственный OOM проверяет обработчик, а не исчерпание памяти.
StrictMode-сценарии и обязательные runtime-проверки — в [регламенте](release-testing.md#свидетельства-ci-для-кандидата).
Актуальные лимиты очередей, времени и памяти заданы в [захвате](../../app/src/main/java/life/andre/message487/BackgroundOperations.kt),
[очереди изображений](../../app/src/main/java/life/andre/message487/NotificationImageQueue.kt),
[outbox](../../app/src/main/java/life/andre/message487/Outbox.kt) и
[HTTP](../../app/src/main/java/life/andre/message487/WebhookClient.kt).

Robolectric и эти эмуляторные наборы не подтверждают настоящий приём/разрешения SMS и уведомлений,
время WorkManager/JobScheduler, смерть процесса, OEM, почтовые клиенты и обновления подписанных APK.
См. [приёмку релиза](release-testing.md) и [сценарий креша/отчёта](diagnostics.md).
Go/JNI-кода, native fuzz/race-набора, классификатора изменений и переиспользования прошлых проверок MegaProxy нет.
