package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.qosp.notes.data.dao.NoteTagDao
import org.qosp.notes.data.dao.TagDao
import org.qosp.notes.data.model.NoteTagJoin
import org.qosp.notes.data.model.Tag
import org.qosp.notes.di.SyncScope

class TagRepository(
    private val tagDao: TagDao,
    private val noteTagDao: NoteTagDao,
    private val noteRepository: NoteRepository,
    private val syncScope: SyncScope,
    private val nodus: org.qosp.notes.data.sync.nodus.integration.NodusAppBridge? = null,
) {

    private suspend fun <T> capture(block: suspend () -> T): T = nodus?.mutate(block=block) ?: block()

    fun getAll(): Flow<List<Tag>> {
        return tagDao.getAll()
    }

    fun getById(tagId: Long): Flow<Tag?> {
        return tagDao.getById(tagId)
    }

    fun getByNoteId(noteId: Long): Flow<List<Tag>> {
        return noteTagDao.getByNoteId(noteId)
    }

    fun getByName(name: String): Flow<Tag?> {
        return tagDao.getByName(name)
    }

    suspend fun insert(tag: Tag): Long {
        return capture { tagDao.insert(tag) }
    }

    suspend fun delete(vararg tags: Tag, shouldSync: Boolean = true) {
        val affectedNotes = tags
            .map { noteTagDao.getNotesByTagId(it.id).first() }
            .flatten()
            .filterNot { it.isLocalOnly }

        capture { tagDao.delete(*tags) }

        if (shouldSync) {
            syncScope.launch {
                affectedNotes.forEach { noteRepository.updateNotes(it) }
            }
        }
    }

    suspend fun update(vararg tags: Tag) {
        capture { tagDao.update(*tags) }
    }

    suspend fun addTagToNote(tagId: Long, noteId: Long, shouldSync: Boolean = true) {
        capture { noteTagDao.insert(NoteTagJoin(tagId, noteId)) }

        if (shouldSync) {
            syncScope.launch {
                val note = noteRepository.getById(noteId).first()?.takeUnless { it.isLocalOnly } ?: return@launch
                noteRepository.updateNotes(note)
            }
        }
    }

    suspend fun deleteTagFromNote(tagId: Long, noteId: Long, shouldSync: Boolean = true) {
        capture { noteTagDao.delete(NoteTagJoin(tagId, noteId)) }

        if (shouldSync) {
            syncScope.launch {
                val note = noteRepository.getById(noteId).first()?.takeUnless { it.isLocalOnly } ?: return@launch
                noteRepository.updateNotes(note)
            }
        }
    }

    suspend fun copyTags(fromNoteId: Long, toNoteId: Long) {
        capture { noteTagDao.copyTags(fromNoteId, toNoteId) }
    }
}
