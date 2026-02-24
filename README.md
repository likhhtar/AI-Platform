# AI Platform - Universal LLM Prompt Testing Platform

A production-ready platform for evaluating and testing LLM agents using declarative YAML configuration files. Built with clean architecture principles and designed for extensibility.

## 🎯 Project Overview

This platform provides a universal solution for testing LLM prompts that:

- **Is NOT domain-specific** - Works with any type of prompt or use case
- **Supports multiple LLM providers** - Pluggable architecture for different AI services
- **Supports multiple agents** - Configure different agent personalities and parameters
- **Uses declarative YAML configuration** - Easy to write and maintain test suites
- **Allows non-backend engineers to write tests** - Simple, intuitive configuration format
- **Is easily extensible** - Add new providers, evaluators, and features without core changes
- **Is deterministic and testable** - Mock providers ensure consistent test results

## 🏗️ Architecture

The project follows **Clean Architecture** principles with clear separation of concerns:

```
ru.yandex.diploma.aiplatform/
├── domain/                    # Core business logic (no dependencies)
│   ├── model/                # Domain entities
│   ├── provider/             # LLM provider interfaces
│   ├── evaluator/            # Evaluation interfaces
│   └── repository/           # Configuration repository interfaces
│
├── application/              # Use cases and application services
│   └── usecase/             # Business use cases
│
├── infrastructure/          # External concerns (frameworks, databases, APIs)
│   ├── llm/                # LLM provider implementations
│   ├── evaluator/          # Evaluator implementations
│   └── yaml/               # YAML configuration parsing
│
└── interface/              # External interfaces (REST, CLI, etc.)
    └── rest/               # REST API controllers and DTOs
```

### Key Architectural Decisions

1. **Hexagonal Architecture**: Domain layer is isolated from external dependencies
2. **Dependency Inversion**: All dependencies point inward toward the domain
3. **Interface Segregation**: Small, focused interfaces for different concerns
4. **Open/Closed Principle**: Easy to extend with new providers and evaluators
5. **Single Responsibility**: Each class has one reason to change

## 🚀 Quick Start

### Prerequisites

- Java 17+
- Gradle 7.0+

### Running the Application

```bash
# Build the project
./gradlew build

# Run the application
./gradlew bootRun

# The API will be available at http://localhost:8080
```

### Running Tests

```bash
# Run all tests
./gradlew test

# Run with coverage
./gradlew test jacocoTestReport
```

## 📝 YAML Configuration Format

Create test configurations using our intuitive YAML format:

```yaml
# Define agents with their configurations
agents:
  - name: translation-agent
    provider: mock
    systemPrompt: "You are a professional translator"
    temperature: 0.2
    maxTokens: 100

# Define prompt templates
prompts:
  - id: translate
    template: "Translate {{text}} to {{language}}"

# Define test cases
tests:
  - promptId: translate
    agent: translation-agent
    variables:
      text: "Hello"
      language: "French"
    expected: "Bonjour"
    evaluator: exact
```

See [`example-test-config.yaml`](example-test-config.yaml) for a complete example.

## 🔌 API Endpoints

### Run Tests from YAML Content

```http
POST /api/tests/run
Content-Type: application/json

{
  "configuration": "agents:\n  - name: test-agent\n    provider: mock\n..."
}
```

### Run Tests from File Upload

```http
POST /api/tests/run/file
Content-Type: multipart/form-data

file: [YAML file]
```

### Response Format

```json
{
  "success": true,
  "total": 3,
  "passed": 2,
  "failed": 1,
  "successRate": 0.67,
  "executionTimeMs": 1250,
  "results": [
    {
      "promptId": "translate",
      "agentName": "translation-agent",
      "variables": {"text": "Hello", "language": "French"},
      "expected": "Bonjour",
      "actual": "Bonjour",
      "passed": true,
      "score": 1.0,
      "explanation": "Output exactly matches expected result",
      "executionTimeMs": 423
    }
  ]
}
```

