package com.bigrocket.service

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Client-side HTTP Range downloader.
 *
 * A single logical file is split into independent byte ranges. Every range is requested
 * with HTTP Range and is sent through a socket factory owned by one physical Android Network,
 * so Wi-Fi and Cellular use separate uplinks without requiring a server-side bonding endpoint.
 *
 * This is intentionally a downloader API, not transparent VPN acceleration: HTTPS payloads
 * belonging to arbitrary third-party applications cannot be rewritten into Range requests by
 * BigRocket without terminating TLS.
 */
class HttpRangeDownloadEngine {

    data class Result(
        val totalBytes: Long,
        val chunkCount: Int,
        val wifiBytes: Long,
        val cellularBytes: Long
    )

    data class Config(
        val chunkSizeBytes: Long = 4L * 1024L * 1024L,
        val maxConcurrentRequests: Int = 4,
        val connectTimeoutMs: Long = 10_000L,
        val readTimeoutMs: Long = 30_000L,
        val writeTimeoutMs: Long = 10_000L
    ) {
        init {
            require(chunkSizeBytes >= MIN_CHUNK_SIZE_BYTES)
            require(maxConcurrentRequests >= 1)
        }
    }

    private data class Chunk(
        val index: Int,
        val start: Long,
        val endInclusive: Long,
        val path: Path
    ) {
        val length: Long get() = endInclusive - start + 1L
    }

    private enum class Path {
        WIFI,
        CELLULAR
    }

    private data class PhysicalPath(
        val kind: Path,
        val network: Network,
        val weight: Int,
        val client: OkHttpClient
    )

