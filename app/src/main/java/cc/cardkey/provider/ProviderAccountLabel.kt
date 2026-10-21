package cc.cardkey.provider

internal data class ProviderAccountLabel(val title: String?, val username: String?, val number: Int,
                                        val credentialId: String?)

internal fun providerAccountLabels(assertions: List<ProvidedAssertion>): List<ProviderAccountLabel> {
    val names = assertions.map { value ->
        val user = value.assertion.user
        val username = user?.name?.takeIf { it.isNotBlank() }
        (user?.displayName?.takeIf { it.isNotBlank() } ?: username) to username
    }
    val counts = names.groupingBy { it }.eachCount()
    return assertions.mapIndexed { index, value ->
        val (title, username) = names[index]
        val ambiguous = title == null || counts.getValue(names[index]) > 1
        ProviderAccountLabel(title, username?.takeIf { it != title }, index + 1,
            value.assertion.credentialId.takeIf { ambiguous }?.joinToString("") { "%02x".format(it) })
    }
}
