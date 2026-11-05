# How to retrieve the documents of a UC07 referral (ARex)

For testers who **receive** the UC07 referral from the OHIA test server. The referral does not carry the radiographs inside it. It carries a **manifest** (an ARex `List`) that points to each image by URL. You fetch the images yourself, with a short-lived token, and check each one against the SHA-256 digest the manifest publishes.

Last tested: 2026-11-05, against the facade in this repository running next to real HAPI 8.12.0 (21 end-to-end checks, plus every command in this document). **Not tested:** the live Oracle deployment, and any receiver other than the stand-in used for testing. ARex is a **draft** specification (v0.2.0).

## What you need before you start

1. **Registered with the organizer** (once): a client id, the organization you play (`Organization/org-restorative-dental-tx` for UC07), your FHIR base URL and your **public** key. How: see "Register once with the organizer" in `docs/CONSUMER_GUIDE.md`. Keep your private key secret; it never leaves your machine.
2. **The referral Bundle your endpoint received.** Save the request body to a file, for example `received-bundle.json`. It contains the manifest and the list of images.
3. **Python 3 with `PyJWT` and `cryptography`** for the script below (`pip install PyJWT cryptography`). Any language with a JOSE library works; see "Other languages".

## The flow in six steps

| # | Step | Request |
|---|---|---|
| 1 | Read the manifest id and the image URLs out of the Bundle | (local) |
| 2 | Discover the token endpoint | `GET <server>/arex/.well-known/smart-configuration` |
| 3 | Sign a client assertion with your private key | (local) |
| 4 | Ask for a token for this one manifest | `POST <token endpoint>` |
| 5 | Download each image with the token | `GET <image url>` with `Authorization: Bearer <token>` |
| 6 | Check each image against its published SHA-256 and size | (local) |

`<server>` is the server's address with no `/fhir`. The script takes it from the image URLs in the Bundle.

## Option A: run the script

Save this as `arex_fetch.py` (the same file is `docs/examples/arex_fetch.py` in the repository):

