# Tidepool build backlog

The MVP from [MVP.md](MVP.md), broken into **commit-sized chunks**. Each chunk:

- is **one commit**, small (aim for ≤ ~300 changed lines),
- leaves `./gradlew build` green,
- includes tests for any logic it adds,
- never creates or changes real cloud resources. Terraform is written and validated (`terraform validate`), never applied.

**Workflow:** every chunk goes on its own branch `chunk/<id>-<slug>` (e.g. `chunk/b3-namespace-naming`)
and gets its own pull request. Nothing is pushed straight to `main`. If an earlier chunk's PR is still
open, the next chunk is **stacked** on that branch (PR base = that branch), with at most 3 open chunk
PRs at a time. Merge stacked PRs oldest first, using **merge commit or rebase, not squash**, so the
PRs stacked on top don't conflict.

Chunks are done **top to bottom**. Ticking one: `- [x] … (YYYY-MM-DD)`.
If a chunk turns out bigger than ~300 lines, split it into `a`/`b` sub-items here and do only the first part.

Tests that need Docker (Testcontainers: Postgres, k3s) are tagged `integration`. They run in GitHub
Actions CI, and locally only when Docker is available.

---

## A. Project skeleton
- [x] A1. Gradle multi-module skeleton (`core`, `server`), Kotlin 2.x, JVM 21 toolchain (foojay auto-provisioning), Gradle wrapper, empty Spring Boot 4 app that starts (2026-11-27)
- [ ] A2. GitHub Actions `ci.yml`: `./gradlew build` on push and PR, integration tests included (Docker available on runners)
- [ ] A3. Actuator health/readiness endpoints + a first `@SpringBootTest`; `integration` test tag wired into Gradle (`./gradlew test` = unit only, `./gradlew integrationTest` = Docker tests)

## B. Core domain (`core` module)
- [ ] B1. `tide.yaml` data classes + Jackson YAML parsing + sample fixtures for the three example services
- [ ] B2. `tide.yaml` validation (Bean Validation) with field-path error messages + tests
- [ ] B3. Namespace naming rules: format, length, reserved names, auto-name from user/service/tag, `-2` suffixing + tests
- [ ] B4. Value templating (`{{ postgres.url }}`, `{{ services.x.url }}`, `{{ services.x.internalUrl }}`) + tests
- [ ] B5. Resolver part 1: expand required services recursively, default tags, cycle detection + tests
- [ ] B6. Resolver part 2: merge infra (one Postgres with a DB per service, union of Kafka topics, one Redis) + tests
- [ ] B7. Command grammar parser for all Slack commands in MVP.md §8, with "did you mean" suggestions + tests
- [ ] B8. Schedule model + evaluator (timezone, working days/hours, holidays, warnBefore, graceAfterCreate, keep-tonight) + tests, including daylight-saving cases

## C. Deployer (fabric8 manifest builders)
- [ ] C1. Namespace builder: labels, annotations (owner, expires-at, services, schedule) + ResourceQuota + LimitRange
- [ ] C2. NetworkPolicy builder: same-namespace + Traefik ingress only
- [ ] C3. Postgres builder: StatefulSet, Service, credentials Secret, one DB per service (init script)
- [ ] C4. Redis builder: Deployment + Service
- [ ] C5. Kafka builder: single-node KRaft StatefulSet + Service + topic-creation Job
- [ ] C6. Service builder: Deployment + Service from `tide.yaml` (rendered env, probes, resources, image pinned by digest)
- [ ] C7. Migrate/seed Job builder
- [ ] C8. Route builder: Traefik routes for `<svc>-<ns>.<baseDomain>`
- [ ] C9. Deployer orchestration: apply in order, wait for readiness, report step progress (unit tests with fabric8 mock server)
- [ ] C10. Deployer integration test against k3s (Testcontainers, `integration` tag)

## D. Integrations
- [ ] D1. `ImageRegistry` interface + ECR implementation (`DescribeImages`, tag → digest) + fake for tests
- [ ] D2. GitHub App auth: JWT → installation token, cached and refreshed
- [ ] D3. GitHub builds: find the run for a tag/PR/branch (`tidepool.tag=` run name), read jobs and steps → progress model
- [ ] D4. GitHub `workflow_dispatch` for `--build` + reading `tide.yaml` at a commit
- [ ] D5. GitHub webhook endpoint with HMAC verification for `workflow_run` / `workflow_job`

