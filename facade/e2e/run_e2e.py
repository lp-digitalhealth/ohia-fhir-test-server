# End-to-end check for the facade (facade/e2e/README.md says how to run it).
# A stand-in tester for the end-to-end run. It plays BOTH roles a real participant has:
#   1. receiver of the UC07 referral (a FHIR endpoint that accepts the transaction Bundle), and
#   2. ARex client (discovery, private_key_jwt token, manifest, images, digest verification).
# Nothing here uses facade internals: it only speaks HTTP, as an outside tester would.
import base64, hashlib, http.server, json, os, sys, threading, time, urllib.error, urllib.parse, urllib.request, uuid
import jwt  # PyJWT

SCRATCH = sys.argv[1]
FACADE = os.environ.get("FACADE", "http://localhost:8091")   # the facade as the tester sees it
S1 = os.environ["API_TOKEN"]                                   # the organizer (shared) token; never hard-code it
RECEIVER_PORT = 9100
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # no proxy for localhost
results = []

def check(name, ok, detail=""):
    results.append((name, ok))
    print(("PASS " if ok else "FAIL ") + name + ((" | " + str(detail)) if detail != "" else ""))

def call(method, url, headers=None, data=None):
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with opener.open(req, timeout=60) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()

# ---- 1. receiver
received = []
class Receiver(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        received.append({"path": self.path, "auth": self.headers.get("Authorization"), "body": body})
        out = b'{"resourceType":"Bundle","type":"transaction-response"}'
        self.send_response(200); self.send_header("Content-Type", "application/fhir+json")
        self.send_header("Content-Length", str(len(out))); self.end_headers(); self.wfile.write(out)
    def log_message(self, *a): pass
srv = http.server.ThreadingHTTPServer(("127.0.0.1", RECEIVER_PORT), Receiver)
threading.Thread(target=srv.serve_forever, daemon=True).start()

h1 = {"Authorization": "Bearer " + S1}
# ---- 2. organizer: seed (real GitHub at the pinned commit, real HAPI) and send
st, _, b = call("POST", FACADE + "/ode/admin/seed", h1)
seed = json.loads(b); check("seed from pinned GitHub commit into real HAPI", st == 200 and seed["resources"] == 37 and seed["payloadsVerified"] == 2, seed.get("ref"))
st, _, b = call("POST", FACADE + "/ode/send", {**h1, "Content-Type": "application/json"},
                json.dumps({"clientId": "tester-e2e", "targetBaseUrl": "http://127.0.0.1:%d/fhir" % RECEIVER_PORT, "targetBearerToken": "tester-own-token"}).encode())
rec = json.loads(b)
check("send: tester's receiver answered 2xx", st == 200 and rec["outcome"] == "delivered", {k: rec[k] for k in ("outcome", "targetStatus", "millis")})
check("receiver got exactly one POST to /fhir with its own bearer token", len(received) == 1 and received[0]["path"] == "/fhir" and received[0]["auth"] == "Bearer tester-own-token")
bundle = json.loads(received[0]["body"])
entries = [e["resource"] for e in bundle["entry"]]
check("bundle is a 37-entry transaction", bundle["type"] == "transaction" and len(entries) == 37, len(entries))
sr = next(r for r in entries if r["resourceType"] == "ServiceRequest")
task = next(r for r in entries if r["resourceType"] == "Task")
check("referral is a new, unique referral (suffixed id), not the authored one", rec["referralId"].startswith("REF-2026-UC07-001-") and rec["referralId"] in json.dumps(sr) and '"REF-2026-UC07-001"' not in json.dumps(bundle), rec["referralId"])
check("ServiceRequest and Task were renamed per send, Patient kept", sr["id"].endswith(rec["sendId"][:8]) and task["id"].endswith(rec["sendId"][:8]) and any(r["resourceType"] == "Patient" and r["id"] == "patient-joe-castle" for r in entries))
manifest = next(r for r in entries if r["resourceType"] == "List")
content = [r for r in entries if r["resourceType"] == "DocumentReference" and any(p.endswith("arex-content-entry") for p in r.get("meta", {}).get("profile", []))]
exp = next(e["valueInstant"] for e in manifest["extension"] if e["url"].endswith("arex-expires"))
check("manifest expiry is fresh (about 30 days out), not the authored 2026-07-22", exp > time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() + 29 * 86400)), exp)
check("content URLs point at the facade with opaque ids", all(c["content"][0]["attachment"]["url"].startswith(FACADE + "/arex/Binary/") and "REF" not in c["content"][0]["attachment"]["url"] for c in content))