```python
#!/usr/bin/env python3
"""Fetch the ARex content objects (the images) of a UC07 referral and verify them.

You give it the referral Bundle your FHIR endpoint received. It then:
  1. finds the ARex manifest and the content entries in the Bundle,
  2. reads the server's SMART discovery document,
  3. proves who you are with a signed client assertion (private_key_jwt) and gets a token
     that is good for this one manifest,
  4. downloads every content object and checks its size and SHA-256 against the published digest.

Usage:
  python arex_fetch.py --bundle received-bundle.json --client-id my-client \
         --key my-private-key.pem --kid my-key-1 --out downloads

Needs:  pip install PyJWT cryptography
Exit code: 0 = everything fetched and verified, 1 = a check failed, 2 = could not get a token.
Never disables TLS verification. If you are behind a proxy, set HTTPS_PROXY / NO_PROXY as usual.
"""
import argparse
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

import jwt  # PyJWT

ARTIFACT_EXT = {"image/png": "png", "image/jpeg": "jpg", "application/pdf": "pdf", "text/plain": "txt"}
ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"


def http(method, url, headers=None, data=None):
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def profile_endswith(resource, suffix):
    return any(p.endswith(suffix) for p in resource.get("meta", {}).get("profile", []))


def find_manifest_and_entries(bundle):
    resources = [e["resource"] for e in bundle["entry"]]
    manifests = [r for r in resources if r["resourceType"] == "List" and profile_endswith(r, "arex-manifest")]
    entries = [r for r in resources if r["resourceType"] == "DocumentReference" and profile_endswith(r, "arex-content-entry")]
    if len(manifests) != 1 or not entries:
        sys.exit("The Bundle must contain exactly one ARex manifest (List) and at least one content entry.")
    return manifests[0], entries


def declared_sha256(attachment):
    algorithm = value = None
    for ext in attachment.get("extension", []):
        if ext["url"].endswith("arex-digest"):
            for part in ext["extension"]:
                if part["url"] == "algorithm":
                    algorithm = part["valueCode"]
                elif part["url"] == "value":
                    value = part["valueString"]
    if algorithm != "sha-256" or not value:
        sys.exit("A content entry has no sha-256 digest; refusing to download it.")
    return value.lower()


def client_assertion(args, private_key, token_endpoint):
    now = int(time.time())
    claims = {
        "iss": args.client_id,          # your client id
        "sub": args.client_id,          # the same value
        "aud": token_endpoint,          # the token URL, exactly as discovery gave it
        "iat": now,
        "exp": now + 120,               # at most 5 minutes ahead
        "jti": str(uuid.uuid4()),       # unique: each assertion works only once
    }
    headers = {"kid": args.kid} if args.kid else None
    return jwt.encode(claims, private_key, algorithm=args.alg, headers=headers)


def get_token(args, private_key, token_endpoint, manifest_id):
    form = urllib.parse.urlencode({
        "grant_type": "client_credentials",
        "client_assertion_type": ASSERTION_TYPE,
        "client_assertion": client_assertion(args, private_key, token_endpoint),
        "scope": "system/List.rs system/Binary.r",
        "authorization_details": json.dumps([{"type": "arex-manifest", "manifest": manifest_id}]),
    }).encode()
    status, _, body = http("POST", token_endpoint, {"Content-Type": "application/x-www-form-urlencoded"}, form)
    if status != 200:
        print("Token request failed: HTTP %d %s" % (status, body.decode("utf-8", "replace")), file=sys.stderr)
        explain = {
            "invalid_client": "Your assertion was refused: wrong key, algorithm, audience, lifetime, or a reused jti.",
            "invalid_grant": "That manifest is not available to you: unknown, meant for someone else, expired or retracted.",
            "invalid_request": "authorization_details is missing or malformed.",
        }
        try:
            print(explain.get(json.loads(body).get("error"), ""), file=sys.stderr)
        except ValueError:
            pass
        sys.exit(2)
    return json.loads(body)["access_token"]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bundle", required=True, help="the referral Bundle your endpoint received (JSON file)")
    ap.add_argument("--client-id", required=True)
    ap.add_argument("--key", required=True, help="your PRIVATE key (PEM)")
    ap.add_argument("--kid", help="key id, as in the public JWK you registered")
    ap.add_argument("--alg", default="RS384", choices=["RS384", "ES384"])
    ap.add_argument("--base", help="server base URL (default: taken from the content URLs in the Bundle)")
    ap.add_argument("--out", default="downloads")
    args = ap.parse_args()

    bundle = json.load(open(args.bundle, encoding="utf-8"))
    manifest, entries = find_manifest_and_entries(bundle)
    first_url = entries[0]["content"][0]["attachment"]["url"]
    base = (args.base or first_url.split("/arex/Binary/")[0]).rstrip("/")
    private_key = open(args.key, "rb").read()

    status, _, body = http("GET", base + "/arex/.well-known/smart-configuration")
    if status != 200:
        sys.exit("Discovery failed: HTTP %d" % status)
    token_endpoint = json.loads(body)["token_endpoint"]
    print("manifest %s, %d content object(s), token endpoint %s" % (manifest["id"], len(entries), token_endpoint))

    token = get_token(args, private_key, token_endpoint, manifest["id"])
    os.makedirs(args.out, exist_ok=True)
    failed = 0
    for entry in entries:
        att = entry["content"][0]["attachment"]
        want = declared_sha256(att)
        for attempt in (1, 2):
            status, headers, data = http("GET", att["url"], {"Authorization": "Bearer " + token, "Accept": att.get("contentType", "*/*")})
            if status == 401 and attempt == 1:  # the 5-minute token ran out: get a fresh one, once
                token = get_token(args, private_key, token_endpoint, manifest["id"])
                continue
            break
        if status != 200:
            print("FAIL %s: HTTP %d (404 means not found, not yours, expired or retracted)" % (entry["id"], status))
            failed += 1
            continue
        got = hashlib.sha256(data).hexdigest()
        if got != want or len(data) != att.get("size"):
            print("FAIL %s: digest or size mismatch (declared %s / %s bytes, got %s / %d bytes). Not saved." % (entry["id"], want, att.get("size"), got, len(data)))
            failed += 1
            continue
        ext = ARTIFACT_EXT.get(att.get("contentType", ""), "bin")
        path = os.path.join(args.out, "%s.%s" % (entry["id"], ext))
        with open(path, "wb") as f:
            f.write(data)
        print("OK   %s -> %s (%d bytes, sha-256 verified)" % (entry["id"], path, len(data)))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
```

Run it:

```bash
python arex_fetch.py --bundle received-bundle.json --client-id my-client \
       --key my-private-key.pem --kid my-key-1 --out downloads
```

You should see one `OK` line per image and exit code 0:

```
manifest arex-manifest-radiographs-<send id>, 2 content object(s), token endpoint https://<server>/arex/auth/token
OK   arex-entry-bitewing-<send id> -> downloads/arex-entry-bitewing-<send id>.png (140 bytes, sha-256 verified)
OK   arex-entry-periapical-<send id> -> downloads/arex-entry-periapical-<send id>.png (137 bytes, sha-256 verified)
```

