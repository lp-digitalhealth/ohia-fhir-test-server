# Building the OHIA facade, step by step

This guide shows how the Java facade in `facade/` was built, in the order it was built, so that others can repeat it or adapt it to another interaction. Each step says what to write, why, and how to check it. Everything stated as "checked" was actually run; what was not run is listed in section 7.

**What you end up with:** a small Java 21 / Spring Boot service that runs beside HAPI and
1. plays the **sender** of the UC07 referral (reads the seeded package back out of HAPI and POSTs a per-send copy to a registered tester), and
2. is a minimal **ARex content server** that serves the referral's images behind expiring, manifest-scoped tokens.

Caddy routes only `/ode/*` and `/arex/*` to it. `/fhir` never passes through it.

Commands assume Linux or macOS (on Windows use WSL). Paths are relative to the repository root.

---

## 1. Before you write any code

### 1.1 Find out what is actually being asked
The facade exists because the connectathon testers were all playing the **receiver** of the UC07 referral and nobody was the **sender**. Before designing anything, read:

| Source | What it told us |
|---|---|
| `docs/OHIA_FHIR_Test_server_PLAN.md` | the decisions already made (F-, O- and S-series), the architecture, what is out of scope |
| the data repo `ohia-test-data` (UC07 folder, `CLAUDE.md`) | the UC07 interaction-1 package already exists as one 37-entry transaction Bundle of idempotent `PUT`s, with real digests over two placeholder PNGs; no loader or validator is committed; logical ids are reused across scenarios |
| the `ode-360x-adapter` repo | its sender is for **360X** (HL7 v2 / C-CDA over Direct), so it was **not** reusable for a straight ODE request |
| the ODE implementation guide (draft 0.1.0) | the initiator and recipient CapabilityStatements, but **no UC07 page** and no exact Bundle shape |
| the ARex specification (v0.2.0 draft, `http://fhir.onyxhealth.io/us/arex/`) | retrieval **requires** OAuth tokens that expire (see 1.2) |

**Lesson:** read the specification before deciding. We first assumed ARex retrieval authorization was undecided; the owner pointed out the spec has an expiring-token requirement, and reading the Security page confirmed it. That changed the scope from "serve some images" to "build a small OAuth server".

### 1.2 What the ARex spec requires (statement ids are the spec's own)
Read through a summarizing fetch tool, and the security page came back partly garbled, so **re-check the exact wording** before relying on it.

- **arex-19:** SMART Backend Services, `client_credentials`, asymmetric JWT client authentication.
- **arex-57:** manifest retrieval needs an OAuth token; anonymous retrieval is never allowed.
- **arex-23 and arex-24:** a token for one manifest must not open another; manifest-scoped grants should use RFC 9396 `authorization_details`.
- **arex-25 and arex-58:** the authenticated client must match the manifest's `requestingOrganization`.
- **arex-60:** a failed entitlement check returns **404, not 403**.
- **arex-26, arex-30:** the manifest declares an expiry; retrieval after it is refused whatever the token says.
- **arex-14, arex-15:** a manifest can be retracted, which ends outstanding grants.
- **arex-4, arex-7, arex-9, arex-12:** absolute URLs only; native octets via `Accept`; verify the digest; support HTTP `Range`.

The spec does **not** give a token lifetime, a header name, a registration mechanism, or a value for the `authorization_details` type. Those are our choices (below).

### 1.3 Decisions (write them down first)
These were made by the owner or made explicit and approved before coding.

| Decision | Choice | Why |
|---|---|---|
| Language | Java 21, Spring Boot 3.5 | owner (closes open item O1) |
| Placement | **beside HAPI**; Caddy routes only `/ode/*` and `/arex/*` | a facade bug cannot break or open the live `/fhir` API |
| Source of the referral | seed the package into HAPI, then **read it back from HAPI** | matches "read from the local FHIR repo" and exercises the real server |
| Wire format | one transaction Bundle POSTed to the tester's FHIR base URL | inferred from the ODE IG initiator capability statement (not spelled out there) |
| 360X | not used | UC07 is a straight ODE request |
| Image access | ARex-style expiring, manifest-scoped tokens | the spec requires it |
| Our own choices | access token life 300 s; manifest life 30 days; `authorization_details` type `arex-manifest`; opaque in-memory tokens; static tester registry file | the spec leaves these open |
| JSON handling | plain Jackson, not the full HAPI model library | lighter; add HAPI later if validation is needed |
| IDs per send | rename only the 22 interaction resources; keep the 15 stable ones | each send is a separate referral, but we do not duplicate organizations or the patient on the tester's side |

