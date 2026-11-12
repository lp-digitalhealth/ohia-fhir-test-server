# OHIA PAS Oral Prior Authorization Claim profile (interim): a PAS Claim that accepts CDT

**Status: DRAFT, interim, 2026-11-12.** Not part of Da Vinci PAS and not (yet) part of the ODE IG (`lp-digitalhealth/ode-ig`). Decision F31 in
[`../docs/OHIA_FHIR_Test_server_PLAN.md`](../docs/OHIA_FHIR_Test_server_PLAN.md).

## Why this exists

Da Vinci PAS 2.2.1 binds `Claim.item.productOrService` **required** to `X12278RequestedServiceType`. That value set contains
CPT, HCPCS, ICD-9, ICD-10, NDC and X12 1365 service types, and **not CDT** (`http://www.ada.org/cdt`). I checked the 2.2.1 package
and the published value set for 2.2.1, the current build and 2.0.1: the same seven systems each time. PAS does allow the `oral`
claim type, so an oral prior authorization is a PAS Claim whose procedure code, a CDT code, fails the binding. The IG authors have
told the owner that PAS should allow CDT; the published artifact does not yet say so.

FHIR does not let a profile derived from the PAS Claim widen a required binding, so this profile is **derived from the core
`Claim`**, not from PAS.

## What it is, and what it is not

- **The UC07 I2 `Claim` claims it** (owner decision, 2026-11-12): I2 is a PAS claim that uses this oral profile, so its CDT codes validate. The cumulative I2 and I4 Bundles validate with 0 errors (they had 3 against plain PAS).
- **A prior-authorization request, not a claim for payment.** `Claim.use` is pinned to `preauthorization` (inherited from PAS), so this is the X12 **278**-shaped request. It is **not** the X12 **837D** dental claim; the ODE IG plans `ODEOralClaim` for that, which is why "Prior Authorization" is in the name.
- **"Oral" means the intended use, not a restriction.** PAS's own `Claim.type` rule (`institutional | professional | oral`) is inherited unchanged, so the profile does not itself force `type = oral`.

## The one difference

| | PAS `profile-claim` 2.2.1 | `ohia-pas-oral-pa-claim` |
|---|---|---|
| `Claim.item.productOrService` binding | required, `X12278RequestedServiceType\|2.2.1` | required, `ohia-requested-service-type-cdt` = **that same value set plus the CDT code system** |
| Everything else (284 snapshot elements) | | identical, element for element |

The owner's constraint is "only override the CDT code; we cannot make other changes", so the profile is **generated**, not
hand-written, and the generator fails if anything else differs:

```bash
python3 -I tools/build_oral_pa_profile.py <extracted PAS 2.2.1 package dir> .
```

It copies PAS's snapshot, changes the single binding, rewrites the identity metadata (id, url, name, title, description,
publisher, status, version, date, `baseDefinition` = core Claim), and then compares the new snapshot to PAS's, element by element.
CDT is ADA-licensed, so the value set includes the code **system** by reference and enumerates no codes.

| File | Canonical |
|---|---|
| `StructureDefinition-ohia-pas-oral-pa-claim.json` | `https://oralhealthalliance.net/fhir/StructureDefinition/ohia-pas-oral-pa-claim` (the ODE IG canonical) |
| `ValueSet-ohia-requested-service-type-cdt.json` | `http://ohia-codes.org/ValueSet/ohia-requested-service-type-cdt` (the OHIA terminology namespace) |

The names are proposals for the owner to confirm.

## Limits (read before relying on it)

- **Not PAS-conformant, by definition.** A Claim that claims this profile is an *OHIA* oral claim that is otherwise identical to a
  PAS one. It says nothing a PAS server is obliged to accept.
- **PAS's request Bundle still rejects it.** `profile-pas-request-bundle` requires its Claim entry to conform to PAS
  `profile-claim` or `profile-claim-update`. Changing that would be a second change to PAS, which the owner ruled out, so a
  PAS `$submit` Bundle containing a Claim that claims this profile is still flagged at Bundle level.
- **It is a copy of PAS's snapshot**, pinned to PAS 2.2.1. If PAS changes, rerun the generator and read the self-diff.
- **The differential is a merge** of PAS's two differentials, written for tools that expect one. It was not independently
  regenerated into a snapshot (the validator CLI used here has no such option), so the **snapshot** is the verified part.
- **Not loaded into the live HAPI server.** The profile loader is not built, and HAPI's handling of PAS profiles is untested.
- It still depends on the PAS 2.2.1 package, which references US Core 7.0.0 profiles, and the project's invariant is US Core 6.1.0.

## Verification

See the section at the end of [`UPSTREAM-NOTES.md`](UPSTREAM-NOTES.md) for what was run and what was not.
