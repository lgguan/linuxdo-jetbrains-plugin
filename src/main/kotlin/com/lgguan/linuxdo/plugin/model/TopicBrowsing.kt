package com.lgguan.linuxdo.plugin.model

import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

@JsonAdapter(TopicTagAdapter::class)
data class TopicTag(val id: Long? = null, val name: String, val slug: String? = null)

class TopicTagAdapter : TypeAdapter<TopicTag>() {
    override fun read(reader: JsonReader): TopicTag {
        if (reader.peek() == JsonToken.STRING) return TopicTag(name = reader.nextString())
        var id: Long? = null
        var name = ""
        var slug: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> if (reader.peek() == JsonToken.NULL) reader.nextNull() else id = reader.nextString().toLongOrNull()
                "name" -> if (reader.peek() == JsonToken.NULL) reader.nextNull() else name = reader.nextString()
                "slug" -> if (reader.peek() == JsonToken.NULL) reader.nextNull() else slug = reader.nextString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return TopicTag(id, name, slug)
    }

    override fun write(writer: JsonWriter, value: TopicTag) {
        writer.beginObject()
        value.id?.let { writer.name("id").value(it) }
        writer.name("name").value(value.name)
        value.slug?.let { writer.name("slug").value(it) }
        writer.endObject()
    }
}

internal object TopicBrowsing {
    fun searchTopics(response: SearchResultResponse): List<Topic> {
        val matches = response.posts.orEmpty().filter { it.topicId != null }.groupBy { it.topicId }
        return response.topics.orEmpty().distinctBy { it.id }.map { topic ->
            val post = matches[topic.id]?.firstOrNull()
            topic.copy(searchPostNumber = post?.postNumber, searchBlurb = post?.blurb)
        }
    }

    /** Keep loaded pages during refresh; newest values replace matching IDs. */
    fun merge(current: List<Topic>, incoming: List<Topic>, refresh: Boolean): List<Topic> {
        if (refresh && incoming.isEmpty()) return emptyList()
        val updates = incoming.associateBy { it.id }
        return if (refresh) incoming.distinctBy { it.id } + current.filter { it.id !in updates }
        else current.map { old -> updates[old.id]?.let { updated ->
            updated.copy(searchPostNumber = old.searchPostNumber ?: updated.searchPostNumber,
                searchBlurb = old.searchBlurb ?: updated.searchBlurb)
        } ?: old } + incoming.filter { next -> current.none { it.id == next.id } }.distinctBy { it.id }
    }
}
