package com.appunite.mockwebserverextensions

import com.appunite.mockwebserver_assertions.body
import com.appunite.mockwebserver_assertions.bodyString
import com.appunite.mockwebserver_assertions.code
import com.appunite.mockwebserver_assertions.header
import com.appunite.mockwebserver_assertions.headers
import com.appunite.mockwebserver_assertions.host
import com.appunite.mockwebserver_assertions.method
import com.appunite.mockwebserver_assertions.path
import com.appunite.mockwebserver_assertions.url
import com.appunite.mockwebserver_assertions.utf8
import com.appunite.mockwebserver_interceptor.TestInterceptor
import com.appunite.mockwebserver_request.Request
import com.appunite.mockwebserverextensions.util.MultipleFailuresError
import com.appunite.mockwebserverextensions.util.ResponseGenerator
import com.appunite.mockwebserverextensions.util.jsonResponse
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.runners.model.MultipleFailureException
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.first
import strikt.assertions.isA
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull
import strikt.assertions.last
import strikt.assertions.message
import strikt.assertions.single
import strikt.mockk.captured

class MockWebServerRuleTest {

    @Suppress("DEPRECATION")
    @get:Rule(order = 0)
    val expectedException: ExpectedException = ExpectedException.none()

    @get:Rule(order = 2)
    val mockWebServerRule: MockWebServerRule = MockWebServerRule()

    @Test
    fun testWhenRequestIsMocked_requestReturnsItsValue() {
        mockWebServerRule.register { _ ->
            MockResponse().setBody("Hello, World!")
        }

        val response = executeRequest("https://example.com/user")

        expectThat(response).and {
            code.isEqualTo(200)
            bodyString.isEqualTo("Hello, World!")
        }
    }

    @Test
    fun testWhenJsonIsMocked_requestReturnsItsValueAndCorrectHeaders() {
        mockWebServerRule.register { _ ->
            jsonResponse("""{"krowa": "pies"}""")
        }

        val response = executeRequest("https://example.com/user")

        expectThat(response).and {
            code.isEqualTo(200)
            headers.header("Content-Type").isEqualTo("application/json")
            bodyString.isEqualTo("""{"krowa": "pies"}""")
        }
    }

    @Test
    fun testWhenMockIsCleared_requestGetsDefault404() {
        expectedException.expect(
            matcher<Any> {
                expectThat(it).isA<Throwable>().message.isNotNull()
                    .contains("Request: GET https://example.com/user, there are no mocks")
            }
        )

        mockWebServerRule.register { _ ->
            MockResponse().setBody("Hello, World!")
        }

        mockWebServerRule.clear()

        val response = executeRequest("https://example.com/user")
        expectThat(response).code.isEqualTo(404)
    }

    @Test
    fun testWhenNoRequestIsMocked_throw404() {
        mockWebServerRule.register {
            expectThat(it).url.path.isEqualTo("/mocked404")

            MockResponse().setResponseCode(404)
        }
        val response = executeRequest("https://example.com/mocked404")

        expectThat(response).code.isEqualTo(404)
    }

    @Test
    fun testTestFailsAndThereIsSomeMockingIssue_theRootOfTheFailureIsOnTheFirstPlace() {
        expectedException.expect(
            matcher<Any> {
                expectThat(it).isA<MultipleFailureException>().message.isNotNull()
                    .contains("There were 2 errors")
                expectThat(it)
                    .isA<MultipleFailureException>()
                    .get("failures") { failures }
                    .first().isA<CustomException>()
            }
        )

        executeRequest("https://example.com/not_mocked")

        throw CustomException
    }

    @Test
    fun testTestFailsAndThereIsSomeMockingIssue_returnInformationAboutExceptionAndNotMockedResponses() {
        expectedException.expect(
            matcher<Any> {
                expectThat(it).isA<Throwable>().message.isNotNull()
                    .contains("Request: GET https://example.com/not_mocked, there are no mocks")

                expectThat(it)

                    .isA<MultipleFailureException>()
                    .get("failures") { failures }
                    .last()
                    .isA<MultipleFailuresError>()
                    .and {
                        message.isNotNull()
                            .contains("Request: GET https://example.com/not_mocked, there are no mocks")
                        get(MultipleFailuresError::failures).isEmpty()
                    }
            }
        )

        executeRequest("https://example.com/not_mocked")

        throw CustomException
    }

