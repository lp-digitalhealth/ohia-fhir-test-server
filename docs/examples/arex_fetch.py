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
