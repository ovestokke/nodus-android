package org.qosp.notes.data.sync.nodus.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.qosp.notes.data.sync.nodus.*
import org.qosp.notes.data.sync.nodus.storage.*

/** A frozen projected basis plus exact own-operation dependencies, not a remote rebase. */
@Serializable
internal data class NodusItemExpectation(
    val revision: String,
    val kind: NoteKind?,
    val items: List<Item>,
    val operationIds: List<String>,
    val completeHistory: Boolean
)

internal fun affectsItemExpectation(draft: NodusDraft, wireId: String): Boolean {
    val path = draft.path.split('/')
    if (path.getOrNull(3) == "conflicts") return path.last() == "apply"
    if (path.size == 5) return draft.method == "PUT"
    return when (path[5]) {
        "content", "trash", "restore", "purge", "order" -> true
        "items" -> path.size == 6 || path.getOrNull(6) == wireId
        else -> false
    }
}

internal suspend fun resolveItemExpectation(dao: NodusDao, connectionId: String, wireId: String, basis: NodusItemExpectation): String? {
    val acknowledged = mutableListOf<Pair<NodusOutbox,String>>()
    for (id in basis.operationIds) {
        val op = dao.outbox(connectionId, id) ?: return null
        if (op.state != NodusOutboxState.RETIRED || dao.operationConflicts(connectionId, id).isNotEmpty()) return null
        val revision = dao.latestEvidence(connectionId, id)?.resourceRevision ?: return null
        if (decimalCompare(revision, basis.revision) > 0) acknowledged += op to revision
    }
    if (!basis.completeHistory) {
        // A successful direct mutation defines this item's revision regardless of an older
        // missing history prefix. Do not infer positions/kinds for subsequent broad edits.
        val last = acknowledged.maxWithOrNull { a,b -> decimalCompare(a.second,b.second) }
        val path = last?.first?.path?.split('/')
        if (path?.getOrNull(5) == "items" && (path.getOrNull(6) == wireId ||
                (path.size == 6 && NodusJson.format.parseToJsonElement(wireString(last.first.body!!)).jsonObject["itemId"] == JsonPrimitive(wireId)))) return last.second
        return null
    }
    var kind = basis.kind
    var items = basis.items.associateBy { it.id }.toMutableMap()
    for ((op, revision) in acknowledged.sortedWith { a,b -> decimalCompare(a.second,b.second) }) {
        val path = op.path.split('/')
        if (path.getOrNull(3) != "notes") return null
        val fields = NodusJson.format.parseToJsonElement(wireString(requireNotNull(op.body))).jsonObject
        fun text(key: String, default: String = "") = fields[key]?.jsonPrimitive?.content ?: default
        fun flag(key: String, default: Boolean = false) = fields[key]?.jsonPrimitive?.boolean ?: default
        fun invalidate() { items = items.mapValues { (_,item) -> if (item.deleted) item else item.copy(revision=revision) }.toMutableMap() }
        when {
            path.size == 5 && op.method == "PUT" -> {
                if (kind != null) return null
                kind = NoteKind.valueOf(text("kind").uppercase())
                items = fields["items"]?.jsonArray?.mapIndexed { position, value ->
                    val input=value.jsonObject
                    Item(input.getValue("id").jsonPrimitive.content,input["text"]?.jsonPrimitive?.content ?: "",input["checked"]?.jsonPrimitive?.boolean ?: false,position,revision,false)
                }?.associateBy { it.id }?.toMutableMap() ?: mutableMapOf()
            }
            path.getOrNull(5) == "content" -> {
                val nextKind = NoteKind.valueOf(text("kind").uppercase())
                fields["items"]?.jsonArray?.let { values ->
                    val wanted=mutableSetOf<String>()
                    values.forEachIndexed { position, value ->
                        val input=value.jsonObject
                        val id=input.getValue("id").jsonPrimitive.content
                        val old=items[id]
                        if (old?.deleted == true) return null
                        val content=input["text"]?.jsonPrimitive?.content ?: ""
                        val checked=input["checked"]?.jsonPrimitive?.boolean ?: false
                        val changed=old == null || old.text!=content || old.checked!=checked || old.position!=position
                        items[id]=Item(id,content,checked,position,if (changed) revision else old!!.revision,false)
                        wanted += id
                    }
                    items=items.mapValues { (id,item) -> if (!item.deleted && id !in wanted) item.copy(deleted=true,revision=revision) else item }.toMutableMap()
                }
                if (nextKind != kind) invalidate()
                kind=nextKind
            }
            path.getOrNull(5) in setOf("trash","restore") -> invalidate()
            path.getOrNull(5) == "purge" -> return null
            path.getOrNull(5) == "order" -> fields["order"]?.jsonArray?.forEachIndexed { position, value ->
                val id=value.jsonPrimitive.content
                val item=items[id] ?: return null
                if (item.deleted) return null
                if (item.position!=position) items[id]=item.copy(position=position,revision=revision)
            }
            path.getOrNull(5) == "items" && path.size == 6 -> {
                val id=text("itemId")
                if (id in items) return null
                items[id]=Item(id,text("text"),flag("checked"),(items.values.maxOfOrNull { it.position } ?: -1)+1,revision,false)
            }
            path.getOrNull(5) == "items" -> {
                val id=path.getOrNull(6) ?: return null
                val old=items[id] ?: return null
                if (old.deleted) return null
                items[id]=old.copy(text=text("text",old.text),checked=flag("checked",old.checked),deleted=op.method=="DELETE",revision=revision)
            }
        }
    }
    return items[wireId]?.revision
}

/** Recovery validates the frozen expectation unchanged; it never adopts a newer item revision. */
internal suspend fun unchangedItemAfterAuthoritativeRead(dao: NodusDao, connectionId: String, mapping: NodusMapping, intentId: String, basis: NodusItemExpectation): String? {
    val snapshot=dao.snapshot(connectionId,NodusResourceType.NOTE,mapping.parentId) ?: return null
    val floor=dao.maximumAcknowledgedRevision(connectionId,NodusResourceType.NOTE,mapping.parentId) ?: return null
    if (decimalCompare(snapshot.revision,basis.revision)<=0 || decimalCompare(snapshot.revision,floor)<0) return null
    val unsettled=dao.unsettledIntents(connectionId,NodusResourceType.NOTE,mapping.parentId)
    if (unsettled.size>100 || unsettled.any { it.intentId!=intentId && affectsItemExpectation(decodeDraft(it),mapping.wireId) }) return null
    val canonical=NodusJson.decode(V2Note.serializer(),wireString(snapshot.body))
    val original=basis.items.firstOrNull { it.id==mapping.wireId } ?: return null
    if (canonical.state==NoteState.PURGED || canonical.kind!=basis.kind || canonical.items.firstOrNull { it.id==mapping.wireId }!=original) return null
    return original.revision
}