    @Test
    fun testTestFailsAndThereIsSomeMockingIssue_listAllUnmatchedRequests() {
        mockWebServerRule.register {
            expectThat(it).url.path.isEqualTo("/different_url")

            MockResponse().setBody("Hello, World!")
        }
        expectedException.expect(
            matcher<Any> {
                expectThat(it).isA<Throwable>().message.isNotNull().and {
                    contains("Request: GET https://example.com/not_mocked, no mock is matching the request")
                    contains("✗ is equal to \"/different_url\"")
                    contains("found \"/not_mocked\"")
                }

                expectThat(it)
                    .isA<MultipleFailureException>()
                    .get("failures") { failures }
                    .last()
                    .isA<MultipleFailuresError>()
                    .and {
                        message.isNotNull()
                            .contains("Request: GET https://example.com/not_mocked, no mock is matching the request")
                        get(MultipleFailuresError::failures)
                            .single()
                            .isA<AssertionError>().and {
                                message.isNotNull().contains("✗ is equal to \"/different_url\"")
                                message.isNotNull().contains("found \"/not_mocked\"")
                            }
                    }
            }
        )

        executeRequest("https://example.com/not_mocked")

        throw CustomException
    }

    @Test
    fun testWhenRequestIsMade_passedUrlAndMethodAreCorrect() {
        val slot = executeRequestAndGetMockedArgumentRequest()

        expectThat(slot).captured.and {
            url.path.isEqualTo("/url")
            url.host.isEqualTo("example.com")
            method.isEqualTo("GET")
        }
    }

    @Test
    fun testWhenRequestIsMadeWithHeader_passedRequestHasHeaderSet() {
        val slot = executeRequestAndGetMockedArgumentRequest {
            it.addHeader("Accept", "application/json")
        }
        expectThat(slot).captured.headers.header("Accept").isEqualTo("application/json")
    }

    @Test
    fun testWhenGetRequestIsMade_bodyIsEmpty() {
        val slot = executeRequestAndGetMockedArgumentRequest()

        expectThat(slot).captured.and {
            body.utf8.isEmpty()
            method.isEqualTo("GET")
        }
    }

    @Test
    fun testWhenPostRequestIsMade_bodyIsSet() {
        val slot = executeRequestAndGetMockedArgumentRequest {
            it.method(
                "POST",
                "Request body".toRequestBody()
            )
        }

        expectThat(slot).captured.and {
            body.utf8.isEqualTo("Request body")
            method.isEqualTo("POST")
        }
    }

    @Test
    fun whenExceptionIsThrown_passIt() {
        expectedException.expect(
            matcher<Any> {
                expectThat(it).isA<CustomException>()
            }
        )

        throw CustomException
    }

    private fun executeRequest(
        url: String,
        builder: (okhttp3.Request.Builder) -> okhttp3.Request.Builder = { it }
    ): Response {
        val client = OkHttpClient.Builder()
            .addInterceptor(TestInterceptor)
            .build()
        val request = builder(
            okhttp3.Request.Builder()
                .url(url)
        )
            .build()
        return client.newCall(request).execute()
    }

    private fun executeRequestAndGetMockedArgumentRequest(
        requestBuilder: (okhttp3.Request.Builder) -> okhttp3.Request.Builder = { it }
    ): CapturingSlot<Request> {
        val slot = slot<Request>()
        val mock = mockk<ResponseGenerator> {
            every { this@mockk.invoke(capture(slot)) } returns MockResponse().setBody("Hello, World!")
        }
        mockWebServerRule.register(mock)

        executeRequest("https://example.com/url", requestBuilder)
        return slot
    }
}

private object CustomException : Exception("Custom exception")

private fun <T> matcher(check: (T) -> Unit): Matcher<T> = object : TypeSafeMatcher<T>() {
    override fun describeTo(description: Description) {}

    override fun describeMismatchSafely(item: T, description: Description) {
        try {
            check(item)
        } catch (e: AssertionError) {
            description.appendText(e.message)
            return
        }
        throw RuntimeException("Something went wrong... exception should happen")
    }

    override fun matchesSafely(item: T): Boolean {
        return try {
            check(item)
            true
        } catch (e: AssertionError) {
            false
        }
    }
}