### 1.4 Prerequisites
- JDK 21 and Maven 3.9 (`java -version`, `mvn -v`).
- Docker, for the routing test and the end-to-end run.
- Python 3 with `PyJWT` and `cryptography` (only for the end-to-end tester and the retrieval examples).
- A checkout of this repository on the branch you were given. Do not commit to `main`.

---

## 2. The build, step by step

### Step 1: project skeleton and the token lock
**Goal:** a service that starts, answers a health check, and refuses every `/ode/**` request without the shared token.

Create `facade/pom.xml` (Spring Boot parent 3.5.16, `java.version` 21, `spring-boot-starter-web`, `spring-boot-starter-test` for tests). Then:

- `FacadeApplication` with `@SpringBootApplication` and `@ConfigurationPropertiesScan`.
- `config/FacadeProperties`: a record bound to the `facade.*` settings in `src/main/resources/application.yml`, each with an environment-variable override (`FACADE_HAPI_BASE_URL`, `FACADE_PUBLIC_BASE_URL`, `API_TOKEN`, `FACADE_DATA_DIR`, ...).
- `web/OdeTokenFilter`: a servlet filter for `/ode/**` that compares `Authorization: Bearer <token>` with `MessageDigest.isEqual` (constant time) and **fails closed**: if no token is configured, everything under `/ode/` is refused.
- `web/HealthController`: `/internal/health` (used by the container health check; Caddy does not route it) and `/ode/health`.

**Check:**
```bash
cd facade && mvn -q test
```
8 tests pass (6 for the token lock, 2 for fail-closed). The fail-closed test is the control: with a blank configured token even `Bearer ` must be refused.

### Step 2: container, compose and Caddy routes
**Goal:** run the facade next to HAPI and route to it without touching `/fhir`.

- `facade/Dockerfile`: multi-stage (Maven + Temurin 21 to build, `eclipse-temurin:21-jre` to run), non-root user, `/data` volume, a `HEALTHCHECK` on `/internal/health`.
- `deploy/docker-compose.yml`: add a `facade` service with **no published ports**, the `facade_data` volume, `../facade/config:/config:ro` (the tester registry folder), and `API_TOKEN: ${API_TOKEN:?...}` so it will not start without the token. **Caddy does not `depends_on` it**, so a facade failure cannot stop `/fhir`.
- `deploy/Caddyfile`: add the two facade routes **before** the existing `@authed` handler. The order is the whole point: `@authed` matches any path with the token, so if it came first, `/ode/*` would be sent to HAPI.

```caddy
@arex path /arex /arex/*
@ode path /ode /ode/*

handle @arex {                    # ARex uses its own tokens, not the shared one
    reverse_proxy facade:8080
}
handle @ode {                     # organizer endpoints: shared token here, and again in the facade
    handle @authed {
        reverse_proxy facade:8080
    }
    handle {
        header WWW-Authenticate Bearer
        respond "Unauthorized: send Authorization: Bearer <token>" 401
    }
}
# ... the existing /fhir handlers follow, unchanged
```

**Check (routing, in a throwaway Docker network):** run three containers on one network: a stand-in HAPI (a second Caddy answering `STUB-HAPI <method> <path>`), the facade, and Caddy with your Caddyfile. Then confirm:

| Request | Expected |
|---|---|
| `/fhir/metadata`, no token | 200, answered by the HAPI stand-in |
| `/fhir/Patient`, no token / with token / `POST` no token | 401 / 200 (HAPI stand-in, not the facade) / 401 |
| `/ode/package`, no token | 401 (Caddy) |
| `/ode/package`, token | reaches the facade |
| `/arex/.well-known/smart-configuration`, no token | 200 (facade) |
| `/arex/List/x` with the **shared** token | 401 (the shared token does not open ARex) |

Also run `caddy validate` on the Caddyfile and `docker compose config -q` (with `POSTGRES_PASSWORD` and `API_TOKEN` set) on the compose file. Both passed.

### Step 3: seed the package into HAPI
**Goal:** `POST /ode/admin/seed` loads the UC07 interaction-1 package into HAPI from the data repository, safely.

