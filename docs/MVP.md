# Tidepool — MVP plan (AWS + GitHub + Slack)

> **Status:** design. Nothing is built yet. The setup steps and commands below describe the **target
> behaviour**. Each section names the milestone (M0–M5) that makes it real.

## 0. Scope in one line

**AWS only. GitHub only. Slack only.** Envs run on EKS, **one namespace per env, referenced by the
namespace name**. Images are built by GitHub Actions and stored in ECR. Build progress is tracked
through GitHub workflow runs. Users drive Tidepool from **Slack or the web UI**, and every status change
is posted to a Slack channel.

The core still talks to small interfaces (registry, builds, cluster, notifier), so a fork can add
GCP, GitLab or Teams later. **The MVP ships exactly one implementation of each.**

| Concern | MVP choice | Not in MVP |
|---|---|---|
| Cloud | AWS: EKS, ECR, Route53, ACM, NLB, RDS, Secrets Manager, IAM | GCP, Azure, on-prem |
| Source + CI | GitHub repos + GitHub Actions (hosted or self-hosted runners) | GitLab, Jenkins, CircleCI |
| Build tracking | GitHub Actions API + `workflow_run` webhooks, via a GitHub App | Other CI systems |
| Registry | ECR | GHCR, Harbor, Docker Hub |
| Chat + notifications | Slack: `/tide` commands, threads, `#tidepool-status` channel | Teams, Google Chat, email |
| Clients | Slack + web UI | `tide` CLI (v1.1) |

---

## 1. Definition of done

| # | Capability | Acceptance test |
|---|---|---|
| 1 | **Deploy services** | `/tide up orders-api:pr-42 web --name orders-pr-42` creates namespace `orders-pr-42`, installs only the deps the services declare, runs migrations/seed, deploys in dependency order and reaches `READY` in under 5 min once the images exist. |
| 2 | **Many envs side by side** | At least 10 envs run at once, each in its own namespace, fully isolated (no traffic or data shared between them). Every command takes the namespace name: `/tide status orders-pr-42`, `kubectl -n orders-pr-42 get pods`. |
| 3 | **Track builds on GitHub** | If `orders-api:pr-42` isn't in ECR yet but its GitHub workflow is running, the env waits (`WAITING_FOR_BUILD`) and shows the run's current job and step plus a link. It continues automatically when the run succeeds, or fails with the run link if the run fails. `--build` starts the workflow when no run exists. |
| 4 | **Access + interact via the UI** | `https://tidepool.tide.acme.dev` (Sign in with Slack) lists envs by namespace with status, owner, TTL, services, tags, build links and URLs. From the UI: create, refresh, extend, delete, live status, logs. Each exposed service opens at `https://<svc>-<namespace>.tide.acme.dev`. |
| 5 | **Slack bot + channel notifications** | Every command in §8 works as `/tide …`. The requester gets one live-updating thread message with buttons. Every lifecycle event (created, waiting for build, ready, failed, expiring, deleted) is posted to `#tidepool-status`. Expired envs are deleted automatically. |
| 6 | **Off-hours destroy** | With the default schedule, every env is warned at 19:30 and destroyed at 20:00 on weekdays, and destroyed on weekends and holidays. `/tide keep <ns> --tonight` skips one night. An env created with `--recreate-morning` is `READY` again by 09:00 with the same namespace and image digests. Schedules (timezone, days, hours, holidays) are set in config, and a different schedule can be picked per env. |

---

## 2. Architecture

```
                 Web UI (Thymeleaf + htmx)          Slack (/tide, #tidepool-status)
                           \                          /   (Socket Mode websocket)
        +-------------------v------------------------v--------------------------+
        |           tidepool-server  (Kotlin + Spring Boot, runs in EKS)         |
        |  UI pages + REST API | command parser | Slack adapter + notifier       |
        |  job runner (steps in RDS) | resolver (tide.yaml) | TTL reaper         |
        |  GitHub client (App) | ECR client | deployer (fabric8)                 |
        +----+----------------+------------------+-------------------+-----------+
             |                |                  |                   |
     GitHub App API      ECR DescribeImages  fabric8 -> EKS     RDS / Secrets Manager
     (runs, dispatch,         |                  |
      tide.yaml, webhooks)    |       +----------v-------------------------------+
             |                |       | EKS: Traefik <- NLB(ACM) <- Route53 *.tide|
   +---------v---------+      |       |  ns orders-pr-42: web, orders-api,        |
   | GitHub Actions    |------+       |        payments-api, postgres, kafka,     |
   | build -> push ECR |  (OIDC role) |        redis                              |
   +-------------------+              |  ns qa-checkout: ...   ns asha-web-main: ..|
                                      +-------------------------------------------+
```

### Components (all inside one Spring Boot app)

