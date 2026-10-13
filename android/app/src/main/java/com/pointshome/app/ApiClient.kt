package com.pointshome.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class AccountInfo(
    val id: String,
    val role: String,
    val email: String?,
    val username: String?,
    val displayName: String,
)

data class LoginSession(val token: String, val account: AccountInfo)
data class FamilyMember(val id: String, val displayName: String, val username: String?)
data class InviteInfo(val code: String, val expiresAt: Long)
data class PointItem(val id: String, val name: String, val points: Int,
                     val maxUses: Int?, val remainingUses: Int?, val expiresAt: Long?)
data class PointClaim(val id: String, val itemId: String, val status: String,
                      val note: String?, val createdAt: Long)
data class PointAction(val id: String, val itemId: String?, val kind: String,
                       val amount: Int, val status: String, val note: String?,
                       val dueAt: Long?, val createdAt: Long)
data class PointLedger(val actionId: String, val delta: Int, val kind: String, val createdAt: Long)
data class PointOverview(val balance: Long, val items: List<PointItem>,
                         val claims: List<PointClaim>, val actions: List<PointAction>,
                         val ledger: List<PointLedger>)
data class RewardInfo(val id: String, val name: String, val description: String?,
                      val cost: Int, val active: Boolean, val rewardKind: String = "STANDARD", val timeMinutes: Int? = null)
data class RedemptionInfo(val id: String, val rewardItemId: String, val actionId: String?,
                          val rewardName: String, val rewardDescription: String?,
                          val cost: Int, val status: String, val requestedAt: Long,
                          val offeredAt: Long?, val receivedAt: Long?,
                          val rewardKind: String = "STANDARD", val timeMinutes: Int? = null)
data class TimeDeviceRegistration(val deviceId: String, val deviceKey: String)
data class TimeGrant(val id: String, val minutes: Int)
data class RewardOverview(val balance: Long, val items: List<RewardInfo>,
                          val redemptions: List<RedemptionInfo>, val hasMore: Boolean)
data class RedemptionPage(val entries: List<RedemptionInfo>, val hasMore: Boolean)
data class AiSetting(val configured: Boolean, val modelId: String?)
data class AiModel(val id: String, val name: String, val acceptsImages: Boolean)
data class AiModelList(val models: List<AiModel>, val fetchedAt: Long)
data class HomeworkSummary(val id: String, val childId: String, val subject: String,
                           val status: String, val parentRating: String?,
                           val photoCount: Int, val createdAt: Long)
data class HomeworkProblem(val location: String, val issue: String, val suggestion: String)
data class HomeworkReview(val summary: String,
                          val strengths: List<String>, val problems: List<HomeworkProblem>,
                          val uncertainties: List<String>, val nextSteps: List<String>)
data class HomeworkChat(val role: String, val content: String, val createdAt: Long)
data class HomeworkDetail(val id: String, val childId: String, val subject: String,
                          val instructions: String?, val status: String, val modelId: String?,
                          val analysis: HomeworkReview?, val aiError: String?,
                          val parentRating: String?, val parentNote: String?,
                          val pointItemId: String?, val pointActionId: String?,
                          val photoCount: Int, val createdAt: Long,
                          val chat: List<HomeworkChat>)
data class HomeworkRule(val goodItemId: String?, val averageItemId: String?,
                        val poorItemId: String?)

class ApiException(message: String, val status: Int? = null) : Exception(message)

class ApiClient(private val baseUrl: String) {
    val serverAddress: String get() = baseUrl.trim().trimEnd('/')
    private data class Cached<T>(val value: T, val savedAt: Long)
    private val cacheLock = Any()
    private val pointCache = LinkedHashMap<String, Cached<PointOverview>>()
    private val rewardCache = LinkedHashMap<String, Cached<RewardOverview>>()

    fun cachedPoints(token: String, childId: String, maxAgeMillis: Long = Long.MAX_VALUE): PointOverview? =
        synchronized(cacheLock) {
            pointCache["$token:$childId"]?.takeIf {
                System.currentTimeMillis() - it.savedAt <= maxAgeMillis
            }?.value
        }

    fun cachedRewards(token: String, childId: String, maxAgeMillis: Long = Long.MAX_VALUE): RewardOverview? =
        synchronized(cacheLock) {
            rewardCache["$token:$childId"]?.takeIf {
                System.currentTimeMillis() - it.savedAt <= maxAgeMillis
            }?.value
        }

