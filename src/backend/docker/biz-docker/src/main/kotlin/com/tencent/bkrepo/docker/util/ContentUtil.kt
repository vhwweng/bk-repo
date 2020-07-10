package com.tencent.bkrepo.docker.util

import com.fasterxml.jackson.databind.JsonNode
import com.tencent.bkrepo.common.api.util.JsonUtils
import com.tencent.bkrepo.docker.artifact.DockerArtifactRepo
import com.tencent.bkrepo.docker.constant.DOCKER_API_VERSION
import com.tencent.bkrepo.docker.constant.DOCKER_CONTENT_DIGEST
import com.tencent.bkrepo.docker.constant.DOCKER_DIGEST
import com.tencent.bkrepo.docker.constant.DOCKER_HEADER_API_VERSION
import com.tencent.bkrepo.docker.constant.EMPTYSTR
import com.tencent.bkrepo.docker.context.DownloadContext
import com.tencent.bkrepo.docker.context.RequestContext
import com.tencent.bkrepo.docker.manifest.ManifestType
import com.tencent.bkrepo.docker.model.DockerBlobInfo
import com.tencent.bkrepo.docker.model.DockerDigest
import com.tencent.bkrepo.docker.model.ManifestMetadata
import com.tencent.bkrepo.docker.response.DockerResponse
import org.apache.commons.io.IOUtils
import org.apache.commons.lang.StringUtils
import org.slf4j.LoggerFactory
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpHeaders.CONTENT_LENGTH
import org.springframework.http.HttpHeaders.CONTENT_TYPE
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import javax.xml.bind.DatatypeConverter

/**
 * docker content  utility
 * to get detail of manifest or blob
 * @author: owenlxu
 * @date: 2019-10-15
 */
class ContentUtil constructor(repo: DockerArtifactRepo) {

    val repo = repo

    companion object {
        private val logger = LoggerFactory.getLogger(ContentUtil::class.java)
        val EMPTY_BLOB_CONTENT: ByteArray = DatatypeConverter.parseHexBinary("1f8b080000096e8800ff621805a360148c5800080000ffff2eafb5ef00040000")

        fun isEmptyBlob(digest: DockerDigest): Boolean {
            return digest.toString() == emptyBlobDigest().toString()
        }

        fun emptyBlobDigest(): DockerDigest {
            return DockerDigest("sha256:a3ed95caeb02ffe68cdd9fd84406680ae93d633cb16422d00e8a7c22955b46d4")
        }

        fun emptyBlobHeadResponse(): DockerResponse {
            return ResponseEntity.ok().header(DOCKER_HEADER_API_VERSION, DOCKER_API_VERSION)
                .header(DOCKER_CONTENT_DIGEST, emptyBlobDigest().toString()).header(CONTENT_LENGTH, "32")
                .header(CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE).build()
        }

        fun emptyBlobGetResponse(): DockerResponse {
            return ResponseEntity.ok().header(CONTENT_LENGTH, "32")
                .header(DOCKER_HEADER_API_VERSION, DOCKER_API_VERSION)
                .header(DOCKER_CONTENT_DIGEST, emptyBlobDigest().toString())
                .header(CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE).body(EMPTY_BLOB_CONTENT)
        }
    }

    fun getManifestType(projectId: String, repoName: String, manifestPath: String): String? {
        return repo.getAttribute(projectId, repoName, manifestPath, "docker.manifest.type")
    }

    fun getSchema2ManifestConfigContent(context: RequestContext, bytes: ByteArray, tag: String): ByteArray {
        val manifest = JsonUtils.objectMapper.readTree(bytes)
        val digest = manifest.get("config").get(DOCKER_DIGEST).asText()
        val fileName = DockerDigest(digest).fileName()
        val configFile = ArtifactUtil.getManifestConfigBlob(repo, fileName, context, tag) ?: run {
            return ByteArray(0)
        }
        logger.info("get manifest config file [$configFile]")
        val downloadContext = DownloadContext(context).sha256(configFile.sha256!!).length(configFile.length)
        val stream = repo.download(downloadContext)
        stream.use {
            return IOUtils.toByteArray(it)
        }
    }

