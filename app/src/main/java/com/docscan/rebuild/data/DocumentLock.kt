package com.docscan.rebuild.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Locks documents inside the app behind a code. The code is kept only as a salted PBKDF2 hash. This hides a
 * document in the app (no preview, no opening, sharing or exporting without the code); the files on the phone are
 * not encrypted. A document opened with the code stays open until the app is left. A locked folder locks everything
 * in it, and in the folders inside it; opening the folder with the code opens all of that.
 */
class DocumentLock(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lock", Context.MODE_PRIVATE)
    private val folders = DocumentFolders(context)

    val hasCode: Boolean get() = prefs.contains("hash")
    /** Locked itself or by a folder it is in. */
    fun locked(id: String) = id in lockedIds() || folders.chainOf(id).any { it in lockedFolders() }
    fun lockedIds(): Set<String> = prefs.getStringSet("locked", emptySet()).orEmpty()
    fun lockedFolders(): Set<String> = prefs.getStringSet("lockedFolders", emptySet()).orEmpty()
    /** Of [ids], the documents that are locked, themselves or by their folder. */
    fun lockedDocuments(ids: Collection<String>): Set<String> { val own = lockedIds(); val shut = lockedFolders(); val place = folders.placement()
        return ids.filter { id -> id in own || folders.chain(place[id]).any { it in shut } }.toSet() }

    fun setCode(code: String) {
        require(valid(code))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP)).putString("hash", Base64.encodeToString(hash(code, salt), Base64.NO_WRAP)).apply()
        changes.value++
    }

    fun check(code: String): Boolean {
        val salt = prefs.getString("salt", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val stored = prefs.getString("hash", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        return MessageDigest.isEqual(stored, hash(code, salt))
    }

    fun setLocked(id: String, locked: Boolean) {
        val ids = lockedIds().toMutableSet().apply { if (locked) add(id) else remove(id) }
        prefs.edit().putStringSet("locked", ids).apply()
        if (!locked) open.remove(id)
        changes.value++
    }

    /** Removes the code; only after it was entered. Every document is unlocked. */
    fun removeCode() { prefs.edit().clear().apply(); open.clear(); openFolders.clear(); changes.value++ }

    fun isOpen(id: String) = id in open || (id !in lockedIds() && folderOpen(folders.folderOf(id)))
    fun opened(id: String) { open.add(id); changes.value++ }

    fun setFolderLocked(folder: String, locked: Boolean) {
        val ids = lockedFolders().toMutableSet().apply { if (locked) add(folder) else remove(folder) }
        prefs.edit().putStringSet("lockedFolders", ids).apply()
        if (!locked) openFolders.remove(folder)
        changes.value++
    }
    /** Whether [folder] can be looked into: no locked folder on its way from the top level is still shut. */
    fun folderOpen(folder: String?) = folders.chain(folder).none { it in lockedFolders() && it !in openFolders }
    /** Opens [folder] and the locked folders above it, with the code entered. */
    fun openedFolder(folder: String) { openFolders.addAll(folders.chain(folder)); changes.value++ }

    companion object {
        /** Documents opened with the code in this session of the app. */
        private val open = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        /** Folders opened with the code in this session of the app. */
        private val openFolders = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        /** Bumped on every change, so screens showing locks refresh. */
        val changes = MutableStateFlow(0)
        fun closeAll() { open.clear(); openFolders.clear(); changes.value++ }
        fun valid(code: String) = code.length in 4..8 && code.all { it in '0'..'9' }
        private fun hash(code: String, salt: ByteArray): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(code.toCharArray(), salt, 60_000, 256)).encoded
    }
}