What the script does for you, and what it refuses to do:

- It never saves an image whose digest or size does not match. It prints `FAIL ... Not saved.` and exits 1; the other images are still processed.
- Tokens last 5 minutes. If one runs out mid-download (HTTP 401), it gets a new token **once** and carries on.
- It never turns off TLS verification.
- Use `--alg ES384` with an EC P-384 key.

| Exit code | Meaning |
|---|---|
| 0 | every image fetched and verified |
| 1 | at least one image failed its check or could not be fetched |
| 2 | no token was issued (the message says why) |

## Option B: by hand with curl

This is the same flow, one step at a time, so you can see exactly what goes over the wire. Needs `curl`, `python3` and `PyJWT`. Run it from the folder that holds `received-bundle.json` and `my-private-key.pem`.

```bash
set -e
BASE=https://<server-host>                # the server address, no /fhir
CLIENT=my-client; KID=my-key-1; BUNDLE=received-bundle.json

# 0. what is in the Bundle you received: the manifest id, and each image's URL, declared digest and size
read -r MANIFEST < <(python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); print(next(e["resource"]["id"] for e in b["entry"] if e["resource"]["resourceType"]=="List" and any(p.endswith("arex-manifest") for p in e["resource"].get("meta", {}).get("profile", []))))' $BUNDLE)
python3 - $BUNDLE > objects.tsv <<'EOF'
import json, sys
b = json.load(open(sys.argv[1]))
for e in b["entry"]:
    r = e["resource"]
    if r["resourceType"] == "DocumentReference" and any(p.endswith("arex-content-entry") for p in r.get("meta", {}).get("profile", [])):
        a = r["content"][0]["attachment"]
        digest = next(x["valueString"] for ext in a["extension"] if ext["url"].endswith("arex-digest") for x in ext["extension"] if x["url"] == "value")
        print("\t".join([r["id"], a["url"], digest, str(a["size"]), a["contentType"]]))
EOF
echo "manifest: $MANIFEST"; cat objects.tsv | cut -c1-110

# 1. discovery
TOKEN_URL=$(curl -s $BASE/arex/.well-known/smart-configuration | python3 -c 'import sys,json; print(json.load(sys.stdin)["token_endpoint"])')
echo "token endpoint: $TOKEN_URL"

# 2. sign a client assertion (valid 2 minutes, usable once)
ASSERTION=$(TOKEN_URL=$TOKEN_URL CLIENT=$CLIENT KID=$KID python3 - <<'EOF'
import os, time, uuid, jwt
now = int(time.time())
print(jwt.encode({"iss": os.environ["CLIENT"], "sub": os.environ["CLIENT"], "aud": os.environ["TOKEN_URL"],
                  "iat": now, "exp": now + 120, "jti": str(uuid.uuid4())},
                 open("my-private-key.pem", "rb").read(), algorithm="RS384", headers={"kid": os.environ["KID"]}))
EOF
)

# 3. ask for a token for this one manifest
ACCESS=$(curl -s -X POST "$TOKEN_URL" \
  --data-urlencode grant_type=client_credentials \
  --data-urlencode client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer \
  --data-urlencode "client_assertion=$ASSERTION" \
  --data-urlencode "authorization_details=[{\"type\":\"arex-manifest\",\"manifest\":\"$MANIFEST\"}]" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')
echo "got a token: ${#ACCESS} characters"

# 4. read the manifest
curl -s -H "Authorization: Bearer $ACCESS" $BASE/arex/List/$MANIFEST | python3 -c 'import sys,json; m=json.load(sys.stdin); print("manifest status:", m["status"], "| entries:", len(m["entry"]))'

# 5 + 6. fetch each image and verify it
mkdir -p by-hand
while IFS=$'\t' read -r ID URL DIGEST SIZE TYPE; do
  curl -s -H "Authorization: Bearer $ACCESS" -H "Accept: $TYPE" -o "by-hand/$ID.png" "$URL"
  GOT=$(sha256sum "by-hand/$ID.png" | cut -d' ' -f1); LEN=$(stat -c%s "by-hand/$ID.png")
  if [ "$GOT" = "$DIGEST" ] && [ "$LEN" = "$SIZE" ]; then echo "OK   $ID ($LEN bytes, digest matches)"; else echo "FAIL $ID"; fi
done < objects.tsv
```

### What each step returns

**Discovery** (`GET /arex/.well-known/smart-configuration`, no token needed):

