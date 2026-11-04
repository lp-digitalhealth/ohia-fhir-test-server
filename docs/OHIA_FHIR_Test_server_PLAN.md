# OHIA FHIR Test Server — Plan

**Status:** living document. Last updated 2026-11-04.
**Product owner:** Mark Marciante (decides design). Developers and coding assistants build to this plan.
**Branch:** `November_Testing`.

## How to read this file

Read `docs/README.md`, `docs/CURRENT_STATE.MD` and `docs/TRANSITION.MD` first. This file records **decisions**. Each item carries one label:

| Label | Meaning |
|---|---|
| **FINAL** | Decided by the owner. Build to it. Do not re-open it unless the source code or a test shows the decision rests on something false; then raise it immediately with the file, line or request that contradicts it. |
| **OPEN** | Not decided. Do not build it. Present options with trade-offs to the owner. |
| **SUPERSEDED** | Was decided or proposed, then replaced. Kept as history. |
| **VERIFIED** | Checked by running it or reading a primary source (cited). |
| **UNVERIFIED** | Inferred, summarized by a tool, or not tested. |

When a decision changes, update this file in the same change.

---

## 1. Purpose

A **running, durable FHIR test server** that connectathon participants call. It is not a specification exercise. It plays different parties for different participants, accepts their data, answers their requests, and records what fails so the Oral Health Data Exchange (ODE) referral IG can be improved. Synthetic data only. Hosting must cost nothing.

## 2. Working agreement (owner's rules)

1. **Explain before acting.** Say which file or function changes, what it does now, and what it becomes. Narrate as you go.
2. **Design decisions are surfaced, never made unilaterally.** Present options with trade-offs, even when one is clearly better. Design discussion precedes code.
3. **A settled design is trusted** unless the source contradicts it (see FINAL above). Taste and second thoughts are not grounds to re-open.
4. **Say what was not verified.** Reading code is not a test result.
5. **When uncertain, ask.**

## 3. Repository rules (owner)

- Work on **`November_Testing`** (note the capital T; Git branch names are case-sensitive).
- Do not create other branches. Do not commit to `main`.
- `/docs` is the primary channel for developers and their coding assistants.

---

## 4. Finalized decisions

