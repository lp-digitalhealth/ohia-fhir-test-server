# End-to-end check: the facade RECEIVES a UC04a payload and PULLS its images with ARex.
# Real HAPI, the real UC04a data (pinned commit of ohia-test-data), and an independent Python sender
# stand-in (uc04a_sender_stub.py) that verifies the facade's signed assertion with PyJWT.
# See facade/e2e/README.md for how to start the pieces. Needs: pip install PyJWT cryptography
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uc04a_sender_stub import Sender  # noqa: E402

FACADE = os.environ.get("FACADE", "http://localhost:8091")
HAPI = os.environ.get("HAPI", "http://localhost:8090/fhir")
S1 = os.environ["API_TOKEN"]
PORT = int(os.environ.get("SENDER_PORT", "9200"))
PIN = "42ddc430a4ae80cb10d29345a60a4bd4795e7937"   # ohia-test-data commit the fixtures come from
PKG = "2026-CMS-Connectathon/fhir-resources/purpose-built/uc04a-teledentistry-commercial/interactions/interaction-01"
LOCAL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "test", "resources", "uc04a-i1")
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
results = []


def check(name, ok, detail=""):
    results.append((name, ok))
    print(("PASS " if ok else "FAIL ") + name + ((" | " + str(detail)) if detail != "" else ""))


def call(method, url, headers=None, data=None):
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with opener.open(req, timeout=120) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def source(name, local_name):
    """The real file from the pinned commit; falls back to the identical repository copy if offline."""
    st, _, body = call("GET", "https://raw.githubusercontent.com/lp-digitalhealth/ohia-test-data/%s/%s/%s" % (PIN, PKG, name))
    if st == 200:
        return body, "GitHub " + PIN[:7]
    return open(os.path.join(LOCAL, local_name), "rb").read(), "repository copy"


bundle_bytes, origin = source("interaction-01-bundle.json", "interaction-01-bundle.json")
photo1, _ = source("arex-payloads/intraoral-photo-1.jpg", "intraoral-photo-1.jpg")
photo2, _ = source("arex-payloads/intraoral-photo-2.jpg", "intraoral-photo-2.jpg")
photos = {"arex-entry-photo-1": photo1, "arex-entry-photo-2": photo2}
print("data source:", origin)

bundle = json.loads(bundle_bytes)
# E2E_PAYLOAD chooses the payload variant: "authored" (as in the data repository), "no-subscription" (the
# Subscription removed) or "with-filter" (the Subscription given the backport-filter-criteria extension).
# Real HAPI 8.12.0 refuses the authored Subscription (HAPI-0011). The facade then stores the rest and reports
# SUBSCRIPTION_NOT_STORED, so for "authored" we expect 29 stored entries and that one extra finding.
VARIANT = os.environ.get("E2E_PAYLOAD", "authored")
if VARIANT == "no-subscription":
    bundle["entry"] = [e for e in bundle["entry"] if e["resource"]["resourceType"] != "Subscription"]
elif VARIANT == "with-filter":
    for e in bundle["entry"]:
        if e["resource"]["resourceType"] == "Subscription":
            e["resource"]["_criteria"] = {"extension": [{"url": "http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/backport-filter-criteria", "valueString": "Task?_id=task-referral-tracking"}]}
DROPPED = VARIANT == "authored"
EXPECTED_ENTRIES = len(bundle["entry"]) - (1 if DROPPED else 0)
EXPECTED_CODES = {"SIGNATURE_PLACEHOLDER", "MANIFEST_EXPIRED_PER_PAYLOAD"} | ({"SUBSCRIPTION_NOT_STORED"} if DROPPED else set())
print("payload variant:", VARIANT, "| entries:", EXPECTED_ENTRIES)
resources = [e["resource"] for e in bundle["entry"]]
manifest = next(r for r in resources if r["resourceType"] == "List")
entries = [r for r in resources if r["resourceType"] == "DocumentReference" and any(p.endswith("arex-content-entry") for p in r.get("meta", {}).get("profile", []))]
sender = Sender(PORT, FACADE, manifest, photos)
for e in entries:  # the tester's content server is the stand-in: point the payload's URLs at it
    e["content"][0]["attachment"]["url"] = sender.base + "/arex/Binary/" + e["id"]
payload = json.dumps(bundle).encode()
H = {"Authorization": "Bearer " + S1}


def receive(body=payload, headers=None):
    st, hd, b = call("POST", FACADE + "/ode/receive/uc04a", {**(headers or H), "Content-Type": "application/fhir+json"}, body)
    return st, (json.loads(b) if b and st == 200 else b)


def image(rid, key):
    return call("GET", "%s/ode/receipts/%s/images/%s" % (FACADE, rid, key), H)


def statuses(rc):
    return [o["status"] for o in rc["objects"]]


def codes(rc):
    return {f["code"] for f in rc["findings"]}


# ---- 1. the server's identity and the open public key
st, _, b = call("GET", FACADE + "/arex/jwks.json")
jwks = json.loads(b)
check("our public key is published without a token and has no private parts", st == 200 and len(jwks["keys"]) == 1 and not any(k in jwks["keys"][0] for k in ("d", "p", "q", "dp", "dq", "qi")), jwks["keys"][0].get("kid"))
st, _, b = call("GET", FACADE + "/ode/identity", H)
ident = json.loads(b)
check("the organizer can read our identity to hand to a tester", st == 200 and ident["client_id"] == "ohia-test-server" and ident["jwks_uri"].endswith("/arex/jwks.json"), ident["jwks_uri"])
check("identity needs the shared token", call("GET", FACADE + "/ode/identity")[0] == 401)

