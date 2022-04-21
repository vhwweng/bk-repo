package com.tencent.bkrepo.generic.service

import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.stream.CompositeOutputStream
import com.tencent.bkrepo.common.artifact.api.ArtifactFile
import com.tencent.bkrepo.common.artifact.api.ArtifactInfo
import com.tencent.bkrepo.common.artifact.exception.ArtifactNotFoundException
import com.tencent.bkrepo.common.artifact.exception.NodeNotFoundException
import com.tencent.bkrepo.common.artifact.manager.StorageManager
import com.tencent.bkrepo.common.artifact.message.ArtifactMessageCode
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactContext
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactDownloadContext
import com.tencent.bkrepo.common.artifact.repository.core.ArtifactService
import com.tencent.bkrepo.common.artifact.resolve.file.ArtifactFileFactory
import com.tencent.bkrepo.common.artifact.resolve.file.chunk.ChunkedFileOutputStream
import com.tencent.bkrepo.common.artifact.stream.FileArtifactInputStream
import com.tencent.bkrepo.common.artifact.util.http.IOExceptionUtils
import com.tencent.bkrepo.common.bksync.BkSync
import com.tencent.bkrepo.common.bksync.BlockInputStream
import com.tencent.bkrepo.common.bksync.ByteArrayBlockInputStream
import com.tencent.bkrepo.common.bksync.ChecksumIndex
import com.tencent.bkrepo.common.bksync.FileBlockInputStream
import com.tencent.bkrepo.common.security.util.SecurityUtils
import com.tencent.bkrepo.common.storage.core.StorageProperties
import com.tencent.bkrepo.common.storage.credentials.StorageCredentials
import com.tencent.bkrepo.common.storage.monitor.Throughput
import com.tencent.bkrepo.generic.artifact.GenericArtifactInfo
import com.tencent.bkrepo.generic.config.GenericProperties
import com.tencent.bkrepo.generic.dao.SignFileDao
import com.tencent.bkrepo.generic.model.TSignFile
import com.tencent.bkrepo.repository.api.NodeClient
import com.tencent.bkrepo.repository.api.RepositoryClient
import com.tencent.bkrepo.repository.pojo.node.NodeDetail
import com.tencent.bkrepo.repository.pojo.node.service.NodeCreateRequest
import com.tencent.bkrepo.repository.pojo.repo.RepositoryDetail
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.system.measureNanoTime

/**
 * 增量同步实现类
 *
 * */
