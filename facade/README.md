# OHIA facade (Java)

A small Java service that runs **beside** HAPI. It lets the test server play the **sender** of the UC07 referral and act as a minimal **ARex content server**, because the testers are all receivers.

- Caddy routes only `/ode/*` and `/arex/*` here. `/fhir` never passes through it, so a facade problem cannot break or open the FHIR API.
- Java 21, Spring Boot 3.5, JSON handled with Jackson, JWTs with Nimbus. Plan decisions: S3, S4, S5 in `docs/OHIA_FHIR_Test_server_PLAN.md`.

How it was built, step by step (for anyone who wants to repeat or adapt it): `docs/FACADE_BUILD_GUIDE.md`. How a tester retrieves the images: `docs/AREX_RETRIEVAL_GUIDE.md`. An end-to-end run against real HAPI: `e2e/README.md`.

## What it does

| Path | Who may call | What |
|---|---|---|
| `POST /ode/admin/seed` | organizer (shared token) | Loads the UC07 interaction-1 package into HAPI from the data repo at a **pinned commit**. Verifies both ARex image digests first; changes nothing if anything is wrong. Idempotent. |
| `GET /ode/package`, `GET /ode/package/bundle` | organizer | What is seeded; the package read back from HAPI. |
| `POST /ode/send` | organizer | Sends the referral to a registered tester: one transaction Bundle POSTed to the tester's FHIR base URL. Body: `{"clientId", "targetBaseUrl", "targetBearerToken"?}`. |
| `GET /ode/sends`, `GET /ode/sends/{id}` | organizer | Delivery log. |
| `GET /ode/manifests/{id}`, `POST /ode/manifests/{id}/retract` | organizer | Inspect, or withdraw, a sent ARex manifest. |
| `GET /arex/.well-known/smart-configuration` | anyone | SMART discovery. |
| `POST /arex/auth/token` | registered tester | SMART Backend Services token: `client_credentials`, `private_key_jwt`, manifest-scoped. |
| `GET /arex/List/{id}`, `GET /arex/List?arex-manifest-transaction-identifier=`, `GET /arex/Binary/{id}` | tester with a token | The manifest and its content objects (native octets or FHIR `Binary`; `Range` supported). |
| `GET /fhir/ExplanationOfBenefit/{id}`, `GET /fhir/ExplanationOfBenefit?patient=&identifier=&status=&_id=&_count=` | shared token | Read and search of the five oral claims-sharing EOBs (see below). Read-only. |
| `POST /ode/admin/seed-eob` | organizer | Loads those five EOBs from the data repo at a **pinned commit**; checks all five first and changes nothing if any fails. Idempotent. |

"Organizer" means the shared `API_TOKEN` (decision S1), checked by Caddy and again inside the facade. The `/arex` paths do **not** accept it; they use the tokens issued by the token endpoint.

## What each send does

Every send is a separate referral. The facade reads the seeded package **back out of HAPI**, makes a copy for this send, and posts it:

- The 22 interaction resources (referral, task, imaging, notes, communication, provenance, ARex manifest and entries) get new ids ending in a short send id, and the referral id becomes `REF-2026-UC07-001-<send id>`. The 15 stable resources (organizations, practitioners, patient, coverage, ...) keep their authored ids.
- ARex content URLs are rewritten to this server (`<PUBLIC_BASE_URL>/arex/Binary/<opaque id>`), and the manifest gets a fresh expiry (default 30 days; the authored one was already in the past).
- The manifest is published for **one** registered tester only. Its requesting organization must be an Organization in the package, or the send is refused.
- The target must be on that tester's allow-list **and** resolve to a public address (loopback, private, link-local and similar ranges are refused). Redirects are not followed.

## ARex rules it enforces (from the ARex v0.2.0 draft, statement ids in brackets)

- Token request needs a signed client assertion: RS384 or ES384 only, issuer = subject = registered client id, audience = the token URL, expiry at most 5 minutes ahead, one-time `jti` [arex-19]. Every failure gives the same `invalid_client`.
- The grant is scoped to one manifest through RFC 9396 `authorization_details`. The `type` value `arex-manifest` is **this server's own choice**; the spec page read did not name one.
- The registered organization must match the manifest's requesting organization, at issue and on every retrieval [arex-25].
- No token or a bad token: 401. A valid token that is not entitled (other manifest, other organization, expired, retracted): **404, never 403** [arex-60].
- After the manifest's expiry or retraction, retrieval is refused whatever the token says [arex-26, arex-14, arex-15].
- A token opens only its own manifest and that manifest's content [arex-23]. The access token lasts 300 seconds (SMART's recommendation; the spec gives none).
- Each object's digest is checked against the published SHA-256 before it is served.

## Registering a tester

Copy `config/clients.example.json` to `config/clients.json` (that file is git-ignored) and add the tester's `clientId`, the `requestingOrganization` they play (for UC07: `Organization/org-restorative-dental-tx`), their `allowedTargetPrefixes` (https URLs), and their public key as a JWK set. The file is re-read when it changes; no restart needed. A malformed file is refused as a whole.

## Configuration

Environment variables (see `src/main/resources/application.yml`): `API_TOKEN`, `FACADE_HAPI_BASE_URL`, `FACADE_PUBLIC_BASE_URL` (the public https base, no path), `FACADE_DATA_DIR`, `FACADE_CLIENTS_FILE`, `FACADE_SEED_DATA_REF` (a pinned commit), `FACADE_MANIFEST_TTL_DAYS`.

`facade.send.allow-http` and `facade.send.allow-private-targets` exist for automated tests only. Leave both `false`.

