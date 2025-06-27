package me.ash.reader.domain.service

import android.content.Context
import android.util.Log
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import com.rometools.rome.feed.synd.SyndFeed
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.ash.reader.R
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.security.NextcloudNewsSecurityKey // To be created
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.android.NotificationHelper
import me.ash.reader.infrastructure.di.DefaultDispatcher
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.html.Readability
import me.ash.reader.infrastructure.net.ApiResult
import me.ash.reader.infrastructure.net.getOrNull
import me.ash.reader.infrastructure.net.getOrThrow
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rss.provider.nextcloudnews.NextcloudNewsAPI
import me.ash.reader.infrastructure.rss.provider.nextcloudnews.NextcloudNewsDTO
import me.ash.reader.ui.ext.decodeHTML
import me.ash.reader.ui.ext.dollarLast
import me.ash.reader.ui.ext.isFuture
import me.ash.reader.ui.ext.spacerDollar
import timber.log.Timber
import java.util.Date
import javax.inject.Inject

class NextcloudNewsRssService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val articleDao: ArticleDao,
    private val feedDao: FeedDao,
    private val groupDao: GroupDao,
    private val rssHelper: RssHelper,
    private val notificationHelper: NotificationHelper,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val workManager: WorkManager,
    private val accountService: AccountService,
) : AbstractRssRepository(
    articleDao,
    groupDao,
    feedDao,
    workManager,
    rssHelper,
    notificationHelper,
    ioDispatcher,
    defaultDispatcher,
    accountService
) {

    override val importSubscription: Boolean = false // Nextcloud News API handles subscriptions directly
    override val addSubscription: Boolean = true
    override val moveSubscription: Boolean = true
    override val deleteSubscription: Boolean = true
    override val updateSubscription: Boolean = true // Renaming feeds/folders

    private suspend fun getApi(): NextcloudNewsAPI {
        val account = accountService.getCurrentAccount()
        requireNotNull(account) { "Current account is null" }
        val securityKey = NextcloudNewsSecurityKey(account.securityKey) // Assumes NextcloudNewsSecurityKey is created
        return NextcloudNewsAPI.getInstance(
            context = context,
            serverUrl = securityKey.serverUrl ?: throw IllegalStateException("Server URL is not set"),
            username = securityKey.username ?: throw IllegalStateException("Username is not set"),
            password = securityKey.password ?: throw IllegalStateException("Password is not set"),
            clientCertificateAlias = securityKey.clientCertificateAlias
        )
    }

    override suspend fun validCredentials(account: Account): Boolean {
        val securityKey = NextcloudNewsSecurityKey(account.securityKey)
        val api = NextcloudNewsAPI.getInstance(
            context = context,
            serverUrl = securityKey.serverUrl ?: return false,
            username = securityKey.username ?: return false,
            password = securityKey.password ?: return false,
            clientCertificateAlias = securityKey.clientCertificateAlias
        )
        val result = api.getVersion() // A simple call to check credentials
        return result.isSuccess.also { success ->
            if (success) {
                // Optionally, fetch user info if there was a user endpoint
                // and update account name like in GoogleReaderRssService
                // For now, we assume Nextcloud username is known or not needed for display name
            }
        }
    }

    override suspend fun clearAuthorization() {
        NextcloudNewsAPI.clearInstance()
    }

    override suspend fun subscribe(
        feedLink: String,
        searchedFeed: SyndFeed, // May not be fully populated or needed if API returns title
        groupId: String, // This is folderId for Nextcloud
        isNotification: Boolean,
        isFullContent: Boolean,
        isBrowser: Boolean
    ) = coroutineScope {
        val accountId = accountService.getCurrentAccountId()
        val ncGroupId = groupId.dollarLast().toLongOrNull() // Assuming group ID is "accountId$folderId"

        val result = getApi().createFeed(url = feedLink, folderId = ncGroupId)
        val createdFeed = result.getOrThrow().feeds.firstOrNull() // API returns a list with the created feed
        requireNotNull(createdFeed) { "Failed to create feed or parse response" }

        feedDao.insert(
            Feed(
                id = accountId.spacerDollar(createdFeed.id.toString()),
                name = createdFeed.title.decodeHTML() ?: searchedFeed.title.decodeHTML() ?: context.getString(R.string.empty),
                url = createdFeed.url,
                groupId = if (createdFeed.folderId != 0L && createdFeed.folderId != null) accountId.spacerDollar(createdFeed.folderId.toString()) else accountService.getDefaultGroup().id,
                accountId = accountId,
                isNotification = isNotification,
                isFullContent = isFullContent,
                isBrowser = isBrowser,
                icon = createdFeed.faviconLink ?: rssHelper.queryRssIconLink(createdFeed.url) // Fetch if not provided
            )
        )
        // Consider a selective sync for the new feed if needed, or rely on next full sync
    }

    override suspend fun addGroup(destFeed: Feed?, newGroupName: String): String = coroutineScope {
        val accountId = accountService.getCurrentAccountId()
        val result = getApi().createFolder(name = newGroupName)
        val createdFolder = result.getOrThrow().folders.first() // API returns list with one folder
        val groupId = accountId.spacerDollar(createdFolder.id.toString())
        groupDao.insert(Group(id = groupId, name = createdFolder.name, accountId = accountId))

        // If destFeed is provided, move it to the new group (folder)
        if (destFeed != null) {
            val feedIdLong = destFeed.id.dollarLast().toLong()
            getApi().moveFeed(feedId = feedIdLong, folderId = createdFolder.id)
            feedDao.update(destFeed.copy(groupId = groupId))
        }
        return@coroutineScope groupId
    }

    override suspend fun renameGroup(group: Group) {
        val ncGroupId = group.id.dollarLast().toLong()
        getApi().renameFolder(folderId = ncGroupId, newName = group.name).getOrThrow()
        super.renameGroup(group) // Updates local DB
    }

    override suspend fun moveFeed(originGroupId: String, feed: Feed) {
        val ncFeedId = feed.id.dollarLast().toLong()
        val ncDestFolderId = if (feed.groupId == accountService.getDefaultGroup().id) null else feed.groupId.dollarLast().toLong()
        getApi().moveFeed(feedId = ncFeedId, folderId = ncDestFolderId).getOrThrow()
        super.moveFeed(originGroupId, feed) // Updates local DB
    }

    override suspend fun changeFeedUrl(feed: Feed) {
        // Nextcloud News API v1.3 does not support changing feed URL directly.
        // A workaround would be to delete and re-add, but that loses history.
        throw UnsupportedOperationException("Changing feed URL is not supported by Nextcloud News API v1.3")
    }

    override suspend fun renameFeed(feed: Feed) {
        val ncFeedId = feed.id.dollarLast().toLong()
        getApi().renameFeed(feedId = ncFeedId, newTitle = feed.name).getOrThrow()
        super.renameFeed(feed) // Updates local DB
    }

    override suspend fun deleteGroup(group: Group, onlyDeleteNoStarred: Boolean?) {
        // Nextcloud API deletes folder and contained feeds.
        // Local DB handling should align.
        val ncGroupId = group.id.dollarLast().toLong()
        getApi().deleteFolder(folderId = ncGroupId).getOrThrow()
        // Superclass handles local deletion of group and its feeds/articles
        super.deleteGroup(group, false) // `onlyDeleteNoStarred` might not be relevant if API force-deletes
    }

    override suspend fun deleteFeed(feed: Feed, onlyDeleteNoStarred: Boolean?) {
        val ncFeedId = feed.id.dollarLast().toLong()
        getApi().deleteFeed(feedId = ncFeedId).getOrThrow()
        // Superclass handles local deletion of feed and its articles
        super.deleteFeed(feed, false) // `onlyDeleteNoStarred` might not be relevant
    }


    override suspend fun sync(feedId: String?, groupId: String?): ListenableWorker.Result = coroutineScope {
        // Nextcloud News sync is different. It doesn't have per-feed/group sync endpoint.
        // It uses a lastModified timestamp.
        // For simplicity in this initial implementation, we'll always do a full sync.
        // A more optimized version could be developed later.
        if (feedId != null || groupId != null) {
            Timber.tag("NextcloudSync").w("Per-feed or per-group sync requested but not granularly supported by Nextcloud API v1.3 via this service's current sync implementation. Performing full sync.")
        }
        return@coroutineScope syncAll()
    }

    private suspend fun syncAll(): ListenableWorker.Result = supervisorScope {
        val preTime = System.currentTimeMillis()
        try {
            val account = accountService.getCurrentAccount() ?: return@supervisorScope ListenableWorker.Result.failure()
            val accountId = account.id!!
            val api = getApi()

            // 1. Fetch remote folders (groups) and feeds
            val remoteFoldersDeferred = async(ioDispatcher) { api.getAllFolders().getOrNull()?.folders ?: emptyList() }
            val remoteFeedsDeferred = async(ioDispatcher) { api.getAllFeeds().getOrNull()?.feeds ?: emptyList() }

            val remoteFolders = remoteFoldersDeferred.await().map { ncFolder ->
                Group(
                    id = accountId.spacerDollar(ncFolder.id.toString()),
                    name = ncFolder.name.decodeHTML(),
                    accountId = accountId
                )
            }
            val remoteFeeds = remoteFeedsDeferred.await().map { ncFeed ->
                Feed(
                    id = accountId.spacerDollar(ncFeed.id.toString()),
                    name = ncFeed.title.decodeHTML(),
                    url = ncFeed.url,
                    groupId = if (ncFeed.folderId != 0L) accountId.spacerDollar(ncFeed.folderId.toString()) else accountService.getDefaultGroup(accountId).id,
                    accountId = accountId,
                    icon = ncFeed.faviconLink, //  ?: rssHelper.queryRssIconLink(ncFeed.url), // Fetching icons can be slow, do it on demand or batched
                    isNotification = feedDao.queryById(accountId.spacerDollar(ncFeed.id.toString()))?.isNotification ?: false, // Preserve local settings
                    isFullContent = feedDao.queryById(accountId.spacerDollar(ncFeed.id.toString()))?.isFullContent ?: false,
                    isBrowser = feedDao.queryById(accountId.spacerDollar(ncFeed.id.toString()))?.isBrowser ?: false,
                )
            }

            // Update local groups and feeds
            groupDao.insertOrUpdate(remoteFolders)
            feedDao.insertOrUpdate(remoteFeeds)

            // Delete local groups and feeds not present remotely
            val remoteFolderIds = remoteFolders.map { it.id }.toSet()
            groupDao.queryAll(accountId).filterNot { it.id in remoteFolderIds }.forEach { groupDao.delete(it) } // Cascading delete should handle feeds/articles

            val remoteFeedIds = remoteFeeds.map { it.id }.toSet()
            feedDao.queryAll(accountId).filterNot { it.id in remoteFeedIds }.forEach { feedDao.delete(it) } // Cascading delete for articles

            // 2. Fetch items
            // Sync strategy from Nextcloud News API docs:
            // Initial: GET /items?type=3&getRead=false&batchSize=-1 (unread)
            //           GET /items?type=2&getRead=true&batchSize=-1 (starred)
            // Syncing: GET /items/updated?lastModified=...&type=3
            // For simplicity, this first pass will fetch all unread and all starred items.
            // A more sophisticated approach would use /items/updated with lastModified.

            val lastSyncTimestamp = account.updateAt?.time ?: 0L // Milliseconds
            var newLastSyncTimestamp = System.currentTimeMillis()

            val updatedItemsDeferred = async(ioDispatcher) {
                api.getUpdatedItems(lastModified = lastSyncTimestamp / 1000, type = 3, id = 0).getOrNull()?.items ?: emptyList()
            }
            // Additionally, fetch all starred items as `getUpdatedItems` might not return items whose content hasn't changed but star status did.
            // Or, rely on the fact that client-side star changes are pushed up.
            // For now, let's fetch starred explicitly to ensure consistency.
             val starredItemsDeferred = async(ioDispatcher) {
                api.getItems(type = 2, id = 0, getRead = true, batchSize = -1).getOrNull()?.items ?: emptyList()
            }


            val ncItems = (updatedItemsDeferred.await() + starredItemsDeferred.await()).distinctBy { it.id }

            if (ncItems.isNotEmpty()) {
                val articles = mapNcItemsToArticles(ncItems, accountId, Date(newLastSyncTimestamp))
                articleDao.insertOrUpdate(*articles.toTypedArray()) // Handles conflicts by replacing
            }

            // 3. Push local changes (read/unread, starred/unstarred)
            // This part requires tracking local changes since last sync, which is complex.
            // The current AbstractRssRepository design has syncReadStatus and markAsStarred for individual items.
            // A full "push all local states" is not trivial.
            // For now, we assume changes are pushed immediately by other methods (markAsRead, markAsStarred)
            // and this sync focuses on pulling.
            // A more robust solution would queue local changes and push them here.


            accountService.update(account.copy(updateAt = Date(newLastSyncTimestamp)))
            Timber.tag("NextcloudSync").i("Sync completed in ${System.currentTimeMillis() - preTime}ms. Fetched ${ncItems.size} items.")
            ListenableWorker.Result.success()

        } catch (e: Exception) {
            Timber.tag("NextcloudSync").e(e, "Sync failed: ${e.message}")
            ListenableWorker.Result.failure()
        }
    }

    private fun mapNcItemsToArticles(ncItems: List<NextcloudNewsDTO.Item>, accountId: Int, currentTime: Date): List<Article> {
        return ncItems.mapNotNull { ncItem ->
            val feedId = accountId.spacerDollar(ncItem.feedId.toString())
            // Ensure feed exists locally, otherwise skip (or handle as error)
            if (feedDao.queryById(feedId) == null) {
                Timber.tag("NextcloudSync").w("Skipping item ${ncItem.id} because feed $feedId not found locally.")
                return@mapNotNull null
            }

            Article(
                id = accountId.spacerDollar(ncItem.id.toString()),
                date = ncItem.pubDate?.let { Date(it * 1000) }?.takeIf { !it.isFuture(currentTime) } ?: Date(ncItem.lastModified * 1000),
                title = ncItem.title.decodeHTML() ?: context.getString(R.string.empty),
                author = ncItem.author.decodeHTML(),
                rawDescription = ncItem.body ?: "",
                shortDescription = Readability.parseToText(ncItem.body, ncItem.url).take(280),
                img = rssHelper.findThumbnail(ncItem.body) ?: ncItem.mediaThumbnail,
                link = ncItem.url ?: "",
                feedId = feedId,
                accountId = accountId,
                isUnread = ncItem.unread,
                isStarred = ncItem.starred,
                updateAt = Date(ncItem.lastModified * 1000)
            )
        }
    }


    override suspend fun markAsRead(groupId: String?, feedId: String?, articleId: String?, before: Date?, isUnread: Boolean) {
        val accountId = accountService.getCurrentAccountId()
        val api = getApi()

        val itemsToMark = when {
            articleId != null -> listOf(articleId.dollarLast().toLong())
            // Nextcloud API doesn't support marking whole folder/feed read before a certain date.
            // It marks based on newestItemId known to client, or all items.
            // For simplicity, we'll mark specific items if listed, or rely on newest item ID for folder/feed.
            // A more granular "mark X, Y, Z as read" is better.
            else -> {
                // This logic from GoogleReaderRssService needs to be adapted.
                // We need IDs of articles to mark.
                val articles = when {
                    groupId != null -> articleDao.queryMetadataByGroupIdWhenIsUnread(accountId, groupId, !isUnread, before)
                    feedId != null -> articleDao.queryMetadataByFeedId(accountId, feedId, !isUnread, before)
                    else -> articleDao.queryMetadataAll(accountId, !isUnread, before)
                }
                articles.map { it.id.dollarLast().toLong() }
            }
        }

        if (itemsToMark.isNotEmpty()) {
            val result = if (isUnread) { // Mark as UNREAD
                api.markMultipleItemsUnread(itemsToMark)
            } else { // Mark as READ
                api.markMultipleItemsRead(itemsToMark)
            }
            if (result.isSuccess) {
                super.markAsRead(groupId, feedId, articleId, before, isUnread) // Update local DB
            } else {
                Timber.tag("NextcloudService").e("Failed to mark items as read/unread on server: ${result.getOrNull()}")
                // Potentially throw to indicate failure or handle more gracefully
            }
        } else if (articleId == null && before == null) { // Mark all in feed/group as read
            val newestLocalItemId = when {
                 feedId != null -> articleDao.queryByFeedId(accountId, feedId).maxOfOrNull { it.id.dollarLast().toLong() }
                 groupId != null -> articleDao.queryByGroupId(accountId, groupId).maxOfOrNull { it.id.dollarLast().toLong() }
                 else -> articleDao.queryAllArticles(accountId).maxOfOrNull { it.id.dollarLast().toLong() }
            } ?: 0L // If 0, API might mark truly all items.

            val res = when {
                 feedId != null -> if(!isUnread) api.markFeedRead(feedId.dollarLast().toLong(), newestLocalItemId) else ApiResult.Success(Unit) // No "mark feed unread"
                 groupId != null -> if(!isUnread) api.markFolderRead(groupId.dollarLast().toLong(), newestLocalItemId) else ApiResult.Success(Unit) // No "mark folder unread"
                 else -> if(!isUnread) api.markAllItemsRead(newestLocalItemId) else ApiResult.Success(Unit) // No "mark all unread"
            }
             if (res.isSuccess) {
                super.markAsRead(groupId, feedId, articleId, before, isUnread) // Update local DB
            } else {
                Timber.tag("NextcloudService").e("Failed to mark folder/feed as read on server")
            }
        }
    }

    override suspend fun syncReadStatus(articleIds: Set<String>, isUnread: Boolean): Set<String> = coroutineScope {
        val api = getApi()
        val ncArticleIds = articleIds.mapNotNull { it.dollarLast().toLongOrNull() }
        if (ncArticleIds.isEmpty()) return@coroutineScope emptySet()

        val result = if (isUnread) {
            api.markMultipleItemsUnread(ncArticleIds)
        } else {
            api.markMultipleItemsRead(ncArticleIds)
        }

        return@coroutineScope if (result.isSuccess) {
            articleIds // Assume success means all specified IDs were processed
        } else {
            Timber.tag("NextcloudService").e("Failed to sync read status for ${articleIds.size} items server: ${result.getOrNull()}")
            emptySet()
        }
    }

    override suspend fun markAsStarred(articleId: String, isStarred: Boolean) {
        val ncArticleId = articleId.dollarLast().toLongOrNull() ?: return
        val api = getApi()

        val result = if (isStarred) {
            api.markItemStarred(ncArticleId)
        } else {
            api.markItemUnstarred(ncArticleId)
        }

        if (result.isSuccess) {
            super.markAsStarred(articleId, isStarred) // Update local DB
        } else {
            Timber.tag("NextcloudService").e("Failed to mark item $articleId as starred=$isStarred on server: ${result.getOrNull()}")
            // Potentially throw or notify user
        }
    }
}
