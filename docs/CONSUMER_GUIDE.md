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

`GET <base-url>/metadata` needs **no** token. Use it to check the server is up and to read its capabilities. It is a draft statement of the capability this TEST server is going for; its `rest.documentation` says which parts are implemented today and which are only the target.

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

**FHIR client libraries (for `/fhir`):** configure a bearer-token (or custom `Authorization`) header on the client. `/fhir` does not use OAuth yet, so don't configure a token endpoint or client ID there. The ARex image endpoints under `/arex` **do** use OAuth; see the next section.

## UC07: receive the referral and fetch the radiographs

In UC07 **this server plays the referring practice (the sender)** and **you play the receiving practice**. The organizer triggers a send to your FHIR endpoint. You then fetch the referral's radiographs from this server using ARex.

### 1. Register once with the organizer
Send the organizer these four things (never your private key):
1. A **client id** you choose (letters, digits, `.`, `_`, `-`).
2. The organization you play. For UC07 that is `Organization/org-restorative-dental-tx`.
3. Your **FHIR base URL**, which must be `https://` and publicly reachable. The server refuses to send to private or local addresses and only to URLs you registered.
4. Your **public key as a JWK**, either RSA of 2048 bits or more (signs with RS384) or EC P-384 (signs with ES384). A quick way to make an RSA pair in Python (needs the `cryptography` package):

```python
import base64
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization
key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
b64 = lambda i: base64.urlsafe_b64encode(i.to_bytes((i.bit_length() + 7) // 8, "big")).rstrip(b"=").decode()
n = key.public_key().public_numbers()
print({"kty": "RSA", "kid": "my-key-1", "alg": "RS384", "use": "sig", "n": b64(n.n), "e": b64(n.e)})   # send this
open("my-private-key.pem", "wb").write(key.private_bytes(serialization.Encoding.PEM,
    serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))                                 # keep this secret
```

### 2. Receive the referral
When the organizer sends, your endpoint gets **one** request:
- `POST <your FHIR base URL>` with `Content-Type: application/fhir+json`.
- The body is a FHIR `Bundle` of type `transaction` with 37 entries, each a `PUT` of one resource: the referral `ServiceRequest` and coordination `Task`, the patient and practitioners, clinical notes and imaging pointers, a payer-notification `Communication`, and the ARex manifest (a `List`) with its two content entries (`DocumentReference`).
- If your endpoint needs a bearer token, give it to the organizer. It is used for that one call and is not stored or logged.
- **Answer with a 2xx** when you accept it. If you refuse it, return an `OperationOutcome` with the reason; the organizer sees your status code and the start of your response.
- **Every send is a new referral:** a new referral id (`REF-2026-UC07-001-<send id>`) and new ids for the referral, task and ARex resources. The patient, practitioners and organizations keep the same ids each time.
- The Bundle shape is the server's reading of the draft ODE guide, which has no UC07 page. If your system expects something different, tell the organizer.

### 3. Fetch the images (ARex)
A fuller, standalone guide with a tested script is in `docs/AREX_RETRIEVAL_GUIDE.md`.

The content URLs in the manifest need a token; anonymous access is never allowed. Base URL below is the server's address with no `/fhir`.

