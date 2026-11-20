# Onboarding a new client to the test server

For the **organizer**: the person who runs the server and brings a new tester (a vendor, a clinic system, a payer) onto it. Read this once the server is up. It assumes you can open a terminal and SSH to the VM, and it explains each term as it comes.

**Status.** Written from the code and the other guides in this repository. No real client has completed either flow on the live VM yet (`docs/CURRENT_STATE.MD` records that as not tested). Where a step depends on something not yet proven, it says so.

## 1. The two directions

A tester connects to this server in one of two ways. Decide which one applies before you do anything else.

| | **A. The client sends to us, and we call back** | **B. We send to the client, and they call us** |
|---|---|---|
| Example | UC04a: a teledentistry platform sends a referral; this server plays the in-office practice | UC07: this server sends a referral; the client plays the receiving dentist |
| The client calls | `POST /ode/receive/uc04a` (or `/uc07`) on this server, with the shared token | its own endpoint, which we call |
| The ARex call | **We** call **their** ARex server to fetch the images, signing with **our** key | **They** call **our** ARex server to fetch images, signing with **their** key |
| What you register | Nothing on our side. The client registers **us** at their server | The client **in `facade/config/clients.json`** (their key, their organization, where we may send) |
| Where you read the result | The receipt in `/viewer/` or `GET /ode/receipts` | The delivery log, `GET /ode/sends` |

**ARex** is the draft standard for sharing images and documents: the referral carries a small manifest (a list), and the receiver pulls the actual files from the sender's server with a short-lived token. "Calling back" means that pull.

Sections 3 to 5 cover direction A (the one you described: a client calls our endpoint, then we use ARex to call them back). Section 6 covers B.

## 2. Before anyone connects: is the server healthy?

Run the harness (`ohia-facade-tester`) against your own server first. It tests the server the way a client would, so a pass means a client will at least be able to reach it and be turned away when they should be.

```bash
export BASE_URL=https://<your-hostname>          # the server root, no /fhir
export API_TOKEN=<the shared token>              # from deploy/.env on the VM; never type it into chat
python -m tester run --case baseline
```

Expect 15 of 15 steps to pass. The steps that matter for onboarding:

| Step | What it proves for a new client |
|---|---|
| B02, B03, B04 | Without a token the server says 401, with the right one it answers, with a wrong one it says 401 |
| B07 | The facade (the part that receives and calls back) is running |
| B08 | Our ARex discovery document points at the real public address, not `localhost` |
| B09 | Our public key is published and no private key is exposed |
| B14 | `/ode/identity` tells a client our client id, algorithm and key address |

If a step fails, the report says why. Fix it before onboarding anyone.

## 3. Direction A, step by step

### Step A1: collect what you need from the client

Copy this sheet into your notes and fill it in with the client.

| Item | Value |
|---|---|
| Organization and contact | |
| Role they play (UC04a platform, or other) | |
| Their ARex base URL (everything before `/Binary/` in their image links) | `https://...` |
| Their ARex discovery address (usually `<base>/.well-known/smart-configuration`) | |
| Their person who can register our key at their authorization server | |
| Which server they will send from (so you know whom to expect in `/viewer/`) | |

Their image URLs must be `https` and on a public address. A name that doesn't resolve (like the sample data's `meridian-teledental.example.org`) cannot be called back.

### Step A2: give the client what they need

Send through a private channel, not email lists or issue trackers:

1. **Base URL** (`https://<your-hostname>`), and the receive address `https://<your-hostname>/ode/receive/uc04a`.
2. **The shared token.** It gives full read and write access to this server's test data, so treat it like a password. Rotate it afterwards if it was ever shared somewhere broad (section 7).
3. **Our identity**, which is not secret. A client needs it to register us:
   ```bash
   curl -s -H "Authorization: Bearer $API_TOKEN" https://<your-hostname>/ode/identity
   ```
   It gives our client id (`ohia-test-server`), the signing algorithm (RS384) and the address of our public key (`/arex/jwks.json`, open, no token).
4. **`docs/UC04A_SENDING_GUIDE.md`**. It explains exactly what to send and what their ARex server must serve.

### Step A3: the client registers us

This happens on **their** side, and you cannot do it for them. They add our client id and public key (or the key address) to their authorization server, so our token request will be accepted. Ask them to confirm in writing when done.

### Step A4: the client checks their connection before sending

Anyone can run the same harness you ran in section 2, from their own machine:

```bash
export BASE_URL=https://<your-hostname>
export API_TOKEN=<the token you gave them>
python -m tester run --case baseline
```

