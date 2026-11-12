# Install the OHIA FHIR test server

**Last checked:** 2026-11-12. Facts are labeled **VERIFIED** (run and observed by the assistant in a cloud sandbox, x86_64, from a clean clone), **OBSERVED** (the owner saw it on the live Oracle Arm VM; recorded in [`docs/CURRENT_STATE.MD`](../docs/CURRENT_STATE.MD)) or **UNVERIFIED**. Section 12 lists what has and has not been checked.

**Synthetic data only. Do not put real patient data in this server.** Access is one shared bearer token (section 10).

## What you are installing

Four containers, started by one Compose file ([`docker-compose.yml`](docker-compose.yml)):

| Container | What it is | Reachable from outside? |
|---|---|---|
| `caddy` | HTTPS front door; enforces the shared token | yes (ports 80 and 443) |
| `hapi` | HAPI FHIR JPA server (R4), the FHIR store | only through Caddy |
| `hapi-db` | PostgreSQL 16 (the store's database) | no |
| `facade` | Java 21 service built from [`../facade`](../facade): UC07 and UC04a sender and receiver, ARex content server, the `/viewer/` page | only through Caddy |

What Caddy exposes ([`Caddyfile`](Caddyfile)):

| Path | Goes to | Token needed? |
|---|---|---|
| `GET /fhir/metadata` | the facade's CapabilityStatement (falls back to HAPI's if the facade is down) | no |
| `OPTIONS` (on paths other than `/ode`, `/arex`, `/viewer`) | HAPI (browser CORS preflight) | no |
| `/fhir/*` (everything else) | HAPI | **yes** |
| `/ode/*` (seed, send, receipts, identity, health) | facade | **yes** |
| `/arex/*` (ARex content, OAuth token endpoint, JWKS) | facade | no: the facade enforces its own ARex tokens |
| `/viewer/*` (the static page) | facade | no: the page asks you for the token |
| anything else, including `/` | none | **yes** (401) |

## Prerequisites

- A Linux host, x86_64 or arm64. **VERIFIED** on x86_64 (sandbox). **OBSERVED** on arm64 (Oracle Ampere, Ubuntu 26.04.1).
- Docker Engine and the Compose v2 plugin (`docker compose version` must work).
- **Outbound HTTPS** from the host and from the containers to: the container registries, Maven Central (the facade is built from source), and `raw.githubusercontent.com` (the facade downloads its seed data from the data repository, section 7).
- For a public install: inbound TCP 80 and 443, and a DNS name (section 2).
- **Memory, measured** (idle, sandbox, all four containers running): about 1.5 GiB (HAPI 1.14 GiB, facade 195 MiB, PostgreSQL 64 MiB, Caddy 11 MiB). The peak during the first image build and under load was **not measured**.

## 1. Create the host (Oracle Cloud example; you do this)

Console wording changes; if a step does not match, send the screen text or error to the assistant.

1. Console: Compute, Instances, Create instance.
2. Image: Canonical Ubuntu, **aarch64**. Shape: the Always Free `VM.Standard.A1.Flex` is often out of capacity; the live server runs on the paid **`VM.Standard.A2.Flex`** (**OBSERVED**). Pick what the console offers.
3. Networking: a public subnet and **assign a public IPv4 address**.
4. Add or download an SSH key; keep the private key private.
5. If you get "Out of host capacity": retry later, try another availability domain, or use a smaller shape (see the memory figures above).
6. Open the subnet's **security list** (or a network security group) for ingress: TCP 80 and 443 from anywhere, TCP 22 from your own IP.

## 2. A hostname for HTTPS (public install only)

Caddy obtains a certificate automatically but needs a DNS name that points at the VM's public IP.
- A name you own (an `A` record to the IP), or
- a free wildcard name such as `<ip-with-dashes>.sslip.io` (for example `203-0-113-7.sslip.io`). **OBSERVED** on the live server: certificate issued and `/fhir/metadata` answered over HTTPS. Shared free services can hit certificate rate limits.

For a local or private install skip this section and use `SITE_ADDRESS=:80` (plain HTTP).

## 3. Install Docker

Either of these has been used; check with `docker compose version` afterwards.

```bash
# A. Ubuntu's packages (what the live VM runs: docker.io 29.1.3 with the Compose plugin 5.7.0; OBSERVED)
#    The Compose plugin's package name on your Ubuntu release was not recorded: if `docker compose` is missing, search `apt search docker-compose`.
sudo apt-get update && sudo apt-get install -y docker.io

# B. Docker's convenience script (UNVERIFIED on arm64 here)
curl -fsSL https://get.docker.com | sudo sh

sudo usermod -aG docker $USER && newgrp docker
```

