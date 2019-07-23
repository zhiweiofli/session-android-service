package org.whispersystems.signalservice.loki.api

import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.functional.map
import nl.komponents.kovenant.task
import okhttp3.*
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.internal.util.JsonUtil
import org.whispersystems.signalservice.loki.utilities.prettifiedDescription
import java.io.IOException
import java.security.SecureRandom

internal class LokiSwarmAPI(private val database: LokiAPIDatabaseProtocol) {

    companion object {
        internal var failureCount: MutableMap<LokiAPITarget, Int> = mutableMapOf()

        // region Settings
        private val minimumSnodeCount = 2
        private val targetSnodeCount = 3
        internal val failureThreshold = 2
        // endregion

        // region Clearnet Setup
        private val seedNodePool: Set<String> = setOf( "http://3.104.19.14:22023", "http://13.238.53.205:38157", "http://imaginary.stream:38157" )
        internal var randomSnodePool: MutableSet<LokiAPITarget> = mutableSetOf()
        // endregion
    }

    // region Caching
    internal fun dropIfNeeded(target: LokiAPITarget, hexEncodedPublicKey: String) {
        val swarm = database.getSwarmCache(hexEncodedPublicKey)?.toMutableSet()
        if (swarm != null && swarm.contains(target)) {
            swarm.remove(target)
            database.setSwarmCache(hexEncodedPublicKey, swarm)
        }
    }
    // endregion

    // region Internal API
    private fun getRandomSnode(): Promise<LokiAPITarget, Exception> {
        if (randomSnodePool.isEmpty()) {
            val target = seedNodePool.random()
            val url = "$target/json_rpc"
            Log.d("Loki", "Invoking get_service_nodes on $target.")
            val body = RequestBody.create(MediaType.get("application/json"), "{ \"method\" : \"get_service_nodes\" }")
            val request = Request.Builder().url(url).post(body)
            val connection = OkHttpClient()
            val deferred = deferred<LokiAPITarget, Exception>()
            connection.newCall(request.build()).enqueue(object : Callback {

                override fun onResponse(call: Call, response: Response) {
                    when (response.code()) {
                        200 -> {
                            val bodyAsString = response.body()!!.string()
                            @Suppress("NAME_SHADOWING") val body = JsonUtil.fromJson(bodyAsString, Map::class.java)
                            val intermediate = body?.get("result") as? Map<*, *>
                            val rawTargets = intermediate?.get("service_node_states") as? List<*>
                            if (rawTargets != null) {
                                randomSnodePool = rawTargets.mapNotNull { rawTarget ->
                                    val rawTargetAsJSON = rawTarget as? Map<*, *>
                                    val address = rawTargetAsJSON?.get("public_ip") as? String
                                    val port = rawTargetAsJSON?.get("storage_port") as? Int
                                    if (address != null && port != null && address != "0.0.0.0") {
                                        LokiAPITarget("https://$address", port)
                                    } else {
                                        Log.d("Loki", "Failed to update random snode pool from: ${rawTarget?.prettifiedDescription()}.")
                                        null
                                    }
                                }.toMutableSet()
                                deferred.resolve(randomSnodePool.random())
                            } else {
                                Log.d("Loki", "Failed to update random snode pool from: ${(rawTargets as List<*>?)?.prettifiedDescription()}.")
                                deferred.reject(LokiAPI.Error.Generic)
                            }
                        }
                        else -> {
                            Log.d("Loki", "Couldn't reach $target.")
                            deferred.reject(LokiAPI.Error.Generic)
                        }
                    }
                }

                override fun onFailure(call: Call, exception: IOException) {
                    deferred.reject(exception)
                }
            })
            return deferred.promise
        } else {
            return task {
                randomSnodePool.random()
            }
        }
    }

    internal fun getSwarm(hexEncodedPublicKey: String): Promise<Set<LokiAPITarget>, Exception> {
        val cachedSwarm = database.getSwarmCache(hexEncodedPublicKey)
        if (cachedSwarm != null && cachedSwarm.size >= minimumSnodeCount) {
            val cachedSwarmCopy = mutableSetOf<LokiAPITarget>() // Workaround for a Kotlin compiler issue
            cachedSwarmCopy.addAll(cachedSwarm)
            return task { cachedSwarmCopy }
        } else {
            val parameters = mapOf( "pubKey" to hexEncodedPublicKey )
            return getRandomSnode().bind {
                LokiAPI(hexEncodedPublicKey, database).invoke(LokiAPITarget.Method.GetSwarm, it, hexEncodedPublicKey, parameters)
            }.map {
                parseTargets(it).toSet()
            }.success {
                database.setSwarmCache(hexEncodedPublicKey, it)
            }
        }
    }
    // endregion

    // region Public API
    internal fun getSingleTargetSnode(hexEncodedPublicKey: String): Promise<LokiAPITarget, Exception> {
        // SecureRandom() should be cryptographically secure
        return getSwarm(hexEncodedPublicKey).map { it.shuffled(SecureRandom()).random() }
    }

    internal fun getTargetSnodes(hexEncodedPublicKey: String): Promise<List<LokiAPITarget>, Exception> {
        // SecureRandom() should be cryptographically secure
        return getSwarm(hexEncodedPublicKey).map { it.shuffled(SecureRandom()).take(targetSnodeCount) }
    }
    // endregion

    // region Parsing
    private fun parseTargets(rawResponse: Any): List<LokiAPITarget> {
        val json = rawResponse as? Map<*, *>
        val rawSnodes = json?.get("snodes") as? List<*>
        if (rawSnodes != null) {
            return rawSnodes.mapNotNull { rawSnode ->
                val rawSnodeAsJSON = rawSnode as? Map<*, *>
                val address = rawSnodeAsJSON?.get("ip") as? String
                val portAsString = rawSnodeAsJSON?.get("port") as? String
                val port = portAsString?.toInt()
                if (address != null && port != null && address != "0.0.0.0") {
                    LokiAPITarget("https://$address", port)
                } else {
                    Log.d("Loki", "Failed to parse target from: ${rawSnode?.prettifiedDescription()}.")
                    null
                }
            }
        } else {
            Log.d("Loki", "Failed to parse targets from: ${rawResponse.prettifiedDescription()}.")
            return listOf()
        }
    }
    // endregion
}