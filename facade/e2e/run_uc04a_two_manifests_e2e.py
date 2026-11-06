# End-to-end check: ONE UC04a payload with TWO ARex manifests (one for the photos, one for a PDF), each served by its own
# independent Python sender (PyJWT) on its own port, so a token for one cannot fetch the other. The facade must request one
# token per manifest, with the scope the senders expect, and pull all three objects. Leaves a receipt for ui_check.js.
# Needs: pip install PyJWT cryptography. Env: API_TOKEN, FACADE, SENDER_PORT (default 9210; uses PORT and PORT+1).
import copy
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
PORT = int(os.environ.get("SENDER_PORT", "9210"))
LOCAL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "test", "resources", "uc04a-i1")
SCOPE = "system/List.r system/DocumentReference.r system/Provenance.r system/Binary.r"
PDF = b"%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n"
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
entries = bundle["entry"]
wrap = lambda rt, rid: next(e for e in entries if e["resource"]["resourceType"] == rt and e["resource"]["id"] == rid)
manifest_a = next(e["resource"] for e in entries if e["resource"]["resourceType"] == "List" and e["resource"]["id"] == "arex-manifest-intraoral-photos")
photos = {i: open(os.path.join(LOCAL, "intraoral-photo-%s.jpg" % n), "rb").read() for i, n in (("arex-entry-photo-1", 1), ("arex-entry-photo-2", 2))}

# the PDF content entry: a copy of photo 1 with its own id, type, size and digest
doc_wrap = copy.deepcopy(wrap("DocumentReference", "arex-entry-photo-1"))
doc = doc_wrap["resource"]
doc["id"] = "arex-entry-doc-1"
doc_wrap["request"]["url"] = "DocumentReference/arex-entry-doc-1"
doc_wrap["fullUrl"] = "urn:uuid:11111111-1111-1111-1111-111111111111"
att = doc["content"][0]["attachment"]
att["contentType"], att["size"] = "application/pdf", len(PDF)
for ext in att["extension"]:
    for part in ext["extension"]:
        if part["url"] == "value":
            part["valueString"] = hashlib.sha256(PDF).hexdigest()
# manifest B lists only the PDF (item reference written ABSOLUTE); manifest A lists only the photos (relative)
mb_wrap = copy.deepcopy(wrap("List", "arex-manifest-intraoral-photos"))
manifest_b = mb_wrap["resource"]
manifest_b["id"] = "arex-manifest-documents"
mb_wrap["request"]["url"] = "List/arex-manifest-documents"
mb_wrap["fullUrl"] = "urn:uuid:22222222-2222-2222-2222-222222222222"
manifest_b["entry"] = [{"item": {"reference": "https://their-app.example.org/fhir/DocumentReference/arex-entry-doc-1"}}]
manifest_a["entry"] = [{"item": {"reference": "DocumentReference/arex-entry-photo-1"}}, {"item": {"reference": "DocumentReference/arex-entry-photo-2"}}]
entries.extend([doc_wrap, mb_wrap])

photo_sender = Sender(PORT, FACADE, manifest_a, photos)
pdf_sender = Sender(PORT + 1, FACADE, manifest_b, {"arex-entry-doc-1": PDF}, content_types={"arex-entry-doc-1": "application/pdf"})
for e in entries:
    r = e["resource"]
    if r["resourceType"] == "DocumentReference" and any(p.endswith("arex-content-entry") for p in r.get("meta", {}).get("profile", [])):
        sender = pdf_sender if r["id"] == "arex-entry-doc-1" else photo_sender
        r["content"][0]["attachment"]["url"] = sender.base + "/arex/Binary/" + r["id"]
payload = json.dumps(bundle).encode()
H = {"Authorization": "Bearer " + S1}

st, _, b = call("POST", FACADE + "/ode/receive/uc04a", {**H, "Content-Type": "application/fhir+json"}, payload)
rc = json.loads(b) if st == 200 else {}
check("payload with two manifests received and accepted", st == 200 and rc.get("accepted") is True, st)
check("both manifests are recorded on the receipt", rc.get("manifestId") == "arex-manifest-intraoral-photos, arex-manifest-documents", rc.get("manifestId"))
check("all three objects pulled and digest verified", [o["status"] for o in rc.get("objects", [])] == ["saved"] * 3, [o["status"] for o in rc.get("objects", [])])
by = {o["entryId"]: o for o in rc.get("objects", [])}
check("each object is linked to its own manifest", by.get("arex-entry-doc-1", {}).get("manifestId") == "arex-manifest-documents" and by.get("arex-entry-photo-1", {}).get("manifestId") == "arex-manifest-intraoral-photos")
check("one token per manifest, each from its own sender", photo_sender.count("POST /arex/auth/token") == 1 and pdf_sender.count("POST /arex/auth/token") == 1, (photo_sender.count("POST /arex/auth/token"), pdf_sender.count("POST /arex/auth/token")))
check("independent senders (PyJWT) verified both signed assertions", photo_sender.assertions_verified == 1 and pdf_sender.assertions_verified == 1 and not photo_sender.assertions_rejected and not pdf_sender.assertions_rejected)
check("the scope asked for is the one the tester specified", photo_sender.scopes == [SCOPE] and pdf_sender.scopes == [SCOPE], (photo_sender.scopes, pdf_sender.scopes))
check("the photos sender was never asked for the PDF, nor the PDF sender for a photo", photo_sender.count("GET /arex/Binary/arex-entry-doc-1") == 0 and pdf_sender.count("GET /arex/Binary/arex-entry-photo") == 0)
st, hd, data = call("GET", "%s/ode/receipts/%s/images/%s" % (FACADE, rc.get("id"), by["arex-entry-doc-1"]["fileKey"]), H)
check("the PDF comes back byte for byte, and only as a download", st == 200 and data == PDF and hd.get("Content-Type") == "application/octet-stream" and hd.get("Content-Disposition", "").startswith("attachment"), hd.get("Content-Type"))
photo_sender.stop()
pdf_sender.stop()
failed = [n for n, ok in results if not ok]
print("\n%d checks, %d failed" % (len(results), len(failed)))
sys.exit(1 if failed else 0)