| Component | Responsibility |
|---|---|
| **UI + REST API** | Server-rendered pages (Thymeleaf + htmx, live updates over SSE) and `/api/v1` for Slack and future clients. |
| **Command parser** | One grammar for Slack commands (§8). Reused by the CLI later. |
| **Slack adapter** | Bolt in **Socket Mode** (outbound websocket, no public URL). Handles slash commands and buttons, updates thread messages, posts to `#tidepool-status`. |
| **Job runner** | Each env action is a job whose steps are stored in RDS, retried on failure and resumed after a restart. |
| **Resolver** | Reads `tide.yaml`, expands required services, merges infra (one Postgres with a DB per service, one Kafka, one Redis). |
| **GitHub client** | Uses a GitHub App installation token. Finds the workflow run for a PR, branch or SHA, reads its jobs and steps, dispatches builds, reads `tide.yaml`. Receives `workflow_run` / `workflow_job` webhooks and polls every 15 s as a fallback. |
| **ECR client** | `ecr:DescribeImages`: does the tag exist, and what is its digest. Deploys are pinned to the digest. |
| **Deployer** | fabric8 Kubernetes client. Builds typed manifests (Deployments, StatefulSets, Services, Jobs, Traefik routes) from `tide.yaml` and applies them to the env's namespace with server-side apply. Deleting the namespace removes everything. |
| **TTL reaper + reconciler** | Warns in Slack 1 h before expiry (with an **Extend 24h** button), then deletes the env. Also compares Tidepool-labelled namespaces with the DB and flags orphans. |
| **Event bus** | Every state change goes to the UI (SSE), the requester's thread and `#tidepool-status`. |

### Env lifecycle

```
PENDING -> RESOLVING -> WAITING_FOR_BUILD? -> PROVISIONING -> DEPLOYING -> READY
                              |                                            |
                           FAILED  <-------------- any step -------------- UPDATING (refresh)
READY -> DELETING -> DELETED                      (manual `down` or TTL)
READY -> DELETING -> SLEEPING_DESTROYED           (off-hours schedule, recipe kept)
SLEEPING_DESTROYED -> PENDING -> ... -> READY     (`recreate`, or automatically before work starts)
SLEEPING_DESTROYED -> DELETED                     (TTL ends)
```

### Why these AWS pieces

| Choice | Reason |
|---|---|
| URLs as **`<svc>-<namespace>.tide.acme.dev`** (one level) | An ACM wildcard `*.tide.acme.dev` covers only one label. With one level, one cert and one Route53 record serve every env forever. |
| **NLB + Traefik**, not one ALB per env | One load balancer for all envs, routing by host header. Cost doesn't grow with the number of envs. |
| **Deps in-cluster** (Postgres, Kafka, Redis on EBS gp3) | Seconds to start, free per env, deleted with the namespace. RDS/MSK per env would take 10–20 min each. |
| **RDS for Tidepool's own state** | Env records and audit log must survive cluster problems. A `db.t4g.micro` is enough. |
| **EKS Pod Identity** | `tidepool-server` gets AWS permissions (ECR read, Secrets Manager read) with no static keys. |
| **Managed node group + Cluster Autoscaler** | Adds nodes when many envs start, removes them when envs are deleted. Karpenter can come later for cost tuning. |

Rough fixed cost before any envs run: about **$110–140 per month** (EKS control plane ~$73, NLB ~$20,
RDS micro ~$15, NAT/egress extra). Env cost is the nodes they use. Check current AWS prices for your region.

---

## 3. Environments = namespaces

**The env name *is* the Kubernetes namespace name.** There's no prefix and no separate ID, so the
same name works in Slack, in the UI, in URLs and in `kubectl`.

### 3.1 Naming

| Rule | Detail |
|---|---|
| Format | Lowercase letters, digits and `-`. Starts with a letter. 3–30 chars. (Keeps `<svc>-<namespace>` within the 63-char DNS label limit, with service names ≤ 30 chars.) |
| Given name | `--name orders-pr-42`. Refused if taken. |
| Auto name (no `--name`) | `<user>-<first-service>-<tag>`, shortened, e.g. `asha-orders-pr42`. Gets `-2`, `-3` if taken. |
| Reserved | `default`, `tidepool`, `traefik`, and anything starting with `kube-`, `aws-`, `amazon-` or `eks-`. |
| Safety | Tidepool only touches namespaces it created, i.e. those labelled `app.kubernetes.io/managed-by=tidepool`. An existing unlabelled namespace with the same name is never reused or deleted. |

### 3.2 What each namespace carries

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: orders-pr-42
  labels:
    app.kubernetes.io/managed-by: tidepool
    tidepool.io/owner: asha               # Slack handle
  annotations:
    tidepool.io/expires-at: "2026-11-28T10:00:00Z"
    tidepool.io/services: "orders-api:pr-42@sha256:…, payments-api:main@sha256:…, web:main@sha256:…"
    tidepool.io/slack-thread: "C0123/1732600000.000100"