Classes: `seed/SeedService`, `seed/RemoteFetcher`, `seed/PackageStore`, `seed/PackageIndex`, `hapi/HapiClient`.

Rules, in this order (the order matters):
1. Fetch `interaction-01-bundle.json` and the two PNGs from the data repo at a **pinned commit** (`facade.seed.data-ref`, never a branch name, so the data cannot change underneath you).
2. Validate the Bundle: type `transaction`, and every entry a `PUT` of its own `Type/id`.
3. Find the ARex manifest `List` and the content `DocumentReference`s by their profile.
4. For each content entry, compute the SHA-256 and the size of the matching PNG and compare with the entry's declared `arex-digest` and `size`. **Any mismatch stops the whole seed.**
5. Only now write: POST the **authored bundle, byte for byte**, to HAPI; save the PNGs and a package index (type, id, fullUrl for each resource) under `facade.data-dir`.

Why verify first: a tampered or stale source must change nothing.

**Check:** `SeedAndReadBackTest` runs the real code against two in-JVM stand-ins (a data host and a HAPI). Control: with one PNG altered by one bit, the seed returns 422 and **nothing** reaches HAPI or the disk.

### Step 4: read the package back out of HAPI
**Goal:** the sender must send what HAPI holds, not what was seeded from files.

`seed/PackageReader` walks the package index, reads each `Type/id` from HAPI, drops the `versionId` and `lastUpdated` that HAPI adds, and assembles a transaction Bundle. A resource missing from HAPI is an error (502), never a partial referral; an unseeded package is 409.

**Check:** the tests include a control that deletes one resource from the HAPI stand-in and expects a failure that names it. Against **real** HAPI 8.12.0 the read-back of all 37 resources had 0 differences from the authored bundle.

### Step 5: the per-send copy
**Goal:** every send is a separate referral that a tester can receive, and re-receive, without colliding with an earlier one.

`send/PerSendCopier` takes the read-back Bundle and returns a modified deep copy:
- **Ids:** the 22 interaction-scoped resources (types `Encounter, Condition, Observation, Procedure, ImagingStudy, DiagnosticReport, DocumentReference, ServiceRequest, Task, Communication, Provenance, List`) get `-<send id>` appended. The 15 stable ones (organizations, practitioners, locations, endpoints, patient, coverage, related person, roles) keep their authored ids.
- **References:** every `reference` that points at a renamed resource is rewritten; every `request.url` is updated; renamed entries get a new `urn:uuid` fullUrl.
- **Referral id:** every occurrence of the authored referral id (identifiers and text) becomes `REF-2026-UC07-001-<send id>`.
- **ARex:** content URLs become `<public base>/arex/Binary/<random opaque id>` (no referral id in the URL); the manifest gets a fresh `arex-expires` (30 days; the authored one was already in the past), a new manifest identifier, and the registered tester's organization as `requestingOrganization`.
- **Refusal:** if that organization is not an `Organization` in the package, the copy is refused, because the sent manifest would point at nothing.

**Check:** `PerSendCopierTest` (8 tests) asserts exactly 22 renamed and 15 kept, no dangling references, no bare original referral id left, the input unchanged, and the refusal.

### Step 6: the tester registry
**Goal:** know who may receive, where, and with which key.

`clients/ClientRegistry` reads a JSON file (`facade.clients-file`, default `/config/clients.json`, git-ignored; the format is in `facade/config/clients.example.json`). Per tester: `clientId`, `requestingOrganization`, `allowedTargetPrefixes` and a public JWK set. It re-reads the file when its modification time changes, so adding a tester needs no restart. A missing file means nobody is registered; a malformed file is refused **as a whole**, never half applied. `http://` prefixes are accepted only when the test-only `allow-http` flag is on.

**Check:** `ClientRegistryTest` (5 tests), including reload-on-change and refusal of bad files.

### Step 7: the SSRF guard
**Goal:** the sender must not be usable to reach the server's own network.

`send/SsrfGuard` accepts a target only if it is `https`, has no credentials in the URL, and **every** address its host resolves to is public. Refused: loopback, private ranges, link-local (including the cloud metadata address), carrier-grade NAT, multicast, unspecified, reserved, and IPv6 loopback, unique-local and link-local. The compose network names (`hapi`, `facade`, ...) resolve to private addresses and are therefore refused. Two settings, `facade.send.allow-http` and `facade.send.allow-private-targets`, exist **for tests only** and default to false.