1. **Discover:** `GET <base>/arex/.well-known/smart-configuration` and read `token_endpoint`.
2. **Sign a client assertion (JWT):** header `alg` is `RS384` or `ES384` (add your `kid`). Claims: `iss` and `sub` = your client id, `aud` = the `token_endpoint` URL exactly, `exp` at most **5 minutes** ahead, and a unique `jti`. Each assertion works **once**.
3. **Request a token:** `POST <token_endpoint>` as `application/x-www-form-urlencoded` with `grant_type=client_credentials`, `client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer`, `client_assertion=<your JWT>`, and `authorization_details=[{"type":"arex-manifest","manifest":"<id of the manifest List in the Bundle>"}]`. You may add `scope=system/List.rs system/Binary.r`. The answer has `access_token` (valid **300 seconds**) and `expires_in`.
4. **Read the manifest (optional):** `GET <base>/arex/List/<manifest id>` with `Authorization: Bearer <access_token>`. You can also search by the referral: `GET <base>/arex/List?arex-manifest-transaction-identifier=urn:ohia:referral-id|<referral id>`.
5. **Fetch each image:** `GET` the `content.attachment.url` of each content entry with the same bearer token. Send `Accept: image/png` for the raw bytes (or `Accept: application/fhir+json` for a FHIR `Binary`). HTTP `Range` requests work.
6. **Verify:** the SHA-256 of what you downloaded must equal the digest in the entry's `arex-digest` extension, and its length must equal `attachment.size`. Do not use an object whose digest does not match.

| You get | Meaning |
|---|---|
| `401 invalid_client` (token endpoint) | Something is wrong with your assertion: wrong key, algorithm, audience, lifetime, or you reused a `jti`. The message is the same for every cause on purpose. |
| `400 invalid_request` | `authorization_details` is missing or not exactly one `arex-manifest` entry. |
| `400 invalid_grant` | That manifest is not available to you: unknown, meant for someone else, expired, or retracted. These look identical on purpose. |
| `400 invalid_scope` | Only `system/List.rs` and `system/Binary.r` are offered. |
| `401` on a manifest or image | No token, a bad token, or the 5-minute token has expired. Get a new one (with a new assertion). |
| `404` on a manifest or image | Not found **or not yours**, expired, or retracted. By design it never says which. |
| `416` | Bad or unsatisfiable `Range`. |

**Timing:** a token lasts 5 minutes. A manifest and its images stay retrievable for **30 days** from the send (the manifest's `arex-expires`), or until the organizer retracts it, whichever is first. After that, retrieval is refused even with a fresh token.

**Good to know:** ARex is a **draft** specification (v0.2.0). The `authorization_details` type value `arex-manifest` is this server's own choice; tell the organizer if your client needs something else. The "radiographs" are 8x8 placeholder PNGs, and the manifest signature in the data is a placeholder.

## Patient-facing apps: the payer's claims data (UC07 interaction 6)
A second base, `https://<server>/patient-access/fhir`, serves the payer's CARIN Blue Button 2.2.0 view of the test member. Same bearer token as the rest of the server. Read it as the patient app would:

1. Read `GET /patient-access/fhir/metadata` (no token needed).
2. Find the member with the payer's online member ID: `GET /Patient?identifier=https://dentaquest.example.org/fhir/online-id|DQ-ONLINE-TX-0038211`.
3. Then `GET /Coverage?beneficiary=Patient/<id>` and `GET /ExplanationOfBenefit?patient=<id>` (two adjudicated claims).

Match these records to the dentist-side ones by business keys (the Medicaid beneficiary ID, the claim and pre-authorization numbers), not by resource id. See the UC07 interaction 6 document in `ohia-test-data`. Read and search only; no SMART login yet. The data is synthetic.

## Good to know
- **The server is shared.** Everyone with the token can read and change everyone's data. Give your resources an identifier or tag that identifies you or your organization, and don't rely on another tester's data staying unchanged.
- **Responses** follow FHIR. Errors come back as an `OperationOutcome`. A `401` comes from the access check, before FHIR is involved.
- **Browsers:** CORS preflight requests (`OPTIONS`) are let through without a token, but the actual request still needs the token. Whether the server's CORS settings work for your browser app has **not been tested**. A web page that holds the token exposes it to its visitors, so avoid shipping the token in a page.
- **This access method is temporary.** A proper OAuth login is planned, and you'll be told before it changes.
- **If something looks wrong:** report the time, the request (without the token), the status code and the response body to the organizer.

Organizer contact: `<add contact here>`

**Playing the platform in UC04a?** The server also receives UC04a interaction 1 and pulls your image manifest. See `docs/UC04A_SENDING_GUIDE.md`.
