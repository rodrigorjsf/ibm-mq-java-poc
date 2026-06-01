# 0003 — Distributed environment realized as a local k3s harness, not real AWS EKS

Status: accepted

To make the standing "distributed Kubernetes + microservices @ ~10k rpm" mandate **demonstrated** rather than only assumed, the project adds a **local Kubernetes harness on k3s**: the IBM MQ container plus two Java microservices (a publisher and a consumer) deployed as Deployments, with the consumer scaled to N replicas to exercise real competing-consumer behavior. `floci` (an MIT-licensed local AWS emulator — LocalStack-class, k3s for Kubernetes) is available **optionally** for AWS-native peripherals (e.g. a DynamoDB/RDS-backed correlation store).

We deliberately do **not** stand up real AWS EKS. Rationale: the initial framing assumed "real AWS EKS via floci", but floci is a *local emulator* — it neither provisions EKS nor supports IBM MQ. A local k3s harness gives genuine multi-pod k8s semantics (competing consumers, shared correlation store, reconnect storms) at **zero cloud cost**, which is what the distributed-correctness scenarios actually need.

## Consequences
- A real-EKS deployment, if ever wanted, is a separate effort (Terraform / eksctl + Amazon MQ or self-managed MQ on EKS), documented as an optional production reference architecture — floci is not the tool for it.
- The local k3s harness is where TASK_3's distributed scenarios (competing-consumers correlation, sustained load) run against real pods, instead of a single Testcontainers broker.
