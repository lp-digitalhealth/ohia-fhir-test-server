# Upstream notes for the OHIA PAS Oral Prior Authorization Claim profile

Two audiences: the **PAS authors** (the real fix) and the **ODE IG maintainers** (a proposal, not an edit). Written 2026-11-05, renamed 2026-11-12 ("overlay" became "oral PA profile").
The owner has been told by the IG authors that PAS should allow CDT for oral claims; nothing below has been confirmed with them
by this project.

## 1. For the Da Vinci PAS authors

**Finding.** In PAS 2.2.1, `Claim.item.productOrService` (profile-claim-base) is bound **required** to
`http://hl7.org/fhir/us/davinci-pas/ValueSet/X12278RequestedServiceType|2.2.1`. Its `compose.include` lists seven systems, and none is
CDT: `data-absent-reason` (not-applicable only), `https://codesystem.x12.org/005010/1365`, `http://www.ama-assn.org/go/cpt`,
`http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets`, `icd9cm`, `http://www.cms.gov/Medicare/Coding/ICD10`, `ndc`. I read it in the
2.2.1 package and in the published value set for 2.2.1, the current build (dated 2026-10-09) and STU2 (2.0.1): identical in all three.
`Claim.type` allows `oral` ("Dental, Denture and Hygiene claims"), so an oral prior authorization is in scope, but its procedure
code (CDT, the HIPAA dental code set) cannot be expressed without a binding error. By contrast, CARIN Blue Button's Oral EOB profile
binds the corresponding element to an ADA CDT value set (from a search summary; the StructureDefinition was not read in full).

**Questions.**
1. What coding do you intend for a CDT code in `Claim.item.productOrService`: `http://www.ada.org/cdt`, or another system?
2. Will the value set (or the binding strength, for oral claims) change in a future release?
3. How do payers who test CDT against PAS today get past the required binding?

**What this project did meanwhile.** Built a profile (this folder) that is a generated copy of `profile-claim` differing only in
adding the CDT code system to that binding. Removing it is a no-op if PAS adds CDT.

A second, related finding: the PAS `Claim` profile's `supportingInfo:AdditionalInformation.value[x]` does not allow a `List`, and ARex
(Onyx) defines no link from a `Claim` to its manifest, so this project links them by identifier plus a local-category reference
(decision F26).

## 2. For the ODE IG maintainers (a PROPOSAL; nothing here edits `ode-ig`)

**Status (owner, 2026-11-12): the profile stays in this repo and is not being moved to `ode-ig`.** This section is kept only as a record of why moving it would be a real decision, in case that changes.

The canonical IG is `lp-digitalhealth/ode-ig` (FSH only, SUSHI + IG Publisher; its `CLAUDE.md` governs). The profile is in this repo, not there, because adopting it is a decision for its maintainers:

- **New dependency.** `ode-ig` depends on US Core 6.1.0 and COW 1.0.0-ballot (CARIN BB was removed and "must not be reintroduced"). This profile is based on PAS and needs `hl7.fhir.us.davinci-pas#2.2.1`, whose profiles reference **US Core 7.0.0** profiles, against the IG's US Core 6.1.0 pin. The plan only says `$submit` is "modeled on" PAS; PAS is not a dependency today.
- **Different job from `ODEOralClaim`.** `ODEOralClaim` (planned, 837D) is the billing claim and already requires CDT. This profile is the prior-authorization request (278-shaped, `use = preauthorization`). They should not be merged or confused.
- **Authoring rules.** `ode-ig` is FSH-only; this profile is generated JSON. Adopting it there means a hand-written FSH `Profile: ... Parent: Claim` that re-declares PAS's constraints (the maintenance burden the generator avoids), or carrying the generated JSON as input. The IP rule applies: reference the CDT system, enumerate no codes (the value set already complies).
- **Build.** `sushi .` and `./_build.sh` must pass; I could not run them here.

## 3. What was verified, and what was not

**Run (2026-11-05):**
- **Self-diff** by `tools/build_oral_pa_profile.py`: 284 snapshot elements compared with PAS `profile-claim` 2.2.1; exactly one differs, `Claim.item.productOrService`, `binding.valueSet` (strength still `required`), plus the description. Anything else makes the build fail.
- **Re-run 2026-11-12 after the rename (same results; scratch copies, nothing in the data changed).**
- **HL7 FHIR Validator 7.0.4** (R4 4.0.1; `hl7.fhir.us.core#6.1.0`, the ARex profiles, `hl7.fhir.us.davinci-pas#2.2.1`, this profile; terminology off) on copies of the UC07 I2 PAS Claim:

| Claim | Profile claimed | Errors |
|---|---|---|
| I2 Claim, three CDT lines (as committed) | PAS `profile-claim` | **3** (the CDT binding) |
| the same Claim | oral PA profile | **0** |
| item 1 given a code from a bogus system (`urn:test:bogus`) | oral PA profile | **1** (binding still required) |
| item 1 given a CPT code | oral PA profile | 0 |
| the same Claim with `use = claim` (a claim for payment) | oral PA profile | **1** ("fixed to 'preauthorization'"): it cannot be mistaken for an 837D claim |

**Bundles (2026-11-12, I2 `Claim` switched to this profile):** the cumulative UC07 I2 (50 entries) and I4 (62 entries) Bundles validate with **0 errors** (260 and 347 warnings); before the switch each had 3. The PAS `ClaimResponse.request` reference to the Claim raised no error (not investigated why; the PAS Bundle profile itself was not run).

For the same Claim, the PAS run and the oral-PA-profile run differ only in the 3 errors disappearing and 3 new warnings that the CDT code
system cannot be code-checked with terminology off (expected: CDT is now in scope of the binding), plus one information message that
differs only in the profile URL it names. All 33 other messages are common to both runs.

**Not verified:**
- That the generated **differential** regenerates into the copied snapshot (no regeneration tool was available; the snapshot is the verified part).
- Loading the profile into the live HAPI server (loader unbuilt; HAPI with PAS profiles untested).
- Validation of a PAS request **Bundle** containing a Claim that claims this profile (it is expected to be flagged at Bundle level; not run).
- Any other validator, any payer's behavior, and the authors' intent (hearsay until they answer).
- CDT codes themselves (ADA-licensed; terminology off).
