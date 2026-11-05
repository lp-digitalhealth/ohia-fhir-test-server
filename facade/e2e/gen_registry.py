# Generates a throwaway tester RSA key and the facade client registry entry for it (facade/e2e/README.md).
# Usage: python gen_registry.py <output folder>   -> tester-key.pem and e2e-clients.json
import json, base64, sys
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization
out = sys.argv[1]
key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
def b64(i): 
    b = i.to_bytes((i.bit_length()+7)//8, "big"); return base64.urlsafe_b64encode(b).rstrip(b"=").decode()
pub = key.public_key().public_numbers()
jwk = {"kty":"RSA","kid":"tester-e2e-key","alg":"RS384","use":"sig","n":b64(pub.n),"e":b64(pub.e)}
open(out+"/tester-key.pem","wb").write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
json.dump({"clients":[{"clientId":"tester-e2e","requestingOrganization":"Organization/org-restorative-dental-tx",
  "allowedTargetPrefixes":["http://127.0.0.1:9100/fhir"],"jwks":{"keys":[jwk]}}]}, open(out+"/e2e-clients.json","w"), indent=1)
print("registry + key written")
