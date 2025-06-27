package me.ash.reader.infrastructure.rss.provider.nextcloudnews

import com.google.gson.annotations.SerializedName

object NextcloudNewsDTO {

    data class Error(
        val message: String?
    )

    data class Folder(
        val id: Long,
        val name: String
    )

    data class FoldersResponse(
        val folders: List<Folder>
    )

    data class Feed(
        val id: Long,
        val url: String,
        val title: String,
        val faviconLink: String?,
        val added: Long,
        val folderId: Long,
        val unreadCount: Int,
        val ordering: Int,
        val link: String?,
        val pinned: Boolean,
        val updateErrorCount: Int?,
        val lastUpdateError: String?
    )

    data class FeedsResponse(
        val feeds: List<Feed>,
        val starredCount: Int?,
        val newestItemId: Long?
    )

    data class Item(
        val id: Long,
        val guid: String,
        val guidHash: String,
        val url: String?,
        val title: String?,
        val author: String?,
        val pubDate: Long?,
        val body: String?,
        val enclosureMime: String?,
        val enclosureLink: String?,
        val mediaThumbnail: String?,
        val mediaDescription: String?,
        val feedId: Long,
        var unread: Boolean,
        var starred: Boolean,
        val rtl: Boolean?,
        val lastModified: Long,
        val fingerprint: String?
    )

    data class ItemsResponse(
        val items: List<Item>
    )

    data class VersionResponse(
        val version: String
    )

    data class StatusResponse(
        val version: String,
        val warnings: Map<String, Boolean>?
    )

    // For marking items
    data class ItemIdList(
        val itemIds: List<Long>
    )

    // For marking folder/feed items as read
    data class MarkReadRequest(
        val newestItemId: Long
    )

    // For creating a folder
    data class CreateFolderRequest(
        val name: String
    )

    // For renaming a folder/feed
    data class RenameRequest(
        @SerializedName("name") // For folder
        val folderName: String? = null,
        @SerializedName("feedTitle") // For feed
        val feedTitle: String? = null
    )

    // For creating a feed
    data class CreateFeedRequest(
        val url: String,
        val folderId: Long?
    )

    // For moving a feed
    data class MoveFeedRequest(
        val folderId: Long?
    )
}
