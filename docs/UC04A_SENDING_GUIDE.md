# UC04a: sending a referral with an image manifest to this server

For testers who play the **platform** (Meridian) in UC04a. This server plays the **in-office practice system** (Barton Springs): it receives your interaction 1 payload, stores it, then pulls the intraoral photos from **your** ARex server and shows the result.

Status: built and tested in a sandbox only. It has not been run on the live VM, and no real tester's ARex server has been tried. The ARex draft (v0.2.0) is silent on several wire details; where this guide says "assumed", that is our reading, and a mismatch shows up as a named finding, not a silent failure.

## 1. What you send

One request:

```bash
curl -X POST "https://<server>/ode/receive/uc04a" \
  -H "Authorization: Bearer <shared token>" \
  -H "Content-Type: application/fhir+json" \
  --data-binary @interaction-01-bundle.json
```

- The body is the UC04a interaction 1 **transaction Bundle**, with the ARex manifest embedded: a manifest `List` (profile `arex-manifest`), one `DocumentReference` per image (profile `arex-content-entry`) and the manifest `Provenance`. Maximum 10 MB (413 above that).
- Not a transaction Bundle: **422**, nothing stored. HAPI refuses it for a reason we cannot work around: **502**, no pull; the reply's `"error"` line says why.
- **References inside the Bundle.** Write them as ordinary relative references (`Patient/patient-sarah-okonkwo`), or as full addresses under `https://<server>/fhir/`. If your app was given `.../ode/receive/uc04a` as a "FHIR base" and wrote full addresses under that, they are accepted too (they are declared as ours). A reference to any other server is refused with `HAPI-0507` and a 502.
- Otherwise **200** with a **receipt** (JSON). A failed pull is a result, not an error: read `findings` and `objects`.

The shared token is the same interim token as the rest of the server (plan item S1).

## 2. What the server does, in order

1. Validates the Bundle and finds the manifest, the entries and the referral id (`urn:ohia:referral-id`).
2. Stores the Bundle in HAPI exactly as received. If HAPI refuses only because of a `Subscription` (real HAPI 8.12 needs a `backport-filter-criteria` extension on it, which the UC04a data does not carry), it stores everything else and reports `SUBSCRIPTION_NOT_STORED`.
3. Pulls the images from **your** ARex server (section 3).
4. Saves the receipt and every digest-verified image. Only verified bytes are ever saved.

## 3. What your ARex server must serve

The base is taken from the entries' content URLs: everything before `/Binary/`. For `https://you.example.org/arex/Binary/arex-entry-photo-1` the base is `https://you.example.org/arex`.

| Step | Request | Our assumption |
|---|---|---|
| Discover | `GET <base>/.well-known/smart-configuration`, else `GET <origin>/.well-known/smart-configuration` | The draft does not say. We read `token_endpoint` and, if present, `authorization_details_types_supported`. |
| Token | `POST <token_endpoint>`, `grant_type=client_credentials`, `client_assertion_type=...jwt-bearer`, `client_assertion=<RS384 JWT>`, `scope=system/List.r system/DocumentReference.r system/Provenance.r system/Binary.r`, `authorization_details=[{"type":"arex-manifest","manifest":"<manifest id>"}]`. One token per manifest. | The type defaults to `arex-manifest`; if you advertise another, we use your first. |
| Published manifest | `GET <base>/List/<manifest id>` with the token | If its `status` is not `current` we **stop** (arex-39). If it is missing we continue and report `PUBLISHED_MANIFEST_UNAVAILABLE`. |
| Each image | `GET <content url>` with the token, `Accept` the declared type | We verify size and SHA-256; a mismatch is not saved. One new token is requested after a 401. |

Our assertion: `iss` = `sub` = our client id, `aud` = your token endpoint exactly as you advertise it, 2-minute `exp`, a fresh `jti`, header `kid`. A transport error on the token request is retried once with a new assertion.

Every URL must be `https`, on a public address, and on **your ARex base's origin**. We never follow redirects. Limits: 20 objects, 10 MB per object, 50 MB per receipt.