```

Inside it, Tidepool creates:
- **ResourceQuota + LimitRange:** caps CPU, memory and volumes per env (defaults in config, overridable per env up to an admin max).
- **NetworkPolicy:** pods accept traffic only from their own namespace and from Traefik. Envs can't reach each other.
- The env's **dependencies** (Postgres, Kafka, Redis), its **services**, the migrate/seed **Jobs** and the **routes**.

### 3.3 Running many at once

| Limit | Default | Config key |
|---|---|---|
| Envs per user | 3 | `defaults.maxEnvsPerUser` |
| Envs in the cluster | 30 | `defaults.maxEnvs` |
| Per-env CPU / memory | 4 vCPU / 8 GiB | `defaults.quota` |

The Cluster Autoscaler adds nodes as envs pile up, and removes them when envs are deleted.

### 3.4 Working with an env directly (devs)

```bash
kubectl get ns -l app.kubernetes.io/managed-by=tidepool
```

```bash
kubectl -n orders-pr-42 get pods
```

```bash
kubectl -n orders-pr-42 logs deploy/orders-api -f
```

```bash
kubectl -n orders-pr-42 port-forward svc/postgres 5432:5432
```

Read-only `kubectl` access is granted through an EKS access entry for a `tidepool-viewers` IAM role.
Changes should go through Tidepool. If someone edits an env by hand, the next `refresh` puts it back
the way `tide.yaml` describes it.

### 3.5 Off-hours schedule (destroy at night and on weekends)

Envs are **destroyed outside working hours** so nothing runs overnight. With working hours of
09:00–20:00 on weekdays, envs run about 55 of the 168 hours in a week, which cuts env compute cost by
roughly **two thirds**. When namespaces are deleted, the Cluster Autoscaler removes the empty nodes too.

**What "destroy" means here:** the namespace and everything in it is deleted, the same as `down`. Tidepool
keeps the env's **recipe** (services, the exact image digests, data mode, owner, name), so it can be
rebuilt the same way, by hand or automatically before work starts.

#### Configuration (admins, in `values.yaml`)

```yaml
schedules:
  default: office-ist                 # used when an env doesn't pick one
  definitions:
    office-ist:
      timezone: Asia/Kolkata          # or "requester" = use the creator's Slack timezone
      workingHours:
        days: [MON, TUE, WED, THU, FRI]
        start: "09:00"
        end: "20:00"
      offHours: destroy               # destroy | none
      warnBefore: 30m                 # Slack warning before the destroy
      holidays: ["2026-12-25", "2027-01-26"]   # whole day counts as off-hours
    us-east:
      timezone: America/New_York
      workingHours: { days: [MON, TUE, WED, THU, FRI], start: "08:00", end: "19:00" }
      offHours: destroy
      warnBefore: 30m
    always-on:                         # for long-running QA or demo envs (admins only)
      offHours: none
  graceAfterCreate: 2h                 # an env created late is never destroyed within 2h of creation
  allowKeepTonight: true               # owners may skip one night
  maxKeepsPerUserPerWeek: 2
