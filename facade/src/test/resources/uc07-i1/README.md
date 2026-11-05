# UC07 interaction-01 test fixtures

These three files are **unmodified copies** from the public repository `lp-digitalhealth/ohia-test-data` (Apache License 2.0) at commit `445d4ce7460c6e62000e275da84258c84bfa873d`:

- `interaction-01-bundle.json` is `2026-CMS-Connectathon/fhir-resources/purpose-built/uc07-pediatric-rootcanal/interactions/interaction-01/interaction-01-bundle.json`
- `radiograph-periapical.png` and `radiograph-bitewing.png` are the files in that folder's `arex-payloads/`

The data is synthetic (no real patients). The facade tests use these copies so they need no network. At run time the facade does **not** use them: it fetches the same files from the data repository at the pinned commit set in `facade.seed.data-ref`.

Do not edit these files. If the data repository changes, bump the pinned commit deliberately, refresh the copies from that commit, and re-run the tests (they assert 37 resources, 22 interaction resources and 2 images).
