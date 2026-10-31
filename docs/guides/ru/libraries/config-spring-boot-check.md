# Проверка конфигурации в ограниченном Spring-контексте

[English version](../../libraries/config-spring-boot-check.md) ·
[Библиотека конфигурации](config-spring-boot.md)

Сервис может использовать `ioc-config-spring-boot` для проверки имён, а затем
связать и проверить свою модель в небольшом Spring-контексте. Сервис выбирает
и beans, и источники конфигурации. Ограничение beans само по себе не ограничивает
импорты, профили и environment overrides.

Этот рецепт — код приложения, проверенный на Java 21 и Spring Boot 4.0.8.
`io.github.xorex-lc:ioc-config-spring-boot:0.3.0-rc.1` опубликован в Central
и GitHub Packages. GitHub consumer выполнил этот же Java-рецепт в
[запуске 40088095516](https://github.com/xORex-LC/ioc-extractor/actions/runs/40088095516).
Central consumer прошёл локально после исправления маршрутизации инструмента.
Исходный CI-отказ сохранён в
[постоянной записи выпуска](../../../../.github/release-notes/ru/ioc-config-spring-boot-0.3.0-rc.1.md).
Исправления включены через PR 19; защищённый CI recovery остаётся открытым.
См. [процедуру публикации, EN](../../library-publication.md),
[справочник API](../../../../adapters/adapter-config-spring-boot/README.md),
[внутренний поток проверки](../../../dev/configuration-inspection.md)
и [основное руководство](config-spring-boot.md).
[ADR 0046](../../../ADR/0046-reusable-spring-boot-configuration-inspection.md)
определяет границу общей библиотеки и приложения.

## Выбор политики источников

| Политика | Входы | Импорты и профили | Когда применять |
|---|---|---|---|
| Эффективная конфигурация Boot | Defaults приложения, YAML-кандидат и обычные Boot overrides | Boot загружает imports и активные profile documents/files, включая вложенные imports | Проверка конфигурации конкретного deployment |
| Закрытый набор входов ниже | Один явный YAML-документ и явная карта literal overrides | Отклоняются; ConfigData не запускается | Проверка самостоятельного кандидата независимо от окружения хоста |

Команда IOC `--ioc.validate-config=PATH` использует первую политику. Она
загружает classpath `application.yml` с необязательным импортом
`./configs/application.yml`, затем кандидата, с environment, system, JSON и CLI
overrides Boot. Импорты могут читать другие места и завершаться ошибкой независимо
от кандидата. Отсутствующий optional import разрешён, обязательный — вызывает
ошибку. Import locations являются доверенными deployment-входами; команда
не изолирует файловую систему или сеть. Правила описывает
[справочник Boot external configuration](https://docs.spring.io/spring-boot/4.0/reference/features/external-config.html).

IOC владеет корневыми locations команды и отклоняет CLI-изменения
`spring.config.location`, `additional-location`, `import` и `name`, включая
индексные и relaxed-написания. Scalar и indexed `spring.main.sources`
отклоняются во всех загруженных каналах до создания дополнительных bean sources.
Команда закрепляет eager initialization, отключает keep-alive и регистрацию
shutdown hook и закрывает явный контекст при успехе и ошибке. Runtime storage,
планировщики и транспорт остаются вне него. Временное подавление process console
принадлежит команде IOC; не используйте его как общий embedded runner.

## Зависимости

Используйте Boot BOM приложения. Добавьте `io.github.xorex-lc:ioc-config-spring-boot`
нужной версии, а для этого рецепта — прямые зависимости приложения
`org.springframework:spring-context` и `org.yaml:snakeyaml`. Проверенный consumer
использует Context 7.0.9 и SnakeYAML 2.6. Сама общая библиотека не требует
SnakeYAML, starter, модели IOC, Bean Validation или auto-configuration module.
Пример использует собственный Spring `Validator`; сервис может выбрать Bean Validation.

## Реализация проверки закрытого набора входов

Сохраните код в `examples/configuration/CollectorConfigurationCheck.java`.
Он принимает только `collector.*`, один YAML-документ и literal-строки.
`${...}` отклоняются без разрешения неявных зависимостей. Classpath
`application.yml`, environment variables, system properties и
`SPRING_APPLICATION_JSON` не участвуют. Проверяется каждое объявление,
включая нижние ключи, перекрытые override.

```java
package examples.configuration;

import com.iocextractor.config.boot.PropertyInspection;
import com.iocextractor.config.boot.PropertyInspector;
import com.iocextractor.config.boot.PropertySchema;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Checks one explicit collector document without starting the collector runtime. */
public final class CollectorConfigurationCheck {
    private CollectorConfigurationCheck() { }

    /** Returns the bound model; failures carry a fixed code, never input values or parser text. */
    public static Collector check(Path candidate, Map<String, String> overrides) {
        var environment = new StandardEnvironment();
        var sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        List<PropertySource<?>> documents;
        try {
            documents = new YamlPropertySourceLoader().load("candidate", new FileSystemResource(candidate));
        } catch (IOException | RuntimeException failure) {
            // Do not attach the loader's exception: its message can contain document values.
            throw new CheckFailure(Code.INPUT_INVALID);
        }
        if (documents.size() != 1) {
            throw new CheckFailure(Code.INPUT_POLICY);
        }
        sources.addLast(documents.getFirst());
        sources.addFirst(new MapPropertySource("explicitOverrides", new LinkedHashMap<>(overrides)));
        for (var source : sources) {
            var enumerable = (EnumerablePropertySource<?>) source;
            for (String name : enumerable.getPropertyNames()) {
                Object value = enumerable.getProperty(name);
                if (!(name.equals("collector") || name.startsWith("collector."))
                        || value instanceof String text && text.contains("${")) {
                    throw new CheckFailure(Code.INPUT_POLICY);
                }
            }
        }
        var inspection = PropertyInspector.inspect(new PropertySchema("collector", Collector.class), sources);
        if (!inspection.complete() || inspection.declarations().stream()
                .anyMatch(declaration -> declaration.status() != PropertyInspection.Status.KNOWN)) {
            throw new CheckFailure(Code.NAME_CHECK);
        }
        // Creating the scope before refresh also closes beans created during a failed refresh.
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            context.register(CheckConfiguration.class);
            context.refresh();
            return context.getBean(Collector.class);
        } catch (RuntimeException failure) {
            throw new CheckFailure(Code.BINDING_OR_VALIDATION);
        }
    }

    /** Immutable settings returned after the short-lived context has closed. */
    @ConfigurationProperties("collector")
    public record Collector(Duration requestTimeout, List<Connection> connections) {
        public Collector {
            connections = connections == null ? List.of() : List.copyOf(connections);
        }
    }

    /** One configured connection; validation, not its constructor, rejects operator mistakes. */
    public record Connection(String name, Boolean enabled) { }

    /** Coarse application-owned outcomes; callers decide output, exit codes and recovery. */
    public enum Code { INPUT_POLICY, INPUT_INVALID, NAME_CHECK, BINDING_OR_VALIDATION }

    /** Contains no underlying throwable or rejected input. */
    public static final class CheckFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Code code;

        private CheckFailure(Code code) {
            super(code.name());
            this.code = code;
        }

        public Code code() { return code; }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(Collector.class)
    static class CheckConfiguration {
        @Bean(name = EnableConfigurationProperties.VALIDATOR_BEAN_NAME)
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        static Validator configurationPropertiesValidator() {
            return new Validator() {
                @Override
                public boolean supports(Class<?> type) { return type == Collector.class; }

                @Override
                public void validate(Object target, Errors errors) {
                    var collector = (Collector) target;
                    if (collector.requestTimeout() == null || collector.requestTimeout().isNegative()
                            || collector.requestTimeout().isZero()) {
                        errors.rejectValue("requestTimeout", "collector.timeout.positive");
                    }
                    if (collector.connections().isEmpty()) {
                        errors.rejectValue("connections", "collector.connections.required");
                    }
                    for (int index = 0; index < collector.connections().size(); index++) {
                        var connection = collector.connections().get(index);
                        if (connection.name() == null || connection.name().isBlank()) {
                            errors.rejectValue("connections[" + index + "].name", "collector.connection.name");
                        }
                        if (connection.enabled() == null) {
                            errors.rejectValue("connections[" + index + "].enabled", "collector.connection.enabled");
                        }
                    }
                }
            };
        }
    }
}
```

Здесь не запускаются `SpringApplication`, component scan, auto-configuration
и ConfigData resolver. Регистрируются только явная property-модель
и инфраструктура проверки. YAML loader читает данные; `spring.config.import`
отклоняется до refresh и не выполняется. Запрет всего namespace `spring.*`
также исключает selectors профилей и дополнительные источники beans.

Используйте такой `collector.yml`:

```yaml
collector:
  request-timeout: 5s
  connections:
    - name: primary
      enabled: true
    - name: fallback
      enabled: false
```

Вызовите `CollectorConfigurationCheck.check(Path.of("collector.yml"), Map.of())`.
Явная карта `Map.of("collector.request-timeout", "2s")` заменяет scalar.
Для списков действуют правила Boot: один override
`collector.connections[0].name` заменяет **весь список**, не сохраняя
`enabled` и fallback-элемент. Validator отклонит такую неполную замену.
Передавайте все поля всех желаемых элементов заменяющего списка.
Агрегация карт следует другим правилам; не выводите происхождение списка
из отчёта об объявлениях.

Результат содержит только неизменяемую модель, без контекста, environment,
источника или loader. У этого примера нет рабочих потоков, БД или транспорта.
Если позднее сервис добавит beans с ресурсами, им нужны close hooks и проверки
успешного и неуспешного refresh. Runtime initialization остаётся вне
конфигурации проверки.

`CheckFailure` намеренно предоставляет только фиксированные категории. Сервис
может явно добавить безопасные структурированные пути полей и подсказки
миграции. Не печатайте binding exception или validation message с входными
данными автоматически. Логи Spring/provider — отдельная граница вывода:
настройте её в приложении. Пример не меняет process-wide streams и не
гарантирует, что произвольные framework-логи не содержат значений.

## Проверки и границы

`ConfigurationCheckRecipeIT` компилирует этот Java-блок и запускает его
в отдельной JVM с ограниченным ожиданием, общей библиотекой конфигурации
и сторонними JAR. Проверяются literal overrides, замена целого списка,
неизвестные имена, запрет источников и placeholders, ошибки ввода и семантики,
изоляция ambient inputs и отсутствие runtime component scanning.
`IocConfigurationContextIT` отдельно проверяет эффективную загрузку Boot,
запрет источников, настоящее связывание IOC aggregates и завершение команды.
Это reactor integration checks, а не проверка холодного репозитория или
второго развёрнутого сервиса.

Отдельный consumer также компилирует и выполняет рецепт на разрешённом JAR
при обычных зависимостях и BOM приложения. Java/YAML-блоки EN/RU должны
совпадать; при изменении примера обновляйте обе страницы и исполняемые проверки.