```json
{
  "issuer": "https://<server>/arex",
  "token_endpoint": "https://<server>/arex/auth/token",
  "grant_types_supported": ["client_credentials"],
  "token_endpoint_auth_methods_supported": ["private_key_jwt"],
  "token_endpoint_auth_signing_alg_values_supported": ["RS384", "ES384"],
  "scopes_supported": ["system/Binary.r", "system/List.rs"],
  "authorization_details_types_supported": ["arex-manifest"]
}
```

**Token** (`POST` to the token endpoint, form-encoded): `200` with `access_token`, `token_type: "bearer"`, `expires_in: 300`, `scope`, and an echo of your `authorization_details`. The response is marked `Cache-Control: no-store`.

**Manifest** (`GET /arex/List/<manifest id>`): the `List` exactly as it was in your Bundle, with `status: current`.

**Image** (`GET` the entry's `content.attachment.url`): the raw bytes with `Content-Type: image/png` when you send `Accept: image/png`. Send `Accept: application/fhir+json` to get a FHIR `Binary` with base64 `data` instead. You can also ask for part of a file: `Range: bytes=0-9` returns `206` with a `Content-Range` header.

**Find the manifest by the referral id** (optional): `GET /arex/List?arex-manifest-transaction-identifier=urn:ohia:referral-id|<referral id>` returns a search `Bundle` with your manifest, only if your token is for it.

## The rules to know

- **Anonymous access is never allowed.** Every manifest and image request needs a token.
- **A token opens exactly one manifest** and that manifest's images, nothing else. Ask for a second manifest and you need a second token.
- **A token lasts 300 seconds (5 minutes).** Get a new one when it runs out; that needs a new client assertion.
- **A client assertion works once.** Its `jti` is remembered, so you cannot send the same one twice. Make a new one for every token request.
- **The manifest stays retrievable for 30 days** from the moment it was sent (the `arex-expires` value in the manifest). After that, retrieval is refused even with a brand-new token. The organizer can also retract it earlier.
- **Always verify the digest.** Do not use an image whose SHA-256 or size does not match what the manifest declares.
- The images in this test are **8x8 placeholder PNGs**, not radiographs, and the manifest's signature is a placeholder.

## When something goes wrong

| You see | What it means | What to do |
|---|---|---|
| Token request: `401 invalid_client` | Your assertion was refused. The answer is the same for every cause on purpose. Common causes: you signed with a different key than the one you registered, the `kid` does not match, wrong algorithm (only RS384 or ES384), `aud` is not the token URL exactly, `exp` is more than 5 minutes ahead or already past, or you reused a `jti`. | Check each of those; make a fresh assertion. |
| Token request: `400 invalid_grant` | That manifest is not available to you: it does not exist, was meant for another tester, has expired, or was retracted. These look identical on purpose. | Check the manifest id in your Bundle. Ask the organizer to confirm your registration and to send again. |
| Token request: `400 invalid_request` | `authorization_details` is missing, or is not exactly one `{"type":"arex-manifest","manifest":"<id>"}` entry. | Fix the request. |
| Token request: `400 invalid_scope` | Only `system/List.rs` and `system/Binary.r` are offered. | Drop or correct `scope`. |
| Manifest or image: `401` | No token, a bad token, or the 5-minute token ran out. | Get a new token. |
| Manifest or image: `404` | Not found **or not yours**, expired, or retracted. By design it never says which. | Check you are using the token for this manifest, and that the 30 days have not passed. |
| Image: `416` | The `Range` header is not valid for this file. | Fix or drop it. |
| `FAIL ... digest or size mismatch` | The bytes you got differ from what the manifest declares. | Do not use the file. Tell the organizer. |

## Other languages

Any JOSE library can do step 3. The assertion is a JWT with:

| Part | Value |
|---|---|
| Header `alg` | `RS384` (RSA key of 2048 bits or more) or `ES384` (EC P-384) |
| Header `kid` | the key id of your registered public key (recommended) |
| `iss` and `sub` | your client id (both the same) |
| `aud` | the `token_endpoint` from discovery, exactly |
| `iat` | now |
| `exp` | now + at most 300 seconds |
| `jti` | a new unique value every time |

The token request is a form `POST` with these fields: `grant_type=client_credentials`, `client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer`, `client_assertion=<the JWT>`, `authorization_details=[{"type":"arex-manifest","manifest":"<manifest id>"}]`, and optionally `scope=system/List.rs system/Binary.r`.

Only the Python and curl versions above were run. Another language is your own to verify.

## Safety notes

- Never send your **private** key to anyone, and never paste it, or an access token, into chat, tickets or logs.
- Do not disable TLS certificate checks to "make it work".
- The `arex-manifest` value in `authorization_details` is this server's own choice, because the draft spec page that was read does not name a type. If your client library needs a different value, tell the organizer.
