package me.ash.reader.infrastructure.rss.provider.nextcloudnews

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import me.ash.reader.infrastructure.di.USER_AGENT_STRING
import me.ash.reader.infrastructure.exception.RemoteCallException
import me.ash.reader.infrastructure.net.ApiResult
import me.ash.reader.infrastructure.rss.provider.ProviderAPI
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.executeAsync
import okio.IOException
import java.util.concurrent.ConcurrentHashMap

class NextcloudNewsAPI private constructor(
    context: Context,
    private val serverUrl: String, // e.g., https://yournextcloud.com/index.php/apps/news/api/v1-3/
    private val username: String,
    private val password: String,
    clientCertificateAlias: String? = null,
) : ProviderAPI(context, clientCertificateAlias) {

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-f".toMediaType()

    private suspend inline fun <reified T> makeRequest(
        method: String,
        path: String,
        body: Any? = null,
        params: List<Pair<String, String>>? = null
    ): ApiResult<T> {
        val urlBuilder = StringBuilder("$serverUrl$path")
        if (params != null) {
            urlBuilder.append("?")
            urlBuilder.append(params.joinToString("&") { "${it.first}=${it.second}" })
        }

        val requestBuilder = Request.Builder()
            .url(urlBuilder.toString())
            .header("User-Agent", USER_AGENT_STRING)
            .header("Authorization", Credentials.basic(username, password))

        if (body != null) {
            requestBuilder.method(method, gson.toJson(body).toRequestBody(jsonMediaType))
        } else {
            requestBuilder.method(method, null)
        }

        return try {
            val response = client.newCall(requestBuilder.build()).executeAsync()
            val responseBodyString = response.body.string()

            if (response.isSuccessful) {
                if (responseBodyString.isEmpty() && Unit is T) {
                    @Suppress("UNCHECKED_CAST")
                    ApiResult.Success(Unit as T)
                } else {
                    ApiResult.Success(toDTO(responseBodyString))
                }
            } else {
                val errorMsg = try {
                    toDTO<NextcloudNewsDTO.Error>(responseBodyString).message ?: responseBodyString
                } catch (e: Exception) {
                    responseBodyString
                }
                ApiResult.BizError(RemoteCallException("Nextcloud API Error: ${response.code} - $errorMsg"))
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e)
        } catch (e: Exception) { // Catch other exceptions like JsonSyntaxException
            ApiResult.BizError(RemoteCallException("Nextcloud API Error: ${e.message}", e))
        }
    }

    private suspend inline fun <reified T> getRequest(path: String, params: List<Pair<String, String>>? = null): ApiResult<T> =
        makeRequest("GET", path, params = params)

    private suspend inline fun <reified T> postRequest(path: String, body: Any? = null): ApiResult<T> =
        makeRequest("POST", path, body = body)

    private suspend inline fun <reified T> putRequest(path: String, body: Any? = null): ApiResult<T> =
        makeRequest("PUT", path, body = body)

    private suspend inline fun <reified T> deleteRequest(path: String, body: Any? = null): ApiResult<T> =
        makeRequest("DELETE", path, body = body)


    // --- API Endpoints ---

    // Version
    suspend fun getVersion(): ApiResult<NextcloudNewsDTO.VersionResponse> =
        getRequest("version")

    // Status
    suspend fun getStatus(): ApiResult<NextcloudNewsDTO.StatusResponse> =
        getRequest("status")

    // Folders
    suspend fun getAllFolders(): ApiResult<NextcloudNewsDTO.FoldersResponse> =
        getRequest("folders")

    suspend fun createFolder(name: String): ApiResult<NextcloudNewsDTO.FoldersResponse> =
        postRequest("folders", NextcloudNewsDTO.CreateFolderRequest(name))

    suspend fun deleteFolder(folderId: Long): ApiResult<Unit> =
        deleteRequest("folders/$folderId")

    suspend fun renameFolder(folderId: Long, newName: String): ApiResult<Unit> =
        putRequest("folders/$folderId", NextcloudNewsDTO.RenameRequest(folderName = newName))

    suspend fun markFolderRead(folderId: Long, newestItemId: Long): ApiResult<Unit> =
        postRequest("folders/$folderId/read", NextcloudNewsDTO.MarkReadRequest(newestItemId))

    // Feeds
    suspend fun getAllFeeds(): ApiResult<NextcloudNewsDTO.FeedsResponse> =
        getRequest("feeds")

    suspend fun createFeed(url: String, folderId: Long?): ApiResult<NextcloudNewsDTO.FeedsResponse> =
        postRequest("feeds", NextcloudNewsDTO.CreateFeedRequest(url, folderId))

    suspend fun deleteFeed(feedId: Long): ApiResult<Unit> =
        deleteRequest("feeds/$feedId")

    suspend fun moveFeed(feedId: Long, folderId: Long?): ApiResult<Unit> =
        postRequest("feeds/$feedId/move", NextcloudNewsDTO.MoveFeedRequest(folderId))

    suspend fun renameFeed(feedId: Long, newTitle: String): ApiResult<Unit> =
        postRequest("feeds/$feedId/rename", NextcloudNewsDTO.RenameRequest(feedTitle = newTitle))

    suspend fun markFeedRead(feedId: Long, newestItemId: Long): ApiResult<Unit> =
        postRequest("feeds/$feedId/read", NextcloudNewsDTO.MarkReadRequest(newestItemId))


    // Items
    suspend fun getItems(
        batchSize: Int = -1, // -1 returns all items
        offset: Long? = null, // item id to start from (older items)
        type: Int, // (Feed: 0, Folder: 1, Starred: 2, All: 3)
        id: Long, // id of folder/feed, 0 for Starred/All
        getRead: Boolean = true, // true for all, false for unread only
        oldestFirst: Boolean = false
    ): ApiResult<NextcloudNewsDTO.ItemsResponse> {
        val params = mutableListOf<Pair<String, String>>()
        params.add("batchSize" to batchSize.toString())
        offset?.let { params.add("offset" to it.toString()) }
        params.add("type" to type.toString())
        params.add("id" to id.toString())
        params.add("getRead" to getRead.toString())
        params.add("oldestFirst" to oldestFirst.toString())
        return getRequest("items", params)
    }

    suspend fun getUpdatedItems(
        lastModified: Long, // timestamp
        type: Int,
        id: Long
    ): ApiResult<NextcloudNewsDTO.ItemsResponse> {
        val params = mutableListOf<Pair<String, String>>()
        params.add("lastModified" to lastModified.toString())
        params.add("type" to type.toString())
        params.add("id" to id.toString())
        return getRequest("items/updated", params)
    }

    suspend fun markItemRead(itemId: Long): ApiResult<Unit> =
        postRequest("items/$itemId/read")

    suspend fun markMultipleItemsRead(itemIds: List<Long>): ApiResult<Unit> =
        postRequest("items/read/multiple", NextcloudNewsDTO.ItemIdList(itemIds))

    suspend fun markItemUnread(itemId: Long): ApiResult<Unit> =
        postRequest("items/$itemId/unread")

    suspend fun markMultipleItemsUnread(itemIds: List<Long>): ApiResult<Unit> =
        postRequest("items/unread/multiple", NextcloudNewsDTO.ItemIdList(itemIds))

    suspend fun markItemStarred(itemId: Long): ApiResult<Unit> =
        postRequest("items/$itemId/star")

    suspend fun markMultipleItemsStarred(itemIds: List<Long>): ApiResult<Unit> =
        postRequest("items/star/multiple", NextcloudNewsDTO.ItemIdList(itemIds))

    suspend fun markItemUnstarred(itemId: Long): ApiResult<Unit> =
        postRequest("items/$itemId/unstar")

    suspend fun markMultipleItemsUnstarred(itemIds: List<Long>): ApiResult<Unit> =
        postRequest("items/unstar/multiple", NextcloudNewsDTO.ItemIdList(itemIds))

    suspend fun markAllItemsRead(newestItemId: Long): ApiResult<Unit> = // Global mark all as read
        postRequest("items/read", NextcloudNewsDTO.MarkReadRequest(newestItemId))


    companion object {
        private val instances: ConcurrentHashMap<String, NextcloudNewsAPI> = ConcurrentHashMap()

        fun getInstance(
            context: Context,
            serverUrl: String,
            username: String,
            password: String,
            clientCertificateAlias: String? = null,
        ): NextcloudNewsAPI {
            val key = "$serverUrl$username$password$clientCertificateAlias"
            return instances.getOrPut(key) {
                // Ensure serverUrl ends with a slash
                val normalizedServerUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
                NextcloudNewsAPI(
                    context,
                    normalizedServerUrl,
                    username,
                    password,
                    clientCertificateAlias
                )
            }
        }

        fun clearInstance() {
            instances.clear()
        }
    }
}
