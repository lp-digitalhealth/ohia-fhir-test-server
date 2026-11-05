# A stand-in SENDER's ARex server, written independently of the facade (Python + PyJWT; the facade uses
# Java + Nimbus). It verifies the client assertion the facade signs, using the PUBLIC key it fetches from the
# facade's /arex/jwks.json, then serves discovery, a token, the published manifest and the images.
# Every failure a real sender could show can be switched on through the attributes below.
import http.server
import json
import threading
import time
import urllib.request
import uuid

import jwt  # PyJWT

_no_proxy = urllib.request.build_opener(urllib.request.ProxyHandler({}))


class Sender:
    def __init__(self, port, facade, manifest, photos, client_id="ohia-test-server", token_type="arex-manifest"):
        self.port, self.facade, self.manifest, self.photos = port, facade, manifest, photos
        self.client_id, self.token_type = client_id, token_type
        self.base = "http://127.0.0.1:%d" % port
        self.token_endpoint = self.base + "/arex/auth/token"
        # switches
        self.tamper = False
        self.refuse_token = False
        self.manifest_mode = "current"       # current | retracted | missing
        self.unauthorized_once = False
        self.photo_overrides = {}            # entry id -> bytes to serve instead
        # observations
        self.requests = []
        self.assertions_verified = 0
        self.assertions_rejected = []
        self.tokens = {}
        self.seen_jti = set()
        self._jwks = None
        self._server = http.server.ThreadingHTTPServer(("127.0.0.1", port), self._handler())
        threading.Thread(target=self._server.serve_forever, daemon=True).start()

    def stop(self):
        self._server.shutdown()

    def reset(self):
        self.tamper = self.refuse_token = self.unauthorized_once = False
        self.manifest_mode = "current"
        self.photo_overrides = {}
        self.requests.clear()
        self.assertions_verified = 0
        self.assertions_rejected.clear()
        self.tokens.clear()

    def count(self, prefix):
        return sum(1 for r in self.requests if r.startswith(prefix))

    # ---- verifying the facade's assertion with its published public key
    def _public_key(self, kid):
        for attempt in (1, 2):
            if self._jwks is None or attempt == 2:
                with _no_proxy.open(self.facade + "/arex/jwks.json", timeout=15) as r:
                    self._jwks = json.loads(r.read())
            for k in self._jwks["keys"]:
                if k.get("kid") == kid:
                    return jwt.algorithms.RSAAlgorithm.from_jwk(json.dumps(k))
        raise ValueError("unknown kid " + str(kid))

    def check_assertion(self, assertion):
        header = jwt.get_unverified_header(assertion)
        if header.get("alg") != "RS384":
            return "alg " + str(header.get("alg"))
        key = self._public_key(header.get("kid"))
        claims = jwt.decode(assertion, key=key, algorithms=["RS384"], audience=self.token_endpoint,
                            options={"require": ["exp", "iss", "sub", "aud", "jti"]})
        if claims["iss"] != self.client_id or claims["sub"] != self.client_id:
            return "iss/sub"
        if claims["exp"] - time.time() > 330:
            return "exp too far"
        if claims["jti"] in self.seen_jti:
            return "jti replay"
        self.seen_jti.add(claims["jti"])
        return None

    # ---- HTTP
    def _handler(self):
        outer = self

        class H(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _send(self, code, body=b"", ctype="application/json"):
                self.send_response(code)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def _authorized(self):
                h = self.headers.get("Authorization", "")
                return h.startswith("Bearer ") and h[7:] in outer.tokens

            def do_GET(self):
                outer.requests.append("GET " + self.path)
                if self.path == "/arex/.well-known/smart-configuration":
                    return self._send(200, json.dumps({"token_endpoint": outer.token_endpoint,
                                                       "authorization_details_types_supported": [outer.token_type]}).encode())
                if self.path.startswith("/arex/List/"):
                    if not self._authorized():
                        return self._send(401)
                    if outer.manifest_mode == "missing" or self.path.split("/")[-1] != outer.manifest["id"]:
                        return self._send(404)
                    m = json.loads(json.dumps(outer.manifest))
                    if outer.manifest_mode == "retracted":
                        m["status"] = "retracted"
                    return self._send(200, json.dumps(m).encode(), "application/fhir+json")
                if self.path.startswith("/arex/Binary/"):
                    if outer.unauthorized_once:
                        outer.unauthorized_once = False
                        return self._send(401)
                    if not self._authorized():
                        return self._send(401)
                    entry = self.path.split("/")[-1]
                    data = outer.photo_overrides.get(entry, outer.photos.get(entry))
                    if data is None:
                        return self._send(404)
                    if outer.tamper:
                        data = data[:-1] + bytes([data[-1] ^ 1])
                    return self._send(200, data, "image/jpeg")
                self._send(404)

            def do_POST(self):
                outer.requests.append("POST " + self.path)
                body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
                if self.path != "/arex/auth/token":
                    return self._send(404)
                form = dict(p.split("=", 1) for p in body.split("&") if "=" in p)
                from urllib.parse import unquote_plus
                form = {k: unquote_plus(v) for k, v in form.items()}
                if outer.refuse_token or form.get("grant_type") != "client_credentials":
                    outer.assertions_rejected.append("refused by switch")
                    return self._send(401, b'{"error":"invalid_client"}')
                try:
                    problem = outer.check_assertion(form.get("client_assertion", ""))
                except Exception as e:  # any verification error is a refusal
                    problem = "verification failed: " + type(e).__name__
                if problem:
                    outer.assertions_rejected.append(problem)
                    return self._send(401, json.dumps({"error": "invalid_client", "error_description": problem}).encode())
                outer.assertions_verified += 1
                details = json.loads(form.get("authorization_details", "[]"))
                if len(details) != 1 or details[0].get("type") != outer.token_type:
                    return self._send(400, b'{"error":"invalid_request"}')
                if details[0].get("manifest") != outer.manifest["id"]:
                    return self._send(400, b'{"error":"invalid_grant"}')
                token = str(uuid.uuid4())
                outer.tokens[token] = time.time() + 300
                return self._send(200, json.dumps({"access_token": token, "token_type": "bearer", "expires_in": 300}).encode())

        return H
