# End-to-end check: the facade RECEIVES a UC07 referral and PULLS its radiographs with ARex.
# Real HAPI, the UC07 data (the repository copy in src/test/resources/uc07-i1), and the independent Python
# sender stand-in (uc04a_sender_stub.py, PyJWT) that verifies the facade's signed assertion.
# Needs: pip install PyJWT cryptography. Env: API_TOKEN, FACADE, SENDER_PORT (default 9201).
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uc04a_sender_stub import Sender  # noqa: E402

FACADE = os.environ.get("FACADE", "http://localhost:8091")
S1 = os.environ["API_TOKEN"]
PORT = int(os.environ.get("SENDER_PORT", "9201"))
LOCAL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "test", "resources", "uc07-i1")
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


bundle = json.load(open(os.path.join(LOCAL, "interaction-01-bundle.json"), encoding="utf-8"))
resources = [e["resource"] for e in bundle["entry"]]
manifest = next(r for r in resources if r["resourceType"] == "List")
entries = [r for r in resources if r["resourceType"] == "DocumentReference" and any(p.endswith("arex-content-entry") for p in r.get("meta", {}).get("profile", []))]
images = {e["id"]: open(os.path.join(LOCAL, "radiograph-" + e["id"][len("arex-entry-"):] + ".png"), "rb").read() for e in entries}
sender = Sender(PORT, FACADE, manifest, images)
for e in entries:  # the tester's content server is the stand-in: point the payload's URLs at it
    e["content"][0]["attachment"]["url"] = sender.base + "/arex/Binary/" + e["id"]
payload = json.dumps(bundle).encode()
H = {"Authorization": "Bearer " + S1}

st, _, b = call("POST", FACADE + "/ode/receive/uc07", {**H, "Content-Type": "application/fhir+json"}, payload)
rc = json.loads(b) if st == 200 else {}
check("UC07 payload received (HTTP 200) and accepted", st == 200 and rc.get("accepted") is True, st)
check("tagged uc07, all %d entries stored" % len(bundle["entry"]), rc.get("useCase") == "uc07" and rc.get("hapiEntries") == len(bundle["entry"]), (rc.get("useCase"), rc.get("hapiEntries")))
check("referral id read from the payload", str(rc.get("referralId", "")).startswith("REF-2026-UC07-001"), rc.get("referralId"))
check("every radiograph pulled and digest verified", [o["status"] for o in rc.get("objects", [])] == ["saved"] * len(images), [o["status"] for o in rc.get("objects", [])])
check("an independent sender (PyJWT) verified our signed assertion", sender.assertions_verified == 1 and not sender.assertions_rejected, sender.assertions_rejected)
ok = True
for o in rc.get("objects", []):
    st, hd, data = call("GET", "%s/ode/receipts/%s/images/%s" % (FACADE, rc["id"], o["fileKey"]), H)
    ok &= st == 200 and data == images[o["entryId"]] and hd.get("Content-Type") == "image/png" and hashlib.sha256(data).hexdigest() == o["declaredSha256"]
check("each radiograph is served back byte for byte as image/png", ok)
st, _, b = call("GET", FACADE + "/ode/receipts?useCase=uc07", H)
check("the uc07 filter lists it", st == 200 and any(r["id"] == rc.get("id") for r in json.loads(b)))
st, _, b = call("GET", FACADE + "/ode/receipts?useCase=uc04a", H)
check("the uc04a filter does not", st == 200 and not any(r["id"] == rc.get("id") for r in json.loads(b)))
sender.stop()
failed = [n for n, ok in results if not ok]
print("\n%d checks, %d failed" % (len(results), len(failed)))
sys.exit(1 if failed else 0)