## 4. Get the code and configure it

```bash
git clone https://github.com/lp-digitalhealth/ohia-fhir-test-server.git
cd ohia-fhir-test-server/deploy
cp .env.example .env
```

Edit `.env` (it is git-ignored; never commit it, never paste it into chat):

| Variable | Required | Meaning |
|---|---|---|
| `POSTGRES_PASSWORD` | **yes** | a long random password |
| `API_TOKEN` | **yes** | the shared bearer token; generate with `openssl rand -hex 32` (hex only, no spaces or quotes) |
| `SITE_ADDRESS` | no (default `:80`) | your hostname for HTTPS, or `:80` for plain HTTP |
| `PUBLIC_BASE_URL` | no | `https://<your-hostname>`, or `http://localhost[:port]` locally; no trailing slash, no `/fhir`. Used in links HAPI returns |
| `HTTP_PORT`, `HTTPS_PORT` | no | host ports (default 80 and 443); change them if those are taken |
| `HAPI_IMAGE`, `POSTGRES_IMAGE`, `CADDY_IMAGE` | no | image overrides (a mirror or a pinned tag) |
| `MAVEN_IMAGE`, `JRE_IMAGE` | no | the facade's two **build** base images (defaults are the Docker Hub ones); set them to build behind a Docker Hub rate limit or a mirror |

**VERIFIED:** `docker compose` refuses to start without `POSTGRES_PASSWORD` or `API_TOKEN` and says which one is missing.

## 5. Build and start

```bash
docker compose build        # builds the facade image from ../facade (downloads Maven dependencies the first time)
docker compose up -d
docker compose ps           # wait until hapi-db and facade say "healthy"
```

Build the facade **before** starting on an existing install, so a build problem cannot touch the running stack (`docker compose build facade`, then `up -d`).

- **VERIFIED (sandbox, clean clone):** with the images pulled, `docker compose up -d` starts all four containers; `hapi-db` and `facade` reach `healthy`; HAPI answered about 20 seconds after the containers started (x86_64, images already pulled; the earlier guide recorded about two minutes for a first start on the VM).
- **OBSERVED (Arm VM, 2026-11-05):** `docker compose build facade` succeeded and the container ran.
- **NOT verified here:** the Maven stage of the facade image build. In the sandbox it fails with a certificate error (`PKIX`), because the sandbox intercepts TLS with a certificate authority the build container does not trust. This is a property of that network, not of the repository: do not disable TLS verification, build on an unintercepted network. To test the rest of the stack in such an environment, build the jar on the host (`cd ../facade && mvn -q -B package -DskipTests`) and wrap it in a runtime-only image tagged `ohia-facade:local`; see [`docs/FACADE_BUILD_GUIDE.md`](../docs/FACADE_BUILD_GUIDE.md), section 4. Compose then uses that image and does not rebuild it.

## 6. Verify it

Set `BASE` to `https://<your-hostname>` (or `http://localhost:<HTTP_PORT>`) and `TOKEN` to your `API_TOKEN`.

```bash
BASE=https://<your-hostname>; TOKEN=<your API_TOKEN>
code() { curl -s -o /dev/null -w '%{http_code}\n' "$@"; }
code $BASE/fhir/metadata                                          # 200  (open)
code $BASE/fhir/Patient                                           # 401  (no token)
code -H "Authorization: Bearer $TOKEN" $BASE/fhir/Patient         # 200
code -X POST -H 'Content-Type: application/fhir+json' -d '{}' $BASE/fhir/Patient   # 401 (a write needs the token)
code $BASE/                                                       # 401  (even HAPI's home page)
code -H "Authorization: Bearer $TOKEN" $BASE/ode/health          # 200  (facade)
code $BASE/ode/identity                                           # 401
code -H "Authorization: Bearer $TOKEN" $BASE/ode/identity        # 200
code $BASE/arex/jwks.json                                         # 200  (open)
code $BASE/arex/.well-known/smart-configuration                   # 200  (open)
code $BASE/viewer/                                                # 200  (open page)
curl -s $BASE/fhir/metadata | head -c 400                         # a CapabilityStatement: fhirVersion 4.0.1, title "OHIA FHIR test server (November testing): target capability"
```