If B03 passes for them, their token and network path work. This avoids a first-send failure that is really a typo in a token.

### Step A5: the first send

The client sends their interaction 1 Bundle:

```bash
curl -X POST "https://<your-hostname>/ode/receive/uc04a" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/fhir+json" \
  --data-binary @interaction-01-bundle.json
```

**What the client gets back, at once:** HTTP `200` and a standard FHIR `transaction-response` Bundle (a failure is an `OperationOutcome` instead; see the table below). The image callback has not happened yet when this arrives. The response headers say where the result will be: `Link: <.../ode/receipts/{id}>; rel="receipt"` and `X-Receipt-Id`.

For your own scripts, adding `?wait=true` to the URL makes the server wait for the callback and answer with the finished receipt (plain JSON, not FHIR). A real client should not use it.

Then you open `https://<your-hostname>/viewer/`, type the token, and look at the **Received** tab. Or:

```bash
curl -s -H "Authorization: Bearer $API_TOKEN" https://<your-hostname>/ode/receipts
```

### Step A6: decide whether the callback worked

A receipt is the result of one send. It is a result even when the callback failed, so read it, don't just look for an error code.

**Check `state` first.** `pending` means the callback is still running and the images are not there yet: refresh in a minute. `complete` means it finished (successfully or not: read the rest). `failed` means the callback itself crashed and `interrupted` means the server restarted during it; in both cases the referral is stored but no images were saved, and the findings say so.

The callback worked when the receipt shows:

- `state: "complete"`.
- `accepted: true`: we stored the payload.
- `stoppedBecause` is empty: the pull did not stop early.
- Every object has `status: "saved"`. We only save an image after its size and SHA-256 match what the client declared.
- The images open in the viewer (`viewable: true`).
- No `TOKEN_REFUSED`, `MANIFEST_NOT_CURRENT` or `DIGEST_MISMATCH` among the findings.

Some findings are normal while ARex is a draft. `SIGNATURE_PLACEHOLDER` and `SIGNATURE_NOT_VERIFIED` appear whenever the manifest signature is a placeholder, because this server does not verify manifest signatures yet.

### When it doesn't work

| What you see | Likely cause | What to do |
|---|---|---|
| `401` on the send | Wrong or missing token, or `bearer` in lowercase | Check the header is exactly `Authorization: Bearer <token>` |
| `413` | Payload over 10 MB | Send smaller images. The body is an `OperationOutcome` |
| `422` | Not a transaction Bundle | The client's file is the wrong shape. The body is an `OperationOutcome` whose `diagnostics` says why |
| `502` with `HAPI-0507` (in the `OperationOutcome` diagnostics) | A reference in the Bundle points at another server | Their references must be relative (`Patient/x`) or under `https://<your-hostname>/fhir/`. See `UC04A_SENDING_GUIDE.md` section 1 |
| Receipt `state` stays `pending` for several minutes | The sender's ARex server is slow or not answering; each call can wait up to a minute | Wait, then read the findings. The client already has its answer: the referral is stored |
| Receipt finding `HOST_NOT_FOUND` | Their ARex host name doesn't resolve | They need a real public name. Nothing was contacted |
| `UNSAFE_URL_REFUSED` | A URL was not https, not public, or on a different origin than their ARex base | They fix the URLs |
| `TOKEN_REFUSED` | Their server rejected our token request | Almost always step A3 is not done, or they registered the wrong key. Ask them to check our client id and key address |
| `TOKEN_ENDPOINT_NOT_DISCOVERED` or `AUTHORIZATION_DETAILS_TYPE_ADJUSTED` | Their discovery document differs from what we assume | The ARex draft is silent on this. Read the finding text and compare with section 3 of the sending guide |
| `DIGEST_MISMATCH` or `SIZE_MISMATCH` | The file they serve is not the one their manifest describes | They fix the manifest or the file. Nothing was saved |
| `MANIFEST_NOT_CURRENT` | Their published manifest is not marked `current` | They fix it. The pull stopped |

For anything not in the table, the full list of finding codes is in `UC04A_SENDING_GUIDE.md` section 6, and the server log is `docker compose logs --since 30m -t facade` on the VM.

## 4. What the harness shows you, and what it does not

The harness is the worked example for onboarding. Use the same pattern for every new client: a case that the organizer and the client can both run, with a report that says what passed.

| What | Covered by the harness today |
|---|---|
| The client can reach the server and the token works | Yes (`baseline`) |
| Our public key and identity are correct | Yes (`baseline`) |
| The patient-app base (UC07 interaction 6) | Yes (`patient-access`, needs the data loaded) |
| The client's **send** and our **callback** (direction A) | **No.** You verify it by reading the receipt (step A6) |

