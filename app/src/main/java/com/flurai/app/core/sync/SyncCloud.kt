package com.flurai.app.core.sync

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/** One document of a user's cloud copy. Numbers are Long/Double, like Firestore returns them. */
class CloudDoc(val id: String, val data: Map<String, Any?>, val updatedAt: Long)

/** A document to write; the cloud stamps its own write time into `updatedAt`. */
class CloudWrite(val collection: String, val id: String, val data: Map<String, Any?>)

/** Why the cloud said no, in terms the sync engine can act on without knowing Firestore. */
class CloudException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason { NotAllowed, Offline, NotFound, Other }
}

/**
 * Where a user's progress is backed up (users/{uid}/{collection}/{id}). This is the seam between
 * the sync engine's merge rules and the real cloud: [FirestoreCloud] in the app, an in-memory
 * copy in tests.
 *
 * Interface rules the engine relies on:
 * - every document carries `updatedAt`, set by the cloud when it is written (so two phones with
 *   different clocks still agree on order); documents from old versions may carry a plain number;
 * - reads come from the server, never a local cache (a cached answer could hide changes);
 * - failures surface as [CloudException].
 */
interface SyncCloud {
    /** Documents of [collection] written after [since] (ms); everything when [since] is 0. */
    suspend fun changedSince(uid: String, collection: String, since: Long): List<CloudDoc>

    /** One document's fields, or null when it does not exist. */
    suspend fun document(uid: String, collection: String, id: String): Map<String, Any?>?

    /** Writes (replaces) the documents. */
    suspend fun write(uid: String, docs: List<CloudWrite>)

    /** Deletes every document of the given collections. */
    suspend fun deleteAll(uid: String, collections: List<String>)

    /** Gets a fresh sign-in token (the old one may carry a stale "email not verified" claim). */
    suspend fun refreshSignIn()
}

/** [SyncCloud] on Cloud Firestore. */
@Singleton
class FirestoreCloud @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
) : SyncCloud {
    private fun collection(uid: String, name: String) = firestore.collection("users").document(uid).collection(name)

    override suspend fun changedSince(uid: String, collection: String, since: Long): List<CloudDoc> = cloud {
        val c = collection(uid, collection)
        // Old backups stored updatedAt as a plain number, which a timestamp filter never matches:
        // a first sync (since = 0) therefore reads everything.
        val query = if (since == 0L) c.get(Source.SERVER) else c.whereGreaterThan("updatedAt", Timestamp(Date(since))).get(Source.SERVER)
        query.await().documents.map { CloudDoc(it.id, it.data.orEmpty(), updatedAtMillis(it.get("updatedAt"))) }
    }

    override suspend fun document(uid: String, collection: String, id: String): Map<String, Any?>? = cloud {
        collection(uid, collection).document(id).get(Source.SERVER).await().data
    }

    override suspend fun write(uid: String, docs: List<CloudWrite>) = cloud {
        docs.chunked(BATCH).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { batch.set(collection(uid, it.collection).document(it.id), it.data + ("updatedAt" to FieldValue.serverTimestamp())) }
            batch.commit().await()
        }
    }

    override suspend fun deleteAll(uid: String, collections: List<String>) = cloud {
        for (name in collections) {
            collection(uid, name).get(Source.SERVER).await().documents.chunked(BATCH).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
        }
    }

    override suspend fun refreshSignIn() = cloud {
        auth.currentUser?.reload()?.await()
        auth.currentUser?.getIdToken(true)?.await()
        Unit
    }

    private inline fun <T> cloud(block: () -> T): T = try {
        block()
    } catch (e: FirebaseFirestoreException) {
        throw CloudException(
            when (e.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED -> CloudException.Reason.NotAllowed
                FirebaseFirestoreException.Code.UNAVAILABLE, FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> CloudException.Reason.Offline
                FirebaseFirestoreException.Code.NOT_FOUND -> CloudException.Reason.NotFound
                else -> CloudException.Reason.Other
            },
            e,
        )
    }

    private companion object {
        /** Firestore allows 500 writes per batch. */
        const val BATCH = 400
    }
}

/** A document's write time in ms. Firestore's typed getters throw on the other type, so the raw value is checked here. */
internal fun updatedAtMillis(value: Any?): Long = when (value) {
    is Timestamp -> value.toDate().time
    is Number -> value.toLong()
    else -> 0L
}