```

#### Per-env choices (users, at create time or later)

| Option | Effect |
|---|---|
| *(nothing)* | Uses the default schedule |
| `--schedule us-east` | Uses a named schedule |
| `--recreate-morning` | Rebuilds the env automatically **15 min before** working hours start, so it's ready when people arrive. It uses the same namespace, the same image digests and a fresh seed. |
| `/tide keep <ns> --tonight` | Skips tonight's destroy (counts against `maxKeepsPerUserPerWeek`) |
| `/tide recreate <ns>` | Rebuilds a schedule-destroyed env now, from its saved recipe |

#### Rules
- **The earliest of TTL and the schedule wins.** An env whose TTL ends before tonight is deleted at TTL.
- **An env created during off-hours** lives at least `graceAfterCreate` (2h) before the schedule applies.
- **Destroyed by schedule ≠ deleted.** The env shows as `SLEEPING_DESTROYED` in Slack and the UI, with a
  **Recreate** button. Its name stays reserved for the owner until its TTL ends.
- **Refreshing with `--latest` on recreate:** `/tide recreate <ns> --latest` uses the newest image for
  each service's ref instead of the saved digests. That's useful when a PR got new commits overnight.
- **Data is not kept across the night** in the MVP. It's re-seeded on recreate. Keeping data overnight
  needs snapshots (v1.1) or sleep mode (v1.3).

#### Implementation
- Each namespace carries `tidepool.io/schedule`, `tidepool.io/recreate-morning` and `tidepool.io/keep-until`
  annotations, so anyone can see its policy with `kubectl describe ns`.
- A `@Scheduled` task runs every minute. It works out which envs pass `end - warnBefore` (warn) and
  `end` (destroy), and, for envs marked `--recreate-morning`, `start - 15m` (recreate). Each action is
  queued as a normal job, so it's retried and visible in the UI like any other.
- Timezones and holidays come from `java.time` with the schedule's zone. Daylight-saving changes are
  handled automatically.

---

## 4. GitHub integration (build tracking)

### 4.1 How a tag maps to a build

Every service uses Tidepool's reusable workflow, so tags and runs follow one convention:

| Trigger | ECR tags pushed |
|---|---|
| Push to a branch | `sha-<7>`, `<branch-slug>` (e.g. `main`, `feature-cart`) |
| Pull request | `sha-<7>`, `pr-<number>` |
| Release tag | `sha-<7>`, `<semver>` |

The reusable workflow also adds `tidepool.tag=<tag>` to the run name. That lets Tidepool find the
run for a requested tag directly, without guessing.

### 4.2 What happens on `up orders-api:pr-42`

1. ECR check: does `orders-api:pr-42` exist?
   - **Yes:** take its digest. If the latest run for PR 42 is still building a newer commit, warn
     ("newer build in progress, using sha-ab12cd3; add `--wait` to wait for it").
   - **No:** go to step 2.
2. GitHub check: latest run of `build.yml` for PR 42.
   - **queued / in_progress:** set `WAITING_FOR_BUILD`, show `Build: job docker (step 4/7 "Push to ECR") · 3m12s · [View run]`, update the display on each `workflow_job` event, and continue when the run succeeds.
   - **failed:** set `FAILED` with "Build failed at job test, step 'Unit tests' · [View run]".
   - **no run, `--build` given:** call `workflow_dispatch` on the PR's head branch, then wait as above.
   - **no run, no `--build`:** `FAILED` with "No image and no build for pr-42. Retry with `--build`".
3. Wait timeout: `build.timeout` in `tide.yaml` (default 30 min).

### 4.3 Reusable build workflow (`tidepool/.github/workflows/build-push-ecr.yml`)

A service repo only needs this:

```yaml
# .github/workflows/build.yml in each service repo
name: build
on:
  push: { branches: [main, "feature/**"] }
  pull_request:
  workflow_dispatch:
permissions: { id-token: write, contents: read }
jobs:
  build:
    uses: <org>/tidepool/.github/workflows/build-push-ecr.yml@v1
    with:
      ecr-repository: orders-api
      aws-region: ap-south-1
      aws-role-arn: arn:aws:iam::<acct>:role/github-ecr-push
```

The reusable workflow runs `actions/checkout`, then `aws-actions/configure-aws-credentials` (OIDC, no
stored keys), then `aws-actions/amazon-ecr-login`, then `docker/build-push-action` with the tags from §4.1.
It runs on GitHub-hosted runners by default. Set `runs-on` for self-hosted runners.

### 4.4 GitHub App permissions

| Permission | Why |
|---|---|
| Actions: **read & write** | Read runs, jobs and steps; `workflow_dispatch` for `--build` |
| Contents: **read** | Read `tide.yaml` from service repos |
| Metadata: read | Required by GitHub |
| Pull requests: read | Resolve `pr-42` to its head branch and SHA |
| Webhook events | `workflow_run`, `workflow_job` → `https://tidepool.tide.acme.dev/api/github/webhook` (HMAC-verified) |

---

## 5. `tide.yaml` (lives in each service repo, at the root)

```yaml
service: orders-api                # ≤ 30 chars
image: <acct>.dkr.ecr.ap-south-1.amazonaws.com/orders-api
github:
  repo: acme/orders-api
  workflow: build.yml
build:
  timeout: 30m
defaultTag: main
port: 8080
health: /healthz
expose: true                       # -> https://orders-api-<namespace>.tide.acme.dev
resources: { cpu: 250m, memory: 512Mi }

requires:
  services: [payments-api]         # resolved recursively at their defaultTag unless given
  postgres:
    database: orders
    data: seed                     # empty | seed   (snapshot comes after MVP, §10)
    migrate: ["./bin/migrate", "up"]
    seed: seed/orders.sql          # path inside the image
  kafka:
    topics: [order-created, order-paid]
  redis: {}

env:
  DATABASE_URL: "{{ postgres.url }}"
  KAFKA_BROKERS: "{{ kafka.brokers }}"
  REDIS_URL: "{{ redis.url }}"
  PAYMENTS_URL: "{{ services.payments-api.internalUrl }}"   # http://payments-api.<namespace>.svc:8080

notify:
  channel: "#orders-team"          # optional: also post this service's env events here
```

Tidepool learns about services from a list in its config (`services: [acme/orders-api, acme/web, …]`)
and reads `tide.yaml` at the **same commit as the image**, so manifest and code always match. It is
parsed into Kotlin data classes and checked with Bean Validation, and errors name the field and line.
The frontend is just another service. It gets backend URLs from env vars at runtime
(`API_URL: "{{ services.orders-api.url }}"`).

---

## 6. Setup guide

About 45 minutes the first time, mostly waiting on Terraform.

