package com.lgguan.linuxdo.plugin.model

import com.google.gson.annotations.SerializedName

data class SiteResponse(
    @SerializedName("categories") val categories: List<Category>? = null
)

data class CategoryListResponse(
    @SerializedName("category_list") val categoryList: CategoryList
)

data class CategoryList(
    @SerializedName("categories") val categories: List<Category>
)

data class Category(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("color") val color: String?,
    @SerializedName("slug") val slug: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("description_text") val descriptionText: String? = null,
    @SerializedName("parent_category_id") val parentCategoryId: Int? = null,
    @SerializedName("topic_count") val topicCount: Int? = 0,
    @SerializedName("subcategory_list") val subcategoryList: List<Category>? = null,
    @SerializedName("subcategories") val subcategories: List<Category>? = null,
    @SerializedName("read_restricted") val readRestricted: Boolean? = false,
    @SerializedName("permission") val permission: Int? = null,
    @SerializedName("topic_template") val topicTemplate: String? = null,
    @SerializedName("minimum_required_tags") val minimumRequiredTags: Int = 0
)

data class TopicListResponse(
    @SerializedName("topic_list") val topicList: TopicList
)

data class TopicList(
    @SerializedName("topics") val topics: List<Topic>,
    @SerializedName("more_topics_url") val moreTopicsUrl: String? = null
)

data class Topic(
    @SerializedName("id") val id: Long,
    @SerializedName("title") val title: String,
    @SerializedName("fancy_title") val fancyTitle: String? = null,
    @SerializedName("slug") val slug: String? = null,
    @SerializedName("posts_count") val postsCount: Int = 0,
    @SerializedName("reply_count") val replyCount: Int = 0,
    @SerializedName("highest_post_number") val highestPostNumber: Int = 0,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("last_posted_at") val lastPostedAt: String? = null,
    @SerializedName("bumped_at") val bumpedAt: String? = null,
    @SerializedName("views") val views: Int = 0,
    @SerializedName("like_count") val likeCount: Int = 0,
    @SerializedName("pinned") val pinned: Boolean = false,
    @SerializedName("unseen") val unseen: Boolean = false,
    @SerializedName("unread_posts") val unreadPosts: Int = 0,
    @SerializedName("last_read_post_number") val lastReadPostNumber: Int? = null,
    @SerializedName("category_id") val categoryId: Int? = null,
    @SerializedName("posters") val posters: List<TopicPoster>? = null,
    @SerializedName("tags") val tags: List<TopicTag>? = null,
    @Transient val searchPostNumber: Int? = null,
    @Transient val searchBlurb: String? = null
)

data class TopicPoster(
    @SerializedName("extras") val extras: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("user_id") val userId: Long = 0
)

data class TopicDetailResponse(
    @SerializedName("id") val id: Long,
    @SerializedName("title") val title: String,
    @SerializedName("fancy_title") val fancyTitle: String? = null,
    @SerializedName("category_id") val categoryId: Int? = null,
    @SerializedName("post_stream") val postStream: PostStream,
    @SerializedName("posts_count") val postsCount: Int = 0,
    @SerializedName("reply_count") val replyCount: Int = 0,
    @SerializedName("views") val views: Int = 0,
    @SerializedName("like_count") val likeCount: Int = 0,
    @SerializedName("last_read_post_number") val lastReadPostNumber: Int? = null,
    @SerializedName("highest_post_number") val highestPostNumber: Int? = null,
    @SerializedName("closed") val closed: Boolean? = null,
    @SerializedName("archived") val archived: Boolean? = null,
    @SerializedName("details") val details: TopicPermissions? = null,
    @SerializedName("can_vote") val canVote: Boolean? = null,
    @SerializedName("user_voted") val userVoted: Boolean? = null,
    @SerializedName("vote_count") val voteCount: Int? = null,
    @SerializedName("votes_left") val votesLeft: Int? = null,
    @SerializedName("is_post_voting") val isPostVoting: Boolean? = null,
    @SerializedName("accepted_answer") val acceptedAnswer: com.google.gson.JsonObject? = null,
    @SerializedName("valid_reactions") val validReactions: List<String>? = null
)