    fun clearOverviewCache() = synchronized(cacheLock) {
        pointCache.clear()
        rewardCache.clear()
    }

    private fun <T> rememberOverview(cache: LinkedHashMap<String, Cached<T>>, key: String, value: T) {
        synchronized(cacheLock) {
            cache[key] = Cached(value, System.currentTimeMillis())
            if (cache.size > 8) cache.remove(cache.keys.first())
        }
    }

    suspend fun health(): Boolean = try {
        JSONObject(request("GET", "/api/v1/health")).optString("status") == "UP"
    } catch (_: ApiException) {
        false
    }

    suspend fun requestCode(email: String) {
        request("POST", "/api/v1/auth/verification-codes", json("email" to email))
    }

    suspend fun requestPasswordResetCode(email: String) {
        request("POST", "/api/v1/auth/password-reset-codes", json("email" to email))
    }

    suspend fun resetGuardianPassword(email: String, code: String, newPassword: String) {
        request("POST", "/api/v1/auth/password-resets", json(
            "email" to email, "code" to code, "newPassword" to newPassword,
        ))
    }

    suspend fun registerGuardian(email: String, code: String, password: String, displayName: String): LoginSession =
        parseSession(request("POST", "/api/v1/auth/guardians", json(
            "email" to email, "code" to code, "password" to password, "displayName" to displayName,
        )))

    suspend fun guardianLogin(email: String, password: String): LoginSession =
        parseSession(request("POST", "/api/v1/auth/guardian-sessions", json(
            "email" to email, "password" to password,
        )))

    suspend fun childLogin(username: String, password: String): LoginSession =
        parseSession(request("POST", "/api/v1/auth/child-sessions", json(
            "username" to username, "password" to password,
        )))

    suspend fun me(token: String): AccountInfo =
        parseAccount(JSONObject(request("GET", "/api/v1/auth/me", token = token)))

    suspend fun logout(token: String) {
        request("POST", "/api/v1/auth/logout", token = token)
    }

    suspend fun createChild(token: String, username: String, password: String, displayName: String): FamilyMember =
        parseMember(JSONObject(request("POST", "/api/v1/family/children", json(
            "username" to username, "password" to password, "displayName" to displayName,
        ), token)))

    suspend fun resetChildPassword(token: String, childId: String, newPassword: String) {
        request("POST", "/api/v1/family/children/$childId/password-reset",
            json("newPassword" to newPassword), token)
    }

    suspend fun children(token: String): List<FamilyMember> =
        parseMembers(request("GET", "/api/v1/family/children", token = token))

    suspend fun guardians(token: String): List<FamilyMember> =
        parseMembers(request("GET", "/api/v1/family/guardians", token = token))

    suspend fun invite(token: String, childId: String): InviteInfo {
        val response = JSONObject(request("POST", "/api/v1/family/children/$childId/invites", token = token))
        return InviteInfo(response.getString("code"), response.getLong("expiresAt"))
    }

    suspend fun redeem(token: String, code: String): FamilyMember =
        parseMember(JSONObject(request("POST", "/api/v1/family/invites/redeem", json("code" to code), token)))

    suspend fun points(token: String, childId: String): PointOverview = withContext(Dispatchers.Default) {
        val data = JSONObject(request("GET", "/api/v1/points/children/$childId", token = token))
        fun <T> entries(key: String, parser: (JSONObject) -> T): List<T> {
            val array = data.getJSONArray(key)
            return (0 until array.length()).map { parser(array.getJSONObject(it)) }
        }
        val overview = PointOverview(
            data.getLong("balance"),
            entries("items") { PointItem(it.getString("id"), it.getString("name"), it.getInt("points"),
                it.optNullableInt("maxUses"), it.optNullableInt("remainingUses"), it.optNullableLong("expiresAt")) },
            entries("claims") { PointClaim(it.getString("id"), it.getString("itemId"),
                it.getString("status"), it.optNullableString("note"), it.getLong("createdAt")) },
            entries("actions") { PointAction(it.getString("id"), it.optNullableString("itemId"),
                it.getString("kind"), it.getInt("amount"), it.getString("status"),
                it.optNullableString("note"), it.optNullableLong("dueAt"), it.getLong("createdAt")) },
            entries("ledger") { PointLedger(it.getString("actionId"), it.getInt("delta"),
                it.getString("kind"), it.getLong("createdAt")) },
        )
        rememberOverview(pointCache, "$token:$childId", overview)
        overview
    }