### 6.1 Prerequisites

| Need | Detail |
|---|---|
| AWS account | Admin access for the first `terraform apply` |
| A domain in Route53 | e.g. `acme.dev`. Tidepool uses the `tide.acme.dev` subdomain. |
| GitHub org | Admin rights to create a GitHub App and an OIDC role |
| Slack workspace | Rights to create an app |
| Tools | `terraform` ≥ 1.7, `aws` CLI v2, `kubectl` ≥ 1.30, `helm` ≥ 3.15 |

### 6.2 AWS foundation (M0)

```bash
git clone https://github.com/<org>/tidepool && cd tidepool/deploy/terraform/aws
```

```bash
cp terraform.tfvars.example terraform.tfvars
```

```hcl
region         = "ap-south-1"
cluster_name   = "tidepool"
hosted_zone    = "acme.dev"
base_domain    = "tide.acme.dev"
github_org     = "acme"                 # for the GitHub OIDC trust policy
ecr_repos      = ["orders-api", "payments-api", "web"]
```

```bash
terraform init && terraform apply
```

This creates:
- VPC
- EKS with a managed node group, Cluster Autoscaler, the EBS CSI driver and Pod Identity
- Traefik, behind an NLB
- ECR repos with lifecycle rules (expire `pr-*` tags after 14 days)
- ACM wildcard cert `*.tide.acme.dev`
- Route53 records `*.tide.acme.dev` and `tide.acme.dev`
- RDS Postgres
- Secrets Manager entries (left empty)
- IAM role `github-ecr-push`, trusted by GitHub OIDC for your org
- IAM role `tidepool-server` (Pod Identity: ECR read, Secrets Manager read)
- IAM role `tidepool-viewers`, with read-only `kubectl` access to Tidepool namespaces

```bash
aws eks update-kubeconfig --name tidepool --region ap-south-1
```

### 6.3 GitHub App (M2)

1. GitHub → **Settings → Developer settings → GitHub Apps → New GitHub App**, owned by your org.
   - Webhook URL: `https://tidepool.tide.acme.dev/api/github/webhook`
   - Webhook secret: generate one
   - Permissions and events: as in §4.4
2. Create a **private key** and note the **App ID**.
3. **Install** the app on the org, choosing *only* the service repos.
4. Store the credentials:
   ```bash
   aws secretsmanager put-secret-value --secret-id tidepool/github --secret-string file://github.json
   ```
   `github.json` looks like `{"appId":"123","installationId":"456","webhookSecret":"…","privateKey":"-----BEGIN…"}`.
   Delete the file afterwards.
5. In each service repo, add `.github/workflows/build.yml` (§4.3) and `tide.yaml` (§5).

### 6.4 Slack app (M4)

1. <https://api.slack.com/apps> → **Create New App → From a manifest** → pick the workspace, then paste:
   ```yaml
   display_information:
     name: Tidepool
     description: One-command test environments
   features:
     bot_user: { display_name: tidepool, always_online: true }
     slash_commands:
       - command: /tide
         description: Create and manage test environments
         usage_hint: "up orders-api:pr-42 web | ls | help"
   oauth_config:
     redirect_urls: ["https://tidepool.tide.acme.dev/login/oauth2/code/slack"]
     scopes:
       bot: [commands, chat:write, chat:write.public, users:read, users:read.email]
       user: [openid, email, profile]
   settings:
     socket_mode_enabled: true
     interactivity: { is_enabled: true }
   ```
2. **Basic Information → App-Level Tokens → Generate** with scope `connections:write`. Copy the `xapp-…` token.
3. **Install to Workspace** and copy the `xoxb-…` bot token.
4. From **Basic Information**, copy the **Client ID** and **Client Secret**. The UI's "Sign in with Slack" uses them.
5. Store everything:
   ```bash
   aws secretsmanager put-secret-value --secret-id tidepool/slack --secret-string '{"appToken":"xapp-…","botToken":"xoxb-…","clientId":"…","clientSecret":"…"}'
   ```
6. Create `#tidepool-status` and run `/invite @tidepool` in it.

### 6.5 Install Tidepool (M2–M3)

```bash
cp deploy/helm/tidepool/values.example.yaml values.yaml
```

```yaml
baseDomain: tide.acme.dev
aws:
  region: ap-south-1
  rdsSecret: tidepool/rds            # created by Terraform
github:
  secret: tidepool/github
  services: [acme/orders-api, acme/payments-api, acme/web]
slack:
  secret: tidepool/slack
  statusChannel: "#tidepool-status"
  allowedChannels: []                # empty = any channel
  allowedEmailDomains: [acme.com]
defaults:
  ttl: 24h
  maxTtl: 168h
  maxEnvsPerUser: 3
  maxEnvs: 30
  quota: { cpu: "4", memory: 8Gi }
schedules:                           # full options in §3.5
  default: office-ist
  definitions:
    office-ist:
      timezone: Asia/Kolkata
      workingHours: { days: [MON, TUE, WED, THU, FRI], start: "09:00", end: "20:00" }
      offHours: destroy
      warnBefore: 30m
admins: [platform-lead@acme.com]
```

