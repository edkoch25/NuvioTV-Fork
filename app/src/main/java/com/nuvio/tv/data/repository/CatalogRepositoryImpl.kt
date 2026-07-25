package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.net.URLEncoder
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi
) : CatalogRepository {
    companion object {
        private const val TAG = "CatalogRepository"

        // Short-TTL in-memory catalogue cache. Targets the profile-switch and
        // back-navigation cost: re-fetching an already-seen catalogue page over
        // the network on every visit is the dominant span when switching profiles
        // (~600 ms observed). Keyed on the fully-resolved catalogue URL, which
        // already encodes addon base, type, catalogId, skip and extraArgs, so two
        // requests share a key iff they would hit the same endpoint. TTL is short
        // enough that freshness is a non-issue for a browse session; the bound
        // caps memory on a heavy scroll.
        private const val CATALOG_CACHE_TTL_MS = 90_000L
        private const val CATALOG_CACHE_MAX_ENTRIES = 64

        private data class CacheEntry(val row: CatalogRow, val storedAtMs: Long)

        // access-ordered LinkedHashMap wrapped for LRU eviction; synchronized
        // because catalogue fetches run concurrently across rows.
        private val catalogCache: MutableMap<String, CacheEntry> =
            Collections.synchronizedMap(
                object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
                    override fun removeEldestEntry(eldest: Map.Entry<String, CacheEntry>): Boolean =
                        size > CATALOG_CACHE_MAX_ENTRIES
                }
            )
    }

    override fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean
    ): Flow<NetworkResult<CatalogRow>> = flow {
        emit(NetworkResult.Loading)

        val url = buildCatalogUrl(addonBaseUrl, type, catalogId, skip, extraArgs)

        // Cache hit within TTL: serve the stored row and skip the network entirely.
        // A stale entry is dropped so the fetch below refreshes it.
        val cached = catalogCache[url]
        if (cached != null) {
            if (System.currentTimeMillis() - cached.storedAtMs <= CATALOG_CACHE_TTL_MS) {
                Log.d(TAG, "Catalog cache hit type=$type catalogId=$catalogId skip=$skip url=$url")
                emit(NetworkResult.Success(cached.row))
                return@flow
            } else {
                catalogCache.remove(url)
            }
        }

        Log.d(
            TAG,
            "Fetching catalog addonId=$addonId addonName=$addonName type=$type catalogId=$catalogId skip=$skip skipStep=$skipStep supportsSkip=$supportsSkip url=$url"
        )

        when (val result = safeApiCall(context) { api.getCatalog(url) }) {
            is NetworkResult.Success -> {
                // Main review F17: on duplicate IDs keep the FIRST (catalogue
                // order is meaningful) but backfill visual/metadata fields the
                // first copy lacks from the dropped duplicate, so a richer
                // poster/background/description is never silently lost.
                val merged = LinkedHashMap<String, MetaPreview>(result.data.metas.size)
                result.data.metas.forEach { dto ->
                    val item = dto.toDomain(type, addonBaseUrl)
                    val prior = merged[item.id]
                    merged[item.id] = if (prior == null) {
                        item
                    } else {
                        prior.copy(
                            poster = prior.poster ?: item.poster,
                            background = prior.background ?: item.background,
                            logo = prior.logo ?: item.logo,
                            description = prior.description ?: item.description,
                            releaseInfo = prior.releaseInfo ?: item.releaseInfo,
                            imdbRating = prior.imdbRating ?: item.imdbRating,
                            landscapePoster = prior.landscapePoster ?: item.landscapePoster,
                            behaviorHints = prior.behaviorHints ?: item.behaviorHints
                        )
                    }
                }
                val items = merged.values.toList()
                Log.d(
                    TAG,
                    "Catalog fetch success addonId=$addonId type=$type catalogId=$catalogId items=${items.size}"
                )
                // Build 1: the addon's own declared TTL, against the local one.
                // declared=null means the addon sent no cacheMaxAge, in which
                // case the 90 s local TTL is the only policy in play.
                Log.i(
                    TAG,
                    "CATALOG_TTL declared=${result.data.cacheMaxAge} " +
                        "localMs=$CATALOG_CACHE_TTL_MS " +
                        "addonName=$addonName catalogId=$catalogId"
                )

                val catalogRow = CatalogRow(
                    addonId = addonId,
                    addonName = addonName,
                    addonBaseUrl = addonBaseUrl,
                    catalogId = catalogId,
                    catalogName = catalogName,
                    type = ContentType.fromString(type),
                    rawType = type,
                    items = items,
                    isLoading = false,
                    hasMore = supportsSkip && items.isNotEmpty(),
                    currentPage = if (skipStep > 0) skip / skipStep else 0,
                    supportsSkip = supportsSkip,
                    skipStep = skipStep,
                    nextSkip = if (supportsSkip && items.isNotEmpty()) skip + items.size else skip,
                    extraArgs = extraArgs
                )
                catalogCache[url] = CacheEntry(catalogRow, System.currentTimeMillis())
                emit(NetworkResult.Success(catalogRow))
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "Catalog fetch failed addonId=$addonId type=$type catalogId=$catalogId code=${result.code} message=${result.message} url=$url"
                )
                emit(result)
            }
            NetworkResult.Loading -> { /* Already emitted */ }
        }
    }

    private fun buildCatalogUrl(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): String {
        val trimmedBase = baseUrl.trimEnd('/')
        val queryStart = trimmedBase.indexOf('?')
        val basePath = if (queryStart >= 0) trimmedBase.substring(0, queryStart).trimEnd('/') else trimmedBase
        val baseQuery = if (queryStart >= 0) trimmedBase.substring(queryStart) else ""

        val catalogPath = if (extraArgs.isEmpty()) {
            if (skip > 0) {
                "$basePath/catalog/$type/$catalogId/skip=$skip.json"
            } else {
                "$basePath/catalog/$type/$catalogId.json"
            }
        } else {
            val allArgs = LinkedHashMap<String, String>()
            allArgs.putAll(extraArgs)

            if (!allArgs.containsKey("skip") && skip > 0) {
                allArgs["skip"] = skip.toString()
            }

            val encodedArgs = allArgs.entries.joinToString("&") { (key, value) ->
                "${encodeArg(key)}=${encodeArg(value)}"
            }

            "$basePath/catalog/$type/$catalogId/$encodedArgs.json"
        }

        return catalogPath + baseQuery
    }

    private fun encodeArg(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