    suspend fun addPointItem(token: String, childId: String, name: String, points: Int,
                             maxUses: Int?, durationDays: Int?) {
        val body = JSONObject().put("name", name).put("points", points)
        if (maxUses != null) body.put("maxUses", maxUses)
        if (durationDays != null) body.put("durationDays", durationDays)
        request("POST", "/api/v1/points/children/$childId/items", body, token)
    }

    suspend fun grantPoints(token: String, itemId: String, requestId: String, note: String = "") {
        request("POST", "/api/v1/points/items/$itemId/grant",
            json("note" to note, "requestId" to requestId), token)
    }

    suspend fun deductPoints(token: String, childId: String, points: Int, reason: String,
                             requestId: String) {
        request("POST", "/api/v1/points/children/$childId/deductions",
            JSONObject().put("points", points).put("reason", reason)
                .put("requestId", requestId), token)
    }

    suspend fun claimPoints(token: String, itemId: String) {
        request("POST", "/api/v1/points/items/$itemId/claims", json("note" to ""), token)
    }

    suspend fun approveClaim(token: String, claimId: String) {
        request("POST", "/api/v1/points/claims/$claimId/approve", token = token)
    }

    suspend fun rejectClaim(token: String, claimId: String) {
        request("POST", "/api/v1/points/claims/$claimId/reject", token = token)
    }

    suspend fun reversePoints(token: String, actionId: String) {
        request("POST", "/api/v1/points/actions/$actionId/reverse", token = token)
    }

    suspend fun rewards(token: String, childId: String): RewardOverview = withContext(Dispatchers.Default) {
        val data = JSONObject(request("GET", "/api/v1/rewards/children/$childId", token = token))
        val items = data.getJSONArray("items")
        val redemptions = data.getJSONArray("redemptions")
        val overview = RewardOverview(data.getLong("balance"),
            (0 until items.length()).map { parseReward(items.getJSONObject(it)) },
            (0 until redemptions.length()).map { parseRedemption(redemptions.getJSONObject(it)) },
            data.getBoolean("hasMore"))
        rememberOverview(rewardCache, "$token:$childId", overview)
        overview
    }

    suspend fun redemptionHistory(token: String, childId: String, offset: Int): RedemptionPage =
        withContext(Dispatchers.Default) {
        val data = JSONObject(request("GET", "/api/v1/rewards/children/$childId/redemptions?offset=$offset",
            token = token))
        val entries = data.getJSONArray("entries")
        RedemptionPage((0 until entries.length()).map { parseRedemption(entries.getJSONObject(it)) },
            data.getBoolean("hasMore"))
    }

    suspend fun createReward(token: String, childId: String, name: String,
                             description: String, cost: Int, kind: String = "STANDARD", minutes: Int? = null) {
        request("POST", "/api/v1/rewards/children/$childId/items",
            JSONObject().put("name", name).put("description", description).put("cost", cost)
                .put("rewardKind", kind).put("timeMinutes", minutes ?: JSONObject.NULL), token)
    }

    suspend fun archiveReward(token: String, rewardId: String) {
        request("POST", "/api/v1/rewards/items/$rewardId/archive", token = token)
    }

    suspend fun restoreReward(token: String, rewardId: String) {
        request("POST", "/api/v1/rewards/items/$rewardId/restore", token = token)
    }

    suspend fun redeemReward(token: String, rewardId: String, requestId: String,
                             device: TimeDeviceRegistration? = null) {
        request("POST", "/api/v1/rewards/items/$rewardId/redeem",
            json("requestId" to requestId), token, extraHeaders = timeHeaders(device))
    }

    suspend fun provideReward(token: String, redemptionId: String) {
        request("POST", "/api/v1/rewards/redemptions/$redemptionId/provide", token = token)
    }

    suspend fun confirmReward(token: String, redemptionId: String) {
        request("POST", "/api/v1/rewards/redemptions/$redemptionId/confirm", token = token)
    }

