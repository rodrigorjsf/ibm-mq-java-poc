# 0006 — Role-based connection factories: a pooled producer factory and a dedicated long-lived consumer factory

Status: accepted (implementation tracked in issue #25)

## Context

The application wires **one shared** `JmsPoolConnectionFactory` (`MqConnectionFactoryFactory`) and injects it into the
producer and both consumers, sized `maxConnections=8` / `maxSessionsPerConnection=8`. Two problems surfaced while
documenting the pooling configuration (see `research-output/pooled-jms-factory-tuning.md`):

1. **The sizing contradicts the concurrency model.** The harness states parallelism comes from **N replicas, one
   single-threaded loop per pod** (`AbstractHarnessRunner`), so a pod needs ~1 connection and ~1 session at a time — not the
   64-session ceiling the 8×8 implies. The 8×8 is leftover from a multi-thread-per-pod model the project does not use.
2. **Producer and consumer have opposite connection lifecycles**, and one pool cannot be tuned for both:
   - A **producer** opens a short-lived `JMSContext` per `send` → the pool's connection reuse is a strong win.
   - The idiomatic production **consumer** is **long-lived**: it holds one physical connection for the pod's life and loops
     `receive()`/`commit()` on the same session (decided as the primary idiom; an async `MessageListener` is the scale-up
     variant). A pool wrapping a single long-held connection collapses to effective size 1 — redundant — and `pooled-jms`
     explicitly discourages async `MessageListener` usage on a pooled connection.

A real-world topology of "a `JmsPoolConnectionFactory` for the publisher + a raw `MQConnectionFactory` for the consumer"
prompted the question of whether that split is sound. It is — when the consumer is long-lived.

## Decision

Adopt **role-based connection factories** as the canonical topology:

- **Producer side** → a **`JmsPoolConnectionFactory`** (pooled), sized so `maxConnections × replicas` stays under the SVRCONN
  channel's `MAXINST`, and `maxSessionsPerConnection` stays at/below the channel's `SHARECNV`.
- **Long-lived consumer side** → a **dedicated `MQConnectionFactory`** (non-pooled), holding one connection for the pod's
  life. (A pool of `maxConnections=1` is an acceptable variant when the consumer keeps a churning lifecycle for didactic
  reasons.)
- A **single shared pooled factory** for both is acceptable **only** when both sides churn short-lived contexts **and** no
  async listener is used — it is the simple baseline, not the production ideal.

A dedicated **non-pooled** consumer factory is correct **only** for a long-lived consumer; pairing a raw factory with a
churning consumer would handshake a new socket per message. The two decisions (factory topology and consumer lifecycle) move
together — see consumer-lifecycle decision recorded with this change.

## Alternatives considered

- **Keep one shared pool for everything (status quo).** Simplest, one config point. Rejected as the *ideal* because it tunes
  one pool for two opposite workloads and pins a long-lived consumer connection inside the producer's pool; kept as a
  documented acceptable baseline for the churn-only / no-listener case.
- **Two independent pooled factories (one per role).** Keeps pool connection re-validation on both sides; useful when the
  consumer also churns or runs multiple consumer threads per pod. Retained as a documented variant, not the default.

## Consequences

- **Code (issue #25 — deepen the messaging seam):** introduce role-qualified factory beans; the producer injects the pooled
  factory, the consumers inject the dedicated factory; rewrite the consumers from per-poll churn to a long-lived held context
  (with `@PreDestroy` close and reconnect handling), and adjust the harness runners and the demo runner accordingly. Right-size
  the producer pool per §3–§4 of the fact sheet. This ADR is the decision; #25 is the build, done test-first.
- **Correctness is unchanged.** Factory topology affects connection/session **lifecycle and throughput only** — never commit
  or idempotency. `commit()` is per-session; delivery idempotency comes from the shared Correlation store. A pool can be a
  *source* of redelivery (a connection invalidated around the commit); the Correlation store is the *defence*.
- **Documentation:** the guide gains a "Connection pooling & factory topology" deep dive (`maxConnections` vs
  `maxSessionsPerConnection`, sizing under ~10k rpm, good/bad Mermaid scenarios, the producer-vs-consumer verdict);
  `CONTEXT.md` gains the sharpened terms (physical connection / session / shared conversation / role-based factories);
  `research-output/pooled-jms-factory-tuning.md` is the validated source of truth.
- **Reversibility:** changing the topology later means re-wiring bean qualifiers and the consumer lifecycle across the
  producer, both consumers, the harness, and the demo — a non-trivial, cross-cutting change, which is why it is recorded as an
  ADR rather than left implicit.
