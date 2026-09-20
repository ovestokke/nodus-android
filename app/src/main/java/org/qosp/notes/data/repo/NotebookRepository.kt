package org.qosp.notes.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.qosp.notes.data.dao.NotebookDao
import org.qosp.notes.data.model.Notebook

class NotebookRepository(
    private val notebookDao: NotebookDao,
    private val noteRepository: NoteRepository,
    private val nodus: org.qosp.notes.data.sync.nodus.integration.NodusAppBridge? = null,
) {

    private suspend fun <T> capture(block: suspend () -> T): T = nodus?.mutate(block=block) ?: block()

    suspend fun insert(notebook: Notebook): Long {
        return capture { notebookDao.insert(notebook) }
    }

    suspend fun delete(vararg notebooks: Notebook) {
        val affectedNotes = notebooks
            .map { noteRepository.getByNotebook(it.id).first() }
            .flatten()
            .filterNot { it.isLocalOnly }

        capture { notebookDao.delete(*notebooks) }
    }

    suspend fun update(vararg notebooks: Notebook, shouldSync: Boolean = true) {
        capture { notebookDao.update(*notebooks) }
    }

    fun getById(notebookId: Long): Flow<Notebook?> {
        return notebookDao.getById(notebookId)
    }

    fun getAll(): Flow<List<Notebook>> {
        return notebookDao.getAll()
    }

    fun getByName(name: String): Flow<Notebook?> {
        return notebookDao.getByName(name)
    }
}