    suspend fun rejectReward(token: String, id: String) { request("POST", "/api/v1/rewards/redemptions/$id/reject", token = token) }
    suspend fun cancelTimeRequest(token: String, id: String) { request("POST", "/api/v1/rewards/redemptions/$id/cancel", token = token) }
    suspend fun registerTimeDevice(token: String, childId: String, deviceId: String, name: String): TimeDeviceRegistration {
        val data = JSONObject(request("POST", "/api/v1/app-time/children/$childId/device",
            json("deviceId" to deviceId, "deviceName" to name), token))
        return TimeDeviceRegistration(data.getString("deviceId"), data.getString("deviceKey"))
    }
    suspend fun timeGrants(token: String, device: TimeDeviceRegistration): List<TimeGrant> = withContext(Dispatchers.Default) {
        val data = JSONArray(request("GET", "/api/v1/app-time/grants", token = token, extraHeaders = timeHeaders(device)))
        (0 until data.length()).map { data.getJSONObject(it).let { j -> TimeGrant(j.getString("id"), j.getInt("minutes")) } }
    }
    suspend fun acknowledgeTime(token: String, id: String, device: TimeDeviceRegistration) {
        request("POST", "/api/v1/app-time/grants/$id/received", token = token, extraHeaders = timeHeaders(device))
    }
    private fun timeHeaders(device: TimeDeviceRegistration?) = device?.let {
        mapOf("X-Time-Device-Id" to it.deviceId, "X-Time-Device-Key" to it.deviceKey)
    } ?: emptyMap()

    suspend fun deepSeekSetting(token: String): AiSetting =
        parseAiSetting(JSONObject(request("GET", "/api/v1/ai/deepseek", token = token)))

    suspend fun refreshDeepSeekModels(token: String, draftKey: String): AiModelList {
        val data = JSONObject(request("POST", "/api/v1/ai/deepseek/models/refresh",
            json("apiKey" to draftKey), token, readTimeoutMillis = 25_000))
        val array = data.getJSONArray("models")
        return AiModelList((0 until array.length()).map { index ->
            val model = array.getJSONObject(index)
            AiModel(model.getString("id"), model.optString("name", model.getString("id")),
                model.getBoolean("acceptsImages"))
        }, data.getLong("fetchedAt"))
    }

    suspend fun saveDeepSeekSetting(token: String, draftKey: String, modelId: String): AiSetting =
        parseAiSetting(JSONObject(request("PUT", "/api/v1/ai/deepseek",
            json("apiKey" to draftKey, "modelId" to modelId), token,
            readTimeoutMillis = 25_000)))

    suspend fun testDeepSeek(token: String) {
        request("POST", "/api/v1/ai/deepseek/test", token = token, readTimeoutMillis = 55_000)
    }

    suspend fun removeDeepSeek(token: String) {
        request("DELETE", "/api/v1/ai/deepseek", token = token)
    }

    suspend fun homeworkList(token: String, childId: String): List<HomeworkSummary> =
        withContext(Dispatchers.Default) {
        val data = JSONArray(request("GET", "/api/v1/homework/children/$childId/submissions", token = token))
        (0 until data.length()).map { index ->
            val item = data.getJSONObject(index)
            HomeworkSummary(item.getString("id"), item.getString("childId"), item.getString("subject"),
                item.getString("status"), item.optNullableString("parentRating"),
                item.getInt("photoCount"), item.getLong("createdAt"))
        }
    }

    suspend fun homeworkDetail(token: String, submissionId: String): HomeworkDetail =
        withContext(Dispatchers.Default) {
            parseHomeworkDetail(JSONObject(request("GET", "/api/v1/homework/submissions/$submissionId",
                token = token)))
        }

