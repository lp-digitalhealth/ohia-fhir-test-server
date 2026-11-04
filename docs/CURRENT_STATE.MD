# CURRENT STATE
This document is for coding assistants to put the current state of the repo

**As of 2026-11-04.** Branch: `November_Testing`. Facts below are labeled VERIFIED (run and observed) or UNVERIFIED.

## Deployed server
- **VERIFIED:** the generic stack in `deploy/` (HAPI FHIR JPA, PostgreSQL 16, Caddy) runs on an Oracle Cloud Arm VM (`VM.Standard.A2.Flex`, a paid shape, because Always Free A1 capacity was unavailable). OS Ubuntu 26.04.1 aarch64. Docker is Ubuntu's `docker.io` 29.1.3 with the Compose plugin 5.7.0.
- **VERIFIED:** `GET /fhir/metadata` over HTTPS returned a `CapabilityStatement` (FHIR 4.0.1), through a free `sslip.io` hostname. The arm64 images pulled and started.
- Before auth, the log showed internet scanners probing the server within minutes of going public.
- The hostname and IP are deliberately not recorded here (public repo).
- A custom domain is **on hold** (owner, 2026-11-04). Nothing depends on it.

## Authentication (decision S1, open item S2)
- **In this branch:** interim shared bearer token enforced by `deploy/Caddyfile`; `API_TOKEN` is passed by `deploy/docker-compose.yml`, documented in `deploy/.env.example` and `deploy/README.md` section 7.
- **VERIFIED (stand-in upstream, not real HAPI):** 401 without or with a wrong token; 200 with the right one; `GET`/`HEAD /fhir/metadata` and `OPTIONS` open; writes to `/fhir/metadata` need the token; Compose refuses to start without `API_TOKEN`.
- **UNVERIFIED:** the live VM with real HAPI behind the rule, browser CORS behavior, and whether the change has been rolled out to the VM. **Roll-out is a manual step** (add `API_TOKEN` to the VM's `.env`, `git pull`, `docker compose up -d`). Until then the live server is still open.
- **Revisit in January 2027:** replace the shared token with the real design (SMART backend services and standalone launch, F9 and M1). Needs O1 first.

## For testers
`docs/CONSUMER_GUIDE.md` explains how to connect.

## Not built yet (see the plan, sections 7 and 8)
Role base paths, CRD/DTR/PAS, 360X receiver, ODE referral validation feedback, SMART/OAuth, the milestone H spike, and the UC07 test data.
