package app.jingting.localplayer.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.*
import androidx.room.withTransaction
import app.jingting.localplayer.*
import app.jingting.localplayer.core.*
import app.jingting.localplayer.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    val graph = application.graph
    val media = graph.dao.observeMedia().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val collections = graph.dao.observeCollections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val schedules = graph.dao.observeSchedules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val exports = graph.dao.observeExports().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playback = graph.playback.state
    val message = MutableStateFlow<String?>(null)
    val scanning = MutableStateFlow(false)
    val permission = MutableStateFlow(graph.media.permissions())
    val exactAllowed = MutableStateFlow(graph.schedules.canSchedule())
    val timer = graph.timers.state
    init {
        action { graph.exports.recover(); graph.playback.connect() }
    }
    fun action(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { message.value = "操作未完成，请检查文件、输入或系统权限" } } }
    fun refresh() {
        permission.value = graph.media.permissions(); exactAllowed.value = graph.schedules.canSchedule()
        if (scanning.value) return
        action { scanning.value = true; try { graph.media.refresh(); graph.playback.pruneUnavailable() } finally { scanning.value = false } }
    }
    fun resume() { refresh(); action { graph.schedules.reconcile(); graph.timers.reconcile() } }
    fun import(uris: List<Uri>, flags: Int) = action {
        var count = 0
        for (uri in uris) { try { graph.media.importLocal(uri, flags); count++ } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Present a fixed message without provider details. */ } }
        message.value = if (count == uris.size) "已导入 $count 个本地文件" else "已导入 $count 个文件；其余文件不可访问、无音轨或不是系统本地来源"
    }
    fun play(ids: List<String>, mode: PlaybackMode = playback.value.mode) = action { graph.playback.play(ids, mode) }
    fun playCollection(id: String, index: Int = 0) = action { graph.playback.playFrom(graph.dao.entries(id).map { it.mediaId }, playback.value.mode, index) }
    fun playStartingAt(ids: List<String>, id: String) = action { graph.playback.playFrom(ids, playback.value.mode, ids.indexOf(id).coerceAtLeast(0)) }
    fun renameMedia(id: String, name: String) = action {
        graph.dao.renameMedia(id, name)
        val title = graph.dao.mediaByIds(listOf(id)).firstOrNull()?.title ?: return@action
        graph.playback.updateTitle(id, title)
    }
    fun removeEntries(ids: List<String>) = action { graph.db.withTransaction { ids.forEach { graph.dao.deleteEntry(it) } } }
    fun renameMediaBatch(names: Map<String, String>) = action {
        graph.db.withTransaction { names.forEach { (id, title) -> graph.dao.renameMedia(id, title) } }
        names.forEach { (id, title) -> graph.playback.updateTitle(id, title.trim()) }
        message.value = "已更新 ${names.size} 个名称"
    }
    fun createCollectionWithMedia(name: String, ids: List<String>) = action {
        val item = CollectionEntity(name = name.trim().take(80))
        graph.db.withTransaction {
            graph.dao.putCollection(item)
            graph.dao.putEntries(ids.distinct().mapIndexed { index, id -> EntryEntity(collectionId = item.id, mediaId = id, position = index) })
        }
        message.value = "已加入集合"
    }
    fun applyEntryOrder(collectionId: String, selected: List<String>) = action {
        graph.db.withTransaction {
            val entries = graph.dao.entries(collectionId)
            val lookup = entries.associateBy { it.id }
            val order = SelectionOrder.moveFirst(entries.map { it.id }, selected)
            graph.dao.putEntries(order.mapIndexed { index, id -> lookup.getValue(id).copy(position = index) })
        }
        message.value = "已按选择顺序排列"
    }
    fun createCollection(name: String) = action { if (name.isNotBlank()) graph.dao.putCollection(CollectionEntity(name = name.trim().take(80))) }
    fun renameCollection(item: CollectionEntity, name: String) = action { if (name.isNotBlank()) graph.dao.putCollection(item.copy(name = name.trim().take(80))) }
    fun deleteCollection(id: String) = action { graph.dao.deleteCollection(id); graph.schedules.reconcile() }
    fun addToCollection(id: String, ids: List<String>) = action {
        graph.db.withTransaction { val existing = graph.dao.entries(id); val last = existing.maxOfOrNull { it.position } ?: -1; val added = ids.distinct().filter { mediaId -> existing.none { it.mediaId == mediaId } }; graph.dao.putEntries(added.mapIndexed { i, mediaId -> EntryEntity(collectionId = id, mediaId = mediaId, position = last + i + 1) }) }
        message.value = "已加入集合"
    }
    fun moveEntry(collectionId: String, entryId: String, direction: Int) = action {
        graph.db.withTransaction {
            val list = graph.dao.entries(collectionId).toMutableList()
            val old = list.indexOfFirst { it.id == entryId }; val next = old + direction
            if (old >= 0 && next in list.indices) { java.util.Collections.swap(list, old, next); graph.dao.putEntries(list.mapIndexed { i, entry -> entry.copy(position = i) }) }
        }
    }
    suspend fun reorderEntries(collectionId: String, orderedIds: List<String>) = viewModelScope.async {
        graph.db.withTransaction {
            val entries = graph.dao.entries(collectionId)
            val byId = entries.associateBy { it.id }
            val order = (orderedIds.filter { it in byId } + entries.map { it.id }).distinct()
            graph.dao.putEntries(order.mapIndexed { i, id -> byId.getValue(id).copy(position = i) })
        }
    }.await()
    fun export(ids: List<String>, title: String, concatenate: Boolean, mode: String = "ORIGINAL") = action { graph.exports.enqueue(ids, title, concatenate, mode); message.value = "已加入导出任务" }
    fun exportBatch(ids: List<String>, names: List<String>, concatenate: Boolean, mode: String) = action {
        if (concatenate) graph.exports.enqueue(ids, names.first(), true, mode)
        else ids.forEachIndexed { index, id -> graph.exports.enqueue(listOf(id), names[index], false, mode) }
        message.value = if (concatenate) "已加入拼接任务" else "已加入 ${ids.size} 个导出任务"
    }
}
