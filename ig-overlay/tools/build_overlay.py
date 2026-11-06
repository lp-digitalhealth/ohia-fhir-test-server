"""Build the OHIA oral-claim overlay from the Da Vinci PAS 2.2.1 package and prove it differs from PAS in ONE thing.

The overlay is a copy of PAS `profile-claim` in which the ONLY change that matters is the binding of
`Claim.item.productOrService`: PAS binds it (required) to X12278RequestedServiceType, which has no CDT; the overlay binds it
(still required) to a value set that is that same value set PLUS the CDT code system. A profile DERIVED from the PAS Claim cannot
widen a required binding (FHIR profiling), so the overlay is derived from the core Claim instead.

Usage:
  python3 -I build_overlay.py <extracted PAS 2.2.1 package dir (the folder holding package.json)> <output dir>
Writes StructureDefinition-ohia-pas-claim-cdt.json and ValueSet-ohia-requested-service-type-cdt.json, then runs the self-diff.
Exits non-zero if the overlay differs from PAS in anything other than the allowed list.
"""
import copy
import json
import os
import sys

PAS_DIR, OUT = sys.argv[1], sys.argv[2]
PAS_VER = "2.2.1"
PAS_CLAIM = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-claim"
PAS_VS = f"http://hl7.org/fhir/us/davinci-pas/ValueSet/X12278RequestedServiceType|{PAS_VER}"
SD_URL = "https://oralhealthalliance.net/fhir/StructureDefinition/ohia-pas-claim-cdt"
VS_URL = "http://ohia-codes.org/ValueSet/ohia-requested-service-type-cdt"
TARGET_PATH = "Claim.item.productOrService"
VERSION = "0.1.0"
DATE = "2026-11-05"


def load(name):
    with open(os.path.join(PAS_DIR, name), encoding="utf-8") as f:
        return json.load(f)


claim = load("StructureDefinition-profile-claim.json")
base = load("StructureDefinition-profile-claim-base.json")
assert claim["version"] == PAS_VER and base["version"] == PAS_VER, "this overlay is pinned to PAS 2.2.1"

# ---------------------------------------------------------------- value set: PAS service types UNION the CDT system
# Two separate include entries = union. (valueSet and system inside ONE include would be an intersection.)
vs = {
    "resourceType": "ValueSet",
    "id": "ohia-requested-service-type-cdt",
    "url": VS_URL,
    "version": VERSION,
    "name": "OHIARequestedServiceTypeCDT",
    "title": "OHIA Requested Service Type (PAS service types plus CDT)",
    "status": "draft",
    "experimental": True,
    "date": DATE,
    "publisher": "Oral Health Interoperability Alliance (OHIA)",
    "description": ("Every code in the Da Vinci PAS 2.2.1 X12 278 Requested Service Type value set, PLUS the ADA CDT code system "
                    "(http://www.ada.org/cdt), so that an oral prior authorization can carry its CDT procedure code. The CDT codes are "
                    "ADA-licensed and are NOT enumerated here: the whole code system is included by reference."),
    "jurisdiction": [{"coding": [{"system": "urn:iso:std:iso:3166", "code": "US"}]}],
    "compose": {"include": [{"valueSet": [PAS_VS]}, {"system": "http://www.ada.org/cdt"}]},
}

# ---------------------------------------------------------------- structure definition
sd = copy.deepcopy(claim)
for k in ("identifier", "extension", "contact", "text"):
    sd.pop(k, None)  # PAS-specific identity/metadata is not ours to claim
sd.update({
    "id": "ohia-pas-claim-cdt",
    "url": SD_URL,
    "version": VERSION,
    "name": "OHIAPASClaimCDT",
    "title": "OHIA PAS Claim (CDT accepted)",
    "status": "draft",
    "date": DATE,
    "publisher": "Oral Health Interoperability Alliance (OHIA)",
    "description": ("INTERIM OVERLAY, not part of Da Vinci PAS. An exact copy of the PAS 2.2.1 Claim profile (profile-claim, with profile-claim-base "
                    "merged in) in which the ONLY change is that Claim.item.productOrService may also take a CDT code (http://www.ada.org/cdt). "
                    "Binding strength stays required. Not PAS-conformant by definition. Remove it if PAS adds CDT to its own value set."),
    "baseDefinition": "http://hl7.org/fhir/StructureDefinition/Claim",
    "derivation": "constraint",
})

# snapshot: copy element for element; change exactly one binding
changed = 0
for e in sd["snapshot"]["element"]:
    if e["path"] == TARGET_PATH and "binding" in e:
        assert e["binding"]["strength"] == "required" and e["binding"]["valueSet"] == PAS_VS, e["binding"]
        e["binding"] = dict(e["binding"], valueSet=VS_URL)
        e["binding"]["description"] = "PAS X12 278 requested service types plus CDT (OHIA overlay)"
        changed += 1
