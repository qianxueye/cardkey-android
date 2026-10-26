package cc.cardkey.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.Color
import cc.cardkey.R
import cc.cardkey.issuer.*

@Composable
internal fun IssuerBadgeRoute() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val reader = remember { IssuerReader(IssuerSecrets(context.getSharedPreferences("issuer-operator", Context.MODE_PRIVATE)), scope) }
    DisposableEffect(reader) { onDispose { reader.close() } }
    IssuerBadgePanel(reader)
}

@Composable
internal fun IssuerBadgePanel(reader: IssuerReader) {
    val state by reader.state.collectAsState()
    val front by reader.front.state.collectAsState()
    val back by reader.back.state.collectAsState()
    var address by remember { mutableStateOf(state.origin) }
    var token by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().testTag("issuer-records"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.issuer_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.issuer_intro), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(address, { address = it; token = ""; reader.editConnection() },
            label = { Text(stringResource(R.string.issuer_address)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it; reader.editConnection() },
            label = { Text(stringResource(R.string.issuer_token)) }, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("issuer-token"))
        Text(stringResource(R.string.issuer_token_hint), style = MaterialTheme.typography.bodySmall)
        Button(onClick = { reader.connect(address, token); token = "" }, enabled = !state.busy) {
            Text(stringResource(R.string.issuer_connect))
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        state.actor?.let { actor ->
            Text(stringResource(R.string.issuer_operator, actor))
            TextButton(onClick = reader::close) { Text(stringResource(R.string.issuer_disconnect)) }
            Text(stringResource(R.string.issuer_people), style = MaterialTheme.typography.titleLarge)
            if (state.peopleLoaded && state.people.isEmpty() && state.peopleCursor.isEmpty() && !state.busy && state.error == null) Text(stringResource(R.string.issuer_no_people))
            state.people.forEach { person ->
                OutlinedButton(onClick = { reader.selectPerson(person) },
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (state.person?.id == person.id) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                    modifier = Modifier.fillMaxWidth().semantics { selected = state.person?.id == person.id }) {
                    Text("${person.name} · ${person.reference}")
                }
            }
            if (state.peopleCursor.isNotEmpty()) TextButton(onClick = reader::morePeople, enabled = !state.busy) { Text(stringResource(R.string.issuer_more_people)) }
        }
        state.person?.let { person ->
            HorizontalDivider()
            Text(stringResource(R.string.issuer_person_current), style = MaterialTheme.typography.titleLarge)
            Text("${person.name} · ${person.reference}")
            Text(listOf(person.department, person.title, person.badge).filter { it.isNotEmpty() }.joinToString(" · "))
            Text(stringResource(if (person.active) R.string.issuer_active else R.string.issuer_inactive))
            state.cardsError?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = reader::retryCards, enabled = !state.busy) { Text(stringResource(R.string.issuer_retry_cards)) }
            }
            if (state.cardsLoaded && state.cards.isEmpty() && state.cardsCursor.isEmpty() && !state.busy && state.cardsError == null) Text(stringResource(R.string.issuer_no_cards))
            state.cards.forEach { card ->
                OutlinedButton(onClick = { reader.selectCard(card) },
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (state.card?.id == card.id) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                    modifier = Modifier.fillMaxWidth().semantics { selected = state.card?.id == card.id }) {
                    Text(stringResource(R.string.issuer_card, card.serial, stringResource(cardStatus(card.status))))
                }
            }
            if (state.cardsCursor.isNotEmpty()) TextButton(onClick = reader::moreCards, enabled = !state.busy) { Text(stringResource(R.string.issuer_more_cards)) }
        }
        state.card?.let { card ->
            Text(stringResource(R.string.issuer_card, card.serial, stringResource(cardStatus(card.status))), style = MaterialTheme.typography.titleLarge)
            state.revisionsError?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = reader::retryRevisions, enabled = !state.busy) { Text(stringResource(R.string.issuer_retry_revisions)) }
            }
            if (state.revisionsLoaded && state.revisions.isEmpty() && state.revisionsCursor.isEmpty() && state.current == null && !state.busy && state.revisionsError == null) Text(stringResource(R.string.issuer_no_badge))
            state.revisions.forEach { revision ->
                OutlinedButton(onClick = { reader.selectRevision(revision) },
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (state.revision?.id == revision.id) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                    modifier = Modifier.fillMaxWidth().semantics { selected = state.revision?.id == revision.id }) {
                    Text(stringResource(if (revision.id == state.current) R.string.issuer_current else R.string.issuer_history, revision.created))
                }
            }
            if (state.revisionsCursor.isNotEmpty()) TextButton(onClick = reader::moreRevisions, enabled = !state.busy) { Text(stringResource(R.string.issuer_more_history)) }
        }
        state.revision?.let { revision ->
            Text(stringResource(R.string.issuer_snapshot), style = MaterialTheme.typography.titleLarge)
            Text("${revision.name} · ${revision.reference}")
            Text(listOf(revision.department, revision.title, revision.badge).filter { it.isNotEmpty() }.joinToString(" · "))
            Text(stringResource(R.string.issuer_published, revision.created, revision.actor))
            Text(stringResource(R.string.issuer_archived, revision.sourceCreated))
            Text(stringResource(R.string.issuer_front), style = MaterialTheme.typography.titleMedium)
            CardArtworkHero(front, reader::retryArtwork)
            Text(stringResource(R.string.issuer_back), style = MaterialTheme.typography.titleMedium)
            CardArtworkHero(back, reader::retryArtwork)
        }
    }
}
private fun cardStatus(status: String): Int = when (status) {
    "issued" -> R.string.issuer_issued
    "lost" -> R.string.issuer_lost
    "revoked" -> R.string.issuer_revoked
    else -> R.string.issuer_other_status
}
