# Тесты и CI

[English](../en/testing.md) | [Русский](../ru/testing.md)

Локально и в GitHub Actions используются одинаковые команды:

| Набор | Команда | Что проверяется |
| --- | --- | --- |
| Android | `bundle exec fastlane android checks` | JVM-логика, HTTP через MockWebServer, БД/настройки/provider через Robolectric, Compose UI, локализация, манифест, контраст обеих тем, debug/release lint и сборки, отсутствие release-подписи |
| Python | `PYTHON=.venv/bin/python bundle exec fastlane android python_checks` | HTTP-клиент тестового сервера на локальном HTTP-стенде, форматирование закреплёнными Black/isort |
| n8n | `bundle exec fastlane android server_tests` | Приём test/notification/SMS, валидация, HTTP-ошибка, неверный ACK и таймаут на живом сервере |

Для Python создайте `.venv` командой `python3 -m venv .venv` и установите
`requirements-dev.txt`. Для n8n сначала выполните
`docker compose -f DevServer/compose.yaml up -d --wait --wait-timeout 300`.
Серверные проверки используют синтетические данные и сохраняют их в истории execution.

`.github/workflows/ci.yml` запускает все три job на PR, push в main и вручную.
XML/HTML-отчёты Android-тестов и lint загружаются и при ошибках. Успешный Android-job
также публикует debug APK и неподписанный release APK. Подпись release в этих проверках
не используется. Локальный успех не подтверждает результат GitHub для неотправленных изменений.

## Сравнение с MegaProxy

Совпадают применимые категории: JVM-логика, Android-интеграция, Compose через Robolectric,
контракты ресурсов/безопасности/UI, Python и форматирование, lint и сборки. Message487
дополнительно проверяет живой Docker/n8n. Go race-тесты и необязательный fuzz-lane
MegaProxy относятся к его Go/JNI-коду; здесь такого кода нет. Его Python-тесты истории
CI относятся к скриптам, которых в Message487 нет.

Compose-тесты используют настоящую навигацию, экраны, ViewModel и настройки с тестовым
Application без запуска Worker и установки глобального обработчика крешей. Явная
фабрика ViewModel привязывает каждый тест к своему Application; перед проверками
дожидаются фоновых операций. Покрыты переходы и возврат, валидация и сохранение подключения,
массовый выбор приложений, очистка диагностики.

Эти тесты не подтверждают системную доставку SMS/уведомлений, работу разрешений,
планирование WorkManager, настоящий Android Keystore, смерть процесса или почтовый клиент.
Для этого нужны устройства/эмулятор; сценарий креша описан в [диагностике](diagnostics.md).
Как и MegaProxy, обязательный hosted CI не зависит от нестабильной доступности KVM.

Подписанная release-сборка отдельно запускает Android-тесты и lint с signing-переменными.
PR-проверки отвергают эти переменные и остаются неподписанными. См. [релизы](releases.md).
