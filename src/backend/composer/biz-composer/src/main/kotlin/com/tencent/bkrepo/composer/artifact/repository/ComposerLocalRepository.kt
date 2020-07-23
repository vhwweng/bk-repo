package com.tencent.bkrepo.composer.artifact.repository

import com.google.gson.GsonBuilder
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactUploadContext
import com.tencent.bkrepo.common.artifact.repository.local.LocalRepository
import com.tencent.bkrepo.composer.COMPOSER_VERSION_INIT
import com.tencent.bkrepo.composer.INIT_PACKAGES
import org.springframework.stereotype.Component
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactSearchContext
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactTransferContext
import com.tencent.bkrepo.common.artifact.resolve.file.ArtifactFileFactory
import com.tencent.bkrepo.common.artifact.stream.Range
import com.tencent.bkrepo.common.query.model.PageLimit
import com.tencent.bkrepo.common.query.model.QueryModel
import com.tencent.bkrepo.common.query.model.Rule
import com.tencent.bkrepo.common.query.model.Sort
import com.tencent.bkrepo.common.service.util.HttpContextHolder
import com.tencent.bkrepo.composer.ARTIFACT_DIRECT_DOWNLOAD_PREFIX
import com.tencent.bkrepo.composer.pojo.ArtifactRepeat
import com.tencent.bkrepo.composer.util.DecompressUtil.wrapperJson
import com.tencent.bkrepo.composer.util.HttpUtil.requestAddr
import com.tencent.bkrepo.composer.util.JsonUtil
import com.tencent.bkrepo.composer.util.JsonUtil.wrapperJson
import com.tencent.bkrepo.composer.util.JsonUtil.wrapperPackageJson
import com.tencent.bkrepo.repository.pojo.node.service.NodeCreateRequest
import org.springframework.transaction.annotation.Transactional
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import com.tencent.bkrepo.composer.pojo.ArtifactRepeat.NONE
import com.tencent.bkrepo.composer.pojo.ArtifactRepeat.FULLPATH
import com.tencent.bkrepo.composer.pojo.ArtifactRepeat.FULLPATH_SHA256

@Component
class ComposerLocalRepository : LocalRepository(), ComposerRepository {

    /**
     * Composer节点创建请求
     */
    fun getCompressNodeCreateRequest(context: ArtifactUploadContext): NodeCreateRequest {
        val nodeCreateRequest = getNodeCreateRequest(context)
        return nodeCreateRequest.copy(
            fullPath = "/$ARTIFACT_DIRECT_DOWNLOAD_PREFIX/${context.artifactInfo.artifactUri.removePrefix("/")}",
            overwrite = true
        )
    }

    fun getJsonNodeCreateRequest(
        context: ArtifactUploadContext,
        fullPath: String
    ): NodeCreateRequest {
        val nodeCreateRequest = getNodeCreateRequest(context)
        return nodeCreateRequest.copy(
            overwrite = true,
            fullPath = fullPath
        )
    }

    /**
     *
     */
    private fun indexer(context: ArtifactUploadContext, repeat: ArtifactRepeat) {
        with(context.artifactInfo) {
            // 先读取并保存文件信息。
            val composerJsonNode = context.getArtifactFile().getInputStream()
                .wrapperJson(artifactUri)
            with(composerJsonNode) {
                // 查询对应的 "/p/%package%.json" 是否存在
                val pArtifactUri = "/p/$packageName.json"
                val node = nodeResource.detail(projectId, repoName, pArtifactUri).data
                val resultJson = if (node == null) {
                    JsonUtil.addComposerVersion(String.format(COMPOSER_VERSION_INIT, packageName), json, packageName, version)
                } else {
                    // if "/p/%package%.json" is Exists
                    // load version jsonFile, update "/p/%package%.json"
                    stream2Json(context)?.let {
                        JsonUtil.addComposerVersion(it, json, packageName, version)
                    }
                }
                val byteArrayInputStream = ByteArrayInputStream(GsonBuilder().create().toJson(resultJson).toByteArray())
                val jsonFile = ArtifactFileFactory.build(byteArrayInputStream)
                ArtifactUploadContext(jsonFile).let { jsonUploadContext ->
                    val jsonNodeCreateRequest = getJsonNodeCreateRequest(
                        context = jsonUploadContext,
                        fullPath = "/p/$packageName.json"
                    )
                    nodeResource.create(jsonNodeCreateRequest)
                    jsonFile.let {
                        storageService.store(
                            jsonNodeCreateRequest.sha256!!,
                            it, context.storageCredentials
                        )
                    }
                }
            }
        }
    }

