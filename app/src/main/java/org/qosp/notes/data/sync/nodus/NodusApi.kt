package org.qosp.notes.data.sync.nodus

import androidx.annotation.Keep
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/** Retrofit creates this implementation at runtime; keeping it prevents R8 from replacing the proxy cast. */
@Keep
internal interface NodusApi {
    @HTTP(method = "GET", path = "api/v2/capabilities", hasBody = false)
    suspend fun getCapabilities(): Response<V2Capabilities>

    @HTTP(method = "GET", path = "api/v2/notes/{noteId}", hasBody = false)
    suspend fun getNote(@Path("noteId") noteId: String): Response<V2Note>

    @HTTP(method = "PUT", path = "api/v2/notes/{noteId}", hasBody = true)
    suspend fun createNote(@Path("noteId") noteId: String, @Body body: V2CreateNote): Response<V2Receipt>

    @HTTP(method = "PATCH", path = "api/v2/notes/{noteId}", hasBody = true)
    suspend fun editNote(@Path("noteId") noteId: String, @Body body: V2EditNote): Response<V2Receipt>

    @HTTP(method = "POST", path = "api/v2/notes/{noteId}/content", hasBody = true)
    suspend fun content(@Path("noteId") noteId: String, @Body body: V2Content): Response<V2Receipt>

    @HTTP(method = "POST", path = "api/v2/notes/{noteId}/trash", hasBody = true)
    suspend fun trash(@Path("noteId") noteId: String, @Body body: V2Trash): Response<V2Receipt>

    @HTTP(method = "POST", path = "api/v2/notes/{noteId}/restore", hasBody = true)
    suspend fun restore(@Path("noteId") noteId: String, @Body body: V2Lifecycle): Response<V2Receipt>

    @HTTP(method = "POST", path = "api/v2/notes/{noteId}/purge", hasBody = true)
    suspend fun purge(@Path("noteId") noteId: String, @Body body: V2Lifecycle): Response<V2Receipt>

    @HTTP(method = "POST", path = "api/v2/notes/{noteId}/items", hasBody = true)
    suspend fun append(@Path("noteId") noteId: String, @Body body: V2Append): Response<V2Receipt>

    @HTTP(method = "PATCH", path = "api/v2/notes/{noteId}/items/{itemId}", hasBody = true)
    suspend fun itemEdit(@Path("noteId") noteId: String, @Path("itemId") itemId: String, @Body body: V2ItemEdit): Response<V2Receipt>

    @HTTP(method = "DELETE", path = "api/v2/notes/{noteId}/items/{itemId}", hasBody = true)
    suspend fun deleteItem(@Path("noteId") noteId: String, @Path("itemId") itemId: String, @Body body: V2ChildDelete): Response<V2Receipt>

    @HTTP(method = "PATCH", path = "api/v2/notes/{noteId}/items/{itemId}/checked", hasBody = true)
    suspend fun toggle(@Path("noteId") noteId: String, @Path("itemId") itemId: String, @Body body: V2Toggle): Response<V2Receipt>

    @HTTP(method = "POST", path = "api/v2/notes/{noteId}/order", hasBody = true)
    suspend fun order(@Path("noteId") noteId: String, @Body body: V2Order): Response<V2Receipt>

    @HTTP(method = "POST", path = "api/v2/notes/{noteId}/reminders", hasBody = true)
    suspend fun reminderCreate(@Path("noteId") noteId: String, @Body body: V2ReminderCreate): Response<V2Receipt>

    @HTTP(method = "PATCH", path = "api/v2/notes/{noteId}/reminders/{reminderId}", hasBody = true)
    suspend fun reminderEdit(@Path("noteId") noteId: String, @Path("reminderId") reminderId: String, @Body body: V2ReminderEdit): Response<V2Receipt>

    @HTTP(method = "DELETE", path = "api/v2/notes/{noteId}/reminders/{reminderId}", hasBody = true)
    suspend fun deleteReminder(@Path("noteId") noteId: String, @Path("reminderId") reminderId: String, @Body body: V2ChildDelete): Response<V2Receipt>

    @HTTP(method = "POST", path = "api/v2/notes/{noteId}/attachments", hasBody = true)
    suspend fun attachmentCreate(@Path("noteId") noteId: String, @Body body: V2AttachmentCreate): Response<V2Receipt>

    @HTTP(method = "PATCH", path = "api/v2/notes/{noteId}/attachments/{attachmentId}", hasBody = true)
    suspend fun attachmentEdit(@Path("noteId") noteId: String, @Path("attachmentId") attachmentId: String, @Body body: V2AttachmentEdit): Response<V2Receipt>

