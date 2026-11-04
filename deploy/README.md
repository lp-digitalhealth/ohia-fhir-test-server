# Deploy: plain FHIR endpoint (HAPI + PostgreSQL + Caddy)

This is the **generic stack only**: a FHIR R4 server behind HTTPS. Access is protected by one **interim shared bearer token** (section 7). There is no OAuth, no validation feedback and no payer logic yet. Do not put real patient data in it.

**Tested:** brought up in a cloud sandbox (x86) with Docker Compose; `/fhir/metadata` returned 200 through Caddy (HAPI FHIR Server 8.12.0, FHIR 4.0.1). **Not tested:** ARM (Oracle Ampere), real HTTPS certificates, load.

## 1. Create the Oracle Always Free VM (you do this)

Console wording changes; if a step does not match, send the screen text or error to the assistant.

1. Console: Compute, Instances, Create instance.
2. Image: Canonical Ubuntu (24.04 or 22.04), **aarch64**. Shape: **VM.Standard.A1.Flex** (Always Free eligible). Reported free allowance is 2 OCPU / 12 GB (sources conflict; use what the console shows as Always Free). Start with 2 OCPU / 12 GB.
3. Networking: a public subnet and **assign a public IPv4 address**.
4. Add or download an SSH key; keep the private key private.
5. If you get "Out of host capacity": retry later, try another availability domain if offered, or a smaller shape (HAPI used about 1.3 GiB at idle in the sandbox).
6. After it is running, open the subnet's **security list** (or a network security group) for ingress: TCP 80 and TCP 443 from anywhere, and TCP 22 from your own IP.

## 2. A hostname for HTTPS

Caddy obtains a certificate automatically but needs a DNS name pointing at the VM's public IP.
- Use a name you own (an `A` record to the IP), or
- a free wildcard name such as `<ip-with-dashes>.sslip.io` (for example `203-0-113-7.sslip.io`). This works without buying a domain, but it is **unverified** here and shared services can hit certificate rate limits.

## 3. On the VM

```bash
ssh -i <your-key> ubuntu@<public-ip>
curl -fsSL https://get.docker.com | sudo sh        # Docker official convenience script (arm64 not verified here)
sudo usermod -aG docker $USER && newgrp docker
git clone -b November_Testing https://github.com/lp-digitalhealth/ohia-fhir-test-server.git
cd ohia-fhir-test-server/deploy
cp .env.example .env
```

Edit `.env`: set a long random `POSTGRES_PASSWORD`; set `API_TOKEN` (generate one with `openssl rand -hex 32`); set `SITE_ADDRESS` to your hostname; set `PUBLIC_BASE_URL` to `https://<your-hostname>`. `docker compose` refuses to start if `POSTGRES_PASSWORD` or `API_TOKEN` is missing. Then:

```bash
docker compose up -d
docker compose ps
```

## 4. Check it

```bash
curl -s https://<your-hostname>/fhir/metadata | head -c 300
curl -s -o /dev/null -w '%{http_code}\n' https://<your-hostname>/fhir/Patient                         # expect 401
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer <API_TOKEN>" https://<your-hostname>/fhir/Patient   # expect 200
```

First start takes about two minutes. A JSON CapabilityStatement with `"fhirVersion": "4.0.1"` means it works. `/fhir/metadata` is open on purpose; everything else needs the token (401 without it).

## 5. If it does not answer

- Times out from outside but works on the VM: the OS firewall or the security list. Oracle's Ubuntu images are reported to ship restrictive firewall rules; check ports 80 and 443 there.
- Certificate errors: the name does not resolve to the VM's IP yet, or port 80 is blocked (Caddy needs it to validate).
- `docker compose logs hapi` shows HAPI's startup errors.

## 6. Day to day

```bash
docker compose logs -f hapi    # follow logs
docker compose down             # stop (data stays in volumes)
docker compose pull && docker compose up -d   # update
```

## 7. Access token (interim)

**Revisit in January 2027.** This is a stopgap until the real OAuth design (SMART backend services and standalone launch, plan milestone M1) is built. See `docs/OHIA_FHIR_Test_server_PLAN.md`, S1.

- **What it does:** `Caddyfile` rejects with `401` any request that lacks `Authorization: Bearer <API_TOKEN>`. Two things stay open: `GET`/`HEAD /fhir/metadata` (discovery) and `OPTIONS` (browser CORS preflight). The HAPI home page at `/` needs the token too.
- **Where the token lives:** `deploy/.env` on the VM only (`API_TOKEN=...`). Never commit it, never paste it into chat or an issue.
- **Adding it to a server that is already running:** edit `.env`, then `git pull` and `docker compose up -d`. Only the `caddy` container restarts; HAPI and the database keep their data.
- **Rotating it:** put a new value in `.env`, run `docker compose up -d`, and give the new token to your testers. The old token stops working at once.
- **Limits:** everyone shares one secret; there are no scopes, no per-user audit and no expiry. A leak gives full read and write access. The match is **case-sensitive**: the header must say `Bearer` exactly, and tokens must match exactly.
- **Tested** (against a stand-in upstream, not real HAPI): 401 without a token, with a wrong, shortened or extended token, and for writes; 200 with the right token; metadata and `OPTIONS` open; path tricks such as `metadata/../Patient` rejected; Compose refuses to start without `API_TOKEN`. **Not tested:** the live stack with real HAPI behind it, browser CORS behavior, and per-request timing.
