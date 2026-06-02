# Fact sheet — `pooled-jms` 2.0.9 connection-factory tuning (`maxConnections`, `maxSessionsPerConnection`)

**Status:** validated source of truth. **Scope:** what the `JmsPoolConnectionFactory` knobs mean, their authentic defaults, the blocking behaviour, and how they interact with IBM MQ channel limits (`SHARECNV`, `MAXINST`/`MAXINSTC`) under the project's standing topology (Kubernetes + competing consumers @ ~10k rpm / ~167 msg/s).

> Constant values below were read from the **authentic `org.messaginghub:pooled-jms:2.0.9` sources jar** in the local Maven repo
> (`~/.m2/repository/org/messaginghub/pooled-jms/2.0.9/pooled-jms-2.0.9-sources.jar`), cross-checked against the compiled
> `.class` bytecode. File:line citations are into that source jar. Do **not** restate these from memory — re-read the jar.

## 1. Authentic defaults (verified)

| Knob | Default | Source (`JmsPoolConnectionFactory.java`) | Meaning |
|---|---|---|---|
| `maxConnections` | **1** | `public static final int DEFAULT_MAX_CONNECTIONS = 1;` (L91) | Max **physical connections** (TCP sockets) the pool holds. Fed to the connections pool as `setMaxTotalPerKey` + `setMaxIdlePerKey` (L490-491). |
| `maxSessionsPerConnection` | **500** | `public static final int DEFAULT_MAX_SESSIONS_PER_CONNECTION = 500;` (L90) | Max **active sessions** lent by **each** physical connection. Fed to the per-connection session pool as `setMaxTotalPerKey` (`PooledConnection.java` L302). |
| `maxIdleSessionsPerConnection` | 500 | L101 (= `DEFAULT_MAX_SESSIONS_PER_CONNECTION`) | Idle sessions kept per connection before destroy-on-return. |
| `blockIfSessionPoolIsFull` | **true** | `private boolean blockIfSessionPoolIsFull = true;` (L103) | When all sessions are in use, a session request **blocks** (vs. throwing). Fed to `setBlockWhenExhausted` (`PooledConnection.java` L360). |
| `blockIfSessionPoolIsFullTimeout` | **-1** (forever) | L104 | How long to block before giving up. `-1` = **block indefinitely**. |
| `connectionIdleTimeout` | 30 000 ms | `private int connectionIdleTimeout = 30 * 1000;` (L102) | Idle physical connection eviction age. |
| `timeBetweenEvictionRuns` | **-1** (off) | `DEFAULT_TIME_BETWEEN_EVICTION_RUNS = -1` (L88) | Background eviction sweep disabled by default. |
| `useAnonymousProducers` | true | L105 | One shared anonymous `MessageProducer` per session (per-destination producers cached only if disabled). |

Pool-exhaustion recovery backoff (when a borrowed connection turns out dead): `EXHUASTION_RECOVER_RETRY_LIMIT = 20`,
initial backoff `1_000 ms`, backoff limit `10_000 ms` (L84-86) — note the upstream typo `EXHUASTION`.

## 2. The two-ceiling model (the single most-missed point)

`maxConnections` and `maxSessionsPerConnection` are **orthogonal ceilings on different resources**:

- **`maxConnections`** bounds **physical connections** — TCP sockets, each costing a TCP + TLS + MQ handshake and one slot
  against the SVRCONN channel.
- **`maxSessionsPerConnection`** bounds **sessions multiplexed onto each** physical connection — cheap logical units of
  work, carried as shared conversations over the socket.

The effective ceiling on **concurrent sessions** the pool can hand out is therefore:

```
max concurrent sessions ≈ maxConnections × maxSessionsPerConnection
```

When a thread calls `createSession`/`createContext` and that ceiling is reached, the behaviour is governed by
`blockIfSessionPoolIsFull` (default **true** + timeout **-1**) → the caller **blocks indefinitely**. It does **not** throw.
This is why an under-sized pool under load presents as a **silent hang**, not an error — the classic misdiagnosis.

A correct mental model: think in terms of **how many JMS operations run concurrently per process**, then choose
`maxConnections` and `maxSessionsPerConnection` so their product comfortably exceeds that number, while keeping
`maxSessionsPerConnection` aligned to the channel's `SHARECNV` (§3) and `maxConnections × replicas` under `MAXINST` (§4).

## 3. `maxSessionsPerConnection` ⇄ `SHARECNV` (the channel-side bound)

Sessions on one physical connection are conversations multiplexed over the **same** TCP socket. How many can usefully
share that socket is negotiated against the SVRCONN channel's **`SHARECNV`** attribute (server-side; the client cannot
request an arbitrary count — see the `WMQ_SHARE_CONV_ALLOWED` on/off-only gotcha in `MqConnectionFactoryFactory`).