    suspend fun uploadHomework(token: String, childId: String, subject: String,
                               instructions: String, photos: List<ByteArray>): HomeworkDetail =
        withContext(Dispatchers.IO) {
            val boundary = "pointshome-${UUID.randomUUID()}"
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(baseUrl.trim().trimEnd('/') +
                    "/api/v1/homework/children/$childId/submissions").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 40_000
                    doOutput = true
                    setChunkedStreamingMode(8192)
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                }
                connection.outputStream.use { output ->
                    fun write(value: String) = output.write(value.toByteArray(Charsets.UTF_8))
                    fun field(name: String, value: String) {
                        write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n")
                        write(value)
                        write("\r\n")
                    }
                    field("subject", subject)
                    field("instructions", instructions)
                    photos.forEachIndexed { index, bytes ->
                        write("--$boundary\r\nContent-Disposition: form-data; name=\"photos\"; filename=\"page$index.jpg\"\r\n")
                        write("Content-Type: image/jpeg\r\n\r\n")
                        output.write(bytes)
                        write("\r\n")
                    }
                    write("--$boundary--\r\n")
                }
                val status = connection.responseCode
                val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) throw ApiException(apiError(response, status))
                parseHomeworkDetail(JSONObject(response))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (known: ApiException) {
                throw known
            } catch (_: Exception) {
                throw ApiException("上传失败，请检查网络或照片大小")
            } finally {
                connection?.disconnect()
            }
        }

    suspend fun homeworkPhoto(token: String, submissionId: String, ordinal: Int): ByteArray =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(baseUrl.trim().trimEnd('/') +
                    "/api/v1/homework/submissions/$submissionId/photos/$ordinal")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5_000
                    readTimeout = 15_000
                    setRequestProperty("Authorization", "Bearer $token")
                }
                val status = connection.responseCode
                if (status !in 200..299) {
                    val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }.orEmpty()
                    throw ApiException(apiError(body, status))
                }
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (output.size() <= 2_500_000) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        output.write(chunk, 0, count)
                    }
                    output.toByteArray()
                }
                if (bytes.size > 2_500_000) throw ApiException("作业照片过大")
                bytes
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (known: ApiException) {
                throw known
            } catch (_: Exception) {
                throw ApiException("无法读取作业照片")
            } finally {
                connection?.disconnect()
            }
        }

    suspend fun analyzeHomework(token: String, submissionId: String): HomeworkDetail =
        withContext(Dispatchers.Default) {
            parseHomeworkDetail(JSONObject(request("POST", "/api/v1/homework/submissions/$submissionId/analyze",
                token = token, readTimeoutMillis = 190_000)))
        }

    suspend fun askHomework(token: String, submissionId: String, question: String): HomeworkDetail =
        withContext(Dispatchers.Default) {
            parseHomeworkDetail(JSONObject(request("POST", "/api/v1/homework/submissions/$submissionId/chat",
                json("question" to question), token, readTimeoutMillis = 190_000)))
        }

    suspend fun reviewHomework(token: String, submissionId: String, rating: String,
                               note: String, itemId: String?): HomeworkDetail {
        val body = JSONObject().put("rating", rating).put("note", note)
        if (itemId != null) body.put("pointItemId", itemId)
        return withContext(Dispatchers.Default) {
            parseHomeworkDetail(JSONObject(request("POST", "/api/v1/homework/submissions/$submissionId/review",
                body, token)))
        }
    }

    suspend fun homeworkRule(token: String, childId: String): HomeworkRule =
        parseHomeworkRule(JSONObject(request("GET", "/api/v1/homework/children/$childId/rule",
            token = token)))

    suspend fun saveHomeworkRule(token: String, childId: String, rule: HomeworkRule): HomeworkRule {
        val body = JSONObject().put("goodItemId", rule.goodItemId)
            .put("averageItemId", rule.averageItemId).put("poorItemId", rule.poorItemId)
        return parseHomeworkRule(JSONObject(request("PUT", "/api/v1/homework/children/$childId/rule",
            body, token)))
    }

    suspend fun deleteHomework(token: String, submissionId: String) {
        request("DELETE", "/api/v1/homework/submissions/$submissionId", token = token)
    }

    private suspend fun request(method: String, path: String, body: JSONObject? = null,
                                token: String? = null,
                                readTimeoutMillis: Int = 5_000,
                                extraHeaders: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val base = baseUrl.trim().trimEnd('/')
            if (!base.startsWith("http://") && !base.startsWith("https://")) {
                throw ApiException("后端地址需要以 http:// 或 https:// 开头")
            }
            connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5_000
                readTimeout = readTimeoutMillis
                setRequestProperty("Accept", "application/json")
                if (token != null) setRequestProperty("Authorization", "Bearer $token")
                extraHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
            }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(response).optString("message") }.getOrNull()
                throw ApiException(message?.takeIf { it.isNotBlank() } ?: "请求失败（HTTP $status）", status)
            }
            response
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (known: ApiException) {
            throw known
        } catch (_: Exception) {
            throw ApiException("无法连接服务器，请检查地址和网络")
        } finally {
            connection?.disconnect()
        }
    }

    private fun json(vararg values: Pair<String, String>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) -> put(key, value) }
    }

    private fun parseSession(response: String): LoginSession {
        val data = JSONObject(response)
        return LoginSession(data.getString("token"), parseAccount(data.getJSONObject("account")))
    }

    private fun parseAccount(data: JSONObject): AccountInfo = AccountInfo(
        id = data.getString("id"),
        role = data.getString("role"),
        email = data.optString("email").takeIf { it.isNotBlank() && it != "null" },
        username = data.optString("username").takeIf { it.isNotBlank() && it != "null" },
        displayName = data.getString("displayName"),
    )

    private fun parseMembers(response: String): List<FamilyMember> {
        val data = JSONArray(response)
        return (0 until data.length()).map { parseMember(data.getJSONObject(it)) }
    }

    private fun parseMember(data: JSONObject): FamilyMember = FamilyMember(
        id = data.getString("id"),
        displayName = data.getString("displayName"),
        username = data.optString("username").takeIf { it.isNotBlank() && it != "null" },
    )

    private fun parseReward(data: JSONObject): RewardInfo = RewardInfo(
        data.getString("id"), data.getString("name"), data.optNullableString("description"),
        data.getInt("cost"), data.getBoolean("active"), data.optString("rewardKind", "STANDARD"), data.optNullableInt("timeMinutes"))

    private fun parseRedemption(data: JSONObject): RedemptionInfo = RedemptionInfo(
        data.getString("id"), data.getString("rewardItemId"), data.optNullableString("actionId"),
        data.getString("rewardName"), data.optNullableString("rewardDescription"),
        data.getInt("cost"), data.getString("status"), data.getLong("requestedAt"),
        data.optNullableLong("offeredAt"), data.optNullableLong("receivedAt"),
        data.optString("rewardKind", "STANDARD"), data.optNullableInt("timeMinutes"))

    private fun parseAiSetting(data: JSONObject): AiSetting = AiSetting(
        data.getBoolean("configured"), data.optNullableString("modelId"))

    private fun parseHomeworkDetail(data: JSONObject): HomeworkDetail {
        val analysis = data.optJSONObject("analysis")?.let { item ->
            val strengths = item.optJSONArray("strengths") ?: JSONArray()
            val problems = item.optJSONArray("problems") ?: JSONArray()
            val uncertainties = item.optJSONArray("uncertainties") ?: JSONArray()
            val nextSteps = item.optJSONArray("nextSteps") ?: JSONArray()
            fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }
            HomeworkReview(item.optString("summary"), strings(strengths),
                (0 until problems.length()).map { index ->
                    val problem = problems.getJSONObject(index)
                    HomeworkProblem(problem.optString("location"), problem.optString("issue"),
                        problem.optString("suggestion"))
                }, strings(uncertainties), strings(nextSteps))
        }
        val chat = data.optJSONArray("chat") ?: JSONArray()
        return HomeworkDetail(data.getString("id"), data.getString("childId"),
            data.getString("subject"), data.optNullableString("instructions"),
            data.getString("status"), data.optNullableString("modelId"), analysis,
            data.optNullableString("aiError"), data.optNullableString("parentRating"),
            data.optNullableString("parentNote"), data.optNullableString("pointItemId"),
            data.optNullableString("pointActionId"), data.getInt("photoCount"),
            data.getLong("createdAt"), (0 until chat.length()).map { index ->
                val message = chat.getJSONObject(index)
                HomeworkChat(message.getString("role"), message.getString("content"),
                    message.getLong("createdAt"))
            })
    }

    private fun parseHomeworkRule(data: JSONObject): HomeworkRule = HomeworkRule(
        data.optNullableString("goodItemId"), data.optNullableString("averageItemId"),
        data.optNullableString("poorItemId"))

    private fun apiError(response: String, status: Int): String =
        runCatching { JSONObject(response).optString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "请求失败（HTTP $status）"

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (isNull(key)) null else getInt(key)

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (isNull(key)) null else getLong(key)
}
