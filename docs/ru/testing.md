# Тесты и CI

[English](../en/testing.md) | [Русский](../ru/testing.md)

Локально и в GitHub Actions используются одинаковые команды:

| Набор | Команда | Что проверяется |
| --- | --- | --- |
| Android | `bundle exec fastlane android checks` | JVM-логика, HTTP через MockWebServer, БД/настройки/provider через Robolectric, Compose UI, локализация, манифест, контраст обеих тем, debug/release lint и сборки, отсутствие release-подписи |
| Эмулятор | `ANDROID_HOME=/path/to/sdk ANDROID_SERIAL=emulator-5554 bundle exec fastlane android device_tests` | Настоящий Keystore, шифрование настроек/SQLite-очереди, сохранность при повторе, HTTP/ACK/redirect и границы доступа FileProvider на API 26/35 |
| Python | `PYTHON=.venv/bin/python bundle exec fastlane android python_checks` | HTTP-клиент тестового сервера на локальном HTTP-стенде, релизная автоматизация с mock API и локальным Git, форматирование закреплёнными Black/isort |
| n8n | `bundle exec fastlane android server_tests` | Приём test/notification/SMS, валидация, HTTP-ошибка, неверный ACK и таймаут на живом сервере |

Для Python создайте `.venv` командой `python3 -m venv .venv` и установите
`requirements-dev.txt`. Для n8n сначала выполните
`docker compose -f DevServer/compose.yaml up -d --wait --wait-timeout 300`.
Серверные проверки используют синтетические данные и сохраняют их в истории execution.

`.github/workflows/ci.yml` запускает Android, Python, n8n и отдельные job эмуляторов API 26/35 на PR, push в main и вручную.
При ручном запуске с `release_dry_run_version` выполняется только dry-run релиза;
основные наборы в этом режиме пропускаются.
XML/HTML-отчёты Android-тестов и lint загружаются и при ошибках. Успешный Android-job
также публикует отдельные артефакты debug APK и неподписанного release APK на 14 дней.
Доверенный `pr-artifacts-description.yml` обновляет ссылки в отмеченном блоке описания PR,
сохраняет авторский текст и отвергает старые head/run. Он не исполняет код PR и не создаёт
комментарии; начинает работать после слияния в основную ветку. Подпись release в этих проверках
не используется. Локальный успех не подтверждает результат GitHub для неотправленных изменений.

## Сравнение с MegaProxy

Совпадают применимые категории: JVM-логика, Android-интеграция, Compose через Robolectric,
контракты ресурсов/безопасности/UI, Python и форматирование, lint и сборки. Message487
дополнительно проверяет живой Docker/n8n. Go race-тесты и необязательный fuzz-lane
MegaProxy относятся к его Go/JNI-коду; здесь такого кода нет. У Message487 есть отдельные Python-тесты релизной автоматизации;
классификатора изменений и поиска прошлых успешных проверок MegaProxy здесь нет.

Compose-тесты используют настоящую навигацию, экраны, ViewModel и настройки с тестовым
Application без запуска Worker и установки глобального обработчика крешей. Явная
фабрика ViewModel привязывает каждый тест к своему Application; перед проверками
дожидаются фоновых операций. Покрыты переходы и возврат, валидация и сохранение подключения,
массовый выбор приложений, очистка диагностики.

Эти тесты не подтверждают системную доставку SMS/уведомлений, работу разрешений,
планирование WorkManager, настоящий Android Keystore, смерть процесса или почтовый клиент.
Для этого нужны устройства/эмулятор; сценарий креша описан в [диагностике](diagnostics.md).
Отдельные эмуляторные проверки используют настоящий Keystore, хранилище, HTTP и provider.
Для CI эмуляторов нужен KVM; ошибка запуска или теста оставляет проверку красной.
Автоматического повтора упавших assertions нет.

Подписанная release-сборка отдельно запускает Android-тесты и lint с signing-переменными.
PR-проверки отвергают эти переменные и остаются неподписанными. См. [релизы](releases.md).

## Эмуляторы и JUnit

Используйте **одноразовый** Google APIs AVD с именем `message487-tests`,
`message487-tests-26` или `message487-tests-35`. Тесты очищают настройки и очередь
Message487 на выбранном эмуляторе. Подключите только его и задайте `ANDROID_SERIAL`
и `ANDROID_HOME`: lane отвергает физические устройства, другие имена AVD и дополнительные
подключённые устройства. CI создаёт чистый AVD для каждого API и собирает debug/test APK
без release signing secrets.

Для локального AVD используйте `arm64-v8a` на Apple Silicon или `x86_64` на Intel/Linux:

```sh
sdkmanager "system-images;android-35;google_apis;arm64-v8a"
avdmanager create avd --name message487-tests-35 --package "system-images;android-35;google_apis;arm64-v8a" --device pixel_5
emulator -avd message487-tests-35 -no-snapshot
# В другом терминале; serial возьмите из adb devices:
ANDROID_SERIAL=emulator-5554 bundle exec fastlane android device_tests
```

Повторите с API 26. Проверяется повторное открытие хранилищ; смерть процесса, системный
приём SMS/уведомлений, расписание WorkManager, ограничения OEM и обновление подписанного APK
остаются сценариями приёмки на устройствах. Debug-эмуляторы не сертифицируют релизный кандидат.

Android сохраняет JUnit XML средствами Gradle; Python и live n8n smoke используют
`unittest-xml-reporting`, Node — встроенный JUnit reporter. Для Python и server lane
установите `requirements-dev.txt`. Python XML: `test-results/python/`, n8n XML:
`test-results/server/`, device XML: `app/build/outputs/androidTest-results/connected/`.
CI загружает отчёты и после ошибки тестов; свидетельства эмуляторов включают HTML
и Logcat и хранятся 14 дней.

`test-reports.yml` публикует каждый набор в GitHub Checks и сворачиваемом Actions summary
сразу после завершения job. Внутренний CI и релиз вызывают его напрямую; для fork PR
используется доверенный `workflow_run` после слияния workflow в основную ветку. Reporter
только разбирает артефакты и не запускает код PR. Ошибка без XML остаётся ошибкой CI,
а не успешным тестовым отчётом. JVM-отчёты подписанной сборки публикуются отдельно.

Полный регламент проверки подписанного кандидата, F-Droid и устройств —
[Проверка релиза](release-testing.md).

Регрессионные тесты обновлений покрывают диалог при входе, недельную отсрочку/пропуск,
переход в F-Droid, открытие экрана GitHub без автоматического скачивания, сохранённый
результат фоновой проверки, повторы временных ошибок, отмену и смену источника.
HTTP 429/5xx допускают повтор; 404 и неверные ответы — нет. JVM/Compose-проверки
не подтверждают фактическое время запуска JobScheduler.
