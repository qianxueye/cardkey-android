# Document certificate enrollment

Refs [CardKey #35](https://github.com/qianxueye/cardkey/issues/35). The umbrella issue remains open.

The PIV enrollment panel supports two closed profiles. TLS remains 9A/P-256 with exactly clientAuth. Document signing defaults to 9C, allows the existing PIV slot selector and existing P-256, P-384 or RSA-2048 keys, and requires exactly EKU 1.3.6.1.5.5.7.3.36. Both require a CN-only subject, no SAN, exactly digitalSignature, non-CA constraints, matching SPKI, bounded validity and a chain to the explicitly selected root.

The selected purpose, slot and algorithm travel with the verified CSR. Selection revisions retire old CSR, import, root-picker and confirmation eligibility, including an A→B→A change. The request sends only CSR and OTT to /1.0/sign; provisioner policy determines the role. The slot is a local import destination and is not authorized by the token or encoded in the CSR. JWT decoding is local preflight, not authorization. No key is generated or replaced.

Cancellation while a request is queued restores its unsubmitted CSR state and does not spend the attempt or create an unknown receipt. A synchronized handoff separates queued cancellation from a begun CA call; after that handoff, cancellation remains conservative. Unknown issuance keeps the existing full-CSR attempt ledger and adds an in-model fence over purpose, slot, algorithm/SPKI, CN, CA endpoint and root. A randomized ECDSA CSR cannot bypass this fence. Unknown receipts remain separate from the current form and later requests. These records do not persist across model destruction or app restart. There is no automatic retry or reconciliation.

Import uses the captured slot and revalidates the certificate before the existing key-match, management authorization and write/readback path. Document 9A retains replacement confirmation and cannot enter TLS eligibility or the actual TLS execution boundary. Issuance/import do not automatically sign a document. The existing CMS consumer remains a separate explicit action.

The synthetic loopback step-ca fixture has separate TLS/document provisioners and fixed role templates. It exercises genuine CSR-bound token issuance, all three document algorithms, wrong-role responses, import/readback and the existing CMS consumer. It does not represent service deployment or physical card qualification.

Copy review: [Chinese record](DOCUMENT-ENROLLMENT-COPY.zh-CN.md).