```bash
helm install tidepool ./deploy/helm/tidepool -n tidepool --create-namespace -f values.yaml
```

```bash
kubectl -n tidepool rollout status deploy/tidepool-server
```

### 6.6 Smoke test

1. In Slack, run `/tide help`. The command list should appear.
2. Run `/tide up web --name smoke-1` and `/tide up web --name smoke-2`. Both should show progress in
   their threads, reach `READY` with `https://web-smoke-1…` and `https://web-smoke-2…`, and appear in
   `#tidepool-status`.
3. Run `kubectl get ns -l app.kubernetes.io/managed-by=tidepool`. Both `smoke-1` and `smoke-2` should be listed.
4. Open `https://tidepool.tide.acme.dev`, **Sign in with Slack**, and find both envs in the list.
5. Open a PR on a service, then right away run `/tide up orders-api:pr-<n>`. The env should show
   `WAITING_FOR_BUILD` with the live job and step, then continue on its own.
6. Run `/tide down smoke-1` and confirm. The namespace is deleted and the channel is notified. `smoke-2` is untouched.
7. Off-hours check: run `/tide schedule smoke-2 --set test-5min`, using a test schedule whose working
   hours end 5 minutes from now. You should get the warning, see the namespace disappear and the env
   show as `SLEEPING_DESTROYED`. Then `/tide recreate smoke-2` should bring it back with the same URL.

---

## 7. Slack notifications

### Requester's thread
One message that is **edited in place** as the env progresses, so the channel doesn't fill up:

```
⏳ orders-pr-42 · @asha · TTL 24h
   orders-api:pr-42  🔨 building: job docker, step 4/7 "Push to ECR" (3m12s)  [View run]
   payments-api:main ✅ sha-9f8e7d6
   web:main          ✅ sha-1a2b3c4
   deps: postgres ✅  kafka ✅  redis ✅
```
then:
```
🟢 orders-pr-42 READY in 6m05s
   web          https://web-orders-pr-42.tide.acme.dev
   orders-api   https://orders-api-orders-pr-42.tide.acme.dev
   kubectl -n orders-pr-42 get pods
   [Open in UI] [Extend 24h] [Refresh] [Delete]
```

### `#tidepool-status` (one line per event, threaded per env)

| Event | Message |
|---|---|
| created | `🆕 orders-pr-42 by @asha · orders-api:pr-42, web:main` |
| waiting for build | `🔨 orders-pr-42 waiting for orders-api build · [View run]` |
| ready | `🟢 orders-pr-42 ready in 6m05s · [Open]` |
| failed | `🔴 orders-pr-42 failed at DEPLOYING: orders-api readiness timeout · [Logs] [Retry]` |
| refreshed | `🔄 orders-pr-42 orders-api → sha-77aa001` |
| expiring | `⏰ orders-pr-42 expires in 1h · [Extend 24h]` |
| off-hours warning | `🌙 orders-pr-42 will be destroyed at 20:00 IST (off-hours) · [Keep tonight] [Destroy now]` |
| destroyed for the night | `😴 orders-pr-42 destroyed for the night · recipe saved · [Recreate now]` (adds "auto-recreate 08:45" if set) |
| recreated | `🌅 orders-pr-42 recreated, ready at 08:52 · [Open]` |
| deleted | `🗑 orders-pr-42 deleted (TTL / by @asha)` |

The off-hours warning goes to the requester's thread **and** mentions them, so they see it in time.
`#tidepool-status` also gets one daily summary at the destroy time: `🌙 Destroyed 12 envs for the night, kept 2 (orders-pr-42, qa-checkout)`.

A service's `notify.channel` in `tide.yaml` also receives that service's events.

---

## 8. Slack command reference

`<ns>` is always the env's namespace name.