data class PostStream(
    @SerializedName("posts") val posts: List<Post>,
    @SerializedName("stream") val stream: List<Long>? = null
)

data class PostStreamResponse(
    @SerializedName("post_stream") val postStream: PostStream
)

data class Post(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String? = null,
    @SerializedName("username") val username: String = "",
    @SerializedName("avatar_template") val avatarTemplate: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("cooked") val cooked: String = "",
    @SerializedName("raw") val raw: String? = null,
    @SerializedName("blurb") val blurb: String? = null,
    @SerializedName("post_number") val postNumber: Int = 1,
    @SerializedName("post_type") val postType: Int = 1,
    @SerializedName("reply_to_post_number") val replyToPostNumber: Int? = null,
    @SerializedName("reply_count") val replyCount: Int = 0,
    @SerializedName("reads") val reads: Int = 0,
    @SerializedName("score") val score: Double = 0.0,
    @SerializedName("user_title") val userTitle: String? = null,
    @SerializedName("trust_level") val trustLevel: Int = 0,
    @SerializedName("read") val read: Boolean? = null,
    @SerializedName("topic_id") val topicId: Long? = null,
    @SerializedName("topic_slug") val topicSlug: String? = null,
    @SerializedName("actions_summary") val actionsSummary: List<ActionSummary>? = null,
    @SerializedName("boosts") val boosts: List<PostBoost>? = null,
    @SerializedName("yours") val yours: Boolean? = null,
    @SerializedName("can_edit") val canEdit: Boolean? = null,
    @SerializedName("can_delete") val canDelete: Boolean? = null,
    @SerializedName("can_recover") val canRecover: Boolean? = null,
    @SerializedName("can_view_edit_history") val canViewEditHistory: Boolean? = null,
    @SerializedName("version") val version: Int? = null,
    @SerializedName("edit_reason") val editReason: String? = null,
    @SerializedName("deleted_at") val deletedAt: String? = null,
    @SerializedName("user_deleted") val userDeleted: Boolean? = null,
    @SerializedName("bookmarked") val bookmarked: Boolean? = null,
    @SerializedName("bookmark_id") val bookmarkId: Long? = null,
    @SerializedName("bookmark_name") val bookmarkName: String? = null,
    @SerializedName("bookmark_reminder_at") val bookmarkReminderAt: String? = null,
    @SerializedName("reactions") val reactions: List<Reaction>? = null,
    @SerializedName("current_user_reaction") val currentUserReaction: UserReaction? = null,
    @SerializedName("can_accept_answer") val canAcceptAnswer: Boolean? = null,
    @SerializedName("can_unaccept_answer") val canUnacceptAnswer: Boolean? = null,
    @SerializedName("accepted_answer") val acceptedAnswer: Boolean? = null,
    @SerializedName("can_vote") val canVote: Boolean? = null,
    @SerializedName("post_voting_vote_count") val postVotingVoteCount: Int? = null,
    @SerializedName("post_voting_user_voted") val postVotingUserVoted: Boolean? = null,
    @SerializedName("post_voting_user_voted_direction") val postVotingDirection: String? = null,
    @SerializedName("polls") val polls: List<com.google.gson.JsonObject>? = null,
    @SerializedName("polls_votes") val pollsVotes: com.google.gson.JsonObject? = null
) {
    fun getLikeCount(): Int {
        return actionsSummary?.firstOrNull { it.id == 2 }?.count ?: 0
    }

    fun isLiked(): Boolean {
        return actionsSummary?.firstOrNull { it.id == 2 }?.acted ?: false
    }

    fun getAvatarUrl(size: Int = 48, baseUrl: String = "https://linux.do"): String {
        val tpl = avatarTemplate
        if (tpl.isNullOrBlank()) return ""
        val path = tpl.replace("{size}", size.toString())
        return if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            "${baseUrl.removeSuffix("/")}/${path.removePrefix("/")}"
        }
    }
}