- If `maxSessionsPerConnection` ≤ negotiated `SHARECNV`: sessions truly multiplex over one socket — the intended, efficient case.
- If `maxSessionsPerConnection` > `SHARECNV`: the extra sessions cannot all share the socket; the MQ client opens
  **additional TCP conversations/sockets** under the hood or serializes them, eroding the very socket-economy the pool exists
  to provide. Sizing `maxSessionsPerConnection` far above `SHARECNV` is wasted configuration.

Practical rule: **keep `maxSessionsPerConnection` ≤ the channel `SHARECNV`** (default `SHARECNV` on `DEV.APP.SVRCONN` is 10).

## 4. `maxConnections × replicas` ⇄ `MAXINST`/`MAXINSTC` (the cluster-side bound)

Under the standing topology, the pool exists **per pod**. At **N replicas**, total physical connections against the QMgr =

```
total physical connections ≈ N(replicas) × maxConnections(per pod)
```

The SVRCONN channel caps this with **`MAXINST`** (max total instances of the channel) and **`MAXINSTC`** (max instances per
client address). Exceeding it → the QMgr **refuses** connections with `2025 MQRC_MAX_CONNS_LIMIT_REACHED` /
`2537 MQRC_CHANNEL_NOT_AVAILABLE`. So `maxConnections` is **not** a per-pod-local decision — it must be sized against the
replica count and the channel's `MAXINST`. A large `maxConnections` that is harmless at 1 replica becomes a channel-exhaustion
outage at 50 replicas.

## 5. Producer vs. consumer applicability

| Role | Connection lifecycle | Does the pool help? |
|---|---|---|
| **Producer** | Short-lived / bursty — a `createContext` per `send`, returned immediately. | **Yes, strongly.** The pool reuses the physical connection across sends instead of a socket-per-message handshake. This is the pool's core win. |
| **Long-lived consumer** (idiomatic: one connection held for the pod's life, `receive()`/`commit()` loop, or an async `MessageListener`) | One physical connection held for the process lifetime. | **Little to none.** The pool collapses to effective size 1 — redundant. For an **async `MessageListener`**, pooling is explicitly discouraged by `pooled-jms` (the connection is held outside the pool's control), so a **dedicated, non-pooled** `MQConnectionFactory` is the cleaner choice. |
| **Churning consumer** (a `createContext` per poll — used in this repo's harness/demo for didactic clarity) | Short-lived contexts, like a producer. | **Yes** — same reuse argument as the producer; without a pool a churning consumer would handshake per message. |

**Consequence:** the "pool for the producer + raw `MQConnectionFactory` for the consumer" topology that appears in real
codebases is **legitimate and well-founded when the consumer is long-lived** — not a smell. A single shared pooled factory
for both is acceptable only when **both** sides churn short-lived contexts **and** no async listener is used.

> **Idempotency is not a pool property.** Swapping pooled ↔ raw, or shared ↔ per-role factories, has **zero** effect on
> delivery idempotency — that comes from the shared/persistent Correlation store deduping by id. `commit()` is **per-session**,
> never per-connection; the pool's only commit-related duty is rolling back an uncommitted transacted session on return. The
> real relationship is inverse: a pool (or any connection failure around the commit) can be a **source** of redelivery; the
> Correlation store is the **defence**. Different layers.

## 6. Assessment of this repo's current configuration

`MqConnectionFactoryFactory` configures **one shared** `JmsPoolConnectionFactory` (injected into producer + both consumers)
with `maxConnections=8` and `maxSessionsPerConnection=8` (effective ceiling 64 concurrent sessions per pod).

This **contradicts the documented concurrency model**: the harness states parallelism comes from **N replicas, one
single-threaded loop per pod** (`AbstractHarnessRunner`) — which needs ~1 connection and ~1 session at a time, not 64. The
8×8 is leftover from a multi-thread-per-pod model this project does not use. The role-based topology and right-sizing are
tracked for implementation in **issue #25** (deepen the messaging seam) and recorded in **ADR-0006**.

## 7. Sources

| Source | Grounds | Link |
|---|---|---|
| `pooled-jms` 2.0.9 sources jar (`JmsPoolConnectionFactory.java`, `pool/PooledConnection.java`) | All default constants (§1), the two-ceiling model and blocking semantics (§2), session-pool `setBlockWhenExhausted` wiring. | local `~/.m2/.../pooled-jms-2.0.9-sources.jar` (Maven Central `org.messaginghub:pooled-jms:2.0.9`) |
| `pooled-jms` README / project docs | Guidance that async `MessageListener` usage is discouraged on pooled connections; the pool targets connection/session reuse for producers and short-lived consumers. | <https://github.com/messaginghub/pooled-jms> |
| IBM MQ 9.4 — `DEFINE CHANNEL` (`SVRCONN`) reference | `SHARECNV`, `MAXINST`, `MAXINSTC` channel attributes and the `2025`/`2537` refusal reason codes. | <https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=reference-define-channel-define-new-channel> |