    @HTTP(method = "DELETE", path = "api/v2/notes/{noteId}/attachments/{attachmentId}", hasBody = true)
    suspend fun deleteAttachment(@Path("noteId") noteId: String, @Path("attachmentId") attachmentId: String, @Body body: V2ChildDelete): Response<V2Receipt>

    @HTTP(method = "POST", path = "api/v2/notes/{noteId}/attachments/order", hasBody = true)
    suspend fun attachmentOrder(@Path("noteId") noteId: String, @Body body: V2AttachmentOrder): Response<V2Receipt>

    @HTTP(method = "GET", path = "api/v2/tags/{tagId}", hasBody = false)
    suspend fun getTag(@Path("tagId") tagId: String): Response<V2Tag>

    @HTTP(method = "PUT", path = "api/v2/tags/{tagId}", hasBody = true)
    suspend fun createTag(@Path("tagId") tagId: String, @Body body: V2OrganizationCreate): Response<V2Receipt>

    @HTTP(method = "PATCH", path = "api/v2/tags/{tagId}", hasBody = true)
    suspend fun editTag(@Path("tagId") tagId: String, @Body body: V2OrganizationEdit): Response<V2Receipt>

    @HTTP(method = "DELETE", path = "api/v2/tags/{tagId}", hasBody = true)
    suspend fun deleteTag(@Path("tagId") tagId: String, @Body body: V2OrganizationDelete): Response<V2Receipt>

    @HTTP(method = "GET", path = "api/v2/notebooks/{notebookId}", hasBody = false)
    suspend fun getNotebook(@Path("notebookId") notebookId: String): Response<V2Notebook>

    @HTTP(method = "PUT", path = "api/v2/notebooks/{notebookId}", hasBody = true)
    suspend fun createNotebook(@Path("notebookId") notebookId: String, @Body body: V2OrganizationCreate): Response<V2Receipt>

    @HTTP(method = "PATCH", path = "api/v2/notebooks/{notebookId}", hasBody = true)
    suspend fun editNotebook(@Path("notebookId") notebookId: String, @Body body: V2OrganizationEdit): Response<V2Receipt>

    @HTTP(method = "DELETE", path = "api/v2/notebooks/{notebookId}", hasBody = true)
    suspend fun deleteNotebook(@Path("notebookId") notebookId: String, @Body body: V2OrganizationDelete): Response<V2Receipt>

    @HTTP(method = "GET", path = "api/v2/blobs/{blobId}", hasBody = false)
    suspend fun getBlob(@Path("blobId") blobId: String): Response<V2Blob>

    @HTTP(method = "PUT", path = "api/v2/blobs/{blobId}", hasBody = true)
    suspend fun blobReserve(@Path("blobId") blobId: String, @Body body: V2BlobReserve): Response<V2Receipt>

    @HTTP(method = "GET", path = "api/v2/conflicts/{conflictId}", hasBody = false)
    suspend fun getConflict(@Path("conflictId") conflictId: String): Response<V2Conflict>

    @HTTP(method = "POST", path = "api/v2/conflicts/{conflictId}/apply", hasBody = true)
    suspend fun apply(@Path("conflictId") conflictId: String, @Body body: V2Apply): Response<V2Receipt>

    @HTTP(method = "POST", path = "api/v2/conflicts/{conflictId}/discard", hasBody = true)
    suspend fun discard(@Path("conflictId") conflictId: String, @Body body: V2Discard): Response<DiscardReceipt>

    @HTTP(method = "GET", path = "api/v2/changes", hasBody = false)
    suspend fun getChanges(@Query("after") after: String? = null, @Query("until") until: String? = null, @Query("limit") limit: Int? = null): Response<V2Changes>

    @HTTP(method = "GET", path = "api/v2/conflicts", hasBody = false)
    suspend fun listConflicts(@Query("after") after: String? = null, @Query("until") until: String? = null, @Query("limit") limit: Int? = null): Response<V2Conflicts>

    @Headers("Content-Type: application/octet-stream")
    @HTTP(method = "PUT", path = "api/v2/blobs/{blobId}/content", hasBody = true)
    suspend fun uploadBlob(@Path("blobId") blobId: String, @Header("X-Nodus-Device-Id") deviceId: String, @Header("X-Nodus-Request-Id") requestId: String, @Body body: RequestBody): Response<V2Receipt>

    @Streaming
    @HTTP(method = "GET", path = "api/v2/blobs/{blobId}/content", hasBody = false)
    suspend fun downloadBlob(@Path("blobId") blobId: String, @Header("Range") range: String? = null): Response<ResponseBody>

    @Streaming
    @HTTP(method = "HEAD", path = "api/v2/blobs/{blobId}/content", hasBody = false)
    suspend fun headBlob(@Path("blobId") blobId: String, @Header("Range") range: String? = null): Response<Unit>

}