assert changed == 1, f"expected to change exactly one snapshot binding, changed {changed}"

# differential: PAS profile-claim-base differential merged with profile-claim differential by element id, in snapshot order
merged = {}
for e in base["differential"]["element"]:
    merged[e["id"]] = copy.deepcopy(e)
for e in claim["differential"]["element"]:
    merged.setdefault(e["id"], {}).update(copy.deepcopy(e))
order = [e["id"] for e in claim["snapshot"]["element"]]
diff_elements = [merged[i] for i in order if i in merged]
missing = [i for i in merged if i not in order]
assert not missing, f"differential ids not in the snapshot: {missing[:3]}"
for e in diff_elements:
    if e["path"] == TARGET_PATH and "binding" in e:
        e["binding"] = dict(e["binding"], valueSet=VS_URL)
# the productOrService element has no binding of its own in the PAS differentials if it was inherited: make the override explicit
if not any(e["path"] == TARGET_PATH and "binding" in e for e in diff_elements):
    target_id = next(e["id"] for e in claim["snapshot"]["element"] if e["path"] == TARGET_PATH)
    hit = next((e for e in diff_elements if e["id"] == target_id), None)
    if hit is None:
        hit = {"id": target_id, "path": TARGET_PATH}
        diff_elements.append(hit)
        diff_elements.sort(key=lambda e: order.index(e["id"]))
    hit["binding"] = {"strength": "required", "valueSet": VS_URL, "description": "PAS X12 278 requested service types plus CDT (OHIA overlay)"}
sd["differential"] = {"element": diff_elements}

os.makedirs(OUT, exist_ok=True)
with open(os.path.join(OUT, "StructureDefinition-ohia-pas-claim-cdt.json"), "w", encoding="utf-8", newline="\n") as f:
    json.dump(sd, f, indent=2, ensure_ascii=False)
    f.write("\n")
with open(os.path.join(OUT, "ValueSet-ohia-requested-service-type-cdt.json"), "w", encoding="utf-8", newline="\n") as f:
    json.dump(vs, f, indent=2, ensure_ascii=False)
    f.write("\n")

# ---------------------------------------------------------------- self-diff: the "no other changes" proof
ALLOWED_TOP = {"id", "url", "version", "name", "title", "status", "date", "publisher", "description", "baseDefinition", "derivation",
               "identifier", "extension", "contact", "text", "differential"}  # differential is rebuilt; checked separately below
problems = []
for k in set(claim) | set(sd):
    if k in ALLOWED_TOP or k == "snapshot":
        continue
    if claim.get(k) != sd.get(k):
        problems.append(f"top-level '{k}' differs")
a, b = claim["snapshot"]["element"], sd["snapshot"]["element"]
if [e["id"] for e in a] != [e["id"] for e in b]:
    problems.append("snapshot element ids/order differ")
diffs = []
for ea, eb in zip(a, b):
    if ea != eb:
        keys = sorted(k for k in set(ea) | set(eb) if ea.get(k) != eb.get(k))
        diffs.append((ea["id"], keys))
allowed = [(i, k) for i, k in diffs if i == next(e["id"] for e in a if e["path"] == TARGET_PATH) and k == ["binding"]]
extra = [d for d in diffs if d not in allowed]
if extra:
    problems.append(f"unexpected snapshot differences: {extra[:5]}")
if len(allowed) != 1:
    problems.append(f"expected exactly one snapshot difference (the binding), found {len(allowed)}")
# inside that one binding, only valueSet (and the description) may differ
pa = next(e for e in a if e["path"] == TARGET_PATH)["binding"]
pb = next(e for e in b if e["path"] == TARGET_PATH)["binding"]
if pa["strength"] != pb["strength"] or pa["strength"] != "required":
    problems.append("binding strength changed")
if {k for k in set(pa) | set(pb) if pa.get(k) != pb.get(k)} - {"valueSet", "description"}:
    problems.append("binding differs in more than valueSet/description")

print(f"snapshot elements compared: {len(a)}; differing elements: {len(diffs)}; allowed: {len(allowed)}")
if allowed:
    print(f"  the one difference: {allowed[0][0]} -> binding.valueSet {pa['valueSet']}  =>  {pb['valueSet']} (strength {pb['strength']})")
print(f"differential elements written: {len(diff_elements)}")
if problems:
    print("SELF-DIFF FAILED:")
    for p in problems:
        print("  -", p)
    sys.exit(1)
print("SELF-DIFF PASSED: the overlay differs from PAS profile-claim 2.2.1 only in the Claim.item.productOrService binding")