@Service
class DeltaSyncService(
    genericProperties: GenericProperties,
    val storageManager: StorageManager,
    val nodeClient: NodeClient,
    val signFileDao: SignFileDao,
    val repositoryClient: RepositoryClient,
    val storageProperties: StorageProperties,
    val taskExecutor: ThreadPoolTaskScheduler
) : ArtifactService() {

    private val deltaProperties = genericProperties.delta
    private val blockSize = deltaProperties.blockSize.toBytes().toInt()
    private val patchTimeout = deltaProperties.patchTimeout.toMillis()
    val signFileProjectId = deltaProperties.projectId!!
    val signFileRepoName = deltaProperties.repoName!!
    val signRepo: RepositoryDetail by lazy {
        repositoryClient.getRepoDetail(signFileProjectId, signFileRepoName).data
            ?: throw ErrorCodeException(ArtifactMessageCode.REPOSITORY_NOT_FOUND, signFileRepoName)
    }
    private val fileSizeThreshold = storageProperties.receive.fileSizeThreshold.toBytes()

    /**
     * 签名文件
     * */
    fun sign() {
        with(ArtifactContext()) {
            val node = nodeClient.getNodeDetail(projectId, repoName, artifactInfo.getArtifactFullPath()).data
            if (node == null || node.folder) {
                throw NodeNotFoundException(artifactInfo.getArtifactFullPath())
            }
            // 查看是否已有sign文件，没有则生成。
            val sha256 = node.sha256!!
            signFileDao.findByDetail(sha256, blockSize)?.let {
                val artifactInfo = GenericArtifactInfo(it.projectId, it.repoName, it.fullPath)
                val downloadContext = ArtifactDownloadContext(repo = signRepo, artifact = artifactInfo)
                repository.download(downloadContext)
                return
            }
            // 计算出需要返回的大小
            val length = ceil(node.size.toDouble() / blockSize) * ChecksumIndex.CHECKSUM_SIZE
            response.setContentLength(length.toInt())
            val chunkedArtifactFile = ArtifactFileFactory.buildChunked()
            val chunkedFileOutputStream = ChunkedFileOutputStream(chunkedArtifactFile)
            val outputStream = CompositeOutputStream(chunkedFileOutputStream, response.outputStream).buffered()
            outputStream.use {
                val artifactInputStream = storageManager.loadArtifactInputStream(node, storageCredentials)
                    ?: throw ArtifactNotFoundException("file[$sha256] not found in ${storageCredentials?.key}")
                val nanoTime = measureNanoTime {
                    val bkSync = BkSync(blockSize)
                    artifactInputStream.buffered().use { bkSync.checksum(artifactInputStream, outputStream) }
                }
                val throughput = Throughput(chunkedArtifactFile.getSize(), nanoTime)
                logger.info("Success to generate artifact sign file [${node.fullPath}], $throughput.")
                outputStream.flush()
                chunkedArtifactFile.finish()
                saveSignFile(node, chunkedArtifactFile)
            }
        }
    }

    /**
     * 基于旧文件和增量数据进行合并文件
     * @param oldFilePath 旧文件仓库完整路径
     * */
    fun patch(oldFilePath: String, deltaFile: ArtifactFile): SseEmitter {
        with(ArtifactContext()) {
            val node = nodeClient.getNodeDetail(projectId, repoName, oldFilePath).data
            if (node == null || node.folder) {
                throw NodeNotFoundException(artifactInfo.getArtifactFullPath())
            }
            val hasCompleted = AtomicBoolean(false)
            val clientError = AtomicBoolean(false)
            val contentLength = request.contentLengthLong
            val emitter = SseEmitter(patchTimeout)
            emitter.onCompletion { hasCompleted.set(true) }
            val counterInputStream = CounterInputStream(deltaFile.getInputStream())
            val reportAction = { reportProcess(clientError, hasCompleted, counterInputStream, contentLength, emitter) }
            val ackFuture = taskExecutor.scheduleWithFixedDelay(reportAction, HEART_BEAT)
            val blockInputStream = getBlockInputStream(node, storageCredentials)
            val file = ArtifactFileFactory.buildBkSync(blockInputStream, counterInputStream, blockSize)
            taskExecutor.execute {
                try {
                    val nodeDetail = upload(file, storageCredentials, repositoryDetail, artifactInfo, userId)
                    val event = SseEmitter.event().name(PATCH_EVENT_TYPE_DATA)
                        .data(nodeDetail, MediaType.APPLICATION_JSON)
                    emitter.send(event)
                    emitter.complete()
                } catch (e: Exception) {
                    emitter.completeWithError(e)
                } finally {
                    blockInputStream.close()
                    ackFuture.cancel(true)
                }
            }
            return emitter
        }
    }

    /**
     * 上报进度
     * @param clientError 记录是否发生客户端错误
     * @param hasCompleted 记录是否完成
     * @param inputStream 可计数的输入流
     * @param contentLength 增量文件总长度
     * @param emitter 发送器
     * */
    private fun reportProcess(
        clientError: AtomicBoolean,
        hasCompleted: AtomicBoolean,
        inputStream: CounterInputStream,
        contentLength: Long,
        emitter: SseEmitter
    ) {
        try {
            if (clientError.get()) {
                logger.info("Client has error,ignore report process.")
                return
            }
            if (!hasCompleted.get()) {
                val process = String.format("%.2f", (inputStream.count.toFloat() / contentLength) * 100)
                val msg = "Current process $process%."
                val event = SseEmitter.event().name(PATCH_EVENT_TYPE_INFO).data(msg, MediaType.TEXT_PLAIN)
                emitter.send(event)
                logger.info(msg)
            } else {
                logger.info("Patch has already completed.")
            }
        } catch (e: IOException) {
            if (IOExceptionUtils.isClientBroken(e)) {
                clientError.set(true)
                return
            }
            logger.error("Send sse event failed.", e)
        }
    }

    /**
     * 上传文件
     * */
    private fun upload(
        file: ArtifactFile,
        storageCredentials: StorageCredentials?,
        repositoryDetail: RepositoryDetail,
        artifactInfo: ArtifactInfo,
        userId: String
    ): NodeDetail {
        val request = NodeCreateRequest(
            projectId = repositoryDetail.projectId,
            repoName = repositoryDetail.name,
            folder = false,
            fullPath = artifactInfo.getArtifactFullPath(),
            size = file.getSize(),
            sha256 = file.getFileSha256(),
            md5 = file.getFileMd5(),
            operator = userId,
            overwrite = true
        )
        return storageManager.storeArtifactFile(request, file, storageCredentials)
    }

    /**
     * 保存sign文件到指定仓库
     * @param nodeDetail 节点信息
     * @param file 节点sign文件
     * */
    private fun saveSignFile(nodeDetail: NodeDetail, file: ArtifactFile) {
        with(nodeDetail) {
            val signFileFullPath = "$projectId/$repoName/$blockSize/$fullPath$SUFFIX_SIGN"
            val artifactInfo = GenericArtifactInfo(signFileProjectId, signFileRepoName, signFileFullPath)
            upload(file, signRepo.storageCredentials, signRepo, artifactInfo, SecurityUtils.getUserId())
            val signFile = TSignFile(
                srcSha256 = sha256!!,
                projectId = signFileProjectId,
                repoName = signFileRepoName,
                fullPath = signFileFullPath,
                blockSize = blockSize,
                createdBy = SecurityUtils.getUserId(),
                createdDate = LocalDateTime.now()
            )
            signFileDao.save(signFile)
            logger.info("Success to save sign file[$signFileFullPath].")
        }
    }

    private fun getBlockInputStream(node: NodeDetail, storageCredentials: StorageCredentials?): BlockInputStream {
        val artifactInputStream = storageManager.loadArtifactInputStream(node, storageCredentials)
            ?: throw ArtifactNotFoundException("file[${node.sha256}] not found in ${storageCredentials?.key}")
        artifactInputStream.use {
            // 小于文件内存大小，则使用内存
            val name = node.sha256!!
            if (node.size <= fileSizeThreshold) {
                val dataOutput = ByteArrayOutputStream()
                artifactInputStream.copyTo(dataOutput)
                return ByteArrayBlockInputStream(dataOutput.toByteArray(), name)
            }
            // 本地cache
            if (artifactInputStream is FileArtifactInputStream) {
                return FileBlockInputStream(artifactInputStream.file, name)
            }
            // 远端网络流
            val file = ArtifactFileFactory.build(artifactInputStream, node.size).getFile()!!
            return FileBlockInputStream(file, name)
        }
    }

    private class CounterInputStream(
        val inputStream: InputStream
    ) : InputStream() {
        var count = 0L

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            return inputStream.read(b, off, len).apply {
                if (this > 0) {
                    count += this
                }
            }
        }

        override fun read(): Int {
            return inputStream.read().apply {
                if (this > 0) {
                    count += this
                }
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DeltaSyncService::class.java)
        private const val SUFFIX_SIGN = ".sign"
        private const val PATCH_EVENT_TYPE_INFO = "INFO"
        private const val PATCH_EVENT_TYPE_DATA = "DATA"

        // 3s patch 回复心跳时间，保持连接存活
        private const val HEART_BEAT = 3000L
    }
}