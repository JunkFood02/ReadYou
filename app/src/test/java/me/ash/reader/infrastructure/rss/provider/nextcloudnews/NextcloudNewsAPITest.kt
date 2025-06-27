package me.ash.reader.infrastructure.rss.provider.nextcloudnews

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.net.ApiResult
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import java.util.concurrent.TimeUnit
import org.junit.Assert.*

// It's often better to use RobolectricTestRunner for tests involving Android Context or other SDK parts,
// but for a pure API client test with MockWebServer, MockitoJUnitRunner can suffice if Context is handled.
// For simplicity, using MockitoJUnitRunner and assuming Context won't be deeply used in these unit tests.
@RunWith(MockitoJUnitRunner::class)
class NextcloudNewsAPITest {

    @Mock
    private lateinit var mockContext: Context

    private lateinit var mockWebServer: MockWebServer
    private lateinit var nextcloudNewsAPI: NextcloudNewsAPI
    private val gson = Gson()

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        // Configure OkHttpClient for testing (e.g., shorter timeouts)
        // The actual ProviderAPI uses a Hilt-injected OkHttpClient.
        // For isolated testing of NextcloudNewsAPI, we might need to adjust how OkHttpClient is provided
        // or ensure the test setup for ProviderAPI allows injecting a test client.
        // For this example, we'll assume ProviderAPI can be instantiated with a test client or context.
        // A more complete setup would involve a test DI module for OkHttpClient.

        val testOkHttpClient = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .writeTimeout(1, TimeUnit.SECONDS)
            .build()

        // Hacky way to set the client for testing, real ProviderAPI uses DI.
        // This is a limitation of testing it in isolation without proper DI setup for tests.
        // In a real scenario, you'd use a Hilt test rule and provide a test OkHttpClient.
        val tempApi = NextcloudNewsAPI.getInstance(
            context = mockContext,
            serverUrl = mockWebServer.url("/").toString(),
            username = "testuser",
            password = "testpassword"
        )
        // This reflection hack is bad practice but shows intent if ProviderAPI.client is not easily settable.
        try {
            val clientField = ProviderAPI::class.java.getDeclaredField("client")
            clientField.isAccessible = true
            clientField.set(tempApi, testOkHttpClient)
        } catch (e: Exception) {
            // Handle exception, perhaps by using a more testable ProviderAPI or test DI
            println("Warning: Could not set test OkHttpClient via reflection. Network calls might not use test client.")
        }
        nextcloudNewsAPI = tempApi
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        NextcloudNewsAPI.clearInstance() // Clear singleton instance
    }

    @Test
    fun `getVersion returns success`() = runBlocking {
        val versionResponse = NextcloudNewsDTO.VersionResponse("1.3.0")
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(gson.toJson(versionResponse))
        )

        val result = nextcloudNewsAPI.getVersion()

        assertTrue(result is ApiResult.Success)
        assertEquals("1.3.0", (result as ApiResult.Success).data.version)

        val request = mockWebServer.takeRequest()
        assertEquals("/version", request.path)
        assertEquals("GET", request.method)
        assertNotNull(request.getHeader("Authorization"))
    }

    @Test
    fun `getAllFolders returns success with folder list`() = runBlocking {
        val folders = listOf(NextcloudNewsDTO.Folder(1, "Tech"), NextcloudNewsDTO.Folder(2, "News"))
        val foldersResponse = NextcloudNewsDTO.FoldersResponse(folders)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(gson.toJson(foldersResponse))
        )

        val result = nextcloudNewsAPI.getAllFolders()

        assertTrue(result is ApiResult.Success)
        assertEquals(2, (result as ApiResult.Success).data.folders.size)
        assertEquals("Tech", result.data.folders[0].name)

        val request = mockWebServer.takeRequest()
        assertEquals("/folders", request.path)
    }

    @Test
    fun `createFolder returns success`() = runBlocking {
         val newFolder = NextcloudNewsDTO.Folder(id = 3, name = "New Folder")
        val createResponse = NextcloudNewsDTO.FoldersResponse(listOf(newFolder))
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(gson.toJson(createResponse))
        )

        val result = nextcloudNewsAPI.createFolder("New Folder")
        assertTrue(result is ApiResult.Success)
        assertEquals("New Folder", (result as ApiResult.Success).data.folders.first().name)

        val request = mockWebServer.takeRequest()
        assertEquals("/folders", request.path)
        assertEquals("POST", request.method)
        val requestBody = request.body.readUtf8()
        val expectedBody = gson.toJson(NextcloudNewsDTO.CreateFolderRequest("New Folder"))
        assertEquals(expectedBody, requestBody)
    }


    @Test
    fun `getItems returns success`() = runBlocking {
        val items = listOf(
            NextcloudNewsDTO.Item(1, "guid1", "hash1", "url1", "Title1", "Author1", 1L, "Body1", null, null, null, null, 10L, false, false, false, 100L, "fp1"),
            NextcloudNewsDTO.Item(2, "guid2", "hash2", "url2", "Title2", "Author2", 2L, "Body2", null, null, null, null, 10L, true, true, false, 200L, "fp2")
        )
        val itemsResponse = NextcloudNewsDTO.ItemsResponse(items)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(gson.toJson(itemsResponse))
        )

        val result = nextcloudNewsAPI.getItems(type = 0, id = 10L, getRead = true)

        assertTrue(result is ApiResult.Success)
        assertEquals(2, (result as ApiResult.Success).data.items.size)
        assertEquals("Title1", result.data.items[0].title)
        assertTrue(result.data.items[1].unread)
        assertTrue(result.data.items[1].starred)

        val request = mockWebServer.takeRequest()
        assertTrue(request.path!!.startsWith("/items"))
        assertTrue(request.path!!.contains("type=0"))
        assertTrue(request.path!!.contains("id=10"))
        assertTrue(request.path!!.contains("getRead=true"))
    }

    @Test
    fun `api call returns error on 401`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("{\"message\":\"Unauthorized\"}")
        )

        val result = nextcloudNewsAPI.getVersion()

        assertTrue(result is ApiResult.BizError)
        val error = result as ApiResult.BizError
        assertTrue(error.error.message!!.contains("401") || error.error.message!!.contains("Unauthorized"))
    }

    @Test
    fun `api call returns network error on IOException`() = runBlocking {
        // Simulate IOException by shutting down server before request or other means
        // For this example, let's assume a malformed URL could trigger something,
        // though typically MockWebServer handles this gracefully unless misconfigured.
        // A better way is to make OkHttp client throw an IOException.
        // This test is more conceptual for now.
        mockWebServer.shutdown() // Cause an IOException when client tries to connect

        // Re-initialize API with a client that will fail immediately
         val failingApi = NextcloudNewsAPI.getInstance(
            context = mockContext,
            serverUrl = "http://localhost:${mockWebServer.port}", // MockWebServer is down
            username = "user",
            password = "password"
        )
        // This is still tricky as the OkHttpClient might be shared or cached.
        // True network error testing often involves more direct OkHttp client manipulation or Robolectric.

        val result = failingApi.getVersion()
        assertTrue(result is ApiResult.NetworkError)
    }

    // Add more tests for:
    // - Other GET, POST, PUT, DELETE endpoints
    // - Different response codes (404, 409, 422, 500)
    // - Empty responses where appropriate (e.g., for DELETE or some PUTs)
    // - Malformed JSON responses
    // - Correct parameter encoding in URLs for GET requests
    // - Correct request body for POST/PUT requests
}
