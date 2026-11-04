# CURRENT STATE
This document is for coding assistants to put the current state of the repo

**As of 2026-11-04 (evening).** The auth change is merged into `main` and `November_Testing`. Facts below are labeled VERIFIED (run and observed) or UNVERIFIED.

## Deployed server
- **VERIFIED:** the generic stack in `deploy/` (HAPI FHIR JPA, PostgreSQL 16, Caddy) runs on an Oracle Cloud Arm VM (`VM.Standard.A2.Flex`, a paid shape, because Always Free A1 capacity was unavailable). OS Ubuntu 26.04.1 aarch64. Docker is Ubuntu's `docker.io` 29.1.3 with the Compose plugin 5.7.0.
- **VERIFIED:** `GET /fhir/metadata` over HTTPS returned a `CapabilityStatement` (FHIR 4.0.1), through a free `sslip.io` hostname. The arm64 images pulled and started.
- **VERIFIED:** the live server now **enforces the interim bearer token** (rolled out 2026-11-04, section "Authentication" below).
- Before auth, the log showed internet scanners probing the server within minutes of going public.
- The hostname and IP are deliberately not recorded here (public repo).
- A custom domain is **on hold** (owner, 2026-11-04). Nothing depends on it.

## Authentication (decision S1, open item S2)
- **In the repo:** interim shared bearer token enforced by `deploy/Caddyfile`; `API_TOKEN` is passed by `deploy/docker-compose.yml`, documented in `deploy/.env.example` and `deploy/README.md` section 7.
- **VERIFIED on the live server (real HAPI, rolled out 2026-11-04):** `GET /fhir/metadata` without a token returned 200; `GET /fhir/Patient` without a token returned 401; the same read with the token returned 200; `POST /fhir/Patient` without a token returned 401. From outside the VM, the no-token requests gave 200 (metadata), 401 (`/fhir/Patient`), 401 (the HAPI home page `/`) and 401 (the POST). HAPI and PostgreSQL did not restart; only Caddy was recreated.
- **VERIFIED earlier on a stand-in upstream (not real HAPI):** a wrong, shortened or extended token gives 401; lowercase `bearer` gives 401 (the match is case-sensitive); `OPTIONS` is open; writes to `/fhir/metadata` need the token; path tricks such as `metadata/../Patient` are rejected; Compose refuses to start without `API_TOKEN`.
- **UNVERIFIED:** browser CORS behavior against real HAPI, the case-sensitivity and wrong-token cases on the live server, and load.
- **Operations:** the token lives only in the VM's `deploy/.env` (never in git) and with the owner. To roll out or rotate it, edit `API_TOKEN` in `.env` and run `docker compose up -d` (see `deploy/README.md` section 7).
- **Revisit in January 2027:** replace the shared token with the real design (SMART backend services and standalone launch, F9 and M1). Needs O1 first.

## For testers
`docs/CONSUMER_GUIDE.md` explains how to connect.

## Not built yet (see the plan, sections 7 and 8)
Role base paths, CRD/DTR/PAS, 360X receiver, ODE referral validation feedback, SMART/OAuth, the milestone H spike, and the UC07 test data.
