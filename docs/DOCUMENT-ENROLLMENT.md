# Document certificate enrollment

Document signing uses PIV slot 9C and an existing P-256, P-384 or RSA-2048 key. Key management is a separate 9D/RSA-2048 purpose. The exact subject, SAN, KU and EKU rules are listed in [PIV certificate enrollment](PIV-ENROLLMENT.md).

The selected issuer person supplies the immutable expected identity. Purpose, slot, algorithm and person/card/CA IDs travel with the verified CSR. The issuer registers these fields and mints authorization from its stored person snapshot. The app compares registration and mint readback against its own snapshot before contacting the CA. The signing token stays in memory.

The app persists the request before issuer mutation and retains unknown outcomes across process recreation. A randomized CSR for the same unresolved intent cannot reopen submission. Purpose and identity changes retire current callbacks and import eligibility while retaining unknown receipts. See the enrollment guide for cancellation, result reporting and recovery boundaries.

Import revalidates the certificate, compares the selected card key, obtains management authorization, writes the leaf and checks readback. Document identities cannot enter TLS qualification. CMS signing remains a separate explicit action after enrollment.

The disposable loopback CA fixture has separate role templates. Its document tests cover all three supported algorithms, wrong-role responses, import/readback and the existing CMS consumer. Run these on the designated node with the Smallstep binaries specified in the enrollment guide; physical qualification is performed separately by the coordinator and Owner.
