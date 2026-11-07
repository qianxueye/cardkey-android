package cc.cardkey.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cc.cardkey.R
import cc.cardkey.enrollment.EnrollmentIdentity
import cc.cardkey.enrollment.EnrollmentTarget
import cc.cardkey.issuer.*

@Composable
internal fun EnrollmentIssuerPanel(reader: IssuerReader, target: EnrollmentTarget?, enabled: Boolean,
                                   onTarget: (EnrollmentTarget?) -> Unit) {
    val state by reader.state.collectAsState()
    var address by remember { mutableStateOf(state.origin) }
    var credential by remember { mutableStateOf("") }
    var caId by remember { mutableStateOf(target?.caId.orEmpty()) }
    var personMenu by remember { mutableStateOf(false) }
    var cardMenu by remember { mutableStateOf(false) }
    val editable = enabled && !state.busy
    LaunchedEffect(enabled) { if (!enabled) credential = "" }
    Column(Modifier.fillMaxWidth().testTag("enrollment-issuer"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(address, { address = it; credential = ""; reader.editConnection(); onTarget(null) },
            enabled = editable, singleLine = true, label = { Text(stringResource(R.string.issuer_address)) },
            modifier = Modifier.fillMaxWidth())
        if (state.identity == null) {
            OutlinedTextField(credential, { credential = it }, enabled = editable, singleLine = true,
                label = { Text(stringResource(R.string.issuer_token)) }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("enrollment-issuer-credential"))
            Text(stringResource(R.string.issuer_token_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { onTarget(null); reader.connect(address, credential); credential = "" }, enabled = editable) {
                Text(stringResource(R.string.issuer_connect))
            }
        } else {
            TextButton(onClick = { reader.close(); onTarget(null) }, enabled = editable) { Text(stringResource(R.string.issuer_disconnect)) }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        if (state.identity?.staff == false) Text(stringResource(R.string.issuer_forbidden), color = MaterialTheme.colorScheme.error)
        if (state.identity?.staff == true) {
            OutlinedTextField(caId, { caId = it; onTarget(null) }, enabled = editable, singleLine = true,
                label = { Text(stringResource(R.string.enrollment_caId)) }, modifier = Modifier.fillMaxWidth())
            Box {
                OutlinedButton(onClick = { personMenu = true }, enabled = editable, modifier = Modifier.fillMaxWidth().testTag("enrollment-person")) {
                    Text(state.person?.let { "${it.name} · ${it.reference}" } ?: stringResource(R.string.enrollment_choosePerson))
                }
                DropdownMenu(expanded = personMenu && editable, onDismissRequest = { personMenu = false },
                    modifier = Modifier.heightIn(max = 320.dp)) {
                    state.people.forEach { person ->
                        DropdownMenuItem(text = { Text("${person.name} · ${person.reference}") }, enabled = person.active,
                            onClick = { personMenu = false; onTarget(null); reader.selectPerson(person) })
                    }
                }
            }
            if (state.peopleLoaded && state.people.isEmpty()) Text(stringResource(R.string.issuer_no_people))
            if (state.peopleCursor.isNotEmpty()) TextButton(onClick = reader::morePeople, enabled = editable) { Text(stringResource(R.string.issuer_more_people)) }
            state.person?.let { person ->
                val identity = remember(person) {
                    runCatching { EnrollmentIdentity(person.reference, person.name, person.upn, person.email) }.getOrNull()
                }
                if (identity == null) Text(stringResource(R.string.enrollment_personNotEligible), color = MaterialTheme.colorScheme.error)
                Text(listOf(person.upn, person.email).filter(String::isNotEmpty).distinct().joinToString(" · "))
                state.cardsError?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = reader::retryCards, enabled = editable) { Text(stringResource(R.string.issuer_retry_cards)) }
                }
                Box {
                    OutlinedButton(onClick = { cardMenu = true }, enabled = editable && person.active && identity != null && caId.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("enrollment-person-card")) {
                        val selected = state.cards.firstOrNull { it.id == target?.cardId }
                        Text(selected?.let { stringResource(R.string.issuer_card, it.serial, stringResource(R.string.issuer_issued)) }
                            ?: stringResource(R.string.enrollment_chooseCard))
                    }
                    DropdownMenu(expanded = cardMenu && editable && person.active && identity != null && caId.isNotBlank(), onDismissRequest = { cardMenu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                        state.cards.filter { it.status == "issued" }.forEach { card ->
                            DropdownMenuItem(text = { Text(card.serial) }, onClick = {
                                cardMenu = false
                                identity?.let { onTarget(EnrollmentTarget(state.origin, person.id, card.id, caId, it)) }
                            })
                        }
                    }
                }
                if (state.cardsLoaded && state.cards.none { it.status == "issued" }) Text(stringResource(R.string.enrollment_noIssuedCards))
                if (state.cardsCursor.isNotEmpty()) TextButton(onClick = reader::moreCards, enabled = editable) { Text(stringResource(R.string.issuer_more_cards)) }
            }
        }
    }
}
