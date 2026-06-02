# Micronaut config validation at startup — `@ConfigurationProperties`, `@Context`, and the `${VAR:default}` blank-vs-unset footgun

Validated facts for making `MqProperties` (and any `@ConfigurationProperties` bean) fail-fast at boot. **Source of truth — check here before re-researching** Micronaut config-validation timing or the `IBM_MQ_PASSWORD` blank footgun. Decision recorded in **ADR-0011**; built for issue **#27**.

## 1. A lazy `@ConfigurationProperties` bean is never validated at boot

A `@ConfigurationProperties` bean is an ordinary lazy singleton. Its `@PostConstruct` (and bean-validation `@Validated` constraints) fire only when the bean is **first created/injected**. If its only consumers are themselves lazy `@Singleton`s that no eager bean pulls, the bean is never instantiated at context startup, so:

- `ApplicationContext.run(Map.of("ibm-mq.queue-manager",""))` starts **cleanly** with invalid config.
- Validation that was supposed to "fail fast at startup" silently does nothing until first real use (i.e. at connect).

**Fix:** annotate the bean `@Context` so the container instantiates (and validates) it at startup. `@Context` is the documented Micronaut idiom for "this bean's lifecycle is tied to the context's."

### `@Context` caveat — only safe when construction has no heavy side effect

`@Context` forces eager creation. For a pure config/validation bean (a `@PostConstruct` that only throws on violation) this is safe. **Do not** put `@Context` on a bean whose construction *connects* to an external system — earlier in this project a `@Context` on a connecting `@Singleton` caused an eager MQ connection at boot and hung a smoke test ~662s (project memory `lazy-singleton-never-runs`). `MqProperties` opens nothing, so `@Context` on it is validation-only.

## 2. `${VAR:default}` applies the default only when the variable is UNSET, not when it is empty

Micronaut property-placeholder resolution treats an environment variable **set to an empty string** as *present* — it resolves to `""` and the `:default` is **not** applied. The default applies only when the variable is genuinely **absent**.

| `IBM_MQ_PASSWORD` state | `${IBM_MQ_PASSWORD:passw0rd}` resolves to |
|---|---|
| unset (absent) | `passw0rd` — default applies |
| `export IBM_MQ_PASSWORD=` (empty) | `""` — default NOT applied → **footgun** |
| `export IBM_MQ_PASSWORD=s3cret` | `s3cret` |

This bit issue #27: the orchestrate worktree exported `IBM_MQ_PASSWORD` **empty**, so the "blessed" default config resolved to a blank password and eager validation rejected it (`user=app` + blank password). The local `~/.local/ibmmq-env.sh` does **not** set the variable at all, so a plain local `mvn` run gets `passw0rd` via the default — the blank came from the empty export in the orchestrate environment, **not** the env script. Earlier notes claiming "the env script sets it empty" were imprecise; the precise cause is *an empty export in some environments* combined with §3.

## 3. The bean's own field defaults can be a self-inconsistent (invalid) combination

`MqProperties` shipped with field defaults `user = "app"` (non-blank) and `password = ""` (blank). Under the credential cross-field rule (`user` non-blank ⇒ `password` required), the bean's **bare defaults are themselves invalid**. Any context that falls back to field defaults (or resolves the password blank per §2) trips the rule. Either supply a valid password in every context that boots the bean, or make the bare defaults valid (e.g. default `user` blank = no-auth).

## 4. k8s / production always sets a concrete password → the footgun is dev/test-only

`deploy/k3s/10-secrets.yaml` sets `IBM_MQ_PASSWORD: "passw0rd"`, and the MQ image's `MQ_APP_PASSWORD` must match it or every JMS connection fails `MQRC_NOT_AUTHORIZED (2035)`. Real deployments therefore present a non-blank password; the blank-resolution footgun appears only in dev/test environments that leave the variable empty or unset.

## 5. Make boot-validation tests environment-independent

Because boot validation now depends on the resolved config, tests that boot a context must not depend on the ambient `IBM_MQ_PASSWORD`:

- Context-start tests that do **not** exercise MQ should pin a valid `ibm-mq.password` in their own property map.
- A startup-validation proof should pin **invalid** configs (asserting the context refuses to boot) **and** an explicit **valid** config (asserting it boots), rather than rely on `ApplicationContext.run()` resolving the ambient env.

This keeps the orchestrate gate (and any CI runner) deterministic regardless of how it injects environment variables — which is the gap that failed #27's first implementation.