A second control comes first: the target must be on the tester's own allow-list of URL prefixes. Redirects are never followed.

Known limit: the address is checked and the connection happens afterwards, so DNS rebinding is not closed. The allow-list is the stronger control.

**Check:** `SsrfGuardTest` runs 28 cases (each blocked range, wrong schemes, credentials, malformed URLs, the public addresses it must allow, and the test-only flags). `SendRefusalsTest` runs with the **production defaults** and shows that even an allow-listed `https://localhost` is refused.

### Step 8: the sender
**Goal:** `POST /ode/send` delivers the referral to a registered tester and records the result.

`send/SendService` does these in order, so that every refusal happens before anything is written:
1. require `clientId` and `targetBaseUrl`; look up the client; check the target against its allow-list; run the SSRF guard;
2. read the package from HAPI (step 4);
3. make the per-send copy (step 5);
4. **save the manifest record first** (`send/ManifestStore`: manifest, tester, organization, expiry, entries) so a tester who fetches an image the instant the referral arrives is already covered;
5. POST the Bundle (`send/OutboundClient`: no redirects, 5 s connect and 60 s read timeouts, 1 MB response cap, optional tester bearer token used once);
6. append the outcome to the delivery log (`send/DeliveryLog`, JSON lines on the data volume). Outcomes: `delivered` (2xx), `rejected-by-target`, `unreachable`. A target that refuses is a **result**, so the API still answers 200.

The tester's own bearer token is never stored or logged.