## E. Server (state, jobs, API)
- [ ] E1. Flyway schema: `envs`, `env_recipes`, `jobs`, `job_steps`, `events`
- [ ] E2. Spring Data JDBC repositories + Testcontainers Postgres test
- [ ] E3. Job runner: claim with `FOR UPDATE SKIP LOCKED`, virtual-thread executor, retries, resume after restart
- [ ] E4. Create-env job: resolve → check images → wait for build → provision → deploy → ready
- [ ] E5. Refresh, extend and delete jobs
- [ ] E6. REST API `/api/v1/envs*` and `/api/v1/services*` (MVP.md §8)
- [ ] E7. Event bus + SSE endpoint `/api/v1/envs/{ns}/events`
- [ ] E8. TTL reaper (`@Scheduled`), with expiring/expired events
- [ ] E9. Off-hours scheduler: warn events, destroy with recipe saved, `SLEEPING_DESTROYED` state
- [ ] E10. Recreate (manual + morning auto-recreate) from the saved recipe, `--latest` option
- [ ] E11. Reconciler: Tidepool-labelled namespaces vs DB, flag orphans
- [ ] E12. Quotas: per-user and cluster-wide env limits, clear errors

## F. Web UI (Thymeleaf + htmx)
- [ ] F1. "Sign in with Slack" (Spring Security OAuth2/OIDC) + layout template
- [ ] F2. Env list page (namespace, owner, status, TTL, schedule, URLs)
- [ ] F3. Create form: service picker, tag picker with build status, name, TTL, schedule, recreate-morning
- [ ] F4. Env detail page: live steps via SSE, build progress + links, URLs, actions (refresh/extend/keep/recreate/delete)
- [ ] F5. Log view per service (follow mode)

## G. Slack
- [ ] G1. Bolt for Java in Socket Mode wired into Spring; `/tide help`
- [ ] G2. `/tide up`, `ls`, `status`, `urls` via the command parser → REST layer
- [ ] G3. Live thread message: one message updated with `chat.update` on each event
- [ ] G4. Buttons: Open, Extend 24h, Refresh, Delete (with confirm), Keep tonight, Recreate
- [ ] G5. `#tidepool-status` notifier + per-service `notify.channel` + nightly summary
- [ ] G6. Remaining commands: `refresh`, `extend`, `down`, `logs`, `keep`, `recreate`, `schedule`, `services`, `images`, `builds`; Slack user → Tidepool user by email

## H. Infra and packaging (written + validated, never applied by automation)
- [ ] H1. Reusable workflow `.github/workflows/build-push-ecr.yml` (OIDC, tag conventions, `tidepool.tag=` run name)
- [ ] H2. Terraform: VPC + EKS (managed node group, Cluster Autoscaler, EBS CSI, Pod Identity)
- [ ] H3. Terraform: ECR repos + lifecycle rules + GitHub OIDC role `github-ecr-push`
- [ ] H4. Terraform: Route53 + ACM wildcard + Traefik behind NLB
- [ ] H5. Terraform: RDS Postgres + Secrets Manager entries + `tidepool-server` and `tidepool-viewers` roles
- [ ] H6. Helm chart `deploy/helm/tidepool` + server image via `bootBuildImage`
- [ ] H7. `terraform validate` + `helm lint` steps in CI

## I. Examples (`tidepool-examples` repo)
- [ ] I1. `payments-api`: Spring Boot skeleton, Postgres + Kafka consumer/producer, `tide.yaml`
- [ ] I2. `orders-api`: REST + Postgres migrations/seed + Redis cache + Kafka, calls payments-api, `tide.yaml`
- [ ] I3. `web`: static frontend configured from env vars at startup, `tide.yaml`
- [ ] I4. Build workflows per service with path filters, calling the reusable workflow

## J. Hardening
- [ ] J1. Failure handling: step timeouts, readable failure reasons, retry from failed step
- [ ] J2. End-to-end test plan + GitHub Actions job against a test EKS (manual trigger only)
- [ ] J3. Docs: setup guide checked against the real code, troubleshooting section
