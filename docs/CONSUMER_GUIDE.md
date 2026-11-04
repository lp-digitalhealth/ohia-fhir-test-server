# Connecting to the OHIA FHIR test server

For connectathon participants and other testers. Last updated 2026-11-04.

## What this is
A **FHIR R4 test server** (HAPI FHIR) for the OHIA connectathon. It holds **synthetic test data only**. Do **not** send real patient data.

**At this stage it is a plain FHIR R4 endpoint.** These are planned but **not available yet**: separate role endpoints (payer, PMS, teledentistry platform), CRD/DTR/PAS services, the 360X receiver, ODE referral validation feedback, and OAuth/SMART login. If you need one of those, ask the organizer when it will be ready.

## What you need from the organizer
1. **Base URL**, for example `https://<server-host>/fhir`
2. **Access token**, a long secret string shared with testers

Treat the token like a password. Don't post it in chat, email lists, issues or screenshots, and don't commit it to a repository. If you think it leaked, tell the organizer; it can be replaced.

## The one rule
Send this header on **every request** except the open metadata check:

```
Authorization: Bearer <your-token>
```

- Write `Bearer` exactly like that, with a capital B. Lowercase is rejected.
- Use **HTTPS** only.
- A missing or wrong token gets `401 Unauthorized`.

`GET <base-url>/metadata` needs **no** token. Use it to check the server is up and to read its capabilities.

## Try it

Set two values once per terminal session, so the token doesn't end up in your command history:

```bash
export OHIA_BASE="https://<server-host>/fhir"
export OHIA_TOKEN="<your-token>"
```

```bash
# 1. Server check (no token needed): expect JSON with "fhirVersion": "4.0.1"
curl -s "$OHIA_BASE/metadata" | head -c 300

# 2. Authenticated read: expect 200 (a Bundle), not 401
curl -s -H "Authorization: Bearer $OHIA_TOKEN" -H "Accept: application/fhir+json" "$OHIA_BASE/Patient?_count=5"

# 3. Create a resource
curl -s -X POST -H "Authorization: Bearer $OHIA_TOKEN" -H "Content-Type: application/fhir+json" \
  -d '{"resourceType":"Patient","name":[{"family":"Example","given":["Test"]}]}' "$OHIA_BASE/Patient"
```

**Windows PowerShell:**

```powershell
$env:OHIA_BASE  = "https://<server-host>/fhir"
$env:OHIA_TOKEN = "<your-token>"
$h = @{ Authorization = "Bearer $env:OHIA_TOKEN"; Accept = "application/fhir+json" }

Invoke-RestMethod "$env:OHIA_BASE/metadata" | Select-Object fhirVersion      # no token needed
Invoke-RestMethod "$env:OHIA_BASE/Patient?_count=5" -Headers $h               # authenticated read
Invoke-RestMethod -Method Post -Uri "$env:OHIA_BASE/Patient" -Headers $h -ContentType "application/fhir+json" `
  -Body '{"resourceType":"Patient","name":[{"family":"Example","given":["Test"]}]}'
```

**Postman and similar tools:** set the base URL, choose Authorization type **Bearer Token** and paste the token. Add `Accept: application/fhir+json`. For writes add `Content-Type: application/fhir+json`.

**FHIR client libraries:** configure a bearer-token (or custom `Authorization`) header on the client. The server does not use OAuth yet, so don't configure a token endpoint or client ID.

## Good to know
- **The server is shared.** Everyone with the token can read and change everyone's data. Give your resources an identifier or tag that identifies you or your organization, and don't rely on another tester's data staying unchanged.
- **Responses** follow FHIR. Errors come back as an `OperationOutcome`. A `401` comes from the access check, before FHIR is involved.
- **Browsers:** CORS preflight requests (`OPTIONS`) are let through without a token, but the actual request still needs the token. Whether the server's CORS settings work for your browser app has **not been tested**. A web page that holds the token exposes it to its visitors, so avoid shipping the token in a page.
- **This access method is temporary.** A proper OAuth login is planned, and you'll be told before it changes.
- **If something looks wrong:** report the time, the request (without the token), the status code and the response body to the organizer.

Organizer contact: `<add contact here>`
