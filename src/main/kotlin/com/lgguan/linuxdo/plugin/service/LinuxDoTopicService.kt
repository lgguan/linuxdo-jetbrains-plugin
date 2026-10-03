package com.lgguan.linuxdo.plugin.service

import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.common.Constants
import com.lgguan.linuxdo.plugin.model.Category
import com.lgguan.linuxdo.plugin.model.TagItem
import com.lgguan.linuxdo.plugin.model.Topic
import com.lgguan.linuxdo.plugin.model.TopicDetailResponse
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@State(
    name = "com.lgguan.linuxdo.plugin.service.LinuxDoTopicService",
    storages = [Storage("LinuxDoCategories.xml")]
)
@Service(Service.Level.APP)
class LinuxDoTopicService : PersistentStateComponent<LinuxDoTopicService.State> {

    class State {
        var cachedCategoriesJson: String? = null
        var cachedTags: MutableList<String> = mutableListOf()
    }

    private var myState = State()

    val categories = CopyOnWriteArrayList<Category>()
    val popularTags = CopyOnWriteArrayList<String>()
    private val categoryMap = ConcurrentHashMap<Int, Category>()

    private val categoryListeners = CopyOnWriteArrayList<(List<Category>) -> Unit>()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        categories.clear()
        categoryMap.clear()
        popularTags.clear()
        state.cachedCategoriesJson = null
        state.cachedTags.clear()
    }

    companion object {
        val DEFAULT_SYSTEM_TAGS = listOf(
            "纯水", "快问快答", "软件开发", "人工智能", "求资源", "配置优化",
            "网络安全", "服务器", "VPS", "抽奖", "病友", "数据库", "订阅节点",
            "动漫", "音乐", "游戏", "二次元", "影视", "摄影", "算法", "职场",
            "嵌入式", "拼车", "赏金任务", "健身", "旅行", "美食", "金融经济",
            "软件测试", "软件调试", "配置优化", "AI", "硬件开发", "硬件测试", "硬件调试",
            "计算机网络", "树洞", "转载", "经验分享", "生活", "技术分享", "求助"
        ).distinct()

        fun getInstance(): LinuxDoTopicService {
            return try {
                ApplicationManager.getApplication()?.getService(LinuxDoTopicService::class.java)
            } catch (_: Throwable) {
                null
            } ?: LinuxDoTopicServiceHolder.INSTANCE
        }
    }

    @Volatile private var categoriesVersion = -1L
    val categoriesAreCurrent: Boolean get() = categoriesVersion == com.lgguan.linuxdo.plugin.net.SessionEpoch.current

    fun addCategoryListener(owner: com.intellij.openapi.Disposable, listener: (List<Category>) -> Unit) {
        addCategoryListener(listener)
        com.intellij.openapi.util.Disposer.register(owner, com.intellij.openapi.Disposable { categoryListeners.remove(listener) })
    }

    fun addCategoryListener(listener: (List<Category>) -> Unit) {
        categoryListeners.add(listener)
    }

    fun removeCategoryListener(listener: (List<Category>) -> Unit) {
        categoryListeners.remove(listener)
    }

    private fun notifyCategoryListeners(list: List<Category>) {
        val version = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        ApplicationManager.getApplication().invokeLater({
            if (version != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
            for (listener in categoryListeners) {
                try {
                    listener(list)
                } catch (t: Throwable) {
                    com.lgguan.linuxdo.plugin.common.LinuxDoLog.warn("Category listener error: ${t.message}")
                }
            }
        }, com.intellij.openapi.application.ModalityState.any())
    }

    fun flattenCategories(cats: List<Category>): List<Category> {
        val result = mutableListOf<Category>()
        fun recurse(list: List<Category>) {
            for (c in list) {
                result.add(c)
                val subs = c.subcategoryList ?: c.subcategories
                subs?.let { recurse(it) }
            }
        }
        recurse(cats)
        return result.distinctBy { it.id }
    }

    private fun indexCategories(cats: List<Category>) {
        for (c in cats) {
            categoryMap[c.id] = c
            val subs = c.subcategoryList ?: c.subcategories
            subs?.let { indexCategories(it) }
        }
    }

    fun loadCategories(onComplete: ((List<Category>) -> Unit)? = null) {
        val version = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try { DiscourseApiClient.getCategories() }
                catch (error: Exception) { Result.failure(error) }
            ApplicationManager.getApplication().invokeLater({
                com.lgguan.linuxdo.plugin.net.SessionEpoch.ifCurrent(version) {
                    categories.clear()
                    categoryMap.clear()
                    val flattened = result.getOrNull()?.let(::flattenCategories).orEmpty()
                    categoriesVersion = if (result.isSuccess) version else -1L
                    categories.addAll(flattened)
                    indexCategories(flattened)
                    notifyCategoryListeners(flattened)
                    onComplete?.invoke(flattened)
                }
            }, com.intellij.openapi.application.ModalityState.any())
        }
    }

    data class HierarchicalCategory(
        val category: Category,
        val depth: Int,
        val parent: Category?
    )

    fun getHierarchicalCategories(): List<HierarchicalCategory> {
        val result = mutableListOf<HierarchicalCategory>()
        val allCats = categories.toList()

        val map = ConcurrentHashMap<Int, Category>()
        fun collect(cats: List<Category>) {
            for (c in cats) {
                map[c.id] = c
                val subs = c.subcategoryList ?: c.subcategories
                subs?.let { collect(it) }
            }
        }
        collect(allCats)

        val topLevel = allCats.filter { it.parentCategoryId == null || it.parentCategoryId == 0 }

        for (parent in topLevel) {
            result.add(HierarchicalCategory(parent, 0, null))
            val directChildren = parent.subcategoryList ?: parent.subcategories ?: emptyList()
            val otherChildren = map.values.filter { it.parentCategoryId == parent.id && directChildren.none { dc -> dc.id == it.id } }
            val allChildren = (directChildren + otherChildren).distinctBy { it.id }.sortedBy { it.id }

            for (child in allChildren) {
                result.add(HierarchicalCategory(child, 1, parent))
                val directGrandChildren = child.subcategoryList ?: child.subcategories ?: emptyList()
                val otherGrandChildren = map.values.filter { it.parentCategoryId == child.id && directGrandChildren.none { dgc -> dgc.id == it.id } }
                val allGrandChildren = (directGrandChildren + otherGrandChildren).distinctBy { it.id }.sortedBy { it.id }
                for (gc in allGrandChildren) {
                    result.add(HierarchicalCategory(gc, 2, child))
                }
            }
        }

        val visitedIds = result.map { it.category.id }.toSet()
        for (cat in map.values.sortedBy { it.id }) {
            if (cat.id !in visitedIds) {
                val parent = cat.parentCategoryId?.let { map[it] }
                result.add(HierarchicalCategory(cat, if (parent != null) 1 else 0, parent))
            }
        }

        return result
    }

    fun getCategory(id: Int?): Category? {
        if (id == null) return null
        return categoryMap[id]
    }

    fun loadTopics(
        filter: Constants.TopicFilter,
        category: Category? = null,
        page: Int = 0,
        tag: String? = null,
        onSuccess: (List<Topic>, Boolean) -> Unit,
        onError: (Throwable) -> Unit
    ): java.util.concurrent.Future<*> {
        return ApplicationManager.getApplication().executeOnPooledThread {
            com.lgguan.linuxdo.plugin.common.LinuxDoLog.info("Loading topics: filter=$filter, cat=${category?.slug}, page=$page")
            val result = try { DiscourseApiClient.getTopicList(filter, category?.slug, category?.id, page, tag) }
                catch (error: Exception) { Result.failure(error) }
            result.onSuccess { topicList ->
                val hasMore = !topicList.moreTopicsUrl.isNullOrBlank()
                com.lgguan.linuxdo.plugin.common.LinuxDoLog.info("Loaded ${topicList.topics.size} topics (hasMore=$hasMore)")
                ApplicationManager.getApplication().invokeLater {
                    onSuccess(topicList.topics, hasMore)
                }
            }.onFailure { err ->
                com.lgguan.linuxdo.plugin.common.LinuxDoLog.warn("Failed to load topics: ${err.message}")
                ApplicationManager.getApplication().invokeLater {
                    onError(err)
                }
            }
        }
    }

    fun loadTopicDetail(
        topicId: Long,
        onSuccess: (TopicDetailResponse) -> Unit,
        onError: (Throwable) -> Unit,
        targetPostNumber: Int? = null,
        resumeLastRead: Boolean = false
    ): java.util.concurrent.Future<*> {
        return ApplicationManager.getApplication().executeOnPooledThread {
            com.lgguan.linuxdo.plugin.common.LinuxDoLog.info("Loading topic detail for #$topicId...")
            val result = DiscourseApiClient.getTopicDetail(topicId, trackVisit = true).mapCatching { initial ->
                val target = targetPostNumber ?: initial.lastReadPostNumber?.takeIf { resumeLastRead }
                if (target != null && target > 1 && initial.postStream.posts.none { it.postNumber == target }) {
                    val around = DiscourseApiClient.getTopicAroundPost(topicId, target).getOrThrow()
                    around.copy(postStream = around.postStream.copy(stream = around.postStream.stream ?: initial.postStream.stream))
                } else initial
            }
            result.onSuccess { detail ->
                com.lgguan.linuxdo.plugin.common.LinuxDoLog.info("Loaded topic #$topicId with ${detail.postStream.posts.size} posts")
                ApplicationManager.getApplication().invokeLater {
                    onSuccess(detail)
                }
            }.onFailure { err ->
                com.lgguan.linuxdo.plugin.common.LinuxDoLog.warn("Failed to load topic #$topicId: ${err.message}")
                ApplicationManager.getApplication().invokeLater {
                    onError(err)
                }
            }
        }
    }

    fun loadPopularTags(onComplete: ((List<String>) -> Unit)? = null) {
        val version = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = DiscourseApiClient.getPopularTags()
            result.onSuccess { tags ->
                if (version != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@onSuccess
                val combined = tags.distinct()
                com.lgguan.linuxdo.plugin.net.SessionEpoch.ifCurrent(version) { popularTags.clear(); popularTags.addAll(combined) }
                try {
                    myState.cachedTags = combined.toMutableList()
                } catch (t: Throwable) {
                    com.lgguan.linuxdo.plugin.common.LinuxDoLog.warn("Failed to serialize tags cache: ${t.message}")
                }
                ApplicationManager.getApplication().invokeLater({
                    if (version != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                    onComplete?.invoke(combined)
                }, com.intellij.openapi.application.ModalityState.any())
            }.onFailure {
                ApplicationManager.getApplication().invokeLater({
                    if (version != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                    onComplete?.invoke(popularTags)
                }, com.intellij.openapi.application.ModalityState.any())
            }
        }
    }

    fun searchTags(query: String, onResult: (List<TagItem>) -> Unit) {
        val version = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = DiscourseApiClient.searchTags(query)
            val items = result.getOrElse { emptyList() }
            ApplicationManager.getApplication().invokeLater({
                if (version != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                onResult(items)
            }, com.intellij.openapi.application.ModalityState.any())
        }
    }

    private object LinuxDoTopicServiceHolder {
        val INSTANCE = LinuxDoTopicService()
    }
}