| Command | What it does | Example |
|---|---|---|
| `help [cmd]` | Lists commands, or the details of one | `/tide help up` |
| `services` | Lists services and what each requires | `/tide services` |
| `images <svc> [--limit 10]` | Lists the newest ECR tags with their build links | `/tide images orders-api` |
| `builds <svc> [--ref pr-42]` | Shows recent GitHub runs with status | `/tide builds orders-api --ref pr-42` |
| `up <svc[:tag]>… [--name <ns>] [--ttl D] [--data empty\|seed] [--build] [--wait] [--schedule S] [--recreate-morning]` | Creates an env in a new namespace | `/tide up orders-api:pr-42 web --name orders-pr-42 --recreate-morning` |
| `ls [--mine\|--all]` | Lists envs: namespace, owner, status, age, TTL left | `/tide ls` |
| `status <ns>` | Shows state, current step, build progress, health, URLs | `/tide status orders-pr-42` |
| `urls <ns>` | Shows just the URLs | `/tide urls orders-pr-42` |
| `refresh <ns> [svc:tag…] [--reset-data] [--build]` | Redeploys with new tags. No tags given means the latest image for each service's current ref. | `/tide refresh orders-pr-42` |
| `extend <ns> <dur>` | Pushes the TTL out (capped at `maxTtl`) | `/tide extend orders-pr-42 24h` |
| `down <ns>` | Deletes the env and its namespace (owner or admin, with confirmation) | `/tide down orders-pr-42` |
| `logs <ns> <svc>` | Links to the UI log view, and shows the `kubectl logs` command | `/tide logs orders-pr-42 orders-api` |
| `keep <ns> --tonight` | Skips tonight's off-hours destroy | `/tide keep orders-pr-42 --tonight` |
| `recreate <ns> [--latest]` | Rebuilds a schedule-destroyed env from its recipe. `--latest` uses the newest images. | `/tide recreate orders-pr-42` |
| `schedule [<ns>] [--set S] [--recreate-morning on\|off]` | Shows the schedules, or shows/changes one env's schedule | `/tide schedule orders-pr-42 --recreate-morning on` |

**Specific errors:**

| Situation | Reply |
|---|---|
| Name taken | `Namespace orders-pr-42 already exists (owner @ravi). Try orders-pr-42-2 or pick another --name` |
| Bad name | `"Orders_PR42" isn't a valid namespace: use lowercase letters, digits and '-', 3–30 chars` |
| Unknown env | `No Tidepool env named orders-pr-24. Did you mean orders-pr-42?` |
| Unknown tag | `orders-api:pr-99 not in ECR and no GitHub run for PR 99. Newest tags: pr-42, main` |
| Quota hit | `You have 3 envs (max 3): orders-pr-12, asha-web-main, qa-7` |
| Typo | `Unknown command "upp". Did you mean "up"?` |

### REST API (used by Slack adapter and future clients)

```
POST   /api/v1/envs                 {services:[{name,tag}], name?, ttl?, data?, build?, wait?} -> 202 {namespace}
GET    /api/v1/envs?owner=me
GET    /api/v1/envs/{ns}
POST   /api/v1/envs/{ns}/refresh    {services?, resetData?, build?}
POST   /api/v1/envs/{ns}/extend     {ttl}
DELETE /api/v1/envs/{ns}
GET    /api/v1/envs/{ns}/events     (server-sent events)
GET    /api/v1/envs/{ns}/logs/{svc}?tail=200&follow=true
GET    /api/v1/services
GET    /api/v1/services/{svc}/images
GET    /api/v1/services/{svc}/builds?ref=pr-42
POST   /api/github/webhook
```

---

## 9. Tech stack

**One language (Kotlin), one framework (Spring Boot), one deployable (the `tidepool-server` image)**,
plus one library for each system Tidepool must talk to.

### Required by the integrations

| Need | Choice |
|---|---|
| Language + framework | **Kotlin 2.x, Spring Boot 4** (Spring MVC on Java 21 virtual threads) |
| Tidepool's state | **Postgres (RDS)** + **Flyway** + **Spring Data JDBC** |
| Kubernetes | **fabric8 kubernetes-client**: typed manifests, server-side apply, watches, logs |
| AWS | **AWS SDK for Java v2**: ECR, Secrets Manager |
| GitHub | **hub4j `github-api`**: App auth, Actions runs, jobs and dispatch |
| Slack | **Bolt for Java**: Socket Mode, Block Kit, buttons |
| Infra | **Terraform** (EKS, ECR, DNS, cert, RDS, IAM) + **Traefik** for routing |

### Comes with Spring, no extra setup
- Thymeleaf + htmx for the UI (htmx is a single JS file, with no Node build)
- Spring Security OAuth2 login for "Sign in with Slack"
- `@Scheduled` for the reaper and GitHub polling
- `@ConfigurationProperties` for config
- Jackson YAML + Bean Validation for `tide.yaml`
- Actuator health probes
- `bootBuildImage` for the container image
- `@SpringBootTest`

Tests also use **Testcontainers** (Postgres + k3s), so the deployer is tested against a real Kubernetes cluster.

### Module layout (Gradle, Kotlin DSL)
```
tidepool/
  core/          domain model, tide.yaml parsing + validation, command grammar, namespace naming rules
  server/        Spring Boot app: ui, api, jobs, resolver, deployer, integrations/{aws,github,slack}
  deploy/        helm/tidepool (installs Tidepool), terraform/aws
  .github/workflows/build-push-ecr.yml   reusable workflow for service repos
```

---

## 10. Milestones

Estimates assume one developer working with Claude. Each milestone ends with a demo you can run.