    /**
     * Downloads [url] to [destination].
     *
     * [wifiNetwork] and [cellularNetwork] are the physical Android networks to use. A null path
     * is excluded. [weights] is the already-authoritative BigRocket routing share; this method
     * does not recalculate or override it.
     */
    suspend fun download(
        url: String,
        destination: File,
        wifiNetwork: Network?,
        cellularNetwork: Network?,
        weights: NetworkWeights = DynamicWeightCalculator.currentWeights(),
        config: Config = Config(),
        progress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Result = withContext(Dispatchers.IO) {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "Only HTTP(S) URLs are supported"
        }

        val paths = buildPaths(
            wifiNetwork = wifiNetwork,
            cellularNetwork = cellularNetwork,
            weights = weights,
            config = config
        )
        if (paths.isEmpty()) throw IOException("No physical network is available")

        val metadataPath = paths.maxBy { it.weight }
        val metadata = fetchMetadata(url, metadataPath.client)

        val totalBytes = metadata.totalBytes
        if (totalBytes == 0L) {
            destination.parentFile?.mkdirs()
            RandomAccessFile(destination, "rw").use { it.setLength(0L) }
            return@withContext Result(0L, 0, 0L, 0L)
        }

        val chunks = buildChunks(totalBytes, config.chunkSizeBytes, paths)
        destination.parentFile?.mkdirs()

        RandomAccessFile(destination, "rw").use { file ->
            file.setLength(totalBytes)

            val fileLock = Any()
            val semaphore = Semaphore(config.maxConcurrentRequests)
            val completed = LongArray(1)
            val wifiBytes = java.util.concurrent.atomic.AtomicLong(0L)
            val cellularBytes = java.util.concurrent.atomic.AtomicLong(0L)

            coroutineScope {
                chunks.map { chunk ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val usedPath = downloadChunkWithFailover(
                                url = url,
                                chunk = chunk,
                                destination = file,
                                fileLock = fileLock,
                                paths = paths,
                                validator = metadata.validator
                            )
                            val bytes = usedPath.second
                            if (usedPath.first == Path.WIFI) {
                                wifiBytes.addAndGet(bytes)
                            } else {
                                cellularBytes.addAndGet(bytes)
                            }
                            synchronized(completed) {
                                completed[0] += bytes
                                progress?.invoke(completed[0], totalBytes)
                            }
                            bytes
                        }
                    }
                }.awaitAll()
            }
        }

        Result(
            totalBytes = totalBytes,
            chunkCount = chunks.size,
            wifiBytes = wifiBytes.get(),
            cellularBytes = cellularBytes.get()
        )
    }

    private fun buildPaths(
        wifiNetwork: Network?,
        cellularNetwork: Network?,
        weights: NetworkWeights,
        config: Config
    ): List<PhysicalPath> {
        val result = ArrayList<PhysicalPath>(2)
        if (wifiNetwork != null && weights.wifiWeight > 0) {
            result += PhysicalPath(
                Path.WIFI,
                wifiNetwork,
                weights.wifiWeight,
                createClient(wifiNetwork, config)
            )
        }
        if (cellularNetwork != null && weights.cellularWeight > 0) {
            result += PhysicalPath(
                Path.CELLULAR,
                cellularNetwork,
                weights.cellularWeight,
                createClient(cellularNetwork, config)
            )
        }

        // A live physical path must never be excluded merely because its current share is zero
        // when it is the only available path.
        if (result.isEmpty()) {
            wifiNetwork?.let {
                result += PhysicalPath(Path.WIFI, it, 100, createClient(it, config))
            }
            cellularNetwork?.let {
                if (result.isEmpty()) {
                    result += PhysicalPath(Path.CELLULAR, it, 100, createClient(it, config))
                }
            }
        }
        return result
    }

    private fun createClient(network: Network, config: Config): OkHttpClient =
        OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> =
                    network.getAllByName(hostname).toList()
            })
            .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(config.writeTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .build()

    private data class Metadata(
        val totalBytes: Long,
        val validator: String?
    )

    private fun fetchMetadata(url: String, client: OkHttpClient): Metadata {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .header("Accept-Encoding", "identity")
            .header("Connection", "close")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 206) {
                val total = parseTotalFromContentRange(response.header("Content-Range"))
                if (total <= 0L) throw IOException("Server returned an invalid Content-Range")
                return Metadata(total, response.validator())
            }

            if (response.code == 416) {
                val total = parseUnsatisfiedTotal(response.header("Content-Range"))
                if (total == 0L) return Metadata(0L, response.validator())
            }

            throw RangeUnsupportedException(
                "Server did not honor HTTP Range for metadata (HTTP ${response.code})"
            )
        }
    }

    private fun downloadChunkWithFailover(
        url: String,
        chunk: Chunk,
        destination: RandomAccessFile,
        fileLock: Any,
        paths: List<PhysicalPath>,
        validator: String?
    ): Pair<Path, Long> {
        val preferred = paths.firstOrNull { it.kind == chunk.path }
        val fallback = paths.firstOrNull { it.kind != chunk.path }
        val candidates = listOfNotNull(preferred, fallback)

        var lastError: Exception? = null
        for (path in candidates) {
            try {
                downloadChunk(url, chunk, destination, fileLock, path.client, validator)
                return path.kind to chunk.length
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw IOException(
            "Range ${chunk.start}-${chunk.endInclusive} failed on all available paths",
            lastError
        )
    }

    private fun downloadChunk(
        url: String,
        chunk: Chunk,
        destination: RandomAccessFile,
        fileLock: Any,
        client: OkHttpClient,
        validator: String?
    ) {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Range", "bytes=${chunk.start}-${chunk.endInclusive}")
            .header("Accept-Encoding", "identity")
            .header("Connection", "close")
            .get()

        validator?.let {
            requestBuilder.header("If-Range", it)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code != 206) {
                throw IOException(
                    "Range ${chunk.start}-${chunk.endInclusive} was not honored (HTTP ${response.code})"
                )
            }

            val contentRange = parseContentRange(response.header("Content-Range"))
            if (contentRange.start != chunk.start ||
                contentRange.endInclusive != chunk.endInclusive
            ) {
                throw IOException(
                    "Server returned unexpected range ${contentRange.start}-${contentRange.endInclusive}"
                )
            }

            val body = response.body ?: throw IOException("Empty HTTP response body")
            if (body.contentLength() >= 0L && body.contentLength() != chunk.length) {
                throw IOException(
                    "Range body length ${body.contentLength()} != expected ${chunk.length}"
                )
            }

            body.byteStream().use { input ->
                var written = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (written < chunk.length) {
                    val wanted = minOf(buffer.size.toLong(), chunk.length - written).toInt()
                    val read = input.read(buffer, 0, wanted)
                    if (read < 0) break
                    if (read == 0) continue

                    synchronized(fileLock) {
                        destination.seek(chunk.start + written)
                        destination.write(buffer, 0, read)
                    }
                    written += read
                }

                if (written != chunk.length) {
                    throw IOException(
                        "Range body ended after $written bytes; expected ${chunk.length}"
                    )
                }
            }
        }
    }

    private fun buildChunks(
        totalBytes: Long,
        chunkSizeBytes: Long,
        paths: List<PhysicalPath>
    ): List<Chunk> {
        val count = ((totalBytes + chunkSizeBytes - 1L) / chunkSizeBytes).toInt()
        if (count == 1 || paths.size == 1) {
            val only = paths.maxBy { it.weight }.kind
            return (0 until count).map { index ->
                val start = index * chunkSizeBytes
                Chunk(
                    index = index,
                    start = start,
                    endInclusive = minOf(totalBytes - 1L, start + chunkSizeBytes - 1L),
                    path = only
                )
            }
        }

        val wifi = paths.firstOrNull { it.kind == Path.WIFI }
        val cellular = paths.firstOrNull { it.kind == Path.CELLULAR }
        val wifiCount = ((count * (wifi?.weight ?: 0)) / 100.0)
            .roundToInt()
            .coerceIn(1, count - 1)

        val assignments = ArrayList<Path>(count)
        repeat(wifiCount) { assignments += Path.WIFI }
        repeat(count - wifiCount) { assignments += Path.CELLULAR }

        // Distribute assignments over chunk indices instead of placing all Wi-Fi ranges first.
        val ordered = ArrayList<Path>(count)
        var w = 0
        var c = wifiCount
        for (index in 0 until count) {
            val wifiTargetBefore = ((index + 1) * wifiCount.toDouble() / count).roundToInt()
            if (wifiTargetBefore > w) {
                ordered += Path.WIFI
                w++
            } else {
                ordered += Path.CELLULAR
                c--
            }
        }

        // Defensive fallback: the weighted planner above always emits exactly [count] entries.
        if (ordered.size != count) return assignments

        return ordered.mapIndexed { index, path ->
            val start = index * chunkSizeBytes
            Chunk(
                index = index,
                start = start,
                endInclusive = minOf(totalBytes - 1L, start + chunkSizeBytes - 1L),
                path = path
            )
        }
    }

    private data class ParsedContentRange(
        val start: Long,
        val endInclusive: Long,
        val total: Long
    )

    private fun parseContentRange(value: String?): ParsedContentRange {
        val match = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""").matchEntire(value.orEmpty())
            ?: throw IOException("Invalid Content-Range: $value")
        val start = match.groupValues[1].toLong()
        val end = match.groupValues[2].toLong()
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLong() ?: -1L
        if (start < 0L || end < start || total < 0L || end >= total) {
            throw IOException("Invalid Content-Range: $value")
        }
        return ParsedContentRange(start, end, total)
    }

    private fun parseTotalFromContentRange(value: String?): Long =
        parseContentRange(value).total

    private fun parseUnsatisfiedTotal(value: String?): Long {
        val match = Regex("""bytes\s+\*/(\d+)""").matchEntire(value.orEmpty())
            ?: return -1L
        return match.groupValues[1].toLong()
    }

    private fun Response.validator(): String? =
        header("ETag")?.takeIf { it.isNotBlank() } ?: header("Last-Modified")?.takeIf { it.isNotBlank() }

    companion object {
        private const val MIN_CHUNK_SIZE_BYTES = 64L * 1024L
    }

    class RangeUnsupportedException(message: String) : IOException(message)
}
