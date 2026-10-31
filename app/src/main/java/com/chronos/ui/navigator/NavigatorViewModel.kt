package com.chronos.ui.navigator

import com.chronos.ui.*
import com.chronos.ui.common.*
import com.chronos.ui.setup.*
import com.chronos.ui.horizon.*
import com.chronos.ui.day.*
import com.chronos.ui.log.*

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chronos.data.ChronosRepository
import com.chronos.data.DatabaseWiring
import com.chronos.model.Enums.ActivityKind
import com.chronos.navigator.model.Graph
import com.chronos.navigator.model.Issue
import com.chronos.navigator.model.Kinds.Dist
import com.chronos.navigator.model.Param
import com.chronos.navigator.llm.EstimatePrior
import com.chronos.navigator.llm.LlmRequest
import com.chronos.navigator.llm.ProposalValidator
import com.chronos.navigator.llm.client.KeyVault
import com.chronos.navigator.llm.client.LlmClient
import com.chronos.navigator.llm.client.LlmSettings
import com.chronos.navigator.llm.client.Profile
import com.chronos.navigator.model.Issue.Fix
import com.chronos.navigator.model.Issue.Severity
import com.chronos.navigator.store.NavEntities
import com.chronos.navigator.store.NavProposals
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Navigator tab's state. Separate from [ChronosViewModel] on purpose: the
 * Navigator reads Chronos (its activities, later its log) but never edits it,
 * and keeping the two apart keeps that one-way.
 */
class NavigatorViewModel(private val app: Application) : AndroidViewModel(app) {
    private val store = DatabaseWiring.navigator(app)
    private val repo = DatabaseWiring.repository(app)
    private val inbox = NavProposals(store.dao())
    val llm = LlmSettings(app)