    @Transactional(rollbackFor = [Throwable::class])
    override fun onUpload(context: ArtifactUploadContext) {
        val repeat = checkRepeatArtifact(context)
        if (repeat != ArtifactRepeat.FULLPATH_SHA256) { indexer(context, repeat) }
        val nodeCreateRequest = getCompressNodeCreateRequest(context)
        nodeResource.create(nodeCreateRequest)
        storageService.store(
            nodeCreateRequest.sha256!!,
            context.getArtifactFile(), context.storageCredentials
        )
    }

    /**
     * 查询对应请求包名的'*.json'文件
     */
    override fun getJson(context: ArtifactSearchContext): String? {
        with(context.artifactInfo) {
            return if (artifactUri.matches(Regex("^/p/(.*)\\.json$"))) {
                val request = HttpContextHolder.getRequest()
                val host = "${request.requestAddr()}/$projectId/$repoName"
                val packageName = artifactUri.removePrefix("/p/").removeSuffix(".json")
                stream2Json(context)?.wrapperJson(host, packageName)
            } else {
                null
            }
        }
    }

    override fun packages(context: ArtifactSearchContext): String? {
        with(context.artifactInfo) {
            val request = HttpContextHolder.getRequest()
            val host = "${request.requestAddr()}/$projectId/$repoName"
            while (nodeResource.detail(projectId, repoName, artifactUri).data == null) {
                val byteArrayInputStream = ByteArrayInputStream(INIT_PACKAGES.toByteArray())
                val artifactFile = ArtifactFileFactory.build(byteArrayInputStream)
                val artifactUploadContext = ArtifactUploadContext(artifactFile)
                val nodeCreateRequest = getNodeCreateRequest(context = artifactUploadContext)
                nodeResource.create(nodeCreateRequest)
                artifactUploadContext.getArtifactFile().let {
                    storageService.store(
                        nodeCreateRequest.sha256!!,
                        it, context.storageCredentials
                    )
                }
            }
            return stream2Json(context)?.wrapperPackageJson(host)
        }
    }

    /**
     * 检查上传的构件是否已在仓库中，判断条件：uri && sha256
     * 降低并发对索引文件的影响
     * @return ArtifactRepeat.FULLPATH_SHA256 存在完全相同构件，不操作索引
     * @return ArtifactRepeat.FULLPATH 请求路径相同，但内容不同，更新索引
     * @return ArtifactRepeat.NONE 无重复构件
     */
    private fun checkRepeatArtifact(context: ArtifactUploadContext): ArtifactRepeat {
        val artifactUri = context.artifactInfo.artifactUri
        val artifactSha256 = context.getArtifactFile().getFileSha256()

        return with(context.artifactInfo) {
            val projectQuery = Rule.QueryRule("projectId", projectId)
            val repositoryQuery = Rule.QueryRule("repoName", repoName)
//            val sha256Query = Rule.QueryRule("sha256", artifactSha256)
            val fullPathQuery = Rule.QueryRule("fullPath", artifactUri)

            val queryRule = Rule.NestedRule(
                mutableListOf(projectQuery, repositoryQuery, fullPathQuery),
                Rule.NestedRule.RelationType.AND
            )
            val queryModel = QueryModel(
                page = PageLimit(0, 10),
                sort = Sort(listOf("name"), Sort.Direction.ASC),
                select = mutableListOf("projectId", "repoName", "fullPath", "sha256"),
                rule = queryRule
            )
            val nodeList = nodeResource.query(queryModel).data?.records
            if (nodeList.isNullOrEmpty()) {
                NONE
            } else {
                // 上传时重名构件默认是覆盖操作，所以只会存在一个重名构件。
                if (nodeList.first()["sha256"] == artifactSha256) {
                    FULLPATH_SHA256
                } else {
                    FULLPATH
                }
            }
        }
    }

    /**
     * 加载搜索到的流并返回内容
     */
    private fun stream2Json(context: ArtifactTransferContext): String? {
        return with(context.artifactInfo) {
            val node = nodeResource.detail(projectId, repoName, artifactUri).data ?: return null
            node.nodeInfo.takeIf { !it.folder } ?: return null
            val inputStream = storageService.load(node.nodeInfo.sha256!!, Range.ofFull(node.nodeInfo.size), context.storageCredentials)
                ?: return null

            val stringBuilder = StringBuilder("")
            var line: String
            BufferedReader(InputStreamReader(inputStream)).use { bufferedReader ->
                while ((bufferedReader.readLine().also { line = it }) != null) {
                    stringBuilder.append(line)
                }
            }
            stringBuilder.toString()
        }
    }
}
