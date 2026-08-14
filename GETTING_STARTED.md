# Getting Started with Hitorro Mesh

A guided tour through the four deployment tiers, from a 30-second in-JVM
demo to a production Kubernetes deploy. Pick the tier that matches
where you are.

**Tiers at a glance:**

| Tier | Depends on | Wall-clock | Best for |
|---|---|---|---|
| [Tier 1](#tier-1--in-jvm-30-seconds) | just Java 21 + Maven | 30s | trying the API, unit tests |
| [Tier 2](#tier-2--local-nats-5-minutes) | + `nats-server` binary | 5min | local dev, real NATS transport |
| [Tier 3](#tier-3--docker-compose-15-minutes) | + Docker | 15min | integration testing, demos |
| [Tier 4](#tier-4--kubernetes-production) | + K8s cluster | 30min+ | production |

---

## Tier 1 — In-JVM (30 seconds)

Everything in one JVM, transport is `InMemoryMeshTransport`. Useful
for driving the mesh from unit tests or Spring Boot apps that own
their own agents.

**Prereqs:** Java 21, Maven.

```bash
git clone https://github.com/geekychris/hitorro-all.git
cd hitorro-all
./checkout-modules.sh   # clones every hitorro-* sub-repo
mvn -pl hitorro-mesh-examples install
mvn -pl hitorro-mesh-examples test -Dtest=EndToEndTest
```

Green output? You've just run 4 real distributed queries (WHERE,
GROUP BY, JOIN, LIMIT) with 2 in-memory agents. See
[`EndToEndTest`](../hitorro-mesh-examples/src/test/java/com/hitorro/mesh/examples/EndToEndTest.java)
for the wiring pattern — copy it into your own project to embed the
mesh.

---

## Tier 2 — Local NATS (5 minutes)

Real network transport, real Spring Boot fat JARs, all on your laptop.
Uses the reproducer scripts in [`hitorro-mesh-examples/scripts/`](../hitorro-mesh-examples/scripts/).

**Prereqs:**
- Java 21
- `nats-server` binary — install with `brew install nats-server` or
  use the one bundled with [Orion](https://github.com/geekychris/orion_mesh)
  at `~/.orion/bin/nats-server`. `export PATH=$HOME/.orion/bin:$PATH` if so.
- The two fat JARs built:
  ```bash
  mvn -pl hitorro-mesh-driver-app,hitorro-mesh-agent-app install -DskipTests
  ```

**Bring it up:**

```bash
cd hitorro-mesh-examples/scripts

./mesh-init-data.sh     # dataset + Spring config under /tmp/hitorro-mesh-smoke
./mesh-up.sh            # nats + driver + 2 agents in the background
./mesh-status.sh        # driver's view of live agents
./mesh-query.sh "SELECT id, title FROM docs WHERE lang = 'en'"

./mesh-down.sh          # tear down
```

Or all-in-one, self-asserting:
```bash
./mesh-smoke.sh         # init → up → query → assert → down. Exit 0 = pass.
```

**Same tier, secured transport** (phase 7a + smoke test):
```bash
./mesh-tls-smoke.sh     # generates self-signed CA, TLS-secured NATS,
                        # mesh with tls:// + PKCS12 truststore
./mesh-mtls-smoke.sh    # mTLS variant — client cert required by NATS
```

---

## Tier 3 — Docker Compose (15 minutes)

5 containers on one Docker network — `nats` + driver + 3 agents.
Uses the assets in [`hitorro-mesh-examples/docker/`](../hitorro-mesh-examples/docker/).

**Prereqs:**
- Docker (or Podman with `docker compose` alias)
- Fat JARs pre-built (same as Tier 2)

**Bring it up:**

```bash
cd hitorro-mesh-examples/docker
docker compose up -d --build
docker compose ps         # driver + 3 agents show "Up (healthy)"

curl -s -X POST http://localhost:8085/mesh/queries \
  -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT id FROM docs WHERE lang='\''en'\''","timeoutMs":5000}' | jq .

docker compose down
```

Images are hardened (phase 7e): non-root user, tuned JVM opts,
HEALTHCHECK against `/actuator/health`. Override JVM heap at runtime:
```bash
docker compose run -e JAVA_OPTS='-Xmx8g' driver
```

---

## Tier 4 — Kubernetes (production)

Helm chart in [`hitorro-mesh-k8s/helm/hitorro-mesh`](../hitorro-mesh-k8s/helm/hitorro-mesh)
deploys one driver + N agents, one Deployment per agent, with matching
`Service` + optional `Ingress` for the driver's REST endpoint.

**Prereqs:**
- A K8s cluster with `kubectl` context set
- A NATS service in the cluster (deploy the community `nats` chart, or
  point at an existing broker via `nats.url`)
- The two container images pushed to a registry your cluster can pull from:
  ```bash
  # Local build (once):
  docker build -f hitorro-mesh-examples/docker/Dockerfile.driver -t your-registry/hitorro-mesh-driver:3.0.1 .
  docker build -f hitorro-mesh-examples/docker/Dockerfile.agent  -t your-registry/hitorro-mesh-agent:3.0.1 .
  docker push your-registry/hitorro-mesh-driver:3.0.1
  docker push your-registry/hitorro-mesh-agent:3.0.1
  ```

**Install:**

```bash
cd hitorro-mesh-k8s/helm
helm install mesh ./hitorro-mesh \
    --set driver.image.repository=your-registry/hitorro-mesh-driver \
    --set agent.image.repository=your-registry/hitorro-mesh-agent \
    --set nats.url=nats://nats.nats.svc.cluster.local:4222
```

**Optional overrides** (in `my-values.yaml`):

```yaml
# Enable pod disruption budget for driver HA
driver:
  pdb:
    enabled: true
    minAvailable: 1

# Ingress for external access
driver:
  ingress:
    enabled: true
    className: nginx
    host: mesh.your-domain.com

# Secure NATS transport (phase 7a)
nats:
  url: "tls://nats.internal:4222"
  security:
    tls: true
    trustStorePath: /secrets/ca.p12
    trustStorePassword: changeit
    keyStorePath: /secrets/client.p12
    keyStorePassword: changeit
    credentialsFile: /secrets/mesh.creds
```

**Query it:**

```bash
kubectl port-forward svc/mesh-hitorro-mesh-driver 8085:8085
curl -s -X POST http://localhost:8085/mesh/queries ...
```

---

## Query language — cheat sheet

The mesh accepts standard SQL that `jvssql` understands. Features
supported for distribution:

| Query shape | Example |
|---|---|
| Filter + projection | `SELECT id FROM docs WHERE lang = 'en'` |
| GROUP BY + aggregate | `SELECT lang, COUNT(*) FROM docs GROUP BY lang` |
| DISTINCT | `SELECT DISTINCT lang FROM docs` |
| HAVING | `SELECT lang, COUNT(*) c FROM docs GROUP BY lang HAVING c > 10` |
| LIMIT + ORDER BY | `SELECT id FROM docs ORDER BY size_kb DESC LIMIT 10` |
| Broadcast JOIN (INNER/LEFT/RIGHT/FULL) | `SELECT d.id, l.name FROM docs d JOIN langs l ON d.lang = l.code` |
| Shuffle-hash JOIN (fact × fact) | `SELECT d.id, e.action FROM docs d JOIN events e ON d.id = e.doc_id` |
| Shuffle-hash JOIN + WHERE | `... WHERE d.title = 'X' AND e.action = 'view'` |
| Shuffle-hash JOIN + GROUP BY | `SELECT d.lang, COUNT(*) FROM docs d JOIN events e ON ... GROUP BY d.lang` |
| Windowed aggregate (batch) | `SELECT WIN_START(event_time, 60000) ws, COUNT(*) n FROM events GROUP BY WIN_START(event_time, 60000)` |
| Windowed aggregate (streaming) | Same query, streaming source registered — emits per-window rows incrementally |

**Web UI** (phase 7o) — after `mesh-up.sh`, open <http://localhost:8085/>
in a browser for a self-contained admin/debug/playground app: cluster
status, SQL playground, streaming console with cancel, EXPLAIN plan
viewer, active-queries manager, metrics snapshot.

**Explicit cancel** (phase 7d):
```bash
curl -X DELETE http://localhost:8085/mesh/queries/{queryId}
curl -s http://localhost:8085/mesh/queries | jq   # list in-flight
```

**Explain a query** (phase 7h) — see the planned execution shape without running:
```bash
curl -s 'http://localhost:8085/mesh/queries/explain?sql=SELECT+lang,+COUNT(*)+FROM+docs+GROUP+BY+lang' | jq
# → { planType: "TwoStagePlan", partialSql: "SELECT lang, COUNT(*) AS __c0__ ...",
#     combineSql: "SELECT lang, SUM(__c0__) AS c0 ...", partitions: [...] }
```

**Retry** transient failures (phase 7g) — pass `retries` in the request body:
```bash
curl -X POST http://localhost:8085/mesh/queries \
  -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT id FROM docs","timeoutMs":5000,"retries":2}'
# Retries whole query up to 2 times on AgentTaskException with 100ms · 2^n backoff.
```

**Server-Sent Events** for row-at-a-time streaming:
```bash
curl -N 'http://localhost:8085/mesh/queries/stream?sql=SELECT+id+FROM+docs&timeoutMs=60000'
```

---

## Troubleshooting

**"no live agent advertises 'jvssql'"** — the driver's capability
lookup returned zero agents. Check `curl /mesh/agents` returns rows;
if not, agents aren't heartbeating (see agent logs — TLS, network,
config issue).

**"only registered broadcast tables can be joined"** — a JOIN
referenced a table the mesh doesn't know about. Either register it
distributed via `driver.tables` or broadcast via
`driver.broadcast-tables`.

**Windowed streaming query stalls forever** — check the
[phase-6d.2.2 idle timeout](ROADMAP.md#-phase-6d22--idle-timeout-watermarks-shipped).
Default 30s; for backfill scenarios you may need to disable it or
tune down.

**"query timeout"** exception in the REST response — increase
`timeoutMs` (default 5000). See [phase 7b](ROADMAP.md#-phase-7b--query-timeouts--cancel-propagation-shipped).

**Agent stuck in `MISSING` state** — the K8s cluster manager
declares an expected agent list; if a pod hasn't heartbeated within
`agent-expiry` (default 15s), it's flagged MISSING. Check pod logs,
NATS connectivity, and TLS config.

---

## Where to next

- **Comprehensive user guide** (PDF/HTML with diagrams): [`docs/user-guide/`](docs/user-guide/) — run `./build.sh` to render
- Full feature list + design decisions: [ROADMAP.md](ROADMAP.md)
- Architecture overview: [ARCHITECTURE.md](ARCHITECTURE.md)
- REST API summary: [`MeshRestController`](../hitorro-mesh-driver-app/src/main/java/com/hitorro/mesh/driver/app/MeshRestController.java)
- Wire protocol DTOs: [`hitorro-mesh-core`](src/main/java/com/hitorro/mesh)
