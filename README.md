# AI-Platform

Платформа для прогона тестов на промптах и их оптимизации. Kotlin + Spring Boot, конфиги — YAML.

## Что нужно

- JDK 17
- Gradle wrapper уже в репозитории (`./gradlew`)

## Запуск

Тесты (без реальных LLM, deterministic-провайдер):

```bash
./gradlew test
```

Сервер:

```bash
./gradlew bootRun
```

Поднимается на `http://localhost:8080`.

**Важно:** при старте без профиля `test` срабатывает `StartupRunner` и сразу гоняет `real-test-config.yaml`. Если не нужно — либо отключи провайдер в конфиге, либо запускай с `--args='--spring.profiles.active=test'` (тогда API работает, автопрогон нет).

## LLM-провайдеры

В `src/main/resources/application.yml` включи нужный провайдер и пропиши ключ:

| Провайдер | Переменная | Поле в yaml-агенте |
|-----------|------------|-------------------|
| OpenAI | `OPENAI_API_KEY` | `provider: openai` |
| OpenRouter | `OPENROUTER_API_KEY` | `provider: openrouter` |
| Yandex Eliza | `SOY_TOKEN` | `provider: yandex-eliza` |
| deterministic | — | `provider: deterministic` (для локальных тестов) |

По умолчанию включён только `deterministic`.

## API

Основная ручка — `POST /api/tests/run`.

Тело: путь к yaml или сам yaml целиком:

```bash
curl -X POST http://localhost:8080/api/tests/run \
  -H 'Content-Type: application/json' \
  -d '{"configuration": "example-test-config.yaml"}'
```

Загрузка файла: `POST /api/tests/run/file` (multipart, поле `file`).

В ответе — результаты тестов, пути к html-отчётам (`reportPath`, `optimizationReportPath`).

## Конфиги

Примеры в корне (`example-test-config.yaml`, `optimization-test-config.yaml`).

Минимальная структура yaml: `agents`, `prompts`, `tests` (или `test_cases`). Для оптимизации — блок `optimizer`:

```yaml
optimizer:
  enabled: true
  mode: apply        # suggest | apply
  type: rule-based   # rule-based | llm
  iterations: 2
```

## Куда пишутся артефакты

- HTML-отчёты → `reports/`
- Baseline'ы для регрессии → `data/baselines/`