## Build and test

```bash
cd facade
mvn test          # 223 tests, no network or Docker needed
mvn package       # builds target/ohia-facade-0.1.0.jar
```

Docker (as the compose file does it): `docker compose build facade`.

## UC04a receiver and viewer (S6, S7)

`POST /ode/receive/uc04a` stores a UC04a interaction 1 Bundle in HAPI and pulls the manifest's images from the sender with ARex, then answers with a receipt. `GET /ode/receipts`, `/ode/receipts/{id}` and `/ode/receipts/{id}/images/{fileKey}` read results; `GET /ode/identity` and the open `GET /arex/jwks.json` publish our client id and public key; `/viewer/` is a static page that shows receipts and images. Report-first: gaps are named findings, a retracted manifest or refused token stops the pull. Not implemented: manifest signature verification, download resume. Full detail: `docs/UC04A_SENDING_GUIDE.md`. Settings: `facade.client.*` in `application.yml`. Independent checks: `e2e/run_uc04a_e2e.py`, `e2e/viewer_check.js`.

## Sending UC04a, receiving UC07, and the page (S8 to S10)

- **Send UC04a interaction 1:** `POST /ode/admin/seed?useCase=uc04a` once, then `POST /ode/send` with `"useCase":"uc04a"` (UC07 is the default when none is named). Our ARex server hosts the two photos. A tester that plays a different organization for UC04a needs `organizationsByUseCase` in `config/clients.json` (see `clients.example.json`); without it the send is refused with a plain message.
- **Receive UC07:** `POST /ode/receive/uc07`. `GET /ode/receipts?useCase=uc07` filters. `GET /ode/clients` lists the registered testers for the send form (never their keys).
- **The page (`/viewer/`):** tabs for Received (with a use-case filter), Send a referral and Sent.
- **Image viewer:** every image on the page (received, about to be sent, or carried by a past send) opens in one viewer with its name, type, size and SHA-256, next and previous (buttons or arrow keys), "Actual size", and Esc to close. Sent images come from `GET /ode/package/images/{useCase}/{entryId}` and `GET /ode/sends/{sendId}/images/{entryId}`; a send's manifest (`GET /ode/manifests/{id}`) now lists its entries. Bytes are shown inline only if they really are a JPEG or PNG; anything else is a download.
- **CapabilityStatement:** `GET /fhir/metadata` (open) returns the facade's statement of the capability this test server is going for, from `src/main/resources/capability/capability-statement.json` (edit it as data). It is draft and experimental and says what is implemented today and what is only the target. Caddy falls back to HAPI's own statement if the facade is down.
- **Several manifests, scope, absolute references:** a received payload may carry several ARex manifests; each is pulled with its own token (`scope` defaults to `system/List.r system/DocumentReference.r system/Provenance.r system/Binary.r`, override with `facade.arex.scope` / `FACADE_AREX_SCOPE`). References to the requesting organization and to the manifest a Provenance targets are compared by type and id, so relative and absolute forms match. Non-image files are offered as downloads on the page.
- **Known limit:** received payloads and the data we send from share one HAPI. A tester who sends us the sample referral under the same resource ids overwrites our copy of it. Use "Load the data" (or `POST /ode/admin/seed?useCase=...`) before sending to restore it.

## Oral claims-sharing EOBs (read and search)

`GET /fhir/ExplanationOfBenefit` serves the five oral claims-sharing `ExplanationOfBenefit` resources of the data repository (UC02a, UC02b, UC03 and the two of UC04a, all interaction 5), which declare the profile address `https://oralhealthalliance.net/fhir/StructureDefinition/ode-dental-claim` (the ODE oral profile, derived from CARIN Blue Button's Professional profile).

- **Read-only.** Read by id and search by `patient` (a reference, a bare id, or a full URL), `identifier` (`system|value`, `|value`, `system|` or `value`), `status`, `_id` and `_count` (0 to 100). A comma means OR; a repeated parameter means AND. Any other parameter is refused with 400 and an OperationOutcome, so a client is never told "no results" for a question we did not understand. POST, PUT and DELETE are refused with 405.
- **Not in HAPI.** The facade keeps the set in `facade_data` (`eob/oral-eobs.json`) and serves it itself. Caddy sends this path to the facade instead of HAPI (OPTIONS still goes to HAPI), and the shared token is required in Caddy and again in the facade (`OdeTokenFilter`).
- **Loading.** `POST /ode/admin/seed-eob` fetches the five files at a pinned commit (`facade.eob.data-ref`) and checks each one (an ExplanationOfBenefit, a safe id, the profile declared, claim type `oral`, a `Patient/<id>` reference, no duplicate id) before it stores anything. Until it has run, a read is 404 and a search is an empty Bundle that says nothing is loaded.
- **Not validated.** No published implementation guide defines the profile: the ODE IG removed its EOB profile on purpose and plans a `Claim`-based submission instead, while the data still declares this one. So the checks above are consistency checks on the data, not profile validation, and the facade publishes no StructureDefinition. The capability statement says so.
- The five resources reference a Patient, Organization and other resources that are not served here; those references are not resolved.

## Known limits

- Data stores are plain files on the `facade_data` volume; access tokens and replay records are in memory, so a restart ends outstanding tokens.
- DNS-rebinding between the address check and the connection is not closed; the per-tester allow-list is the main control.
- Only interaction 1 (the referral) is sent. Interactions 2 and 4 are not implemented.
- The ODE profiles are not built, so nothing here validates ODE conformance.
- The ARex images are 8x8 placeholder PNGs and the Provenance signature in the data is a placeholder.
