/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.  
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 *
 */

package com.tencent.bkrepo.generic.artifact

import com.tencent.bkrepo.common.api.constant.MediaTypes
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.util.toJsonString
import com.tencent.bkrepo.common.artifact.exception.ArtifactNotFoundException
import com.tencent.bkrepo.common.artifact.exception.ArtifactValidateException
import com.tencent.bkrepo.common.artifact.exception.UnsupportedMethodException
import com.tencent.bkrepo.common.artifact.message.ArtifactMessageCode
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactRemoveContext
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactUploadContext
import com.tencent.bkrepo.common.artifact.repository.local.LocalRepository
import com.tencent.bkrepo.common.service.util.HeaderUtils
import com.tencent.bkrepo.common.service.util.ResponseBuilder
import com.tencent.bkrepo.generic.constant.BKREPO_META_PREFIX
import com.tencent.bkrepo.generic.constant.GenericMessageCode
import com.tencent.bkrepo.generic.constant.HEADER_EXPIRES
import com.tencent.bkrepo.generic.constant.HEADER_MD5
import com.tencent.bkrepo.generic.constant.HEADER_OVERWRITE
import com.tencent.bkrepo.generic.constant.HEADER_SEQUENCE
import com.tencent.bkrepo.generic.constant.HEADER_SHA256
import com.tencent.bkrepo.generic.constant.HEADER_UPLOAD_ID
import com.tencent.bkrepo.repository.pojo.node.service.NodeCreateRequest
import com.tencent.bkrepo.repository.pojo.node.service.NodeDeleteRequest
import org.springframework.stereotype.Component
import javax.servlet.http.HttpServletRequest

@Component
class GenericLocalRepository : LocalRepository() {

    override fun onUploadBefore(context: ArtifactUploadContext) {
        super.onUploadBefore(context)
        // 若不允许覆盖, 提前检查节点是否存在
        val overwrite = HeaderUtils.getBooleanHeader(HEADER_OVERWRITE)
        val uploadId = HeaderUtils.getHeader(HEADER_UPLOAD_ID)
        val sequence = HeaderUtils.getHeader(HEADER_SEQUENCE)?.toInt()
        if (!overwrite && !isBlockUpload(uploadId, sequence)) {
            with(context.artifactInfo) {
                val node = nodeClient.detail(projectId, repoName, getArtifactFullPath()).data
                if (node != null) {
                    throw ErrorCodeException(ArtifactMessageCode.NODE_EXISTED, getArtifactName())
                }
            }
        }
    }

    override fun onUploadValidate(context: ArtifactUploadContext) {
        super.onUploadValidate(context)
        // 校验sha256
        val calculatedSha256 = context.getArtifactSha256()
        val uploadSha256 = HeaderUtils.getHeader(HEADER_SHA256)
        if (uploadSha256 != null && !calculatedSha256.equals(uploadSha256, true)) {
            throw ArtifactValidateException("File sha256 validate failed.")
        }
        // 校验md5
        val calculatedMd5 = context.getArtifactMd5()
        val uploadMd5 = HeaderUtils.getHeader(HEADER_MD5)
        if (uploadMd5 != null && !calculatedMd5.equals(calculatedMd5, true)) {
            throw ArtifactValidateException("File md5 validate failed.")
        }
    }

    override fun onUpload(context: ArtifactUploadContext) {
        val uploadId = context.request.getHeader(HEADER_UPLOAD_ID)
        val sequence = context.request.getHeader(HEADER_SEQUENCE)?.toInt()
        if (isBlockUpload(uploadId, sequence)) {
            this.blockUpload(uploadId, sequence!!, context)
            context.response.contentType = MediaTypes.APPLICATION_JSON
            context.response.writer.println(ResponseBuilder.success().toJsonString())
        } else {
            val nodeDetail = storageManager.storeArtifactFile(
                buildNodeCreateRequest(context),
                context.getArtifactFile(),
                context.storageCredentials
            )
            context.response.contentType = MediaTypes.APPLICATION_JSON
            context.response.writer.println(ResponseBuilder.success(nodeDetail).toJsonString())
        }
    }

    override fun remove(context: ArtifactRemoveContext) {
        with(context.artifactInfo) {
            val node = nodeClient.detail(projectId, repoName, getArtifactFullPath()).data
                ?: throw ArtifactNotFoundException("Artifact[$this] not found")
            if (node.folder) {
                if (nodeClient.countFileNode(projectId, repoName, getArtifactFullPath()).data!! > 0) {
                    throw UnsupportedMethodException("Delete non empty folder is forbidden")
                }
            }
            val nodeDeleteRequest = NodeDeleteRequest(projectId, repoName, getArtifactFullPath(), context.userId)
            nodeClient.delete(nodeDeleteRequest)
        }
    }

    override fun buildNodeCreateRequest(context: ArtifactUploadContext): NodeCreateRequest {
        return super.buildNodeCreateRequest(context).copy(
            expires = HeaderUtils.getLongHeader(HEADER_EXPIRES),
            overwrite = HeaderUtils.getBooleanHeader(HEADER_OVERWRITE),
            metadata = resolveMetadata(context.request)
        )
    }

    /**
     * 判断是否为分块上传
     */
    private fun isBlockUpload(uploadId: String?, sequence: Int?): Boolean {
        return !uploadId.isNullOrBlank() && sequence != null
    }

    private fun blockUpload(uploadId: String, sequence: Int, context: ArtifactUploadContext) {
        with(context) {
            if (!storageService.checkBlockId(uploadId, storageCredentials)) {
                throw ErrorCodeException(GenericMessageCode.UPLOAD_ID_NOT_FOUND, uploadId)
            }
            val overwrite = HeaderUtils.getBooleanHeader(HEADER_OVERWRITE)
            storageService.storeBlock(uploadId, sequence, getArtifactSha256(), getArtifactFile(), overwrite, storageCredentials)
        }
    }

    /**
     * 从header中提取metadata
     */
    private fun resolveMetadata(request: HttpServletRequest): Map<String, String> {
        val metadata = mutableMapOf<String, String>()
        val headerNames = request.headerNames
        for (headerName in headerNames) {
            if (headerName.startsWith(BKREPO_META_PREFIX, true)) {
                val key = headerName.substring(BKREPO_META_PREFIX.length).trim()
                if (key.isNotBlank()) {
                    metadata[key] = HeaderUtils.getUrlDecodedHeader(headerName)!!
                }
            }
        }
        return metadata
    }
}