# ---- 2. the happy path
sender.reset()
st, rc = receive()
check("payload received (HTTP 200) and accepted", st == 200 and rc["accepted"] is True, st)
check("%d entries stored in HAPI as received" % EXPECTED_ENTRIES, rc["hapiEntries"] == EXPECTED_ENTRIES, rc["hapiEntries"])
check("referral and manifest identified", rc["referralId"] == "REF-2026-UC04A-001" and rc["manifestId"] == "arex-manifest-intraoral-photos", (rc["referralId"], rc["manifestId"]))
check("both images pulled and digest verified", statuses(rc) == ["saved", "saved"] and all(o["viewable"] for o in rc["objects"]), statuses(rc))
check("findings are exactly the data's known gaps", codes(rc) == EXPECTED_CODES, sorted(codes(rc)))
check("an independent sender (PyJWT) verified our signed assertion with our published key", sender.assertions_verified == 1 and not sender.assertions_rejected, sender.assertions_rejected)
check("sender saw: discovery, one token, the published manifest, two images", sender.count("GET /arex/.well-known") == 1 and sender.count("POST /arex/auth/token") == 1 and sender.count("GET /arex/List/") == 1 and sender.count("GET /arex/Binary/") == 2, sender.requests)
st, _, b = call("GET", HAPI + "/ServiceRequest/servicerequest-referral", {"Accept": "application/fhir+json"})
check("the referral really is in HAPI", st == 200 and json.loads(b)["resourceType"] == "ServiceRequest")
ok = True
for o in rc["objects"]:
    st, hd, data = image(rc["id"], o["fileKey"])
    ok &= st == 200 and data == photos[o["entryId"]] and hd.get("Content-Type") == "image/jpeg" and hashlib.sha256(data).hexdigest() == o["declaredSha256"]
check("each pulled image is served back byte for byte as image/jpeg", ok)
good_receipt = rc["id"]

# ---- 3. failure modes (each against the same payload)
sender.reset(); sender.tamper = True
st, rc = receive()
check("a tampered image is reported and never stored or served", statuses(rc) == ["digest-mismatch"] * 2 and "DIGEST_MISMATCH" in codes(rc) and image(rc["id"], rc["objects"][0]["fileKey"])[0] == 404, statuses(rc))

sender.reset(); sender.manifest_mode = "retracted"
st, rc = receive()
check("a retracted published manifest stops the pull before any download", "MANIFEST_NOT_CURRENT" in codes(rc) and sender.count("GET /arex/Binary/") == 0 and statuses(rc) == ["not-pulled"] * 2, sorted(codes(rc)))

sender.reset(); sender.manifest_mode = "missing"
st, rc = receive()
check("a sender with no manifest endpoint is a finding and the images are still pulled", "PUBLISHED_MANIFEST_UNAVAILABLE" in codes(rc) and statuses(rc) == ["saved", "saved"], sorted(codes(rc)))

sender.reset(); sender.refuse_token = True
st, rc = receive()
check("a refused token is reported, the payload is still stored", rc["accepted"] is True and "TOKEN_REFUSED" in codes(rc) and statuses(rc) == ["not-pulled"] * 2, sorted(codes(rc)))

sender.reset(); sender.unauthorized_once = True
st, rc = receive()
check("a token that runs out mid-download is replaced once", statuses(rc) == ["saved", "saved"] and sender.count("POST /arex/auth/token") == 2,
      {"statuses": statuses(rc), "token_posts": sender.count("POST /arex/auth/token"), "stopped": rc.get("stoppedBecause"), "requests": sender.requests})

html = b"<html><script>alert(document.cookie)</script></html>"
sender.reset(); sender.photo_overrides = {"arex-entry-photo-1": html}
hostile = json.loads(payload)
for e in [r["resource"] for r in hostile["entry"]]:
    if e["resourceType"] == "DocumentReference" and e["id"] == "arex-entry-photo-1":
        att = e["content"][0]["attachment"]
        att["size"] = len(html)
        for ext in att["extension"]:
            for part in ext["extension"]:
                if part["url"] == "value":
                    part["valueString"] = hashlib.sha256(html).hexdigest()
st, rc = receive(json.dumps(hostile).encode())
st1, hd1, _ = image(rc["id"], rc["objects"][0]["fileKey"])
check("html served as an image is stored but only ever offered as a download", rc["objects"][0]["viewable"] is False and hd1.get("Content-Type") == "application/octet-stream" and hd1.get("Content-Disposition", "").startswith("attachment") and rc["objects"][1]["viewable"] is True, hd1.get("Content-Type"))

# ---- 4. refusals before anything is written
check("shared token required to receive", call("POST", FACADE + "/ode/receive/uc04a", {"Content-Type": "application/json"}, payload)[0] == 401)
check("something that is not a transaction Bundle is refused (422)", receive(b'{"resourceType":"Patient"}')[0] == 422)
st, _, b = call("GET", FACADE + "/ode/receipts?limit=5", H)
check("receipts are listed newest first", st == 200 and len(json.loads(b)) >= 1)

sender.stop()
failed = [n for n, ok in results if not ok]
print("\n%d checks, %d failed" % (len(results), len(failed)))
print("a good receipt for the browser check:", good_receipt)
sys.exit(1 if failed else 0)
