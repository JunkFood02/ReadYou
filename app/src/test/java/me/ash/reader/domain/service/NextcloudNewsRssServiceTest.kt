package me.ash.reader.domain.service

import android.content.Context
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.account.security.NextcloudNewsSecurityKey
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.android.NotificationHelper
import me.ash.reader.infrastructure.net.ApiResult
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rss.provider.nextcloudnews.NextcloudNewsAPI
import me.ash.reader.infrastructure.rss.provider.nextcloudnews.NextcloudNewsDTO
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import java.util.Date
import org.junit.Assert.*

@ExperimentalCoroutinesApi
@RunWith(MockitoJUnitRunner::class)
class NextcloudNewsRssServiceTest {

    // Using UnconfinedTestDispatcher for simplicity in this example.
    // Consider StandardTestDispatcher or other dispatchers based on testing needs.
    private val testDispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()

    @Mock private lateinit var mockContext: Context
    @Mock private lateinit var mockArticleDao: ArticleDao
    @Mock private lateinit var mockFeedDao: FeedDao
    @Mock private lateinit var mockGroupDao: GroupDao
    @Mock private lateinit var mockRssHelper: RssHelper
    @Mock private lateinit var mockNotificationHelper: NotificationHelper
    @Mock private lateinit var mockWorkManager: WorkManager
    @Mock private lateinit var mockAccountService: AccountService
    // We need a way to mock NextcloudNewsAPI. One way is to make it a dependency,
    // another is to use a factory that can be controlled in tests,
    // or use a testing version of its companion object getInstance.
    // For this example, we'll assume we can control its instance or mock its behavior.
    // This is a common challenge with static getInstance() patterns.
    // Let's assume NextcloudNewsAPI.getInstance can be managed for tests (e.g. via a test rule or DI override)
    // For now, we'll mock it when getApi() is called. This is not ideal.
    @Mock private lateinit var mockNextcloudNewsAPI: NextcloudNewsAPI


    private lateinit var nextcloudNewsRssService: NextcloudNewsRssService
    private lateinit var mockWebServer: MockWebServer // For API if not fully mocked

    private val testAccountId = 1
    private val testAccount = Account(
        id = testAccountId,
        name = "Test Nextcloud Account",
        type = AccountType.NextcloudNews,
        securityKey = NextcloudNewsSecurityKey("http://test.com", "user", "pass").encode(),
        updateAt = Date()
    )
    private val defaultGroup = Group(id = "${testAccountId}$0", name = "Default", accountId = testAccountId)


    @Before
    fun setUp() {
        mockWebServer = MockWebServer() // In case API isn't fully injectable/mockable
        mockWebServer.start()

        // Setup mock AccountService
        runTest { // Needed for suspend functions
            `when`(mockAccountService.getCurrentAccount()).thenReturn(testAccount)
            `when`(mockAccountService.getCurrentAccountId()).thenReturn(testAccountId)
            `when`(mockAccountService.getDefaultGroup()).thenReturn(defaultGroup)
            `when`(mockAccountService.getDefaultGroup(testAccountId)).thenReturn(defaultGroup)
        }


        // This is where mocking NextcloudNewsAPI.getInstance would ideally happen.
        // Since NextcloudNewsRssService calls NextcloudNewsAPI.getInstance internally,
        // we need a robust way to inject a mock. If not possible, tests become integration tests.
        // For this skeleton, we'll assume direct mocking of API methods if `getApi()` was refactored
        // to allow injection, or we test its effects.
        // A better approach: Refactor NextcloudNewsRssService to take NextcloudNewsAPI.Factory as a dependency.

        nextcloudNewsRssService = NextcloudNewsRssService(
            context = mockContext,
            articleDao = mockArticleDao,
            feedDao = mockFeedDao,
            groupDao = mockGroupDao,
            rssHelper = mockRssHelper,
            notificationHelper = mockNotificationHelper,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher,
            workManager = mockWorkManager,
            accountService = mockAccountService
            // If NextcloudNewsAPI was injected: mockNextcloudNewsAPI
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        NextcloudNewsAPI.clearInstance() // Important if using the real singleton
    }

    @Test
    fun `validCredentials returns true on successful API version check`() = runTest {
        // This test currently relies on the actual NextcloudNewsAPI.getInstance behavior.
        // To make it a unit test, NextcloudNewsAPI should be injectable.
        // For now, this will be more of an integration test snippet with MockWebServer.
        NextcloudNewsAPI.clearInstance() // Ensure fresh instance for this test
        val tempApi = NextcloudNewsAPI.getInstance(mockContext, mockWebServer.url("/").toString(), "user", "pass")


        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{\"version\":\"1.3.0\"}"))

        val result = nextcloudNewsRssService.validCredentials(testAccount.copy(
            securityKey = NextcloudNewsSecurityKey(mockWebServer.url("/").toString(), "user", "pass").encode()
        ))

        assertTrue(result)
        assertEquals("/version", mockWebServer.takeRequest().path)
    }