The harness cannot yet play the client's ARex server. That needs the public receiver on a second VM, which is planned and not built (`ohia-facade-tester` README). Until then, a real client, or a stand-in sender, is how the callback is checked. Adding a harness case that sends a sample referral to this server and reads the receipt would close the gap on the sending half. It would not replace a client's own ARex server.

## 5. A short checklist to keep for each client

| Done | Step |
|---|---|
| | Server healthy: `baseline` passes 15 of 15 |
| | Sheet in A1 filled in |
| | Base URL, receive address and token sent privately |
| | Our identity and the sending guide sent |
| | Client confirms they registered our client id and key |
| | Client ran `baseline` from their side: B03 passes |
| | First send made; receipt read (A6) |
| | Every image `saved`, none of the failure findings |
| | Date, client and receipt id written down |

## 6. Direction B: we send to the client

Use this when the client receives referrals from us (UC07), or sends us nothing and only pulls from our ARex server.

1. **Get their public key** as a JWK set (their authorization key, algorithm RS384). Never ask for or accept a private key.
2. **Add them to `facade/config/clients.json`** on the VM. Copy `facade/config/clients.example.json` first if the file doesn't exist. It is not in git, and the facade re-reads it when it changes (no restart). A malformed file is refused as a whole, so keep a copy of the last good file before you edit.

   | Field | What to put |
   |---|---|
   | `clientId` | A short unique name for them |
   | `requestingOrganization` | The organization they play. UC07: `Organization/org-restorative-dental-tx` |
   | `organizationsByUseCase` | Only if they play a different organization in UC04a, for example `{"uc04a": "Organization/org-barton-springs-dental"}` |
   | `allowedTargetPrefixes` | Where we may send. `https` only, for example `https://tester.example.org/fhir`. A send to any other address is refused |
   | `jwks` | Their public key set |

3. **Check it** with `GET /ode/clients` (shared token): their client id should be listed. The page at `/viewer/` also lists them under **Send a referral**.
4. **Load the data once**: `POST /ode/admin/seed` for UC07, or `POST /ode/admin/seed?useCase=uc04a` for UC04a.
5. **Send** from the page (**Send a referral**), or:
   ```bash
   curl -s -X POST -H "Authorization: Bearer $API_TOKEN" -H 'Content-Type: application/json' \
     -d '{"clientId":"<their id>","targetBaseUrl":"https://<their server>/fhir"}' https://<your-hostname>/ode/send
   curl -s -H "Authorization: Bearer $API_TOKEN" https://<your-hostname>/ode/sends   # the delivery log
   ```
6. **Their callback** is the client pulling images from `https://<your-hostname>/arex/...` with a token they request from us. They need our discovery document, which is open: `https://<your-hostname>/arex/.well-known/smart-configuration`. The consumer guide for them is `docs/CONSUMER_GUIDE.md` and `docs/AREX_RETRIEVAL_GUIDE.md`.

The image link in a manifest only works for the organization named as its requester, and only until it expires (30 days) or is retracted. A client that gets `404` on an image is, by design, not told whether the image doesn't exist or isn't theirs.

## 7. Keeping access under control

- **One shared token** protects the whole server for now. Anyone who has it can read and change everything. Keep the list of who has it.
- **To rotate it** (do this if it was shared too widely, or when a client leaves): on the VM, edit `API_TOKEN` in `deploy/.env`, run `docker compose up -d`, and give the new token to everyone still testing. The old token stops working at once. See `deploy/README.md` section 10.
- **To remove a direction-B client**: delete their entry from `facade/config/clients.json`. They can no longer get an ARex token or be sent to.
- **To remove a direction-A client**: there is nothing to unregister on our side. Rotate the token if they should lose access, and ask them to remove our client id from their server.
- **Replacing the shared token** with per-client login (SMART backend services) is planned for January 2027. When that arrives, this guide's token steps change.

## 8. Not verified

- No real client has run either direction on the live VM. The receive path and the callback have been tested in a sandbox with an independent sender (see `docs/CURRENT_STATE.MD`).
- The ARex draft (v0.2.0) leaves several wire details open (discovery, the `authorization_details` type, manifest signatures). Expect to adjust to each client and record what you learn.
- The two new endpoints on the unmerged branches (oral claims under `/fhir/ExplanationOfBenefit`, and `/patient-access/fhir`) are not covered here. Their guides are in the branch documents.
