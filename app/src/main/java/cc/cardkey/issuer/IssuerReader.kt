package cc.cardkey.issuer

import cc.cardkey.R
import cc.cardkey.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class IssuerReaderState(
    val origin: String = "", val actor: String? = null, val busy: Boolean = false, val error: Int? = null,
    val people: List<IssuerPerson> = emptyList(), val peopleCursor: String = "", val person: IssuerPerson? = null,
    val cards: List<IssuerCard> = emptyList(), val cardsCursor: String = "", val card: IssuerCard? = null,
    val revisions: List<BadgeRevision> = emptyList(), val revisionsCursor: String = "", val current: String? = null,
    val revision: BadgeRevision? = null,
)
internal class IssuerReader(private val secrets: IssuerSecrets, private val scope: CoroutineScope,
    private val makeClient: (String, String) -> IssuerClient = { origin, token -> IssuerClient(origin, token) }) {
    private val mutable = MutableStateFlow(IssuerReaderState(origin = secrets.origin()))
    val state = mutable.asStateFlow()
    private var client: IssuerClient? = null
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
            if (failure.status == 401 && epoch == generation) unauthorized()
            throw failure
        }
    }
    private fun retire() {
        generation++
        job?.cancel(); job = null
        front.clear(); back.clear(); artworkClient = null; artworkRevision = null
    }
    fun editConnection() {
        retire(); client = null
        mutable.value = IssuerReaderState(origin = mutable.value.origin)
    }
    fun close() { retire(); client = null; mutable.value = IssuerReaderState(origin = mutable.value.origin) }
    private fun unauthorized() {
        val origin = mutable.value.origin
        retire(); client = null
        runCatching { secrets.forget(origin) }
        mutable.value = IssuerReaderState(origin = origin, error = R.string.issuer_unauthorized)
    }
    private fun run(block: suspend () -> Unit) {
        val epoch = generation
        mutable.value = mutable.value.copy(busy = true, error = null)
        job = scope.launch {
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (epoch == generation) {
                    if (failure is IssuerFailure && failure.status == 401) unauthorized()
                    else mutable.value = mutable.value.copy(error = when {
                        failure is IssuerFailure && failure.status == 404 -> R.string.issuer_missing
                        failure is IssuerFailure && failure.status in 300..399 -> R.string.issuer_redirect
                        failure is IllegalArgumentException || failure is IllegalStateException -> R.string.issuer_invalid_response
                        else -> R.string.issuer_failed
                    })
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
            val api = makeClient(origin, token)
            val actor = api.me()
            currentCoroutineContext().ensureActive()
            try { secrets.save(origin, token) } catch (_: Exception) {
                mutable.value = mutable.value.copy(error = R.string.issuer_secret_save_failed); return@run
            }
            client = api
            mutable.value = mutable.value.copy(actor = actor)
            val people = api.people()
            currentCoroutineContext().ensureActive()
            mutable.value = mutable.value.copy(people = people.items, peopleCursor = people.next)
        }
    }
    fun morePeople() {
        val api = client ?: return
        val cursor = mutable.value.peopleCursor
        if (cursor.isEmpty() || mutable.value.busy) return
        run {
            val page = api.people(cursor); currentCoroutineContext().ensureActive()
            require(page.next.isEmpty() || page.next != cursor)
            mutable.value = mutable.value.copy(people = (mutable.value.people + page.items).distinctBy { it.id }, peopleCursor = page.next)
        }
    }
    fun selectPerson(person: IssuerPerson) {
        val api = client ?: return
        retire()
        mutable.value = mutable.value.copy(person = person, cards = emptyList(), cardsCursor = "", card = null,
            revisions = emptyList(), revisionsCursor = "", current = null, revision = null)
        run {
            val page = api.cards(person.id); currentCoroutineContext().ensureActive()
            mutable.value = mutable.value.copy(cards = page.items, cardsCursor = page.next)
        }
    }
    fun moreCards() {
        val api = client ?: return
        val person = mutable.value.person ?: return
        val cursor = mutable.value.cardsCursor
        if (cursor.isEmpty() || mutable.value.busy) return
        run {
            val page = api.cards(person.id, cursor); currentCoroutineContext().ensureActive()
            require(page.next.isEmpty() || page.next != cursor)
            mutable.value = mutable.value.copy(cards = (mutable.value.cards + page.items).distinctBy { it.id }, cardsCursor = page.next)
        }
    }
    fun selectCard(card: IssuerCard) {
        val api = client ?: return
        require(card.person == mutable.value.person?.id)
        retire()
        mutable.value = mutable.value.copy(card = card, revisions = emptyList(), revisionsCursor = "", current = null, revision = null)
        run {
            val current = api.current(card)
            val page = api.history(card); currentCoroutineContext().ensureActive()
            mutable.value = mutable.value.copy(current = current?.id, revisions = page.items, revisionsCursor = page.next)
            current?.let { showRevision(it, api) }
        }
    }
    fun moreRevisions() {
        val api = client ?: return
        val card = mutable.value.card ?: return
        val cursor = mutable.value.revisionsCursor
        if (cursor.isEmpty() || mutable.value.busy) return
        run {
            val page = api.history(card, cursor); currentCoroutineContext().ensureActive()
            require(page.next.isEmpty() || page.next != cursor)
            mutable.value = mutable.value.copy(revisions = (mutable.value.revisions + page.items).distinctBy { it.id }, revisionsCursor = page.next)
        }
    }
    fun selectRevision(revision: BadgeRevision) {
        val api = client ?: return
        require(revision.card == mutable.value.card?.id && revision.person == mutable.value.person?.id)
        retire()
        mutable.value = mutable.value.copy(busy = false, error = null, revision = null)
        showRevision(revision, api)
    }
    private fun showRevision(revision: BadgeRevision, api: IssuerClient) {
        mutable.value = mutable.value.copy(revision = revision)
        artworkClient = api; artworkRevision = revision
        val reference = CardArtworkReference("${api.origin}/${mutable.value.actor}/${revision.card}", revision.id)
        front.load(reference); back.load(reference)
    }
    fun retryArtwork() { mutable.value.revision?.let(::selectRevision) }
}