## 🧩 Extensibility

### Adding New LLM Providers

1. Implement the [`LlmProvider`](src/main/kotlin/ru/yourorg/diploma/aiplatform/domain/provider/LlmProvider.kt) interface
2. Add the `@Component` annotation
3. The provider will be automatically registered

```kotlin
@Component
class CustomLlmProvider : LlmProvider {
    override val providerId = "custom"
    
    override suspend fun generate(request: LlmRequest): LlmResponse {
        // Implementation
    }
}
```

### Adding New Evaluators

1. Implement the [`Evaluator`](src/main/kotlin/ru/yourorg/diploma/aiplatform/domain/evaluator/Evaluator.kt) interface
2. Add the `@Component` annotation
3. The evaluator will be automatically registered

```kotlin
@Component
class CustomEvaluator : Evaluator {
    override val evaluatorType = "custom"
    
    override fun evaluate(output: String, expected: String, metadata: Map<String, Any>): EvaluationResult {
        // Implementation
    }
}
```

## 🧪 Built-in Components

### LLM Providers

- **MockProvider** (`mock`) - Deterministic responses for testing
- **OpenAiProvider** (`openai`) - OpenAI API integration (stub implementation)

### Evaluators

- **ExactMatchEvaluator** (`exact`) - Exact string matching
- **ContainsEvaluator** (`contains`) - Substring matching

Both evaluators support:
- Case sensitivity control (`caseSensitive: true/false`)
- Whitespace trimming (`trimWhitespace: true/false`)

## 🔧 Configuration

### Application Properties

Configure the application via [`application.yml`](src/main/resources/application.yml):

```yaml
llm:
  providers:
    openai:
      enabled: false
      api-key: ${OPENAI_API_KEY:}
    mock:
      enabled: true
```

### Environment Variables

- `OPENAI_API_KEY` - OpenAI API key (when using OpenAI provider)

## 🧪 Testing Strategy

The project includes comprehensive tests demonstrating:

- **Unit Tests** - Domain logic and individual components
- **Integration Tests** - Use case orchestration
- **Deterministic Testing** - Using mock providers for consistent results

Key test files:
- [`PromptTest.kt`](src/test/kotlin/ru/yourorg/diploma/aiplatform/domain/model/PromptTest.kt) - Domain entity tests
- [`ExactMatchEvaluatorTest.kt`](src/test/kotlin/ru/yourorg/diploma/aiplatform/infrastructure/evaluator/ExactMatchEvaluatorTest.kt) - Evaluator tests
- [`RunTestSuiteUseCaseTest.kt`](src/test/kotlin/ru/yourorg/diploma/aiplatform/application/usecase/RunTestSuiteUseCaseTest.kt) - Use case integration tests

## 📊 Monitoring

The application includes Spring Boot Actuator endpoints:

- `/actuator/health` - Application health status
- `/actuator/info` - Application information
- `/actuator/metrics` - Application metrics

## 🎓 Educational Value

This project demonstrates:

- **Clean Architecture** implementation in Kotlin
- **Hexagonal Architecture** (Ports & Adapters)
- **SOLID Principles** in practice
- **Dependency Injection** with Spring Boot
- **Test-Driven Development** approaches
- **API Design** best practices
- **Configuration Management** patterns

## 🔮 Future Enhancements

Potential extensions (not implemented):

- **Semantic Evaluators** - Using embedding similarity
- **Regex Evaluators** - Pattern-based matching
- **Custom Metrics** - Domain-specific evaluation criteria
- **Batch Processing** - Parallel test execution
- **Result Persistence** - Database storage of test results
- **Web UI** - Frontend for test management
- **CI/CD Integration** - GitHub Actions, Jenkins plugins

## 📄 License

This project is created for educational purposes as part of a diploma thesis.

---

**Built with ❤️ using Clean Architecture principles**