| Milestone | Delivers | Demo | Est. |
|---|---|---|---|
| **M0: AWS foundation** | Terraform module (§6.2), reusable `build-push-ecr.yml`, 3 example services in a demo org | Push to an example repo → image in ECR → `*.tide.acme.dev` resolves | 2–3 days |
| **M1: Deploy engine** | `core` module, resolver, ECR client, fabric8 deployer (namespace + quota + network policy, deps, services, routes), Testcontainers k3s tests | A test (and a dev-only endpoint) brings up the 3 example services in two namespaces at once on EKS | 3–4 days |
| **M2: Server + GitHub** | RDS store, job runner, REST API, GitHub App client, webhooks, `WAITING_FOR_BUILD`, `--build`, TTL reaper, **off-hours scheduler** (warn, destroy, save recipe, recreate, morning recreate), reconciler, Helm chart for Tidepool | Open a PR, `POST /api/v1/envs`, watch it wait for the build and then deploy. A 5-minute test schedule destroys and recreates it. | 5–6 days |
| **M3: Web UI** | Sign in with Slack; env list by namespace; create form (service + tag pickers with build status); env page with live steps, build links, URLs, logs, actions | Full lifecycle from the browser | 2–3 days |
| **M4: Slack** | `/tide` commands, threaded live message, buttons, `#tidepool-status`, per-service channels, expiry warnings | Full lifecycle from Slack, every event in the channel | 2–3 days |
| **M5: Hardening** | Failure handling, quotas, e2e suite run by GitHub Actions against a test EKS, docs | All 6 acceptance tests in §1 pass | 2–3 days |

**Total: about 3 weeks.** M3 and M4 can run in parallel once the M2 API is in place.

### Repos for the MVP
- `tidepool`: server, Helm chart, Terraform, reusable workflow, docs
- `tidepool-examples`: 3 demo services (web, orders-api, payments-api) with `tide.yaml` and `build.yml`

---

## 11. Improvement plan (after MVP)

### v1.1: Data, safety and the CLI (≈2–3 weeks)
- **Snapshots:** `snapshot create <ns> --name X`, then `up … --data snapshot:X`. `pg_dump` and Redis
  RDB files go to **S3**. A mandatory anonymisation SQL script runs on any snapshot made from
  production-like data.
- **`tide` CLI:** a native binary (Clikt + GraalVM) that reuses the `core` command grammar, so every
  Slack command works in a terminal. Adds `tide connect <ns> postgres`, a one-step tunnel.
- **Roles:** viewer, user and admin, mapped from Slack user groups. Quotas per team.
- **Audit log view** in the UI.

### v1.2: GitHub-native flow (≈2 weeks)
- **PR environments:** the label `tidepool` on a PR creates namespace `<repo>-pr-<n>`. URLs and status
  go in a PR comment and a GitHub **Deployment**, so they show on the PR page. Each push refreshes
  the env; merging or closing the PR deletes it.
- **Commit statuses** on the PR: `tidepool/env: ready`.
- `tide.yaml` validation as a GitHub Action check on PRs.

### v1.3: Speed and cost (≈2–3 weeks)
- **Sleep mode** as a second off-hours action (`offHours: sleep`): scale every workload to 0 and keep
  the volumes, so data survives the night and wake-up takes ~1 min instead of a full rebuild.
- **Scale-to-zero when idle** during working hours, woken up on the first request.
- **Shared baseline:** an always-on `baseline` namespace with `main` versions of every service, so an env deploys only the services that changed.
- **Warm dependency pool:** pre-started Postgres, Kafka and Redis, to reach `READY` under 90 s.
- **Karpenter** and **cost per namespace**, shown in the UI and a weekly Slack digest.
- Metrics (Micrometer/Prometheus) once there's somewhere to send them.

### v2: Opening it up (≈4–6 weeks)
- Optional AWS-managed deps: RDS, MSK or ElastiCache per env, for performance-realistic tests.
- A public REST API with an OpenAPI spec, and a separate UI if the htmx pages stop being enough.
- Split into multiple repos, with a provider-writing guide so forks can add other clouds, CI systems or chat tools.
- Temporal as the workflow engine if the job runner becomes a limit.

---

## 12. Decisions to confirm before M0

| Decision | Proposed default |
|---|---|
| AWS region | `ap-south-1` |
| Base domain | `tide.<company-domain>` |
| Runners | GitHub-hosted; self-hosted via the `runs-on` input |
| Default / max TTL | 24h / 7 days |
| Max envs per user / in the cluster | 3 / 30 |
| Per-env quota | 4 vCPU / 8 GiB |
| Working hours / off-hours action | Mon–Fri 09:00–20:00 IST / destroy, warning 30 min before |
| Morning recreate | Opt-in per env (`--recreate-morning`) |
| Keeps per user per week | 2 |
| Tag when none is given | `defaultTag` from `tide.yaml`, else `main` |
| Server framework | Spring Boot 4 + Kotlin (decided) |
| UI | Thymeleaf + htmx (decided) |