**VERIFIED (sandbox, clean clone, 2026-11-12):** every line above returned the code shown, and a wrong token, an extended token, a lowercase `bearer`, and the path trick `/fhir/metadata/../Patient` all returned 401. A CORS preflight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`, no token) returned 200; a bare `OPTIONS` without those headers returns 400 from HAPI, which is not a token failure.
**OBSERVED (live VM):** metadata 200, Patient 401 without and 200 with the token, a write without the token 401, `/viewer/` and `/arex/jwks.json` 200 (after Caddy was recreated, section 9).

## 7. Load the test data (once per install)

The facade downloads its test data from the data repository **at pinned commits** and loads it into HAPI. Run these once after a fresh install, and again after you change a pin (the token is needed):

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" "$BASE/ode/admin/seed?useCase=uc07"
curl -s -X POST -H "Authorization: Bearer $TOKEN" "$BASE/ode/admin/seed?useCase=uc04a"
```

(The page does the same: `/viewer/`, enter the token, **Send a referral**, choose the use case, **Load the data**.)

- **Needs outbound HTTPS from the facade container to `raw.githubusercontent.com`.** **UNVERIFIED here:** in the sandbox both calls returned `502` with `Cannot fetch ... PKIX path building failed`, the same TLS interception as in section 5. The repository records seeding only from the owner's sandbox end-to-end runs against real HAPI ([`docs/CURRENT_STATE.MD`](../docs/CURRENT_STATE.MD)); seeding on the live VM is **not recorded**.
- **The pins** are in [`facade/src/main/resources/application.yml`](../facade/src/main/resources/application.yml): `facade.seed.data-ref` (UC07) and `facade.use-cases.uc04a.data-ref`. **As of 2026-11-12 they are behind the data repository**: UC07 is pinned to `445d4ce` (interaction 1 only), UC04a to `bafcf43` (before the penicillin allergy and the NPI fixes). To load newer data, change the pin and rebuild the facade (`docker compose build facade && docker compose up -d`); the environment variables `FACADE_SEED_DATA_REF` and `FACADE_UC04A_DATA_REF` exist but `docker-compose.yml` does not pass them to the container. Bump deliberately: the facade's tests vendor copies of the data.
- Payloads a tester sends and the data we send from share one HAPI; a tester sending the sample under the same ids overwrites our copy. Reload the data before sending.

## 8. Register a tester (the organizer does this)

Copy `facade/config/clients.example.json` to `facade/config/clients.json` (git-ignored) and fill in the tester's client id, organization, allowed target URL prefixes and public key. No restart is needed; the file is re-read when it changes. For UC04a sending, add `"organizationsByUseCase": {"uc04a": "Organization/org-barton-springs-dental"}` to the tester's entry.

Give a tester our identity with `curl -s -H "Authorization: Bearer $TOKEN" $BASE/ode/identity`; the public key is open at `$BASE/arex/jwks.json`. Guides for testers: [`docs/CONSUMER_GUIDE.md`](../docs/CONSUMER_GUIDE.md), [`docs/UC04A_SENDING_GUIDE.md`](../docs/UC04A_SENDING_GUIDE.md), [`docs/AREX_RETRIEVAL_GUIDE.md`](../docs/AREX_RETRIEVAL_GUIDE.md). See received referrals at `$BASE/viewer/` (enter the token) or `curl -s -H "Authorization: Bearer $TOKEN" $BASE/ode/receipts`.

## 9. Day to day

```bash
docker compose logs -f hapi          # follow logs (also: facade, caddy)
docker compose ps
docker compose down                  # stop; data stays in the volumes
docker compose down -v               # stop AND DELETE the database, the facade's key and data, and Caddy's certificate
```

**Updating:**

```bash
git pull
docker compose build facade           # build first
docker compose up -d                  # HAPI and the database are not recreated unless their settings changed
docker compose up -d --force-recreate caddy   # ONLY when deploy/Caddyfile changed
docker compose ps                     # facade should be healthy
```

- **When `deploy/Caddyfile` changes, recreate Caddy.** **OBSERVED** (live VM, 2026-11-05): `git pull` replaces the file, but the Caddy container keeps the old copy mounted, so a reload re-reads the old file and new routes (`/viewer/`, `/arex/jwks.json`) kept returning 401 until Caddy was recreated. The certificate is kept in the `caddy_data` volume.
- **Keep the `facade_data` volume.** The facade creates its own signing key on first use there (`keys/server-key.json`); losing the volume changes the key testers have.
- A change to `PUBLIC_BASE_URL` recreates HAPI.

## 10. The access token (interim)

**Revisit in January 2027** (decision S2): this is a stopgap until the real design (SMART backend services and standalone launch, plan milestone M1).