**Check:** `SendEndToEndTest` (11 tests) against a receiver stand-in: the receiver gets exactly one POST with its token; the Bundle has 37 entries and no authored example.org URLs; two sends differ; what is sent comes from HAPI (a marker added to HAPI's copy shows up); unknown client, wrong prefix, unseeded package, bad organization and missing token are all refused with the receiver hearing nothing; and the data directory is scanned to prove the tester's token is nowhere on disk.

### Step 9: the ARex token endpoint
**Goal:** SMART Backend Services for retrieval: prove who the tester is, then issue a token for one manifest.

Add the Nimbus library (`com.nimbusds:nimbus-jose-jwt`). Classes in `arex/`:
- **`ClientAssertionVerifier`**: accepts only `RS384` or `ES384`; `iss` and `sub` must be the same registered client id; the signature must verify with a key from that client's JWK set (RSA keys of at least 2048 bits); `aud` must be exactly the token URL; `exp` must be in the future and at most 5 minutes ahead (30 s skew); `jti` must be present and **unused** (remembered until it expires). **Every failure returns the same `invalid_client`**, so a caller learns nothing about which check failed.
- **`ArexTokenService`**: requires `grant_type=client_credentials`; scopes only `system/List.rs` and `system/Binary.r`; **requires** `authorization_details` with exactly one `{"type":"arex-manifest","manifest":"<id>"}` (the spec says "should"; we made it required).
- **`ArexAccess`**: the entitlement check, used at issue **and** on every retrieval: the manifest exists, was published for this client, the client's registered organization equals the manifest's requesting organization, it has not expired (checked at use, so an unexpired token cannot outlive its manifest), and it is not retracted. Anything else gives the same `invalid_grant`.
- **`AccessTokenStore`**: opaque random 256-bit tokens, stored as hashes in memory, valid 300 seconds, each tied to one client and one manifest.

**Check:** the token tests in `ArexTest`: RS384 and ES384 succeed; a key with the same `kid` but different material is refused; `HS256` and unsigned (`none`) are refused; seven different assertion defects all give an **identical** response body; a replayed `jti` is refused; a missing or wrong `authorization_details` is refused; and "someone else's", "unknown", "expired" and "retracted" manifests all give the same `invalid_grant`.

### Step 10: the ARex content endpoints
**Goal:** serve the manifest and its images, and refuse correctly.

`arex/ArexController`:
- `GET /arex/.well-known/smart-configuration`: discovery (`token_endpoint`, `client_credentials`, `private_key_jwt`, `RS384`/`ES384`, scopes, the `arex-manifest` details type).
- `POST /arex/auth/token`: form-encoded; the answer carries `Cache-Control: no-store`.
- `GET /arex/List/{id}`, `GET /arex/List?arex-manifest-transaction-identifier=...`, `GET /arex/Binary/{id}`.

Status rules: **no, unknown or expired token: 401** with a `Bearer` challenge. **A valid token that is not entitled: 404, never 403** (arex-60). A token only ever opens its own manifest and that manifest's content; asking for any other id looks exactly like asking for one that does not exist. `Binary` returns native octets (`Accept: image/png`) or a FHIR `Binary` (`Accept: application/fhir+json`), supports `Range` (206, and 416 for an unsatisfiable range), and **recomputes the SHA-256 of what it is about to serve and refuses (500) if it differs from the published digest**.

**Check:** the retrieval tests in `ArexTest`, including: the shared organizer token does **not** open ARex and an ARex token does not open `/ode/**`; byte-for-byte equality with the source image; ranges `0-9`, `N-`, `-5`, out-of-range and garbage; a token for manifest A gets 404 on manifest B's content; changing the registered organization after a token was issued ends access; search by transaction identifier only ever finds the token's own manifest.

### Step 11: retraction
`web/ManifestAdminController`: `GET /ode/manifests/{id}` (summary) and `POST /ode/manifests/{id}/retract`, both organizer-only. Retraction sets the manifest `status` to `retracted` and the stored flag; from that moment no token can retrieve it and no new token can be issued, whatever time is left on the manifest or on any token already issued.

### Step 12: tests that can fail, and proof that they can
Shared test helpers (all in `facade/src/test`):
- `UcStubs`: an in-JVM data host that serves the **real** UC07 files (copied into `src/test/resources/uc07-i1/`, see the README there) and an in-JVM HAPI that records posts and serves them back with HAPI-style meta.
- `ReceiverStub`: a tester's endpoint that records requests and answers a configurable status.
- `ArexTest.MutableClock`: a clock the test can move, so expiry rules are tested exactly (for example: a valid 5-minute token, a 60-second manifest, advance 61 seconds, expect 404).

Test classes and counts: `ArexTest` 24, `SsrfGuardTest` 28, `SendEndToEndTest` 11, `PerSendCopierTest` 8, `SeedAndReadBackTest` 7, `OdeTokenFilterTest` 6, `ClientRegistryTest` 5, `SendRefusalsTest` 3, `OdeTokenFilterFailClosedTest` 2: **94 in total**.

**Mutation check (do this for every security rule):** break the rule in the source, run the one test class, and confirm a test fails; then restore the source.
```bash
cp src/main/java/org/ohia/facade/arex/ArexAccess.java /tmp/ArexAccess.bak
sed -i 's/!clock.instant().isBefore(m.expiresAt())/false/' src/main/java/org/ohia/facade/arex/ArexAccess.java   # ignore manifest expiry
mvn -q -Dtest=ArexTest test        # must FAIL
cp /tmp/ArexAccess.bak src/main/java/org/ohia/facade/arex/ArexAccess.java
mvn -q -Dtest=ArexTest test        # must pass again
```
We did this for three rules: ignoring manifest expiry (2 tests failed), allowing assertion replay (1 failed) and letting a token open any manifest (1 failed). Passing on the first run proves little until you have seen the test fail.

### Step 13: the end-to-end run against the real thing
Stand-ins prove your code, not your assumptions. Run the whole flow against a **real HAPI** and the **real data repository at the pinned commit**, with a stand-in tester written independently (Python and PyJWT, a different JWT library than the facade's Nimbus). The commands are in `facade/e2e/README.md`; the checker is `facade/e2e/run_e2e.py`. It makes 21 checks, from "seed loads 37 resources into real HAPI" through "token issued", "replay refused", "images match their digests", "retraction ends access" to "the delivery log never contains the tester's token". All 21 passed.

### Step 14: deploy
Follow `deploy/README.md` sections 5, 7 and 8: `git pull`, `docker compose build facade`, `docker compose up -d`, register a tester in `facade/config/clients.json`, seed, send. **Build before starting**, so a build problem cannot interrupt the running stack.

---

## 3. How a tester retrieves the documents
That is a separate guide: `docs/AREX_RETRIEVAL_GUIDE.md` (with a tested script, `docs/examples/arex_fetch.py`). The short tester instructions are also in `docs/CONSUMER_GUIDE.md`.

---

## 4. Pitfalls we hit (so you do not)

| What happened (or the trap) | Cause | Fix |
|---|---|---|
| `POST /ode/admin/seed` returned 404 in the first end-to-end run | the running jar was built **before** the seed code existed; `mvn test` does not rebuild the jar | run `mvn -q package -DskipTests` before running the jar. Note `mvn clean` **deletes** the jar. |
| `docker build` of the facade failed with a PKIX certificate error in the sandbox | the sandbox's TLS-intercepting proxy is not trusted by Maven inside the build container | do **not** disable TLS. Build the jar on the host and package it into a runtime-only image to test everything else. The real multi-stage `Dockerfile` could therefore not be built in the sandbox; the owner built it on the Arm VM (no proxy) and it worked. |
| the container showed `Up ... (unhealthy)` on the VM although the app was fine | the `HEALTHCHECK` grepped for the text `200 OK`, but Tomcat sends `HTTP/1.1 200` with no reason phrase, so the probe failed every time. It was never run before shipping because the sandbox test image had no health check | match the status code (`^HTTP/1\.[01] 200`). **Run the probe before you ship it:** `docker run --health-cmd '<the exact command>' ...` shows Docker's verdict without a build. Also test that it **fails** for a 404 and for a closed port. |
| every image request would have been refused | the authored manifest's `arex-expires` was **already in the past** (2026-07-22) | the per-send copy sets a fresh expiry |
| a unit test showed a dangling `Organization/...` reference | the registered tester's organization was not an organization in the package | refuse the send up front (step 5) and test the refusal |
| a refusal test got the wrong error message | the allow-list prefix `https://localhost` does not cover `https://localhost:9`, so the allow-list refused first | add the exact prefixes, so the address check is what refuses |
| a trap we avoided by design (not hit): `/ode/*` requests would have gone to HAPI | `@authed` matches any path, so it must come **after** the facade routes in the Caddyfile | order the handlers as in step 2, and keep the routing test |
| a log search returned "binary file matches" and hid a result | a stray control character in Maven output | use `grep -a` on build output |
| a snippet crashed with `KeyError: 'meta'` | some `DocumentReference`s (the notes) have no `meta` | always use `.get("meta", {})` when testing for a profile |
| the ARex security page read through a summarizer was partly garbled | the summarizer, not the spec | re-check the numbered statements against the spec source before depending on them |
| two parallel chats both numbered a decision "F30" | the plan's decision table is edited from more than one place | decisions about setup, hosting and auth use their own **S-series** (S1 to S5); business decisions stay F/O. Prefix S, not A, because `A1`/`A2` already name the Oracle Arm VM shapes. |

---

## 5. Adapting this to something else
- **Send a different interaction (for example the post-treatment report):** seed that interaction's package (a second index file), decide which resource types are interaction-scoped for it (`PerSendCopier.INTERACTION_TYPES`), and decide who plays which side. The facade currently sends interaction 1 only.
- **Different data:** change `facade.seed.data-ref` (a commit), `facade.seed.package-path`, and re-copy the fixtures from that commit into `src/test/resources/uc07-i1/` (the tests assert 37 resources, 22 and 15, and two images).
- **Another tester:** add an entry to `facade/config/clients.json`; no restart.
- **Another wire format:** change only `SendService` and `OutboundClient`; the copy, the guards and ARex are independent of it.
- **Real `/fhir` OAuth later (plan item S2):** the ARex token pieces (`ClientAssertionVerifier`, `AccessTokenStore`, discovery) are the starting point for a general token issuer.

---

## 6. File map

| Path | Role |
|---|---|
| `facade/pom.xml`, `facade/Dockerfile`, `facade/.dockerignore` | build and image |
| `facade/src/main/resources/application.yml` | settings and defaults |
| `config/` (`FacadeProperties`, `ClockConfig`) | settings record, clock bean |
| `web/` (`OdeTokenFilter`, `HealthController`, `OdeController`, `SendController`, `ManifestAdminController`, `ApiExceptionHandler`) | organizer endpoints, errors |
| `seed/` (`SeedService`, `RemoteFetcher`, `PackageStore`, `PackageIndex`, `PackageReader`) | seed and read-back |
| `hapi/HapiClient` | the facade's own HAPI access |
| `send/` (`PerSendCopier`, `SsrfGuard`, `OutboundClient`, `SendService`, `ManifestStore`, `ManifestRecord`, `DeliveryLog`, `SendRecord`) | sender |
| `clients/` (`ClientRegistry`, `ClientEntry`) | tester registry |
| `arex/` (`ClientAssertionVerifier`, `ArexTokenService`, `AccessTokenStore`, `ArexAccess`, `ArexController`, `OAuthException`) | ARex auth and content |
| `common/` | exceptions |
| `facade/config/clients.example.json` | registry format |
| `facade/e2e/` | end-to-end run |
| `docs/examples/arex_fetch.py` | tester-side retrieval script |
| `deploy/Caddyfile`, `deploy/docker-compose.yml` | routing and the service |

(The source paths under `facade/src/main/java/org/ohia/facade/` are listed by package.)

---

## 7. What is and is not verified

**Verified (run, 2026-11-05):**
- 94 automated tests pass; three security rules were mutation-checked.
- A 21-check end-to-end run passed against real HAPI 8.12.0 and the real data repository at the pinned commit, from the repository copy of the checker.
- Caddy routing in front of the facade (13 checks with the Phase 1 build, 11 with the final build); `/fhir` behaved exactly as on the live server.
- The retrieval script and the curl flow in `docs/AREX_RETRIEVAL_GUIDE.md`, including a tampered digest, a refused key, a retracted manifest and the token-expiry retry.

**Not verified:**
- The **fixed** `facade/Dockerfile` health probe on the VM (the original multi-stage build did build and run there; the probe bug is described in section 4; the fix was tested with the equivalent run-time probe in the sandbox, not through a build, because Docker Hub rate-limited the sandbox).
- The facade behind the live Caddy and certificate, and any real tester's receiver.
- The Bundle shape. It is an inference from the draft ODE IG, which has no UC07 page.
- ARex conformance beyond the statements listed in 1.2, and the exact spec wording (see 1.2).
- ODE profile conformance (the profiles are unbuilt).
- DNS rebinding, load, and browser CORS behavior.

---

## 8. Extension: receiving UC04a and pulling its images (the facade as an ARex client)

This extends the build so the server also plays the **receiver**. Decisions are plan items S6 and S7; the tester-facing guide is `docs/UC04A_SENDING_GUIDE.md`. Build it in this order, testing each step before the next:

1. **Identity (`keys/`).** `ServerKeyStore` creates an RSA 2048 key on first use (kid `ohia-server-1`, written atomically, owner-only) and refuses a bad key file rather than replacing it. `ClientAssertionSigner` signs RS384 assertions. Publish the public half at `GET /arex/jwks.json`. Test: sign with the signer, verify with the facade's own verifier.
2. **The client (`arexclient/`).** `ArexClient.pull` does discovery, token, published-manifest check, then downloads. Treat every URL from the payload as hostile: SSRF guard, same origin as the sender's ARex base, no redirects, size caps, save only digest-verified bytes. Build a stand-in sender first with a switch for each failure (tampered bytes, retracted manifest, refused token, expired token).
3. **Receiving (`receive/`, `web/ReceiveController`).** Validate, store the Bundle in HAPI exactly as received, pull, write a receipt (`receipt.json` plus images) atomically. A gap the spec leaves open is a **finding**, not a refusal.
4. **Viewer (`static/viewer/`, `ViewerSecurityFilter`).** A static page; `textContent` only; strict CSP; images from `blob:` URLs and only if the bytes sniff as JPEG or PNG. Add an open Caddy route for `/viewer/*` **before** the token handler.
5. **Verify independently.** `facade/e2e/run_uc04a_e2e.py` uses a Python sender that verifies our assertion with PyJWT and the key it fetches from our JWKS; `facade/e2e/viewer_check.js` drives real Chromium, including a hostile receipt.

Lessons from this build, worth repeating: (a) real HAPI refused the authored Subscription (HAPI-0011), which no stand-in HAPI would have shown; (b) a stale pooled connection made 1 in 8 end-to-end runs fail on the second token request, found only by running the real flow repeatedly (fixed with one retry using a fresh assertion; 20 of 20 runs then passed); (c) a browser check that picked its row by text opened the wrong receipt, so it now picks by API data.

**Verified (sandbox, 2026-11-05):** 161 tests; the end-to-end run (21 checks, three payload variants); the viewer check (18 checks). **Not verified:** the live VM, `/viewer/` and `/arex/jwks.json` through the live Caddy, any real tester's ARex server. **Not implemented:** manifest signature verification, download resume.