    fun getSchema2ManifestContent(context: RequestContext, schema2Path: String): ByteArray {
        val manifest = ArtifactUtil.getManifestByName(repo, context, schema2Path) ?: run {
            return ByteArray(0)
        }
        val downloadContext = DownloadContext(context).sha256(manifest.sha256!!).length(manifest.length)
        val stream = repo.download(downloadContext)
        stream.use {
            return IOUtils.toByteArray(it)
        }
    }

    fun getSchema2Path(context: RequestContext, bytes: ByteArray): String {
        val manifestList = JsonUtils.objectMapper.readTree(bytes)
        val manifests = manifestList.get("manifests")
        val maniIter = manifests.iterator()
            while (maniIter.hasNext()) {
                val manifest = maniIter.next() as JsonNode
                val platform = manifest.get("platform")
                val architecture = platform.get("architecture").asText()
                val os = platform.get("os").asText()
                if (StringUtils.equals(architecture, "amd64") && StringUtils.equals(os, "linux")) {
                    val digest = manifest.get(DOCKER_DIGEST).asText()
                    val fileName = DockerDigest(digest).fileName()
                    val manifestFile = ArtifactUtil.getBlobByName(repo, context, fileName) ?: run {
                        return EMPTYSTR
                    }
                    return ArtifactUtil.getFullPath(manifestFile)
                }
            }
        return EMPTYSTR
    }

    fun addManifestsBlobs(context: RequestContext, type: ManifestType, bytes: ByteArray, metadata: ManifestMetadata) {
        if (ManifestType.Schema2 == type) {
            addSchema2Blob(bytes, metadata)
        } else if (ManifestType.Schema2List == type) {
            addSchema2ListBlobs(context, bytes, metadata)
        }
    }

    private fun addSchema2Blob(bytes: ByteArray, metadata: ManifestMetadata) {
        val manifest = JsonUtils.objectMapper.readTree(bytes)
        val config = manifest.get("config")
        config?.let {
            val digest = config.get(DOCKER_DIGEST).asText()
            val blobInfo = DockerBlobInfo("", digest, 0L, "")
            metadata.blobsInfo.add(blobInfo)
        }
    }

    private fun addSchema2ListBlobs(context: RequestContext, bytes: ByteArray, metadata: ManifestMetadata) {
        val manifestList = JsonUtils.objectMapper.readTree(bytes)
        val manifests = manifestList.get("manifests")
        val manifest = manifests.iterator()

        while (manifest.hasNext()) {
            val manifestNode = manifest.next() as JsonNode
            val digestString = manifestNode.get("platform").get(DOCKER_DIGEST).asText()
            val dockerBlobInfo = DockerBlobInfo("", digestString, 0L, "")
            metadata.blobsInfo.add(dockerBlobInfo)
            val manifestFileName = DockerDigest(digestString).fileName()
            val manifestFile = ArtifactUtil.getManifestByName(repo, context, manifestFileName)
            if (manifestFile != null) {
                val fullPath = ArtifactUtil.getFullPath(manifestFile)
                val configBytes = getSchema2ManifestContent(context, fullPath)
                addSchema2Blob(configBytes, metadata)
            }
        }
    }

    fun buildManifestResponse(httpHeaders: HttpHeaders, context: RequestContext, manifestPath: String, digest: DockerDigest, length: Long): DockerResponse {
        val downloadContext = DownloadContext(context).length(length).sha256(digest.getDigestHex())
        val inputStream = repo.download(downloadContext)
        val inputStreamResource = InputStreamResource(inputStream)
        val contentType = getManifestType(context.projectId, context.repoName, manifestPath)
        httpHeaders.apply {
            set(DOCKER_HEADER_API_VERSION, DOCKER_API_VERSION)
        }.apply {
            set(DOCKER_CONTENT_DIGEST, digest.toString())
        }.apply {
            set(CONTENT_TYPE, contentType)
        }
        logger.info("file [$digest] result length [$length] type [$contentType]")
        return ResponseEntity.ok().headers(httpHeaders).contentLength(length).body(inputStreamResource)
    }
}