| # | Decision | Notes |
|---|---|---|
| F1 | **Running endpoints**, not a spec exercise. Consumers hit real URLs. | Owner, 2026-11-04. |
| F2 | **Connectathon scope: only UC07 and UC04a.** UC01, UC02, UC03, UC04b, UC05 and UC06 are out. | So the HIE routing hub, PDex Provider Access, ATR, Plan-Net and standalone CDex pull are **not built** unless the owner says otherwise. |
| F3 | **In scope:** Da Vinci **CRD, DTR, PAS**; the **ODE referral including its CDex and ARex pieces**; **360X**. | Owner. |
| F4 | **Roles.** UC07 (Texas Medicaid root canal): the server is the **payer** (DentaQuest) for CRD, DTR and PAS. UC04a (teledentistry): the server is the **recipient for both the teledentistry platform (Meridian) and the in-office PMS (Barton Springs)**. | Owner. |
| F5 | **US Core 6.1.0 only.** | HTI-1 names 6.1.0 at 45 CFR 170.215(b)(1)(ii) (from secondary sources; confirm against the eCFR text). The owner first said 6.0.0 and accepted the correction. |
| F6 | **Server: HAPI FHIR JPA (R4) on PostgreSQL**, fronted by our own facade. | Chosen for zero-cost hosting. The Microsoft FHIR Server supports only SQL Server (see section 9). |
| F7 | **Hosting: Oracle Cloud Always Free (ARM), zero cost.** Azure is excluded (the owner's organization forces Entra ID on everyone). | Risks in section 10. |
| F8 | **360X transport: Direct/XDM via a HISP first. XDR is deferred** until a partner asks for it. | Owner's information: Kno2 supports Direct only, and most Epic clients use Direct only. |
| F9 | **Patient-facing part: CMS-0057.** The payer's Patient Access API lets the patient view prior authorizations, using the PDex Prior Authorization profile (`ExplanationOfBenefit`, `use = preauthorization`). **Patient authentication: minimal SMART standalone launch** (a small built-in screen where a tester picks a synthetic patient; authorization code + PKCE; patient-scoped tokens). | How a PAS `ClaimResponse` becomes that EOB is not spelled out in the profile page I read. See O9. |
| F10 | **The IG loader identifies profiles by canonical `url` + `version`** (conditional update), never by the package's resource `id`. | Origin: 13 StructureDefinitions share an `id` across packages with different URLs; loading by `id` silently overwrote them. Verified on the Microsoft server; **re-verify on HAPI**. |
| F11 | **Narrative handling: find the exact trigger, then retry without the narrative only on that error and keep the original in the findings log.** | Decided against the Microsoft server's narrative sanitizer. **Carried over**: it applies only if HAPI shows the same behavior. Re-test first. |
| F12 | **The 360X Direct receiver is configurable to take inbound referrals by all three delivery methods: API, webhook and mailbox.** | Owner, 2026-11-04. "All three" is the assistant's reading: the three methods listed in the earlier question. The Kno2 test-domain details remain open (O2). |
| F13 | **ODE referral: validate, but accept. Return the validation feedback.** | Owner, 2026-11-04. Report-first: the submission is stored and the response carries the validation results (for example an `OperationOutcome`). Rejection is not the default. This puts IG loading and conformance reporting in scope for the event, which makes O6 and F11 live. |
| F14 | **UC07 synthetic data is authored by the assistant into `lp-digitalhealth/ohia-test-data`, branch `november_testing`** (lowercase; it exists and matches `main` at `35df2c0`). | Owner, 2026-11-04. Follows that repo's `2026-CMS-Connectathon/CLAUDE.md` (tiers, Bundle conventions, validation, doc sync). Nothing authored yet; what to author first is O13. |
| F15 | **ARex is used for all attachment and imaging exchange in this connectathon.** Other imaging capabilities (WADO-RS / DICOMweb, SMART Imaging Access) are deferred and added later. | Owner, 2026-11-04. What "all" covers is O14. It departs from the standing WADO-RS model in the test-data repo's `CLAUDE.md` section 8 (O15). |

## 5. Superseded decisions (history; do not rebuild)

| Item | What happened |
|---|---|
| Medplum as the store | Proposed by an assistant without the owner's agreement. Never built. |
| Microsoft FHIR Server + SQL Server | Was FINAL. Superseded by F6 for cost reasons. Test findings kept in section 9. |
| Azure VM, then Azure Container Apps | Superseded by F7. |
| .NET facade | Was chosen to match the Microsoft server. Now OPEN (O1). |
| XDR (ITI-41) as the first 360X transport | Superseded by F8. |
| Wide scope (Provider Access, ATR, C4BB queries, UC03/UC05 runner) | Superseded by F2 and F3. |
| US Core 6.0.0 | Corrected to 6.1.0 (F5). |

## 6. Open decisions

| # | Question | Why it matters / what is needed |
|---|---|---|
| O1 | **Facade language** (Java in-process with HAPI, .NET, TypeScript, Python). | Chosen earlier for the Microsoft stack, which is no longer the store. Options to be presented once HAPI is running. |
| O2 | **Kno2 test details.** (Delivery method is now configurable, F12.) Will Kno2 host a Direct address for us; do they have a test domain and trust bundle; do they send standard XDM. | Owner takes these to Kno2. Blocks testing the 360X receiver, not its design. |
| O3 | RESOLVED: see F13. | |
| O4 | RESOLVED: see F14. What to author first is O13. | |
| O5 | RESOLVED: see F15. | |
| O6 | **Draft IG revisions** that reuse one canonical URL. | The Microsoft server could not resolve two versions of one canonical. HAPI is untested. **Now live:** validation is in scope (F13). |
| O7 | **PCC-56 conflict.** The ODE adapter treats it as accept/decline; the IHE 360X supplement (Rev 1.2) defines it as Referral Decline only, with accept inside PCC-55. | Resolve with the adapter's owners before building outbound accept. |
| O8 | **CDT licence.** The test data permits CDT use for OHIA connectathon testing only. | Confirm a shared member-accessible server stays within the ADA permission. |
| O9 | **Subscriptions.** UC04a notifies the patient app ("referral sent", "appointment confirmed"). Does the server deliver, or do participants poll? And how is the PA EOB derived from the PAS result (stored vs. derived on read)? | Untested on HAPI. |
| O10 | **ARex.** The spec (v0.2.0, draft and provisional) has not been read. Per the UC07 document the payer pulls the manifest content and verifies digests, so the server also acts as a client. | Read the spec before designing. |
| O11 | **Push access for this repository.** The Claude GitHub App is not installed here, so the assistant cannot push. | An organization admin must install it (https://github.com/apps/claude/installations/select_target). |
| O12 | **Epic onboarding.** An Epic-using member must add our Direct address as a trading partner. | Needs a partner, a test patient, a HISP and test certificates. |
| O13 | **What UC07 data to author first, and in what order.** | The test-data repo's rules require reading the UC07 companion guide and interaction narratives (`uc07-i1` to `i5`) first, and forbid batch-generating clinically meaningful interactions from naming patterns. Those were not read in full. |
| O14 | **Scope of "ARex for all" (F15).** Does it include UC04a's patient-submitted intraoral photos (modeled today as inline US Core `DocumentReference`)? Is the server only an ARex Content Consumer (UC07 payer), or also a Content Server? | ARex v0.2.0 defines a Content Server and a Content Consumer role. The artifacts page does not say how a PAS `Claim` references a manifest (UNVERIFIED). |
| O15 | **Reconcile the test-data repo's `CLAUDE.md` section 8** (standing model: ImagingStudy + WADO-RS, "do not re-derive") with F15 (ARex for this event). | That file says to update it when a settled modeling decision changes. Who edits it, and how the UC07 narrative's WADO-RS steps are treated, is the owner's call. |

---

## 7. Architecture (proposed; consistent with the FINAL decisions)

Facade language is OPEN (O1). Base-path names below are proposals.

```
Internet ── TLS proxy ── facade
                          ├─ /payer/fhir      DentaQuest (UC07)         ┐ one HAPI + PostgreSQL store,
                          ├─ /pms/fhir        Barton Springs PMS (UC04a)│ separate role base paths and
                          ├─ /platform/fhir   Meridian platform (UC04a) ┘ CapabilityStatements
                          ├─ /token, /authorize   backend auth + minimal SMART standalone launch
                          ├─ /cds-services    CRD (CDS Hooks)
                          ├─ /360x/direct     Direct/XDM receiver fed by the HISP (delivery OPEN, O2)
                          └─ → ode-360x-adapter (sidecar, reused as-is)
Store: HAPI FHIR JPA server (R4) + PostgreSQL
```

The test data reuses logical IDs across scenarios (for example the same patient in two use cases), so the seed must isolate or rewrite IDs. How is not yet decided.

### What each use case needs from the server

| Use case | Server role | Endpoints / behavior |
|---|---|---|
| **UC04a** teledentistry | Recipient of both platform and PMS | Receive the CDex provider-to-provider referral push (PMS); `Task`; `Appointment` / `AppointmentResponse`; receive the outcome summary (`ClinicalImpression` + `CarePlan`) back (platform); Subscription notices to the patient app. Per the UC04a companion guide (first half read): no CRD/DTR/PAS, no 360X, no HL7 v2, no Plan-Net. |
| **UC07** Texas Medicaid root canal | Payer (DentaQuest) | CRD `order-sign` service; DTR questionnaire package; PAS `Claim/$submit` returning a `ClaimResponse` with a PA number; receive the provider-to-payer `Communication`; ARex manifest pull and digest check; 360X delivers the imaging reference; CMS-0057 Patient Access (PDex PA EOB) via SMART standalone launch. |

## 8. Milestones (re-cut; proposed, pending owner approval)

| M | Deliverable | Verified by |
|---|---|---|
| H | **HAPI spike.** Run HAPI + PostgreSQL; rerun the profile-loading and validation checks (US Core 6.1.0; Da Vinci IGs; non-conformant resource stored; narrative with a self-closed `<span/>`; two versions of one canonical); Subscription delivery; `private_key_jwt`; SUSHI build of the ODE FSH. | Pass/fail with request and response |
| M1 | Oracle VM (ARM), TLS, three role base paths, backend auth + minimal SMART standalone launch, loader keyed by canonical (F10), UC04a fixtures seeded | Public-URL `curl`; token flows |
| M2 | **UC04a end to end** | Scripted run against the live URL |
| M3 | **UC07 payer:** CRD, DTR, PAS, provider-to-payer `Communication` | Scripted run (needs the UC07 data, O13) |
| M4 | **CMS-0057 Patient Access:** PA EOB, patient-scoped read | A patient token sees only its own PA |
| M5 | UC07 ARex pull, imaging reference, 360X receiver (needs O2, O10, O14) | Per-feature tests |
| M6 | ODE referral validate-but-accept with feedback (F13; in scope), hardening, onboarding guide | Fresh-deploy test |

---

## 9. What has been tested (all in a temporary cloud sandbox)

### Microsoft FHIR Server (image `r4-fhir-server:5.2.36` + SQL Server 2025) — **history; nothing tested on HAPI yet**

- **VERIFIED, ran:** `/metadata` returned 200, FHIR 4.0.1, 146 resource types. With only `Security__Enabled=false` it returned 500 until a placeholder `Authority` was set (`SecurityProvider.cs` requires it).
- **VERIFIED:** US Core 6.1.0 loaded with 31 of 59 StructureDefinitions accepted as published. The other 28 were rejected by the server's narrative sanitizer (HTML5 parse). Cause: 26 contain a self-closed `<span/>`; 2 (`us-core-practitionerrole`, `us-core-relatedperson`) fail on `<img src>` ("Illegal attribute"). Why 31 other profiles with relative image paths pass is **UNEXPLAINED**.
- **VERIFIED:** with a labeled workaround (replace narrative on rejection) all of US Core 6.1.0 loaded and validated: a valid example Patient gave 0 errors (10 terminology warnings, since external ValueSets and licensed code systems are not loaded); the same Patient minus identifier, name and gender gave exactly 3 cardinality errors.
- **VERIFIED:** a non-conformant resource claiming a profile was stored unchanged, and `GET [type]/[id]/$validate?profile=…` reported its errors. `POST [type]/[id]/$validate` with a `Parameters` body returned HTTP 500 (`NullReferenceException`, cause not fully read).
- **VERIFIED:** HRex, CDex, ATR, PDex, PAS, CRD and DTR loaded beside US Core 6.1.0 only (0 request failures; 155 of 240 StructureDefinitions needed the narrative workaround). Validation through one Da Vinci profile (`hrex-patient-demographics`) worked; the other profiles and the 26 distinct `|7.0.0` US Core references were **not tested**. SDC 3.0.0, NDH and Subscriptions Backport profiles referenced by these IGs were not loaded.
- **VERIFIED:** loading by package `id` overwrote 13 profiles; conditional update by `url` + `version` fixed the pair tested.
- **VERIFIED:** storing two versions of one canonical URL worked, but profile resolution then failed for every form (unversioned and `|version`).
- **VERIFIED in source:** the server supports only SQL Server (and a Cosmos DB store being retired).
- **UNTESTED:** Subscription delivery, `private_key_jwt`, `Group/$export`, the SUSHI build of the ODE FSH, and any 360X receive.

### Hosting facts (secondary sources unless noted)

- Oracle Always Free: two x86 micro VMs (reported 1/8 OCPU, 1 GB each); the ARM (Ampere A1) allowance was **reportedly** cut in 2026 from 4 OCPU / 24 GB to 2 OCPU / 12 GB (sources conflict).
- HAPI's published build recipe targets `linux/amd64` and `linux/arm64/v8` (**VERIFIED** in the starter's `build-images.yaml`; the live image manifest was not checked because Docker Hub rate-limited the check).
- HAPI on PostgreSQL uses `org.postgresql.Driver`, the `HapiFhirPostgresDialect` and `hibernate.search.enabled: false` (from the starter's README; not run).
- HAPI IG install at runtime: `hapi.fhir.ig_runtime_upload_enabled` then `POST [server]/ImplementationGuide/$install`; REST-hook subscriptions: `hapi.fhir.subscription.resthook_enabled` (README; not run).
- Docker Hub rate-limited anonymous image pulls from the sandbox (HTTP 429); this may affect pulling HAPI and PostgreSQL images from some hosts.

## 10. Risks

- **Oracle free tier:** idle-instance reclamation (evaluated over 7 days; thresholds reported at 15–20% depending on the source), "out of host capacity" errors on ARM, and allowance changes without announcement. A low-traffic test endpoint may look idle.
- **Memory:** HAPI + PostgreSQL requirements on the target host are unknown.
- **Epic onboarding is outside our control** (O12).
- **HISP dependency** (O2).
- **360X details unread:** supplement §3.55–3.61 and the Direct transport section (§4.I.2.1).
- **CDT licence** (O8).
- **Doc coverage:** the UC04a companion guide was read only in part; UC07's main document was read in full.

## 11. Reference facts (VERIFIED from primary sources, 2026-11-02 unless noted)

| Item | Version / fact |
|---|---|
| Da Vinci CDex | 2.1.0 (one operation, `submit-attachment`; Task profiles `cdex-task-data-request`, `cdex-task-attachment-request`) |
| Da Vinci CRD / DTR / PAS | 2.2.1 / 2.2.0 / 2.2.1 |
| Da Vinci PDex / ATR / HRex | 2.2.0 / 2.1.0 / 1.2.0 |
| CARIN BB / Plan-Net | 2.2.0 / 1.2.0 |
| Subscriptions Backport | 1.1.0 (the package is FHIR 4.3.0) |
| ARex (Onyx) | 0.2.0, "draft and provisional", FHIR R4, draft as of 2026-08-28; no package list |
| SMART Imaging Access | 0.1.0 CI build only; the canonical cited in UC07 returned 404 |
| US Core | current is 9.0.0; policy here is 6.1.0. The Da Vinci IGs above declare US Core 7.0.0 and also 6.1.0 as dependencies. |
| IHE 360X | Rev 1.2, Trial Implementation, 2021-04-14. Transactions PCC-55 to PCC-61. Base transport is Direct with XDM; XDR is an option. Epic's published IHE integration statement lists 360X Initiator and Recipient with the XDR option. |
| PDex Prior Authorization | `ExplanationOfBenefit`, `use = preauthorization`; required `status`, `outcome`, `created`, `type`, `patient`, `insurer`, `provider`, `item` and `insurance`; payers make pending and active decisions available within one business day (page read in part). |

## 12. Next steps (in order)

1. **Fix push access** (O11) so this plan and later work can reach `November_Testing`.
2. **HAPI spike** (milestone H), reporting each check as pass or fail with the actual request and response.
3. **Present the facade-language options** (O1) and the narrative/IG-revision findings from HAPI.
4. **Owner takes the Kno2 questions** (O2) and answers O13, O14 and O15.
5. Re-cut the milestone table into a final one and update this file.

## 13. Earlier artifacts

The first discovery document (`ohia-fhir-test-server/docs/00-discovery.md`) is on branch `claude/dazzling-faraday-emy148` of `lp-digitalhealth/federal-policy-health-scan`. It predates many decisions above (Microsoft server, Azure, the wide scope). Where it disagrees with this file, this file wins. It should be moved here or archived once push access works.