    @Test
    fun `validCredentials returns false on API error`() = runTest {
        NextcloudNewsAPI.clearInstance()
         val tempApi = NextcloudNewsAPI.getInstance(mockContext, mockWebServer.url("/").toString(), "user", "pass")

        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("{\"message\":\"Unauthorized\"}"))

        val result = nextcloudNewsRssService.validCredentials(testAccount.copy(
            securityKey = NextcloudNewsSecurityKey(mockWebServer.url("/").toString(), "user", "pass").encode()
        ))

        assertFalse(result)
    }

    @Test
    fun `subscribe successfully adds feed to local DB and API`() = runTest {
        // This test requires mocking the API instance used by the service.
        // Let's assume `getApi()` can be mocked or refactored.
        // For now, we'll prepare mocks for if it were injectable.
        val feedUrl = "http://example.com/feed.xml"
        val mockSyndFeed = mock<com.rometools.rome.feed.synd.SyndFeed> {
            on { title } doReturn "Test Feed Title"
        }
        val ncFeedId = 123L
        val ncFolderId = 456L
        val groupId = testAccountId.spacerDollar(ncFolderId.toString())

        val createFeedResponse = NextcloudNewsDTO.FeedsResponse(
            feeds = listOf(NextcloudNewsDTO.Feed(ncFeedId, feedUrl, "Test Feed Title API", null, 0L, ncFolderId, 0,0,null,false,0,null)),
            starredCount = 0, newestItemId = 0
        )

        // If NextcloudNewsAPI was injected and mocked:
        // `when`(mockNextcloudNewsAPI.createFeed(feedUrl, ncFolderId)).thenReturn(ApiResult.Success(createFeedResponse))
        // `when`(mockRssHelper.queryRssIconLink(feedUrl)).thenReturn("http://example.com/icon.png")

        // Due to `getApi()` internal call, this test is more of an integration style with MockWebServer
        NextcloudNewsAPI.clearInstance()
        val tempSecurityKey = NextcloudNewsSecurityKey(mockWebServer.url("/").toString(), "user", "pass")
        val tempAccount = testAccount.copy(securityKey = tempSecurityKey.encode())
        `when`(mockAccountService.getCurrentAccount()).thenReturn(tempAccount) // Ensure service uses the MockWebServer URL

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(Gson().toJson(createFeedResponse)))
        `when`(mockRssHelper.queryRssIconLink(feedUrl)).thenReturn("http://example.com/icon.png")


        nextcloudNewsRssService.subscribe(feedUrl, mockSyndFeed, groupId, false, false, false)

        // Verify API call
        val request = mockWebServer.takeRequest()
        assertEquals("/feeds", request.path)
        // Further request body assertions...

        // Verify DB insertion
        verify(mockFeedDao).insert(any<Feed>())
        // ArgumentCaptor could be used to check the details of the inserted Feed.
    }

    @Test
    fun `syncAll fetches folders, feeds, and items, then updates DB`() = runTest {
        NextcloudNewsAPI.clearInstance()
        val tempSecurityKey = NextcloudNewsSecurityKey(mockWebServer.url("/").toString(), "user", "pass")
        val tempAccount = testAccount.copy(securityKey = tempSecurityKey.encode(), updateAt = Date(0)) // last sync was epoch
        `when`(mockAccountService.getCurrentAccount()).thenReturn(tempAccount)

        // Mock API responses
        val ncFolders = listOf(NextcloudNewsDTO.Folder(1L, "Folder1"))
        val ncFeeds = listOf(NextcloudNewsDTO.Feed(10L, "url1", "Feed1", null, 0L, 1L, 0,0,null,false,0,null))
        // lastModified for item should be > account.updateAt
        val ncItemsUpdated = listOf(NextcloudNewsDTO.Item(100L, "g1","h1","u1","Item1",null, System.currentTimeMillis()/1000 - 60, "b1",null,null,null,null,10L,true,false,false,System.currentTimeMillis()/1000 - 60,"f1"))
        val ncItemsStarred = listOf(NextcloudNewsDTO.Item(101L, "g2","h2","u2","Item2",null, System.currentTimeMillis()/1000 - 30, "b2",null,null,null,null,10L,false,true,false,System.currentTimeMillis()/1000 - 30,"f2"))


        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(Gson().toJson(NextcloudNewsDTO.FoldersResponse(ncFolders)))) // Get All Folders
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(Gson().toJson(NextcloudNewsDTO.FeedsResponse(ncFeeds, 0, 0)))) // Get All Feeds
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(Gson().toJson(NextcloudNewsDTO.ItemsResponse(ncItemsUpdated)))) // Get Updated Items
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(Gson().toJson(NextcloudNewsDTO.ItemsResponse(ncItemsStarred)))) // Get Starred Items

        // Mock DAO calls for preserving local settings for feeds
        `when`(mockFeedDao.queryById(anyString())).thenReturn(null) // Assume new feeds
        `when`(mockFeedDao.queryAll(testAccountId)).thenReturn(emptyList()) // For deletions
        `when`(mockGroupDao.queryAll(testAccountId)).thenReturn(emptyList()) // For deletions


        val result = nextcloudNewsRssService.sync(null, null) // Trigger syncAll

        assertEquals(ListenableWorker.Result.success(), result)

        verify(mockGroupDao).insertOrUpdate(anyList())
        verify(mockFeedDao).insertOrUpdate(anyList())
        verify(mockArticleDao).insertOrUpdate(*anyArray()) // anyArray() for vararg
        verify(mockAccountService).update(any<Account>()) // Verify account updateAt is changed

        // Assert that 4 requests were made to mockWebServer (folders, feeds, updated items, starred items)
        assertEquals(4, mockWebServer.requestCount)
    }


    // TODO: Add more tests for:
    // - addGroup, renameGroup, moveFeed, renameFeed, deleteGroup, deleteFeed
    //   - Verify API calls and local DB changes (insert, update, delete)
    //   - Test behavior when API calls fail
    // - sync logic:
    //   - Correct mapping of DTOs to Domain models
    //   - Handling of empty responses from API
    //   - Deletion of local items/feeds/groups not present on server
    //   - Error handling during sync (API errors, network errors) -> should return Result.failure()
    // - markAsRead, syncReadStatus, markAsStarred:
    //   - Verify API calls with correct parameters
    //   - Verify local DB updates upon successful API call
    //   - Behavior when API call fails
    // - Edge cases:
    //   - No network connectivity (how API client handles this and service reacts)
    //   - Server returning unexpected data structures
    //   - Null or invalid credentials
    //   - Test specific Nextcloud item types if they have special handling (e.g. type 0, 1, 2, 3 for items)
}
```