    var graph by mutableStateOf<Graph?>(null); private set
    var info by mutableStateOf<NavEntities.GraphRow?>(null); private set
    /** What the graph still lacks before the layers can run. */
    var issues by mutableStateOf<List<Issue>>(emptyList()); private set
    /** Why the last file was refused; empty after a successful import. */
    var refused by mutableStateOf<List<Issue>>(emptyList()); private set
    var params by mutableStateOf<List<Param>>(emptyList()); private set
    /** Chronos goal activities, the ones a node can count. */
    var activities by mutableStateOf<List<String>>(emptyList()); private set
    var proposals by mutableStateOf<List<NavProposals.View>>(emptyList()); private set
    var calls by mutableStateOf<List<NavEntities.LlmCallRow>>(emptyList()); private set
    var spentThisMonth by mutableStateOf(0.0); private set
    /** The providers set up, and which of them have a key saved. */
    var profiles by mutableStateOf<List<Profile>>(emptyList()); private set
    var keyed by mutableStateOf<Set<String>>(emptySet()); private set
    var activeProfile by mutableStateOf<Profile?>(null); private set
    /** Models the provider being edited offers, once fetched. */
    var fetchedModels by mutableStateOf<List<String>>(emptyList()); private set
    /** The provider [fetchedModels] belongs to. */
    var fetchedFor by mutableStateOf<String?>(null); private set
    /** Bumped when an LLM setting changes, so the panel redraws. */
    var llmRevision by mutableStateOf(0); private set
    /** The newest forecast, and the newest one a week old for the trend. */
    var forecast by mutableStateOf<com.chronos.navigator.store.NavStore.StoredForecast?>(null); private set
    var weekOld by mutableStateOf<com.chronos.navigator.store.NavStore.StoredForecast?>(null); private set
    /** The newest week's hours, the meta row included (see NavStore.WEEK_META). */
    var week by mutableStateOf<List<NavEntities.AllocationRow>>(emptyList()); private set
    /** Whether Chronos's floors currently follow an accepted week. */
    var following by mutableStateOf(false); private set
    /** What the last apply or stop changed in Chronos, for the screen to show. */
    var changes by mutableStateOf<List<com.chronos.navigator.bridge.Bridge.Change>>(emptyList()); private set
    /** Chronos goal activity per node, after links: what the bridge sends the node's hours through. */
    var nodeActivities by mutableStateOf<Map<String, List<String>>>(emptyMap()); private set
    /** The inputs whose answers would move the forecast most, as last ranked. */
    var questions by mutableStateOf<com.chronos.navigator.llm.client.InfoCache.Saved?>(null); private set
    /** Where a node and its Chronos goal disagree, waiting for you to say which is right. */
    var syncDiffs by mutableStateOf<List<com.chronos.navigator.bridge.Sync.Diff>>(emptyList()); private set
    /** Each node's numbers as they stand now: what the forecast and the log say, not only what the file said. */
    var nodeNow by mutableStateOf<Map<String, NodeNow>>(emptyMap()); private set
    /** Each trigger's memory: when it last fired, and whether it is armed. */
    var triggers by mutableStateOf<List<com.chronos.navigator.trigger.Triggers.State>>(emptyList()); private set
    /** Running the layers right now; the screen shows it instead of the busy bar. */
    var computing by mutableStateOf(false); private set
    var loading by mutableStateOf(true); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)

    /** Your conversation with the Navigator, and the crew's jobs, newest first. */
    var chat by mutableStateOf<List<com.chronos.navigator.llm.client.CrewRun.ChatTurn>>(emptyList()); private set
    /** The conversation open, and every conversation, the latest first (28 Oct 2026). */
    var chatId by mutableStateOf<String?>(null); private set
    var chats by mutableStateOf<List<com.chronos.navigator.llm.client.CrewRun.ChatInfo>>(emptyList()); private set
    /** What the app sent by itself (weekly reviews, readings of your log, re-checks): shown in the inbox, not among your conversations. */
    var appNotes by mutableStateOf<List<com.chronos.navigator.llm.client.CrewRun.ChatTurn>>(emptyList()); private set
    var jobs by mutableStateOf<List<com.chronos.navigator.llm.client.CrewRun.Job>>(emptyList()); private set
    var crewPicks by mutableStateOf<Map<String, com.chronos.navigator.llm.client.CrewSettings.Pick>>(emptyMap()); private set
    var freeModels by mutableStateOf<List<com.chronos.navigator.llm.client.ModelInfo>>(emptyList()); private set
    var freeLeft by mutableStateOf(0); private set
    private val crew = com.chronos.navigator.llm.client.CrewSettings(app)

    init {
        refresh()
        viewModelScope.launch { readOutbox() }
        com.chronos.navigator.llm.client.Outbox.onChange = { viewModelScope.launch { readOutbox() } }
        com.chronos.navigator.llm.client.CrewRun.onChange = {
            viewModelScope.launch {
                val before = jobs.filter { it.status == "RUNNING" }.map { it.id }.toSet()
                withContext(Dispatchers.IO) { loadCrew() }
                // A job just ended: its package is in the inbox.
                if (jobs.any { it.id in before && it.status != "RUNNING" }) withContext(Dispatchers.IO) { load() }
            }
        }
    }

    override fun onCleared() {
        com.chronos.navigator.llm.client.CrewRun.onChange = null
        com.chronos.navigator.llm.client.Outbox.onChange = null
        super.onCleared()
    }

    private fun loadCrew() {
        chats = com.chronos.navigator.llm.client.CrewRun.chats(app).filter { !it.fromApp }
        appNotes = com.chronos.navigator.llm.client.CrewRun.chat(app, com.chronos.navigator.llm.client.CrewRun.APP_CHAT)
        // The app's own notes live in the inbox now, not among your conversations (29 Oct 2026).
        chatId = com.chronos.navigator.llm.client.CrewRun.openChatId(app)?.takeIf { it != com.chronos.navigator.llm.client.CrewRun.APP_CHAT }
        chat = chatId?.let { com.chronos.navigator.llm.client.CrewRun.chat(app, it) } ?: emptyList()
        jobs = com.chronos.navigator.llm.client.CrewRun.jobs(app).take(12)
        crewPicks = crew.picks
        freeLeft = crew.freeLeft
        if (freeModels.isEmpty()) freeModels = runCatching {
            app.getSharedPreferences("chronos.llm", android.content.Context.MODE_PRIVATE).getString("crew.models", null)?.let {
                com.google.gson.Gson().fromJson<List<com.chronos.navigator.llm.client.ModelInfo>>(it,
                    object : com.google.gson.reflect.TypeToken<List<com.chronos.navigator.llm.client.ModelInfo>>() {}.type)
            }
        }.getOrNull() ?: emptyList()
    }

    val crewReady get() = crew.ready
    val freeCap get() = crew.freeDailyCap

    /** You told the Navigator something: the crew starts on it in the background. */
    fun tell(text: String) = work {
        if (text.isBlank()) return@work
        com.chronos.navigator.llm.client.CrewRun.tell(app, text)
        loadCrew()
    }

    /** A question about the graph itself, to the crew or through your own chat app. */
    fun askGraph(text: String) = work {
        if (text.isBlank()) return@work
        if (relayMode) {
            computing = true
            try { com.chronos.navigator.llm.client.CrewRun.prepareRelay(app, "GRAPH", text) } finally { computing = false }
        } else com.chronos.navigator.llm.client.CrewRun.askGraph(app, text)
        loadCrew()
    }

    /** Whether messages go to your own chat app (copy and return). */
    var relayMode by mutableStateOf(crew.relay); private set

    fun setRelay(on: Boolean) { crew.relay = on; relayMode = on }

    /** Your message as one question for your own chat app; the card in the chat takes it from there. */
    fun tellRelay(text: String) = work {
        if (text.isBlank()) return@work
        computing = true
        try { com.chronos.navigator.llm.client.CrewRun.prepareRelay(app, "INTAKE", text) } finally { computing = false }
        loadCrew()
    }

    fun reviewRelay() = work {
        computing = true
        try { com.chronos.navigator.llm.client.CrewRun.prepareRelay(app, "WEEKLY", null) } finally { computing = false }
        loadCrew()
    }

    fun relayPrompt(id: String) = com.chronos.navigator.llm.client.CrewRun.relayPrompt(app, id)

    /** The answer you copied from your chat app. */
    fun answerRelay(id: String, text: String) = work {
        message = com.chronos.navigator.llm.client.CrewRun.answerRelay(app, id, text)
        loadCrew(); load()
    }

    /** Set when the app should show the Navigator (a reply was shared to it). */
    var showNavigator by mutableStateOf(false)

    /**
     * A chat app shared its reply to Chronos: it answers the question waiting for one (the newest, if
     * several wait). With none waiting, nothing is done and you are told so.
     */
    fun receiveShared(text: String) {
        showNavigator = true
        work {
            val waiting = com.chronos.navigator.llm.client.CrewRun.jobs(app)
                .filter { it.relay && it.status == "WAITING_ANSWER" }.maxByOrNull { it.createdAt }
            if (waiting == null) {
                message = "No question is waiting for an answer: ask one first with \"My chat app\"."
                return@work
            }
            computing = true
            try {
                message = com.chronos.navigator.llm.client.CrewRun.answerRelay(app, waiting.id, text)
            } catch (e: Exception) {
                // The question keeps waiting; the shared text is kept to look at or paste again.
                sharedFailed = text
                throw e
            } finally { computing = false }
            loadCrew(); load()
        }
    }

    /** A shared reply that could not be used, shown in the question's card to correct or paste again. */
    var sharedFailed by mutableStateOf<String?>(null)

    fun cancelRelay(id: String) = work { com.chronos.navigator.llm.client.CrewRun.cancelRelay(app, id); loadCrew() }

    /** A message that was not sent, sent again as it was. */
    fun retry(turn: com.chronos.navigator.llm.client.CrewRun.ChatTurn) = work {
        chatId?.let { com.chronos.navigator.llm.client.CrewRun.removeTurn(app, it, turn.at) }
        com.chronos.navigator.llm.client.CrewRun.tell(app, turn.text)
        loadCrew()
    }

    /** A message that was not sent, taken back into the box to change it. */
    fun takeBack(turn: com.chronos.navigator.llm.client.CrewRun.ChatTurn) = work {
        chatId?.let { com.chronos.navigator.llm.client.CrewRun.removeTurn(app, it, turn.at) }
        loadCrew()
    }

    /** What the log suggests about hours, days, lengths and sleep, now (no model needed). */
    fun habitsNow() = work {
        val id = com.chronos.navigator.llm.client.CrewRun.habits(app, "you asked for it")
        loadCrew()
        load()
        message = if (id == null) "Your log shows nothing about hours, days, session lengths or sleep worth changing yet."
            else "What your log suggests is in the inbox, each change with its reason."
    }

    /** The weekly review of the levers, now. */
    fun reviewNow() = work {
        com.chronos.navigator.llm.client.CrewRun.review(app, "you asked for it")
        loadCrew()
        message = "The crew is reviewing the week; its package arrives in the inbox."
    }

    /** Deletes the open conversation, when you ask; the others and the inbox stay. */
    fun deleteChat() = work { chatId?.let { com.chronos.navigator.llm.client.CrewRun.deleteChat(app, it) }; loadCrew() }

    /** A new conversation: your next message starts it, with none of the others in front of the model. */
    fun newChat() = work { com.chronos.navigator.llm.client.CrewRun.newChat(app); loadCrew() }

    /** An earlier conversation opened again, to read or to go on with. */
    fun openChat(id: String) = work { com.chronos.navigator.llm.client.CrewRun.openChat(app, id); loadCrew(); page = NavPage.CHAT }

    fun autoAssignCrew() = work {
        com.chronos.navigator.llm.client.CrewRun.autoAssign(app)
        freeModels = com.chronos.navigator.llm.client.CrewRun.freeModels(app)
        loadCrew()
        message = "Each role has a model. Change any of them below."
    }

    fun refreshFreeModels() = work { freeModels = com.chronos.navigator.llm.client.CrewRun.freeModels(app, refresh = true) }

    fun setCrewPick(role: com.chronos.navigator.llm.Crew.Role, profileId: String, model: String) {
        crew.set(role, com.chronos.navigator.llm.client.CrewSettings.Pick(profileId, model))
        crewPicks = crew.picks
    }

    fun setFreeCap(n: Int) { crew.freeDailyCap = n; freeLeft = crew.freeLeft; llmRevision++ }

    /** Approves the ticked changes of a crew package. */
    fun approveCrew(proposalId: String, groups: Set<Int>) = work {
        message = com.chronos.navigator.bridge.NavEngine.approveCrew(app, proposalId, groups)
        load()
    }

    /** How much a goal's deadline matters (it sets the floor its odds are held to). */
    fun setPriority(nodeId: String, priority: Int) = work {
        store.answer(com.chronos.navigator.model.Overrides.nodeKey(nodeId, "priority"), Dist.POINT,
            "Your priority for this goal", mapOf("value" to priority.toDouble()))
        load()
    }

    /** Re-read everything; called when the tab opens, since Setup may have added activities. */
    fun refresh() = work {
        load(); sendQueued(); load()
        // Nothing forecast for today yet (the nightly job has not run, or answers were just completed): do it now.
        val asOf = forecast?.run()?.asOf
        if (graph != null && issues.none { it.severity() != Severity.WARNING } &&
            asOf != ChronosRepository.logicalToday().toString()) compute(allocate = week.isEmpty())
    }

    /** Runs estimate and forecast (and the week's allocation when asked) on this phone. */
    private fun compute(allocate: Boolean) {
        computing = true
        try {
            val out = com.chronos.navigator.bridge.NavEngine.run(app, allocate)
            if (!out.ran) message = out.why
        } finally {
            computing = false
        }
        load()
    }

    fun forecastNow() = work { compute(allocate = false) }

    /** A new proposal for the coming week (replacing one not yet accepted). */
    fun planWeek() = work { compute(allocate = true); message = "The coming week is planned. Check it and accept." }

    /** Accepts the week, with the hours changed in the form, and sends it to Chronos. */
    fun acceptWeek(weekStart: String, edits: Map<String, Double>) = work {
        changes = com.chronos.navigator.bridge.NavEngine.applyWeek(app, weekStart, edits)
        message = "Applied: Chronos's weekly floors follow this week, and the plan was rebuilt."
        load()
    }

    fun stopFollowing() = work {
        changes = com.chronos.navigator.bridge.NavEngine.stopFollowing(app)
        message = "Your own weekly floors and ceilings are back."
        load()
    }

    /** Links a node the graph names no activity for straight to a Chronos activity. */
    fun linkNode(nodeId: String, activityName: String) = work {
        store.link(nodeId + "|" + com.chronos.navigator.bridge.Bridge.ANY, activityName)
        load()
    }

    private fun load() {
        val cfg = repo.loadConfig()
        activities = cfg.activities.filter { it.kind == ActivityKind.GOAL && !it.name.isNullOrBlank() }
            .map { it.name.trim() }
        graph = store.load()
        info = store.info()
        params = store.params()
        issues = graph?.let { store.issues(it, ChronosRepository.logicalToday(), activities) } ?: emptyList()
        forecast = store.latestForecast()
        weekOld = store.weekOldForecast()
        week = store.latestAllocation()
        following = store.following()
        nodeActivities = graph?.let { com.chronos.navigator.bridge.Bridge.nodeActivities(it, cfg, store.links()) } ?: emptyMap()
        // One decision per change: what a newer proposal repeats or covers leaves the inbox (29 Oct 2026).
        inbox.collapseRepeats()
        proposals = inbox.proposals()
        syncDiffs = runCatching { com.chronos.navigator.bridge.Sync.diffs(app) }
            .onFailure { android.util.Log.w("Navigator", "sync diffs failed", it) }.getOrElse { emptyList() }
        nodeNow = graph?.let { nowOf(it, cfg) } ?: emptyMap()
        questions = com.chronos.navigator.llm.client.InfoCache.saved(app)
        triggers = store.triggers().values.toList()
        calls = inbox.calls()
        spentThisMonth = inbox.spentSince(monthStart())
        readProfiles()
        loadCrew()
        loading = false
    }

    // -------------------------------------------------------------------- LLM

    private fun monthStart(): Long = LocalDate.now().withDayOfMonth(1)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun dayStart(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun readProfiles() {
        profiles = llm.profiles
        keyed = profiles.filter { KeyVault.has(app, it.id) }.map { it.id }.toSet()
        keyTails = profiles.associate { p -> p.id to KeyVault.keys(app, p.id).map { "…" + it.takeLast(4) } }
        activeProfile = llm.active
    }

    /** Saves a provider; a blank key keeps the one already stored. */
    fun saveProfile(p: Profile, key: String?) = work {
        llm.put(p)
        if (!key.isNullOrBlank()) KeyVault.save(app, p.id, key)
        message = "Saved." + if (!key.isNullOrBlank()) " The key is encrypted on this phone." else ""
        readProfiles()
    }

    fun removeProfile(id: String) = work { llm.remove(id); message = "Removed."; readProfiles() }

    /** How many keys a provider has, and the last four characters of each, to tell them apart. */
    var keyTails by mutableStateOf<Map<String, List<String>>>(emptyMap()); private set

    /** Another key for a provider (27 Oct 2026): requests go out on whichever key is least busy. */
    fun addKey(profileId: String, key: String) = work {
        if (key.isBlank()) return@work
        KeyVault.saveKeys(app, profileId, KeyVault.keys(app, profileId) + key.trim())
        message = "Key added. Requests now share " + KeyVault.keys(app, profileId).size + " keys."
        readProfiles()
    }

    fun removeKey(profileId: String, index: Int) = work {
        KeyVault.saveKeys(app, profileId, KeyVault.keys(app, profileId).filterIndexed { i, _ -> i != index })
        message = "Key removed."
        readProfiles()
    }

    fun useProfile(id: String) { llm.activeId = id; readProfiles() }

    /** The models a provider offers; the key typed in the form is used if there is one, else the saved one. */
    fun fetchModels(p: Profile, typedKey: String?) = work {
        fetchedModels = emptyList()
        val key = typedKey?.takeIf { it.isNotBlank() } ?: KeyVault.load(app, p.id)
        fetchedModels = LlmClient.models(p, key)
        fetchedFor = p.id
        if (fetchedModels.isEmpty()) message = "The provider listed no models."
    }
    fun setMonthlyCap(usd: Double) { llm.monthlyCapUsd = usd; llmRevision++ }
    fun setDailyCalls(n: Int) { llm.dailyCalls = n; llmRevision++ }

    /**
     * Asks the LLM to propose an answer to a missing estimate. Nothing changes
     * until the proposal is approved. Without a network the question is queued
     * and sent the next time the tab opens online.
     */
    fun askLlm(issue: Issue) = work {
        message = com.chronos.navigator.llm.client.NavAsk.ask(app, com.chronos.navigator.llm.client.NavAsk.estimateTask(issue))
        load()
    }

    /** Asks for a breakdown of a coarse node now, without waiting for its expand_at. */
    fun askExpand(nodeId: String) = work {
        message = com.chronos.navigator.llm.client.NavAsk.ask(app, com.chronos.navigator.llm.client.NavAsk.expandTask(nodeId))
        load()
    }

    /** Asks for ways to recover the deadlines now at risk, as prob_drop would. */
    fun askReplan(nodes: List<String>) = work {
        val f = forecast
        val detail = nodes.joinToString("; ") { id ->
            "P($id by its deadline) is now " + (f?.nodes()?.get(id)?.pByDeadline?.let { "${Math.round(it * 100)}%" } ?: "unknown")
        }
        message = com.chronos.navigator.llm.client.NavAsk.ask(app,
            com.chronos.navigator.llm.client.NavAsk.replanTask("prob_drop", nodes, detail))
        load()
    }

    /** How each learned value got where it is (see navigator History); null until first asked for. */
    var history by mutableStateOf<List<com.chronos.navigator.estimate.History.Series>?>(null); private set

    /** Replays the estimate day by day since the first log. A few seconds at most. */
    fun loadHistory() {
        viewModelScope.launch {
            history = withContext(Dispatchers.IO) {
                runCatching {
                    val g = store.load() ?: return@runCatching emptyList()
                    val inputs = com.chronos.navigator.bridge.NavEngine.inputs(app, g, repo.loadConfig())
                    com.chronos.navigator.estimate.History.build(g, inputs, ChronosRepository.logicalToday())
                }.getOrElse { emptyList() }
            }
        }
    }

    /** Which page of the Navigator is open (the home unless you went into one). */
    var page by mutableStateOf(NavPage.HOME)

    /** The proposal open on its own page, or null for the tab itself. */
    var openProposal by mutableStateOf<String?>(null)
    /** Your messages about proposals not yet answered: on their way, or failed with why (27 Oct 2026). */
    var outbox by mutableStateOf<List<com.chronos.navigator.llm.client.Outbox.Note>>(emptyList()); private set

    /**
     * Says something to the model about a proposal. It is kept first and sent in the background, so
     * leaving the page cannot lose it; its answer (and any revision) arrives on the page.
     */
    fun discuss(proposalId: String, message: String) {
        if (message.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { com.chronos.navigator.llm.client.Outbox.post(app, proposalId, message) }
    }

    fun resend(noteId: String) = viewModelScope.launch(Dispatchers.IO) { com.chronos.navigator.llm.client.Outbox.retry(app, noteId) }

    fun dropNote(noteId: String) = viewModelScope.launch(Dispatchers.IO) { com.chronos.navigator.llm.client.Outbox.drop(app, noteId) }

    /** The outbox read again; an answered message says what happened once, and the page follows a revision. */
    private suspend fun readOutbox() {
        val notes = withContext(Dispatchers.IO) { com.chronos.navigator.llm.client.Outbox.all(app) }
        val done = notes.filter { it.status == "DONE" }
        outbox = notes - done.toSet()
        if (done.isEmpty()) return
        withContext(Dispatchers.IO) { done.forEach { com.chronos.navigator.llm.client.Outbox.drop(app, it.id) }; load() }
        done.forEach { n ->
            if (openProposal == n.proposalId)
                proposals.firstOrNull { it.parentId() == n.proposalId && it.status() == "PENDING" }?.let { openProposal = it.id() }
        }
        done.last().said?.let { message(it) }
    }

    private fun message(text: String) { this.message = text }

    private fun tr0(s: String) = s

    /** Asks the model to estimate a value the forecast is sensitive to; the answer is a proposal. */
    fun refine(key: String, why: String) = work {
        message = com.chronos.navigator.llm.client.NavAsk.ask(app, com.chronos.navigator.llm.client.NavAsk.refineTask(key, why))
        load()
    }

    /** All of them: queued, and sent as far as today's calls and the budget allow. */
    fun refineAll(items: List<Pair<String, String>>) = work {
        items.forEach { (key, why) ->
            com.chronos.navigator.llm.client.NavAsk.enqueue(app, com.chronos.navigator.llm.client.NavAsk.refineTask(key, why))
        }
        message = com.chronos.navigator.llm.client.NavAsk.sendQueued(app) ?: "Queued: they go out as calls allow."
        load()
    }

    /** Evaluates the triggers now (they also run every night) and sends what fired. */
    fun checkTriggers() = work {
        val fired = com.chronos.navigator.bridge.NavEngine.triggers(app)
        message = if (fired.isEmpty()) "No trigger fired." else
            "Fired: " + fired.joinToString { it.kind() } + ". Their answers arrive in the proposals inbox."
        load()
    }

    fun setAutoTriggers(on: Boolean) { llm.autoTriggers = on; llmRevision++ }

    /** Ranks the inputs whose true value would move the forecast most. Takes up to a minute on the phone. */
    fun rankQuestions() = work {
        computing = true
        try { com.chronos.navigator.llm.client.InfoCache.compute(app) } finally { computing = false }
        load()
    }

    /** Questions queued while offline, rebuilt from the graph as it is now and sent. */
    private fun sendQueued() {
        com.chronos.navigator.llm.client.NavAsk.sendQueued(app)?.let { message = it }
    }

    /** A re-check is running: the inbox's button shows it and waits. */
    var rechecking by mutableStateOf(false); private set

    /**
     * The inbox held against the situation as it is now (your request of 20 Oct 2026): the app's
     * rules take out what no longer applies, effects are measured again, and with [withModel]
     * a few older ones are put back to the model, which keeps, revises or drops each.
     */
    fun recheck(withModel: Boolean) = work {
        rechecking = true
        try {
            val r = com.chronos.navigator.llm.client.Recheck.run(app, withModel)
            message = r.line(com.chronos.navigator.llm.client.Recheck.persian(app)) +
                (if (r.notes.isEmpty()) "" else " " + r.notes.first())
        } finally { rechecking = false }
        load()
        loadCrew()
    }

    /** An outdated proposal back in the inbox. */
    fun restoreProposal(id: String) = work { inbox.restore(id); load() }

    fun decide(proposalId: String, approve: Boolean, copyFrom: String? = null) = work {
        message = if (approve) com.chronos.navigator.bridge.NavEngine.approve(app, proposalId, copyFrom)
        else { inbox.decide(proposalId, false, store); "Rejected." }
        load()
    }

    /** Writes one side's value into the other, as you chose. */
    fun applySync(d: com.chronos.navigator.bridge.Sync.Diff, toChronos: Boolean) = work {
        message = com.chronos.navigator.bridge.Sync.apply(app, d, toChronos)
        if (!toChronos) { computing = true; try { com.chronos.navigator.bridge.NavEngine.run(app, false) } finally { computing = false } }
        load()
    }

    fun keepBoth(d: com.chronos.navigator.bridge.Sync.Diff) = work {
        com.chronos.navigator.bridge.Sync.keepBoth(app, d)
        load()
    }

    /** One node's current numbers, for its card. Null fields: nothing known yet. */
    data class NodeNow(
        val p: Double?, val pWeekAgo: Double?, val likely: LocalDate?, val p10: LocalDate?, val p90: LocalDate?,
        val chronosDeadline: LocalDate?, val unitsDone: Double?, val unitsTotal: Double?, val unit: String?,
        val hoursPerUnit: Double?, val chronosHoursPerUnit: Double?, val bias: Double?, val trendPerWeek: Double?,
        val adherence: Double?, val adherenceDays: Double?, val hoursLogged: Double?, val level: Double?,
        val weekHours: Double?, val critical: Double?
    )

    private fun nowOf(g: Graph, cfg: com.chronos.model.Config): Map<String, NodeNow> {
        val acts = nodeActivities
        val post = params.filter { it.key.startsWith("post.") }.associateBy { it.key }
        val weekH = week.filter { it.nodeId != com.chronos.navigator.store.NavStore.WEEK_META }.associate { it.nodeId to it.plannedH }
        return g.nodes.values.associate { n ->
            val f = forecast?.nodes()?.get(n.id)
            val act = acts[n.id]?.firstOrNull()
            val goal = act?.let { a -> cfg.activities.firstOrNull { it.name != null && com.chronos.navigator.model.Requirements.norm(it.name) == a } }
                ?.goalId?.let { cfg.goal(it) }
            val bias = act?.let { post["post.bias.$it"] }
            val closed = goal?.mode == com.chronos.model.Enums.GoalMode.CLOSED
            val hpu = goal?.hoursPerUnit
            val adh = act?.let { post["post.adherence.$it"] }
            val hours = post["post.hours.${n.id}"]
            n.id to NodeNow(
                p = f?.pByDeadline, pWeekAgo = weekOld?.nodes()?.get(n.id)?.pByDeadline,
                likely = f?.p50?.let { LocalDate.parse(it) }, p10 = f?.p10?.let { LocalDate.parse(it) },
                p90 = f?.p90?.let { LocalDate.parse(it) },
                chronosDeadline = if (closed) goal?.deadline else null,
                unitsDone = if (closed) goal?.doneUnits else n.unitsDone,
                unitsTotal = if (closed) goal?.totalUnits else n.unitsTotal,
                unit = goal?.unit ?: n.unitName,
                hoursPerUnit = if (closed && hpu != null && bias != null) hpu * (bias.get("median") ?: 1.0) else null,
                chronosHoursPerUnit = if (closed) hpu else null,
                bias = if (!closed) bias?.get("median") else null,
                trendPerWeek = bias?.get("slope")?.let { Math.exp(it * 7) - 1 },
                adherence = adh?.get("mean"), adherenceDays = adh?.get("days"),
                hoursLogged = hours?.get("hours"), level = hours?.get("level"),
                weekHours = weekH[n.id], critical = f?.critical
            )
        }
    }

    fun markDone(nodeId: String, done: Boolean) = work {
        com.chronos.navigator.bridge.NavEngine.markDone(app, nodeId, done)
        message = if (done) "Marked done." else "Open again."
        load()
    }

    /**
     * A better answer to something the file already states (from the questions
     * panel): saved as yours, over the file's value, recording what it replaces.
     */
    fun answerOver(key: String, dist: Dist, values: Map<String, Double>) = work {
        val g = graph ?: return@work
        val parts = key.split(".")
        val was: Map<String, Double> = when {
            key.startsWith("regime.") -> g.regime(parts[1])?.let { com.chronos.navigator.model.Overrides.was(it) } ?: emptyMap()
            else -> g.node(key.removePrefix("node.").substringBeforeLast('.'))?.let { n ->
                val old = store.params().firstOrNull { it.key == key }?.values?.filterKeys { it.startsWith("was") }
                if (!old.isNullOrEmpty()) old else com.chronos.navigator.model.Overrides.was(n, key.substringAfterLast('.'))
            } ?: emptyMap()
        }
        store.answer(key, dist, "Your answer, over what the file stated", values + was + ("override" to 1.0))
        load()
    }

    fun importFrom(uri: Uri) = work {
        val text = app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("The file could not be read.")
        val result = store.importGraph(text, displayName(uri))
        refused = if (result.ok()) emptyList() else result.invalid()
        message = if (result.ok()) "Goal graph imported." else "The file was not imported."
        load()
    }

    /**
     * A first goal graph made from the goals set up in Chronos (31 Oct 2026): what a new person
     * starts from instead of a graph file. It is checked like any import; a graph already there stays.
     */
    fun startGraphFromGoals() = work {
        if (store.load() != null) { message = "A goal graph is already here."; return@work }
        val cfg = repo.loadConfig()
        val work = cfg.goals.mapNotNull { t ->
            val a = com.chronos.engine.Umbrellas.activityOf(cfg, t) ?: return@mapNotNull null
            if (a.archived || cfg.over(a, ChronosRepository.logicalToday())) return@mapNotNull null
            com.chronos.navigator.model.StarterGraph.Work(t.groupName ?: t.name, t.name ?: a.name, a.name?.trim(),
                t.mode == com.chronos.model.Enums.GoalMode.CLOSED, t.deadline, t.totalUnits, t.doneUnits, t.unit,
                t.hoursPerUnit, t.weeklyFloorH, t.maxPerDayH, t.hard)
        }
        val today = ChronosRepository.logicalToday()
        val json = com.chronos.navigator.model.StarterGraph.json(work, today, java.time.ZoneId.systemDefault().id)
        if (json == null) { message = "Set up at least one goal with its work in Setup first."; return@work }
        val result = store.importGraph(json, com.chronos.navigator.model.StarterGraph.fileName(today))
        refused = if (result.ok()) emptyList() else result.invalid()
        message = if (result.ok()) "Your goal graph is made from your goals. It grows from here." else "The goal graph could not be made."
        load()
    }

    private fun displayName(uri: Uri): String? =
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun link(linkKey: String, activityName: String) = work { store.link(linkKey, activityName); load() }

    /** Saves typed values for a parameter or an in-app answer; they become the user's. */
    fun answer(key: String, dist: Dist, values: Map<String, Double>) = answerAll(listOf(Triple(key, dist, values)))

    /** Several answers that belong together (a regime's dates and its mix), saved as one step. */
    fun answerAll(items: List<Triple<String, Dist, Map<String, Double>>>) = work {
        items.forEach { (key, dist, values) -> store.answer(key, dist, null, values) }
        load()
    }

    private fun work(block: () -> Unit) {
        viewModelScope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                message = e.message ?: "Something went wrong."
            } finally {
                busy = false
                loading = false
            }
        }
    }
}
