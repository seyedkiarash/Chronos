package com.chronos.navigator.llm.client

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What you said about a proposal, kept until a model has answered it (your request of 27 Oct 2026:
 * two messages were refused and lost). Written to a file before anything is sent, then sent in the
 * background, where leaving the page or the app cannot lose it. A failure that passes — no network,
 * a busy provider, every key at its limit for the minute — is tried again by itself; one that does
 * not is kept with its reason, to send again or edit. Messages about one proposal go in order.
 */
object Outbox {

    /** @param status SENDING, FAILED, or DONE (answered; [said] is what happened, shown once and dropped) */
    data class Note(val id: String, val proposalId: String, val text: String, val at: Long,
                    var status: String = "SENDING", var error: String? = null, var tries: Int = 0,
                    var said: String? = null)

    /** Tries before a failure that passes is kept as failed: about half an hour of waiting in all. */
    private const val MOST_TRIES = 7

    private val gson = Gson()

    /** Called after every change, so open screens reload. */
    @Volatile var onChange: (() -> Unit)? = null

    private fun file(context: Context) = File(context.filesDir, "navigator-outbox.json")

    fun all(context: Context): List<Note> = file(context).takeIf { it.exists() }?.let {
        runCatching { gson.fromJson<List<Note>>(it.readText(), object : TypeToken<List<Note>>() {}.type) }.getOrNull()
    } ?: emptyList()

    @Synchronized private fun write(context: Context, notes: List<Note>) {
        file(context).writeText(gson.toJson(notes))
        onChange?.invoke()
    }

    @Synchronized private fun update(context: Context, note: Note) =
        write(context, all(context).map { if (it.id == note.id) note else it })

    /** Your message is kept, then sent when the network allows. */
    fun post(context: Context, proposalId: String, text: String): Note {
        val note = Note(java.util.UUID.randomUUID().toString(), proposalId, text.trim(), System.currentTimeMillis())
        synchronized(this) { write(context, all(context) + note) }
        enqueue(context, note)
        return note
    }

    /** A failed message sent again, as it is. */
    fun retry(context: Context, id: String) {
        val note = all(context).firstOrNull { it.id == id } ?: return
        note.status = "SENDING"; note.error = null; note.tries = 0
        update(context, note)
        enqueue(context, note)
    }

    /** Takes a message out (sent and seen, or given up, or taken back to edit). */
    @Synchronized fun drop(context: Context, id: String) = write(context, all(context).filter { it.id != id })

    private fun enqueue(context: Context, note: Note) {
        val work = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf("note" to note.id))
            .build()
        // One chain per proposal: a second message waits for the first one's answer.
        WorkManager.getInstance(context).enqueueUniqueWork("outbox-${note.proposalId}", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    /** Sends one message. Returns false when it should be tried again later. Blocking. */
    fun send(context: Context, id: String): Boolean {
        val note = all(context).firstOrNull { it.id == id } ?: return true
        if (note.status != "SENDING") return true
        note.tries++
        update(context, note)
        return try {
            note.said = NavAsk.discuss(context, note.proposalId, note.text)
            note.status = "DONE"; note.error = null
            update(context, note)
            true
        } catch (e: Exception) {
            val err = e.message ?: e.javaClass.simpleName
            note.error = err
            val again = NavAsk.passing(err) && note.tries < MOST_TRIES
            if (!again) note.status = "FAILED"
            update(context, note)
            !again
        }
    }
}

/** Sends one kept message, once the network is there; asks to be run again after a failure that passes. */
class OutboxWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val id = inputData.getString("note") ?: return Result.success()
        return if (Outbox.send(applicationContext, id)) Result.success() else Result.retry()
    }
}
