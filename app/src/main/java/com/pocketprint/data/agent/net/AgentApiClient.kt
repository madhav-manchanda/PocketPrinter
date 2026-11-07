package com.pocketprint.data.agent.net

import com.pocketprint.data.agent.model.RemotePrintJob
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.print.model.PrintJobState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Robust HTTP client managing authenticated device communication with the VPS backend.
 */
class AgentApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * Validates a user-entered server URL and verifies that it is a PocketPrint API.
     * HTTPS is required for remote hosts; cleartext HTTP is allowed only for loopback
     * development endpoints.
     */
    suspend fun testConnection(serverUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val normalized = serverUrl.trim().trimEnd('/')
            val parsed = normalized.toHttpUrlOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Enter a valid server URL, including https://"))
            if (parsed.encodedPath != "/" || parsed.query != null || parsed.fragment != null ||
                parsed.username.isNotEmpty() || parsed.password.isNotEmpty()
            ) {
                return@withContext Result.failure(IllegalArgumentException("Enter only the PocketPrint server origin, for example https://print.example.com"))
            }
            val loopback = parsed.host.equals("localhost", true) ||
                parsed.host == "127.0.0.1" || parsed.host == "::1"
            if (parsed.scheme != "https" && !(parsed.scheme == "http" && loopback)) {
                return@withContext Result.failure(IllegalArgumentException("Remote VPS connections must use HTTPS"))
            }

            val request = Request.Builder().url("$normalized/health").get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Server health check failed: HTTP ${response.code}"))
                }
                val json = JSONObject(body)
                if (json.optString("service") != "PocketPrint Cloud" || json.optInt("apiVersion", -1) != 1) {
                    return@withContext Result.failure(Exception("The URL is reachable, but it is not a compatible PocketPrint backend (API v1 required)"))
                }
                Result.success("Connected to PocketPrint Cloud · API v1")
            }
        } catch (e: Exception) {
            Result.failure(Exception("Could not reach PocketPrint server: ${e.message ?: "network error"}", e))
        }
    }

    /**
     * Pairs the Android device with the VPS using the user-configured device password.
     * Returns a revocable device authorization token.
     */
    suspend fun pairDevice(
        serverUrl: String,
        deviceId: String,
        deviceName: String,
        devicePassword: String,
        printerModel: String,
        vidPid: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val endpoint = "$serverUrl/api/devices/pair"
            val bodyJson = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("devicePassword", devicePassword)
                put("printerModel", printerModel)
                put("vidPid", vidPid)
                put("appVersion", "1.0.0")
            }

            val request = Request.Builder()
                .url(endpoint)
                .post(bodyJson.toString().toRequestBody(jsonMedia))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val respObj = JSONObject(bodyStr)
                    val token = respObj.getString("deviceToken")
                    DiagnosticLogger.log("AGENT_NET", "Device successfully paired! Token received.")
                    Result.success(token)
                } else {
                    val errMsg = extractError(bodyStr, "Pairing failed: HTTP ${response.code}")
                    Result.failure(Exception(errMsg))
                }
            }
        } catch (e: Exception) {
            DiagnosticLogger.log("AGENT_NET", "Pairing error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Sends periodic device heartbeat to the VPS to maintain online status and receive pending count.
     */
    suspend fun sendHeartbeat(
        serverUrl: String,
        deviceId: String,
        deviceToken: String,
        isPrinterConnected: Boolean,
        printerStatus: String,
        activeProfile: String
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val endpoint = "$serverUrl/api/devices/$deviceId/heartbeat"
            val bodyJson = JSONObject().apply {
                put("isPrinterConnected", isPrinterConnected)
                put("printerStatus", printerStatus)
                put("activeProfile", activeProfile)
                put("timestamp", System.currentTimeMillis())
            }

            val request = Request.Builder()
                .url(endpoint)
                .header("X-Device-Token", deviceToken)
                .post(bodyJson.toString().toRequestBody(jsonMedia))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val resp = JSONObject(bodyStr)
                    val pending = resp.optInt("pendingJobsCount", 0)
                    Result.success(pending)
                } else {
                    Result.failure(Exception("Heartbeat failed: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Polls the next queued job assigned to this device.
     */
    suspend fun fetchNextJob(
        serverUrl: String,
        deviceId: String,
        deviceToken: String
    ): Result<RemotePrintJob?> = withContext(Dispatchers.IO) {
        try {
            val endpoint = "$serverUrl/api/device/jobs/next"
            val request = Request.Builder()
                .url(endpoint)
                .header("X-Device-Token", deviceToken)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 204 || response.code == 404) {
                    return@withContext Result.success(null)
                }
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful && bodyStr.isNotBlank()) {
                    val json = JSONObject(bodyStr)
                    val job = RemotePrintJob.fromJson(json)
                    Result.success(job)
                } else {
                    Result.failure(Exception("Failed to fetch next job: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Downloads the original printable file for a job directly to app-private cache storage.
     */
    suspend fun downloadJobDocument(
        serverUrl: String,
        jobId: String,
        deviceToken: String,
        leaseToken: String,
        expectedSha256: String,
        destinationFile: File,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val endpoint = "$serverUrl/api/device/jobs/$jobId/download"
            val request = Request.Builder()
                .url(endpoint)
                .header("X-Device-Token", deviceToken)
                .header("X-Lease-Token", leaseToken)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("File download failed: HTTP ${response.code}"))
                }
                val body = response.body ?: return@withContext Result.failure(Exception("Empty download response"))
                val totalBytes = body.contentLength()
                val partFile = File(destinationFile.parentFile, destinationFile.name + ".part")
                val digest = MessageDigest.getInstance("SHA-256")

                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(16384)
                        var bytesRead: Int
                        var downloaded = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            digest.update(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            onProgress(downloaded, totalBytes)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }
                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (expectedSha256.isNotBlank() && !actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    partFile.delete()
                    return@withContext Result.failure(Exception("Downloaded document SHA-256 does not match the server record"))
                }
                if (destinationFile.exists() && !destinationFile.delete()) {
                    partFile.delete()
                    return@withContext Result.failure(Exception("Unable to replace cached document"))
                }
                if (!partFile.renameTo(destinationFile)) {
                    try {
                        partFile.inputStream().use { input ->
                            FileOutputStream(destinationFile).use { output ->
                                input.copyTo(output)
                                output.flush()
                                output.fd.sync()
                            }
                        }
                        partFile.delete()
                    } catch (copyErr: Exception) {
                        partFile.delete()
                        return@withContext Result.failure(Exception("Unable to finalize downloaded document: ${copyErr.message}"))
                    }
                }
                if (!destinationFile.exists() || destinationFile.length() <= 0L) {
                    return@withContext Result.failure(Exception("Downloaded file is empty or inaccessible: ${destinationFile.absolutePath}"))
                }
                Result.success(destinationFile)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Data classes for structured lease and progress responses.
     */
    data class LeaseRenewalResponse(
        val isSuccess: Boolean,
        val expiresAt: String? = null,
        val cancelRequested: Boolean = false,
        val isLeaseLost: Boolean = false,
        val errorMessage: String? = null
    )

    data class ProgressReportResponse(
        val isSuccess: Boolean,
        val canonicalState: String? = null,
        val cancelRequested: Boolean = false,
        val isLeaseLost: Boolean = false,
        val errorMessage: String? = null
    )

    /**
     * Explicitly renews the active job lease on the VPS.
     * Prevents long-running printing operations from losing their lease token.
     * Returns whether cancellation was requested by the user.
     */
    suspend fun renewLease(
        serverUrl: String,
        jobId: String,
        deviceToken: String,
        leaseToken: String
    ): LeaseRenewalResponse = withContext(Dispatchers.IO) {
        try {
            val endpoint = "$serverUrl/api/device/jobs/$jobId/lease/renew"
            val request = Request.Builder()
                .url(endpoint)
                .header("X-Device-Token", deviceToken)
                .header("X-Lease-Token", leaseToken)
                .post("{}".toRequestBody(jsonMedia))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val json = JSONObject(bodyStr)
                    LeaseRenewalResponse(
                        isSuccess = true,
                        expiresAt = json.optString("leaseExpiresAt").takeIf { it.isNotEmpty() },
                        cancelRequested = json.optBoolean("cancelRequested", false)
                    )
                } else if (response.code == 401 || response.code == 404 || response.code == 409) {
                    LeaseRenewalResponse(
                        isSuccess = false,
                        isLeaseLost = true,
                        errorMessage = extractError(bodyStr, "Lease expired or invalid (HTTP ${response.code})")
                    )
                } else {
                    LeaseRenewalResponse(
                        isSuccess = false,
                        isLeaseLost = false,
                        errorMessage = extractError(bodyStr, "Lease renewal failed: HTTP ${response.code}")
                    )
                }
            }
        } catch (e: Exception) {
            LeaseRenewalResponse(
                isSuccess = false,
                isLeaseLost = false,
                errorMessage = e.message ?: "Network error during lease renewal"
            )
        }
    }

    /**
     * Reports live execution progress back to the VPS.
     */
    suspend fun updateJobProgress(
        serverUrl: String,
        jobId: String,
        deviceToken: String,
        leaseToken: String,
        status: PrintJobState,
        progressPercent: Int,
        message: String? = null,
        idempotencyKey: String? = null
    ): ProgressReportResponse = withContext(Dispatchers.IO) {
        try {
            val endpoint = "$serverUrl/api/device/jobs/$jobId/progress"
            val bodyJson = JSONObject().apply {
                put("status", status.name)
                put("progressPercent", progressPercent)
                put("message", message ?: status.displayName)
                if (!idempotencyKey.isNullOrBlank()) {
                    put("idempotencyKey", idempotencyKey)
                }
            }

            val request = Request.Builder()
                .url(endpoint)
                .header("X-Device-Token", deviceToken)
                .header("X-Lease-Token", leaseToken)
                .post(bodyJson.toString().toRequestBody(jsonMedia))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val json = JSONObject(bodyStr)
                    ProgressReportResponse(
                        isSuccess = true,
                        canonicalState = json.optString("canonicalState").takeIf { it.isNotEmpty() },
                        cancelRequested = json.optBoolean("cancelRequested", false)
                    )
                } else if (response.code == 401 || response.code == 404 || response.code == 409) {
                    ProgressReportResponse(
                        isSuccess = false,
                        isLeaseLost = true,
                        errorMessage = extractError(bodyStr, "Progress update rejected: lease expired or revoked (HTTP ${response.code})")
                    )
                } else {
                    ProgressReportResponse(
                        isSuccess = false,
                        isLeaseLost = false,
                        errorMessage = extractError(bodyStr, "Progress update failed: HTTP ${response.code}")
                    )
                }
            }
        } catch (e: Exception) {
            ProgressReportResponse(
                isSuccess = false,
                isLeaseLost = false,
                errorMessage = e.message ?: "Network error updating progress"
            )
        }
    }

    /**
     * Reports page-level event to VPS for fine-grained transmission tracking.
     */
    suspend fun reportPageEvent(
        serverUrl: String,
        jobId: String,
        deviceToken: String,
        leaseToken: String,
        eventId: String,
        eventType: String,
        documentPageNumber: Int,
        scheduledPageNumber: Int,
        copyNumber: Int,
        pageState: String,
        message: String? = null,
        errorCode: String? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val endpoint = "$serverUrl/api/device/jobs/$jobId/pages/events"
            val bodyJson = JSONObject().apply {
                put("eventId", eventId)
                put("eventType", eventType)
                put("documentPageNumber", documentPageNumber)
                put("scheduledPageNumber", scheduledPageNumber)
                put("copyNumber", copyNumber)
                put("pageState", pageState)
                if (!message.isNullOrBlank()) put("message", message)
                if (!errorCode.isNullOrBlank()) put("errorCode", errorCode)
            }

            val request = Request.Builder()
                .url(endpoint)
                .header("X-Device-Token", deviceToken)
                .header("X-Lease-Token", leaseToken)
                .post(bodyJson.toString().toRequestBody(jsonMedia))
                .build()

            client.newCall(request).execute().use { response ->
                Result.success(response.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Acknowledges user cancellation from the agent.
     * Reports [uncertain] = true if USB data was already transmitted to the printer.
     */
    suspend fun acknowledgeCancel(
        serverUrl: String,
        jobId: String,
        deviceToken: String,
        leaseToken: String,
        uncertain: Boolean = false,
        message: String? = null,
        idempotencyKey: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val endpoint = "$serverUrl/api/device/jobs/$jobId/cancel/ack?uncertain=$uncertain"
            val bodyJson = JSONObject().apply {
                put("uncertain", uncertain)
                if (!message.isNullOrBlank()) put("message", message)
                if (!idempotencyKey.isNullOrBlank()) put("idempotencyKey", idempotencyKey)
            }

            val request = Request.Builder()
                .url(endpoint)
                .header("X-Device-Token", deviceToken)
                .header("X-Lease-Token", leaseToken)
                .post(bodyJson.toString().toRequestBody(jsonMedia))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val json = JSONObject(bodyStr)
                    Result.success(json.optString("status", "CANCELLED"))
                } else {
                    Result.failure(Exception(extractError(bodyStr, "Cancel acknowledgement failed: HTTP ${response.code}")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reports successful completion of a job after complete USB stream transmission.
     */
    suspend fun completeJob(
        serverUrl: String,
        jobId: String,
        deviceToken: String,
        leaseToken: String,
        bytesTransferred: Long,
        idempotencyKey: String? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val endpoint = "$serverUrl/api/device/jobs/$jobId/complete"
            val bodyJson = JSONObject().apply {
                put("bytesTransferred", bytesTransferred)
                put("completedAt", System.currentTimeMillis())
                if (!idempotencyKey.isNullOrBlank()) put("idempotencyKey", idempotencyKey)
            }

            val request = Request.Builder()
                .url(endpoint)
                .header("X-Device-Token", deviceToken)
                .header("X-Lease-Token", leaseToken)
                .post(bodyJson.toString().toRequestBody(jsonMedia))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(Exception(extractError(bodyStr, "Completion report failed: HTTP ${response.code}")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reports a failed print job with explicit failure classification.
     */
    suspend fun failJob(
        serverUrl: String,
        jobId: String,
        deviceToken: String,
        leaseToken: String,
        errorMessage: String,
        uncertain: Boolean = false,
        idempotencyKey: String? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val endpoint = "$serverUrl/api/device/jobs/$jobId/fail"
            val bodyJson = JSONObject().apply {
                put("failureReason", errorMessage)
                put("failedAt", System.currentTimeMillis())
                put("uncertain", uncertain)
                if (!idempotencyKey.isNullOrBlank()) put("idempotencyKey", idempotencyKey)
            }

            val request = Request.Builder()
                .url(endpoint)
                .header("X-Device-Token", deviceToken)
                .header("X-Lease-Token", leaseToken)
                .post(bodyJson.toString().toRequestBody(jsonMedia))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(Exception(extractError(bodyStr, "Failure report failed: HTTP ${response.code}")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractError(body: String, fallback: String): String {
        return try {
            val json = JSONObject(body)
            json.optString("detail", json.optString("message", fallback))
        } catch (e: Exception) {
            fallback
        }
    }
}