# ---- 3. tester as ARex client
st, _, b = call("GET", FACADE + "/arex/.well-known/smart-configuration"); disco = json.loads(b)
check("SMART discovery advertises client_credentials + private_key_jwt", disco["grant_types_supported"] == ["client_credentials"] and disco["token_endpoint_auth_methods_supported"] == ["private_key_jwt"], disco["token_endpoint"])
priv = open(SCRATCH + "/tester-key.pem", "rb").read()
def assertion(jti=None, aud=None, lifetime=120):
    now = int(time.time())
    return jwt.encode({"iss": "tester-e2e", "sub": "tester-e2e", "aud": aud or disco["token_endpoint"], "iat": now, "exp": now + lifetime, "jti": jti or str(uuid.uuid4())},
                      priv, algorithm="RS384", headers={"kid": "tester-e2e-key"})
def token_request(a, manifest_id):
    form = urllib.parse.urlencode({"grant_type": "client_credentials", "client_assertion_type": "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
        "client_assertion": a, "authorization_details": json.dumps([{"type": "arex-manifest", "manifest": manifest_id}])}).encode()
    return call("POST", disco["token_endpoint"], {"Content-Type": "application/x-www-form-urlencoded"}, form)
a1 = assertion()
st, hd, b = token_request(a1, manifest["id"]); tok = json.loads(b)
check("token issued: bearer, 300 s, no-store", st == 200 and tok["token_type"] == "bearer" and tok["expires_in"] == 300 and hd.get("Cache-Control") == "no-store")
st, _, b = token_request(a1, manifest["id"])
check("same client assertion cannot be used twice (jti replay)", st == 401 and json.loads(b)["error"] == "invalid_client")
st, _, b = token_request(assertion(), "arex-manifest-radiographs")
check("a manifest id that was never sent is refused (invalid_grant)", st == 400 and json.loads(b)["error"] == "invalid_grant")
H = {"Authorization": "Bearer " + tok["access_token"]}
st, _, _ = call("GET", FACADE + "/arex/List/" + manifest["id"])
check("anonymous manifest read is refused", st == 401)
st, _, b = call("GET", FACADE + "/arex/List/" + manifest["id"], H)
check("manifest read with the token equals the manifest in the bundle", st == 200 and json.loads(b) == manifest)
all_ok = True
for c in content:
    att = c["content"][0]["attachment"]
    declared = next(x["valueString"] for ext in att["extension"] if ext["url"].endswith("arex-digest") for x in ext["extension"] if x["url"] == "value")
    st, hd, data = call("GET", att["url"], {**H, "Accept": "image/png"})
    ok = st == 200 and hashlib.sha256(data).hexdigest() == declared and len(data) == att["size"] and hd.get("Content-Type") == "image/png"
    all_ok &= ok
    st2, hd2, part = call("GET", att["url"], {**H, "Range": "bytes=0-7"})
    all_ok &= (st2 == 206 and part == data[:8] and hd2.get("Content-Range") == "bytes 0-7/%d" % len(data))
check("both images fetched; sha-256 and size match the declared digests; range request works", all_ok, "%d objects" % len(content))
st, _, _ = call("GET", content[0]["content"][0]["url"] if "url" in content[0]["content"][0] else content[0]["content"][0]["attachment"]["url"])
check("anonymous image read is refused", st == 401)
ident = next(i for i in sr["identifier"] if i["system"] == "urn:ohia:referral-id")
st, _, b = call("GET", FACADE + "/arex/List?arex-manifest-transaction-identifier=" + urllib.parse.quote("urn:ohia:referral-id|" + ident["value"]), H)
check("search by the transaction identifier finds the manifest", st == 200 and json.loads(b)["total"] == 1)

# ---- 4. organizer retracts; access ends at once
st, _, b = call("POST", FACADE + "/ode/manifests/%s/retract" % manifest["id"], h1)
check("organizer retracts the manifest", st == 200 and json.loads(b)["retracted"] is True)
st, _, _ = call("GET", content[0]["content"][0]["attachment"]["url"], H)
check("after retraction the still-valid token gets 404 on content", st == 404)
st, _, b = token_request(assertion(), manifest["id"])
check("after retraction no new token is issued (invalid_grant)", st == 400 and json.loads(b)["error"] == "invalid_grant")
st, _, b = call("GET", FACADE + "/ode/sends", h1); log = json.loads(b)
check("delivery log has the send and never the tester's token", st == 200 and log[0]["sendId"] == rec["sendId"] and "tester-own-token" not in json.dumps(log))

failed = [n for n, ok in results if not ok]
print("\n%d checks, %d failed" % (len(results), len(failed)))
sys.exit(1 if failed else 0)
