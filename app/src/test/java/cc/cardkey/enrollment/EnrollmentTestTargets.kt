package cc.cardkey.enrollment

internal fun enrollmentTarget(address: String, identity: EnrollmentIdentity) = EnrollmentTarget(
    issuerOrigin = address, personId = "00000000-0000-4000-8000-000000000001", cardId = "00000000-0000-4000-8000-000000000002", caId = "synthetic-ca", identity = identity)
