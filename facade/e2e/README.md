# End-to-end check (sandbox or any machine with Docker and Python)

Runs the whole UC07 flow against a **real HAPI** and the **real data repository at the pinned commit**, with a stand-in tester that uses a different JWT library (PyJWT) than the facade (Nimbus).
It is not part of `mvn test` because it needs Docker, the network and Python. Needs: `pip install PyJWT cryptography`.

```bash
cd facade
mvn -q package -DskipTests                       # builds target/ohia-facade-0.1.0.jar
docker run -d --name e2e-hapi -p 8090:8080 -e HAPI_FHIR_FHIR_VERSION=R4 hapiproject/hapi:latest
mkdir -p /tmp/e2e/data && python3 e2e/gen_registry.py /tmp/e2e          # tester key + registry
export API_TOKEN=any-long-test-value
API_TOKEN=$API_TOKEN FACADE_HAPI_BASE_URL=http://localhost:8090/fhir FACADE_DATA_DIR=/tmp/e2e/data \
  FACADE_PUBLIC_BASE_URL=http://localhost:8091 FACADE_CLIENTS_FILE=/tmp/e2e/e2e-clients.json SERVER_PORT=8091 \
  java -jar target/ohia-facade-0.1.0.jar --facade.send.allow-http=true --facade.send.allow-private-targets=true &
# wait until http://localhost:8091/internal/health answers, then:
python3 e2e/run_e2e.py /tmp/e2e                  # prints PASS/FAIL per check; exit 0 = all passed
docker rm -f e2e-hapi
```

## UC04a receiver and viewer

`run_uc04a_e2e.py` sends the real UC04a bundle to `/ode/receive/uc04a` with an independent Python sender (`uc04a_sender_stub.py`, PyJWT) that verifies the facade's signed assertion with the key from `/arex/jwks.json`. Start HAPI and the facade as above (no clients file needed), then:

```bash
API_TOKEN=$API_TOKEN E2E_PAYLOAD=authored python3 e2e/run_uc04a_e2e.py      # authored | no-subscription | with-filter
API_TOKEN=$API_TOKEN FACADE_DATA_DIR=/tmp/e2e/data SHOTS=/tmp/shots node e2e/viewer_check.js   # needs Playwright
```

`run_uc07_receive_e2e.py` does the same for a UC07 referral (`POST /ode/receive/uc07`). `ui_check.js` drives the page's Send, Sent and Received tabs against the real facade with a stand-in tester on port 9300; start the facade with a registry that lists a tester `tester-ui` whose `allowedTargetPrefixes` is `http://127.0.0.1:9300/fhir` and whose `organizationsByUseCase` maps `uc04a` to `Organization/org-barton-springs-dental`, run `run_uc07_receive_e2e.py` first (it leaves a UC07 receipt), and start from an empty data folder (the check begins with "data not loaded").

`authored` is the data as published: real HAPI refuses its Subscription, so 29 entries are stored and `SUBSCRIPTION_NOT_STORED` is reported. Run the browser check after the Python runner (it opens a receipt that run leaves).

The two `--facade.send.*` flags exist for tests only (the stand-in receiver is plain http on 127.0.0.1). Never set them on a real deployment.
If a proxy intercepts localhost traffic, set `no_proxy=localhost,127.0.0.1`.
