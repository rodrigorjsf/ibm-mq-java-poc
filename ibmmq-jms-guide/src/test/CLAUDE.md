# Tests — conventions (ibmmq-jms-guide/src/test)

Test-drive every change with the `/tdd` skill: failing test first (red) → pass (green)
→ refactor. Never write tests after the fact to rubber-stamp existing code.

## Scopes — one per class, kept separate
- **unit** — `*Test.java`; surefire (`mvn test`); pure logic, no broker.
- **smoke** — `*Test.java` + `@Tag("smoke")`; fast context/wiring checks.
- **IT** — `*IT.java`; failsafe (`mvn verify`); real broker via Testcontainers `MQContainer`,
  manual `@BeforeAll`/`@AfterAll` lifecycle (no `testcontainers:junit-jupiter` module).
- **e2e** — `*IT.java` + `@Tag("e2e")`; full produce → consume → report flow.
  (Load/volumetry: `*IT` + `@Tag("load")`, excluded from default `verify`, run via `-Pload` — see TASK_3.)

## Style
- Prefer **`@MicronautTest`** with **real implementations and real values**. Use **Mockito**
  only when a real collaborator adds no value, or to drive many scenarios of one unit.
- Assert with **AssertJ** `assertThat(...)` (`org.assertj:assertj-core`, test scope) — not raw JUnit `Assertions`.
- **`@DisplayName`** (pt-BR, matching existing tests) on every test and test class.
- **`@ParameterizedTest`** for table/scenario coverage; **`@Nested`** to group scenarios by behavior.
- Lean on JUnit 5 (parameterized sources, `@Nested`, lifecycle, `assertThatThrownBy`) for concise, non-duplicated tests.