data class PostBoost(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("user_id") val userId: Long? = null,
    @SerializedName("username") val username: String? = null,
    @SerializedName("raw") val raw: String? = null,
    @SerializedName("cooked") val cooked: String? = null,
    @SerializedName("content") val content: String? = null,
    @SerializedName("user") val user: BoostUser? = null
) {
    fun getDisplayUsername(): String {
        return user?.username ?: username ?: "user"
    }

    fun getDisplayContent(): String {
        val cleanCooked = cooked?.replace(Regex("<[^>]*>"), "")?.trim()
        if (!cleanCooked.isNullOrBlank()) return cleanCooked
        if (!raw.isNullOrBlank()) return raw.trim()
        if (!content.isNullOrBlank()) return content.trim()
        return ""
    }
}

data class BoostUser(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("username") val username: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("avatar_template") val avatarTemplate: String? = null
)

data class ActionSummary(
    @SerializedName("id") val id: Int, // 2 = like
    @SerializedName("count") val count: Int = 0,
    @SerializedName("acted") val acted: Boolean? = false,
    @SerializedName("can_act") val canAct: Boolean? = null,
    @SerializedName("can_undo") val canUndo: Boolean? = null
)

data class TopicPermissions(
    @SerializedName("can_create_post") val canCreatePost: Boolean? = null,
    @SerializedName("notification_level") val notificationLevel: Int? = null,
    @SerializedName("created_by") val createdBy: BoostUser? = null
)
data class Reaction(@SerializedName("id") val id: String, @SerializedName("count") val count: Int = 0)
data class UserReaction(@SerializedName("id") val id: String?, @SerializedName("can_undo") val canUndo: Boolean? = null)

data class CurrentUserResponse(
    @SerializedName("current_user") val currentUser: UserInfo? = null
)

data class UserInfo(
    @SerializedName("id") val id: Long,
    @SerializedName("username") val username: String,
    @SerializedName("name") val name: String? = null,
    @SerializedName("avatar_template") val avatarTemplate: String? = null,
    @SerializedName("trust_level") val trustLevel: Int = 0,
    @SerializedName("admin") val admin: Boolean = false,
    @SerializedName("moderator") val moderator: Boolean = false,
    @SerializedName("unread_notifications") val unreadNotifications: Int = 0,
    @SerializedName("unread_high_priority_notifications") val unreadHighPriorityNotifications: Int = 0
)

data class SearchResultResponse(
    @SerializedName("topics") val topics: List<Topic>? = null,
    @SerializedName("posts") val posts: List<Post>? = null,
    @SerializedName("grouped_search_result") val groupedSearchResult: GroupedSearchResult? = null
)

data class GroupedSearchResult(
    @SerializedName("more") val more: Boolean = false,
    @SerializedName("more_full_page_results") val moreFullPageResults: Boolean = false,
    @SerializedName("term") val term: String? = null
)

data class NotificationListResponse(
    @SerializedName("notifications") val notifications: List<DiscourseNotification> = emptyList(),
    @SerializedName("total_rows_notifications") val totalRows: Int = 0,
    @SerializedName("seen_notification_id") val seenNotificationId: Long? = null
)