- **What it does:** Caddy rejects with `401` any request that lacks `Authorization: Bearer <API_TOKEN>`, except `GET`/`HEAD /fhir/metadata`, `OPTIONS`, and the open `/arex/*` and `/viewer/*` routes in the table above. The facade checks the token again on `/ode/**`.
- **Where it lives:** `deploy/.env` on the host only. Never commit it or paste it into chat or an issue.
- **Rotating it:** put a new value in `.env`, run `docker compose up -d`, and give the new token to your testers. Caddy and the facade restart; HAPI and the database keep their data. The old token stops working at once.
- **Limits:** everyone shares one secret; there are no scopes, no per-user audit and no expiry. A leak gives full read and write access. The match is **case-sensitive**: the header must say `Bearer` exactly.

## 11. If something goes wrong

| Symptom | Likely cause and what to do |
|---|---|
| `docker compose build` fails with `429 Too Many Requests` from `registry-1.docker.io` | Docker Hub's rate limit. Set `MAVEN_IMAGE` and `JRE_IMAGE` (and `HAPI_IMAGE`, `POSTGRES_IMAGE`, `CADDY_IMAGE`) to a mirror in `.env`, for example `mirror.gcr.io/library/maven:3.9-eclipse-temurin-21` and `mirror.gcr.io/library/eclipse-temurin:21-jre`. **VERIFIED:** those two images pull and the build gets past the image step. |
| The build or a seed call fails with `PKIX path building failed` / `unable to find valid certification path` | The network intercepts TLS with a certificate the container does not trust (seen in the sandbox). Use an unintercepted network. Do not turn off certificate checking. |
| `Bind for 0.0.0.0:80 failed: port is already allocated` | Something else holds the port. Set `HTTP_PORT` / `HTTPS_PORT` in `.env` (and keep `PUBLIC_BASE_URL` consistent). |
| `docker compose` says `required variable ... is missing a value` | `POSTGRES_PASSWORD` or `API_TOKEN` is not set in `.env`. |
| Times out from outside but works on the host | The OS firewall or the cloud security list. Oracle's Ubuntu images are reported to ship restrictive firewall rules; check ports 80 and 443 there. |
| Certificate errors | The name does not resolve to the host's IP yet, or port 80 is blocked (Caddy needs it to validate). |
| `/viewer/` or `/arex/jwks.json` return 401 after an update | Caddy still has the old Caddyfile: `docker compose up -d --force-recreate caddy`. |
| `facade` shows `unhealthy` | An old build had a bad probe (it searched for the text `200 OK`; Tomcat sends `HTTP/1.1 200`). Rebuild and restart: `docker compose build facade && docker compose up -d`. |
| `/ode/*` or `/arex/*` return 502 | The facade is down; `docker compose logs facade`. `/fhir` keeps working. |
| A tester gets a 502 on a receive endpoint | Read the reply's `"error"` text and `docker compose logs --since 30m -t hapi`. `HAPI-0507 ... external reference` means their Bundle's references point at an address that is not ours; `docker-compose.yml` declares our own addresses as local and accepts references to other servers, and HAPI is recreated by `docker compose up -d`. |
| `GET /fhir/metadata` shows HAPI's own statement, not the OHIA one | The facade is down (Caddy falls back on purpose), or the Caddyfile was not recreated into Caddy. |
| `docker compose logs hapi` shows startup errors | HAPI's own messages; first start is slower than later ones. |

## 12. What has and has not been verified

| Step | Status |
|---|---|
| `docker compose config` valid; refuses to start without `POSTGRES_PASSWORD` or `API_TOKEN` | **VERIFIED** (clean clone, sandbox, 2026-11-12) |
| Four containers start from a clean clone; `hapi-db` and `facade` healthy; HAPI answers | **VERIFIED** (sandbox, x86_64, with the facade image built outside the container) |
| Token and routing matrix in section 6 | **VERIFIED** (sandbox); the core of it also **OBSERVED** on the live VM |
| Base-image substitution (`MAVEN_IMAGE`, `JRE_IMAGE`) lets the facade build get past image pulls behind a rate limit | **VERIFIED** (sandbox) |
| Facade image builds from source, including the Maven stage | **OBSERVED** on the Arm VM; **not possible to verify in the sandbox** (TLS interception) |
| Seeding from `raw.githubusercontent.com` | verified only in the owner's sandbox end-to-end runs; **not recorded** on the live VM; **UNVERIFIED** in this guide's sandbox run (TLS interception) |
| Certificate issuance for a real hostname (Let's Encrypt, `sslip.io`) | **OBSERVED** on the live VM; not tested in the sandbox (`:80` only) |
| arm64 (Oracle Ampere) | **OBSERVED** by the owner; the assistant has only run x86_64 |
| Browser CORS behavior from a real page, load, concurrency | **UNVERIFIED** |
| The facade's own tests (unit and end-to-end) | recorded in [`docs/CURRENT_STATE.MD`](../docs/CURRENT_STATE.MD); not re-run for this guide |
