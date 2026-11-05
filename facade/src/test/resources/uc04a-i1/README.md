# UC04a interaction-01 test fixtures

These three files are **unmodified copies** from the public repository `lp-digitalhealth/ohia-test-data` (Apache License 2.0) at commit `42ddc430a4ae80cb10d29345a60a4bd4795e7937`:

- `interaction-01-bundle.json` is `2026-CMS-Connectathon/fhir-resources/purpose-built/uc04a-teledentistry-commercial/interactions/interaction-01/interaction-01-bundle.json` (30 `PUT` entries, with the ARex manifest `arex-manifest-intraoral-photos` and two content entries embedded)
- `intraoral-photo-1.jpg` and `intraoral-photo-2.jpg` are the files in that folder's `arex-payloads/` (632 bytes each; the declared SHA-256 digests match)

The data is synthetic (no real patients). Things to know about it: the content URLs are on the synthetic host `meridian-teledental.example.org`, the manifest's `arex-expires` (2026-07-29) is already in the past, and the Provenance signature is a placeholder, not a valid JWS. The tests rely on all three.

The tests use these copies so they need no network. Do not edit them. If the data repository changes, refresh the copies from a new commit, update the commit above, and re-run the tests.
