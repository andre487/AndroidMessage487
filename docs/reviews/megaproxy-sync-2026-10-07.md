# Перенос актуальных изменений AndroidMegaProxy — 2026-10-07

Сравнен `andre487/AndroidMegaProxy` до коммита
[`d94f861`](https://github.com/andre487/AndroidMegaProxy/commit/d94f861), включая последние
40 коммитов, с текущим Message487. Изменения адаптированы под Kotlin/Android и n8n;
Go/JNI/VPN-стенд не нужен этому приложению.

| Изменение источника | Решение для Message487 |
| --- | --- |
| [Эмуляторы API 26/35, #64](https://github.com/andre487/AndroidMegaProxy/pull/64) | Два обязательных CI job через общий workflow. Штатный Gradle instrumentation runner; настоящий Keystore, зашифрованные настройки/SQLite, повторы доставки, HTTP/ACK/redirect и FileProvider. Чистые AVD, KVM, JUnit/HTML/Logcat. Локальный lane отвергает физические устройства и обычные AVD. |
| [JUnit Checks, #65](https://github.com/andre487/AndroidMegaProxy/pull/65) | Отдельные отчёты Android JVM, API 26, API 35, Python, n8n и JVM подписанной сборки; Checks и сворачиваемые summaries. Fork PR используют доверенный workflow без исполнения кода PR. Python использует xmlrunner, Node — встроенный reporter с именованным suite. |
| [Артефакты в описании PR, #62](https://github.com/andre487/AndroidMegaProxy/pull/62) | Отдельные debug/unsigned APK. Обновляется только отмеченный блок описания; авторский текст сохраняется, устаревшие head/run и повреждённые маркеры отвергаются. Комментарии не создаются. |
| [Samsung Auto Blocker, #60](https://github.com/andre487/AndroidMegaProxy/pull/60) | EN/RU-инструкция уже была, включая повторное отключение для обновлений внутри приложения. Дополнены USB/ADB, восстановление защиты после неуспешной попытки и ограничения администратора. [Инструкция Samsung](https://www.samsung.com/us/support/answer/ANS10003636/). |
| [Регламент проверки релизов, #47](https://github.com/andre487/AndroidMegaProxy/pull/47), [обновление документации, #51](https://github.com/andre487/AndroidMegaProxy/pull/51) | В нашем регламенте уже есть матрица устройств, PASS/FAIL/BLOCKED/NOT TESTED/N/A, идентичность APK, негативные контроли, восстановление и отдельное решение для GitHub/F-Droid. Добавлены ссылки на свидетельства CI и различие debug/unsigned артефакта и подписанного кандидата. Release gate теперь требует успех обоих эмуляторов на точных head/base, включая version-only PR. |
| [Bundler-артефакты, #53](https://github.com/andre487/AndroidMegaProxy/pull/53) | `.bundle/` и `vendor/bundle/` уже исключены. |
| [Changelog для всех ABI, #58](https://github.com/andre487/AndroidMegaProxy/pull/58), [повторное использование AAR, #55](https://github.com/andre487/AndroidMegaProxy/pull/55) | Не применимо: один APK/versionCode, без native AAR и ABI-вариантов. |
| [Portable config schemas, #66](https://github.com/andre487/AndroidMegaProxy/pull/66) | Не применимо: нет импорта MegaProxyConfig. |
| [Диалог обновления при входе, #61](https://github.com/andre487/AndroidMegaProxy/pull/61) | Отдельное изменение пользовательского интерфейса; в этот перенос CI и документации не включено. |

## Проверки

- 66 Android JVM-тестов, debug/release lint, debug/test APK и unsigned release APK: PASS.
- 4 instrumentation-теста на каждом отдельном AVD API 26 и API 35: PASS.
  Первый прогон API 26 обнаружил ошибку нового теста: `SQLiteOpenHelper` реализует
  `AutoCloseable` только с API 29. Тест исправлен на `try/finally`; оба API перепроверены.
  Для финального API 35 потребовался перезапуск AVD на другом порту после сбоя регистрации ADB;
  assertions не менялись после успешного API 26.
- 17 Python-тестов, включая release gate, обновление описания PR и защиту device lane:
  PASS; Black/isort: PASS.
- 5 Node-тестов форматирования Telegram с корректным JUnit suite: PASS.
- Actionlint 1.7.11, Ruby syntax и `git diff --check`: PASS.

Live n8n smoke не выполнен: локальный Docker daemon недоступен. Hosted GitHub Actions,
публикация Checks и реальная запись описания PR ещё не выполнялись. Подписанный APK,
реальные SMS/notification capture, смерть процесса, OEM и F-Droid этой проверкой не сертифицированы.
Локальные XML сохранены в игнорируемом `test-results/`; это не результаты опубликованного CI.
Созданные одноразовые AVD остановлены и удалены; существующие AVD и данные телефонов не менялись.

После слияния workflow в основную ветку заработают `workflow_run` для описания PR и fork-отчётов.
Для защиты обычных PR добавьте оба `Android emulator API 26/35 / Device tests` в required checks
(точные имена приведены в [релизной автоматизации](../ru/release-automation.md)).
Скрипт релизной финализации требует их самостоятельно; настройки GitHub в этой задаче не менялись.