data class DiscourseNotification(
    @SerializedName("id") val id: Long,
    @SerializedName("notification_type") val notificationType: Int,
    @SerializedName("read") var read: Boolean = false,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("topic_id") val topicId: Long? = null,
    @SerializedName("post_number") val postNumber: Int? = null,
    @SerializedName("slug") val slug: String? = null,
    @SerializedName("data") val data: NotificationData? = null
) {
    fun getDisplayAuthor(): String {
        return data?.displayUsername
            ?: data?.originalUsername
            ?: data?.username
            ?: if (notificationType == 12 || notificationType == 6) "system" else "系统"
    }

    fun getDisplayTitle(): String {
        if (!data?.badgeName.isNullOrBlank()) {
            return "获得了 '${data.badgeName}'"
        }
        if (!data?.message.isNullOrBlank()) {
            return data.message
        }
        if (!data?.topicTitle.isNullOrBlank()) {
            return data.topicTitle
        }
        return slug ?: "通知 #$id"
    }

    fun getTypeActionLabel(): String {
        return when (notificationType) {
            1 -> "提到了你"
            2 -> "回复了你"
            3 -> "引用了你的发言"
            4 -> "编辑了帖子"
            5 -> "赞了你的帖子"
            6 -> "发来私信"
            9 -> "发布了新回复"
            11 -> "链接了你的帖子"
            12 -> "授予你新徽章"
            19 -> if ((data?.count ?: 0) > 1) "等 ${(data?.count ?: 0)} 人赞了你的帖子" else "赞了你的帖子"
            34 -> "发送了微回复"
            else -> "发来通知"
        }
    }

    fun getRelativeTime(): String {
        val timeStr = createdAt ?: return ""
        return try {
            val instant = java.time.Instant.parse(timeStr)
            val now = java.time.Instant.now()
            val diffSeconds = java.time.Duration.between(instant, now).seconds
            when {
                diffSeconds < 60 -> "刚刚"
                diffSeconds < 3600 -> "${diffSeconds / 60}分钟前"
                diffSeconds < 86400 -> "${diffSeconds / 3600}小时前"
                diffSeconds < 86400 * 30 -> "${diffSeconds / 86400}天前"
                else -> java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(instant)
            }
        } catch (_: Throwable) {
            timeStr.take(10)
        }
    }
}

data class NotificationData(
    @SerializedName("topic_title") val topicTitle: String? = null,
    @SerializedName("original_username") val originalUsername: String? = null,
    @SerializedName("display_username") val displayUsername: String? = null,
    @SerializedName("badge_name") val badgeName: String? = null,
    @SerializedName("badge_id") val badgeId: Long? = null,
    @SerializedName("badge_slug") val badgeSlug: String? = null,
    @SerializedName("count") val count: Int? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("username") val username: String? = null,
    @SerializedName("original_post_id") val originalPostId: Long? = null
)

data class UploadResponse(
    @SerializedName("id") val id: Long,
    @SerializedName("url") val url: String,
    @SerializedName("original_filename") val originalFilename: String? = null,
    @SerializedName("filesize") val filesize: Long = 0,
    @SerializedName("width") val width: Int? = null,
    @SerializedName("height") val height: Int? = null,
    @SerializedName("short_url") val shortUrl: String? = null,
    @SerializedName("short_path") val shortPath: String? = null,
    @SerializedName("extension") val extension: String? = null
)

data class TagListResponse(
    @SerializedName("tags") val tags: List<TagItem> = emptyList()
)

data class TagItem(
    @SerializedName("id") val id: String,
    @SerializedName("text") val text: String,
    @SerializedName("count") val count: Int = 0,
    @SerializedName("name") val name: String? = null,
    @SerializedName("slug") val slug: String? = null,
    @SerializedName("disabled") val disabled: Boolean = false,
    @SerializedName("title") val title: String? = null
)

data class TagSearchResultResponse(
    @SerializedName("results") val results: List<TagItem> = emptyList(),
    @SerializedName("required_tag_group") val requiredTagGroup: RequiredTagGroup? = null,
    @SerializedName("forbidden") val forbidden: Boolean = false,
    @SerializedName("forbidden_message") val forbiddenMessage: String? = null
)

data class RequiredTagGroup(@SerializedName("name") val name: String = "", @SerializedName("min_count") val minCount: Int = 0)
