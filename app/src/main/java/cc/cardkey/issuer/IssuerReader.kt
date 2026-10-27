package cc.cardkey.issuer

import cc.cardkey.R
import cc.cardkey.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class IssuerReaderState(
    val origin: String = "", val identity: IssuerIdentity? = null, val busy: Boolean = false, val error: Int? = null,
    val people: List<IssuerPerson> = emptyList(), val peopleCursor: String = "", val person: IssuerPerson? = null,
    val peopleLoaded: Boolean = false,
    val cards: List<IssuerCard> = emptyList(), val cardsCursor: String = "", val card: IssuerCard? = null,
    val cardsLoaded: Boolean = false, val cardsError: Int? = null,
    val revisions: List<BadgeRevision> = emptyList(), val revisionsCursor: String = "", val current: String? = null,
    val revision: BadgeRevision? = null, val revisionsLoaded: Boolean = false, val revisionsError: Int? = null,
)
internal class IssuerReader(private val secrets: IssuerSecrets, private val scope: CoroutineScope,
    private val makeClient: (String, String) -> IssuerClient = { origin, token -> IssuerClient(origin, token) }) {
    private val mutable = MutableStateFlow(IssuerReaderState(origin = secrets.origin()))
    val state = mutable.asStateFlow()
    private var client: IssuerClient? = null
    private data class Credential(val origin: String, val token: String)
    private var credential: Credential? = null
    private var job: Job? = null
    private var generation = 0L
    private var artworkClient: IssuerClient? = null
    private var artworkRevision: BadgeRevision? = null
    val front = CardArtworkLoader(source(false), scope)
    val back = CardArtworkLoader(source(true), scope)
    private fun source(back: Boolean) = CardArtworkSource {
        val api = requireNotNull(artworkClient)
        val revision = requireNotNull(artworkRevision)
        val epoch = generation
        try { api.artwork(revision, back) } catch (failure: IssuerFailure) {
            if (epoch == generation) {
                when {
                    failure.status == 401 -> unauthorized()
                    failure.status == 403 -> forbidden()
                    failure.status == 404 && mutable.value.identity?.role == IssuerRole.EMPLOYEE -> {
                        retire()
                        mutable.value = mutable.value.copy(revision = null, current = null, revisionsLoaded = false,
                            revisionsError = R.string.issuer_artwork_unavailable)
                    }
                }
            }
            throw failure
        }
    }
    private fun retire() {
        generation++
        job?.cancel(); job = null
        front.clear(); back.clear(); artworkClient = null; artworkRevision = null
    }
    fun editConnection() {
        retire(); client = null; credential = null
        mutable.value = IssuerReaderState(origin = mutable.value.origin)
    }
    fun close() { retire(); client = null; credential = null; mutable.value = IssuerReaderState(origin = mutable.value.origin) }
    private fun unauthorized() {
        val origin = mutable.value.origin
        val rejected = credential
        retire(); client = null; credential = null
        rejected?.let { runCatching { secrets.forget(it.origin, it.token) } }
        mutable.value = IssuerReaderState(origin = origin, error = R.string.issuer_unauthorized)
    }
    private fun forbidden() {
        val origin = mutable.value.origin
        retire(); client = null; credential = null
        mutable.value = IssuerReaderState(origin = origin, error = R.string.issuer_forbidden)
    }
    private enum class Query { CARDS, REVISIONS }
    private fun run(query: Query? = null, block: suspend () -> Unit) {
        val epoch = generation
        mutable.value = when (query) {
            Query.CARDS -> mutable.value.copy(busy = true, cardsError = null)
            Query.REVISIONS -> mutable.value.copy(busy = true, revisionsError = null)
            null -> mutable.value.copy(busy = true, error = null)
        }
        job = scope.launch {
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (epoch == generation) {
                    if (failure is IssuerFailure && failure.status == 401) unauthorized()
                    else if (failure is IssuerFailure && failure.status == 403) forbidden()
                    else {
                        val error = when {
                            failure is IssuerIdentityFailure -> R.string.issuer_invalid_identity
                            failure is IssuerFailure && failure.status == 404 -> R.string.issuer_missing
                            failure is IssuerFailure && failure.status in 300..399 -> R.string.issuer_redirect
                            failure is IllegalArgumentException || failure is IllegalStateException -> R.string.issuer_invalid_response
                            else -> R.string.issuer_failed
                        }
                        mutable.value = when (query) {
                            Query.CARDS -> mutable.value.copy(cardsError = error)
                            Query.REVISIONS -> mutable.value.copy(revisionsError = error)
                            null -> mutable.value.copy(error = error)
                        }
                    }
                }
            } finally { if (epoch == generation) mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun connect(address: String, enteredToken: String) {
        editConnection()
        val origin = runCatching { issuerOrigin(address) }.getOrElse {
            mutable.value = mutable.value.copy(error = R.string.issuer_address_error); return
        }
        mutable.value = IssuerReaderState(origin = origin)
        run {
            val token = enteredToken.ifEmpty {
                try { secrets.token(origin).orEmpty() } catch (_: Exception) {
                    mutable.value = mutable.value.copy(error = R.string.issuer_secret_read_failed); return@run
                }
            }
            if (token.isBlank() || token.length > 4096 || token.any { it.code !in 33..126 }) {
                mutable.value = mutable.value.copy(error = R.string.issuer_token_required); return@run
            }
            credential = Credential(origin, token)
            val api = makeClient(origin, token)
            val identity = api.me()
            currentCoroutineContext().ensureActive()
            try { secrets.save(origin, token) } catch (_: Exception) {
                mutable.value = mutable.value.copy(error = R.string.issuer_secret_save_failed); return@run
            }
            client = api
            mutable.value = mutable.value.copy(identity = identity)
            if (identity.staff) {
                val people = api.people()
                currentCoroutineContext().ensureActive()
                mutable.value = mutable.value.copy(people = people.items, peopleCursor = people.next, peopleLoaded = true)
            } else {
                val person = api.selfPerson()
                currentCoroutineContext().ensureActive()
                mutable.value = mutable.value.copy(person = person)
                val cards = api.cards(person.id)
                currentCoroutineContext().ensureActive()
                mutable.value = mutable.value.copy(cards = cards.items, cardsCursor = cards.next, cardsLoaded = true)
            }
        }
    }
    fun morePeople() {
        if (mutable.value.identity?.staff != true) return
        val api = client ?: return
        val cursor = mutable.value.peopleCursor
        if (cursor.isEmpty() || mutable.value.busy) return
        run {
            val page = api.people(cursor); currentCoroutineContext().ensureActive()
            require(page.next.isEmpty() || page.next != cursor)
            mutable.value = mutable.value.copy(people = (mutable.value.people + page.items).distinctBy { it.id }, peopleCursor = page.next, peopleLoaded = true)
        }
    }
    fun selectPerson(person: IssuerPerson) {
        if (mutable.value.identity?.staff != true || person !in mutable.value.people) return
        loadPersonCards(person)
    }
    private fun loadPersonCards(person: IssuerPerson) {
        val api = client ?: return
        retire()
        mutable.value = mutable.value.copy(person = person, cards = emptyList(), cardsCursor = "", card = null,
            revisions = emptyList(), revisionsCursor = "", current = null, revision = null,
            cardsLoaded = false, cardsError = null, revisionsLoaded = false, revisionsError = null)
        run(Query.CARDS) {
            val page = api.cards(person.id); currentCoroutineContext().ensureActive()
            mutable.value = mutable.value.copy(cards = page.items, cardsCursor = page.next, cardsLoaded = true)
        }
    }
    fun moreCards() {
        val api = client ?: return
        val person = mutable.value.person ?: return
        val cursor = mutable.value.cardsCursor
        if (cursor.isEmpty() || mutable.value.busy) return
        run(Query.CARDS) {
            val page = api.cards(person.id, cursor); currentCoroutineContext().ensureActive()
            require(page.next.isEmpty() || page.next != cursor)
            mutable.value = mutable.value.copy(cards = (mutable.value.cards + page.items).distinctBy { it.id }, cardsCursor = page.next, cardsLoaded = true)
        }
    }
    fun retryCards() {
        if (mutable.value.cardsCursor.isNotEmpty()) moreCards() else mutable.value.person?.let(::loadPersonCards)
    }
    fun selectCard(card: IssuerCard) {
        val api = client ?: return
        if (card !in mutable.value.cards || card.person != mutable.value.person?.id) return
        retire()
        mutable.value = mutable.value.copy(card = card, revisions = emptyList(), revisionsCursor = "", current = null, revision = null,
            revisionsLoaded = false, revisionsError = null)
        run(Query.REVISIONS) {
            val current = api.current(card)
            val page = if (mutable.value.identity?.staff == true) api.history(card) else IssuerPage<BadgeRevision>(emptyList(), "")
            currentCoroutineContext().ensureActive()
            mutable.value = mutable.value.copy(current = current?.id, revisions = page.items, revisionsCursor = page.next, revisionsLoaded = true)
            current?.let { showRevision(it, api) }
        }
    }
    fun moreRevisions() {
        if (mutable.value.identity?.staff != true) return
        val api = client ?: return
        val card = mutable.value.card ?: return
        val cursor = mutable.value.revisionsCursor
        if (cursor.isEmpty() || mutable.value.busy) return
        run(Query.REVISIONS) {
            val page = api.history(card, cursor); currentCoroutineContext().ensureActive()
            require(page.next.isEmpty() || page.next != cursor)
            mutable.value = mutable.value.copy(revisions = (mutable.value.revisions + page.items).distinctBy { it.id }, revisionsCursor = page.next, revisionsLoaded = true)
        }
    }
    fun retryRevisions() {
        if (mutable.value.revisionsCursor.isNotEmpty()) moreRevisions() else mutable.value.card?.let(::selectCard)
    }
    fun selectRevision(revision: BadgeRevision) {
        if (mutable.value.identity?.staff != true || (revision !in mutable.value.revisions && revision != mutable.value.revision)) return
        val api = client ?: return
        require(revision.card == mutable.value.card?.id && revision.person == mutable.value.person?.id)
        retire()
        mutable.value = mutable.value.copy(busy = false, error = null, revision = null)
        showRevision(revision, api)
    }
    private fun showRevision(revision: BadgeRevision, api: IssuerClient) {
        mutable.value = mutable.value.copy(revision = revision)
        artworkClient = api; artworkRevision = revision
        val reference = CardArtworkReference("${api.origin}/${mutable.value.identity?.id}/${mutable.value.identity?.role}/${mutable.value.identity?.personID}/${revision.card}", revision.id)
        front.load(reference); back.load(reference)
    }
    fun retryArtwork() {
        if (mutable.value.identity?.staff == true) mutable.value.revision?.let(::selectRevision)
        else mutable.value.card?.let(::selectCard)
    }
}