**Several manifests in one payload** (for example one for images and one for a PDF) are supported. Each manifest is pulled on its own: its own token, its own published-manifest check, its own objects. A content entry belongs to the manifest whose list names it (item references may be relative or absolute). Non-image files are saved and offered as a download on the page, never shown inline. If one manifest stops (retracted, token refused), the others are still pulled.

**References may be relative or absolute** (`Organization/x` or `https://your-server/fhir/Organization/x`): the requesting organization and the manifest a Provenance targets are compared by type and id.

## 4. Register our key with your server

Ask the organizer for our identity (`GET /ode/identity`, shared token), or read it yourself:

- client id: `ohia-test-server`
- algorithm: RS384, `kid` `ohia-server-1`
- public key: `GET https://<server>/arex/jwks.json` (open, public half only)

Register that client id and key (or the JWKS URL) in your authorization server before you send.

## 5. Reading the result

- Browser: `https://<server>/viewer/`. Type the shared token (kept in that tab only). It lists receipts and shows findings, per-image results and the images.
- API (shared token): `GET /ode/receipts`, `GET /ode/receipts/{id}`, `GET /ode/receipts/{id}/images/{fileKey}`. Images are served inline only if the bytes really are JPEG or PNG; anything else is a download.

## 6. Findings

Each finding has a code, a spec reference where one exists, and a sentence. The server never refuses to act on a gap it could still work through; it names it.

| Code | Meaning |
|---|---|
| `SIGNATURE_PLACEHOLDER` / `SIGNATURE_NOT_VERIFIED` | **We do not verify the manifest signature** (arex-8): the draft does not define the canonical form, the key source or the trust anchor. A placeholder or empty signature is named; a present one is reported as not verified. |
| `MANIFEST_EXPIRED_PER_PAYLOAD` | The embedded expiry has passed (arex-26). We still try; you decide. |
| `MANIFEST_EXPIRY_UNREADABLE`, `MANIFEST_EXPIRY_MISSING` | No usable `arex-expires`. |
| `REQUESTING_ORG_MISMATCH` | The manifest's requesting organization is not `Organization/org-barton-springs-dental`. |
| `NO_AREX_MANIFEST` | No manifest with entries; stored, nothing to pull. |
| `ENTRY_INCOMPLETE`, `ENTRY_ID_SANITIZED` | An entry lacks an id, url or SHA-256 (not pulled), or its id was not a safe file name. |
| `SUBSCRIPTION_NOT_STORED` | HAPI refused the Subscription; the rest was stored (plan item O9). |
| `HOST_NOT_FOUND` | The host name in your content URLs does not resolve (for example the sample's `meridian-teledental.example.org`). Nothing was contacted. |
| `ENTRY_NOT_IN_A_MANIFEST` | The payload has several manifests and none lists this content entry, so there is no token to pull it with. It was not pulled. |
| `UNSAFE_URL_REFUSED` | A URL was not https, not public, not on your base's origin, or not under your base. |
| `TOKEN_ENDPOINT_NOT_DISCOVERED`, `AUTHORIZATION_DETAILS_TYPE_ADJUSTED` | A discovery or token-request assumption did not hold. |
| `TOKEN_REFUSED` | You refused our token. The pull stops. |
| `TOKEN_REQUEST_RETRIED` | The first token request failed in transport and a fresh assertion succeeded. |
| `MANIFEST_NOT_CURRENT` | Your published manifest is not `current`. The pull stops. |
| `PUBLISHED_MANIFEST_UNAVAILABLE`, `PUBLISHED_MANIFEST_DIFFERS` | The published manifest could not be read, or its entries or expiry differ from the embedded copy. |
| `OBJECT_TOO_LARGE`, `OBJECT_LIMIT`, `SIZE_MISMATCH`, `DIGEST_MISMATCH`, `TRANSFER_ERROR` | An image failed a check and was not saved. The others continue. |
| `CONTENT_TYPE_NOT_IMAGE` | Saved, but never shown inline. |

## 7. Not done

- No manifest signature verification, and no resume of an interrupted download.
- Only interaction 1 is handled.
- DNS rebinding between the address check and the connection is not closed.
- The ARex wording was read through a summarizer; statements arex-8, 22, 39, 43, 48 and 59 should be re-read against the spec.
