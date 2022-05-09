/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2022 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.scanner.event.listener

import com.tencent.bkrepo.common.api.util.toJsonString
import com.tencent.bkrepo.repository.api.MetadataClient
import com.tencent.bkrepo.repository.pojo.metadata.MetadataSaveRequest
import com.tencent.bkrepo.scanner.QUALITY_RED_LINE
import com.tencent.bkrepo.scanner.event.SubtaskStatusChangedEvent
import com.tencent.bkrepo.scanner.pojo.response.ScanQualityResponse
import com.tencent.bkrepo.scanner.service.ScanQualityService
import com.tencent.bkrepo.scanner.utils.ScanPlanConverter
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

@Component
class SubtaskStatusChangedEventListener(
    private val metadataClient: MetadataClient,
    private val scanQualityService: ScanQualityService
) {
    @Async
    @EventListener(SubtaskStatusChangedEvent::class)
    fun listen(event: SubtaskStatusChangedEvent) {
        with(event.subtask) {
            // 未指定扫描方案表示为系统级别触发的扫描，不更新元数据
            if (planId == null) {
                return
            }

            logger.info("SubtaskStatusChangedEvent:${event.toJsonString()}")
            //获取方案质量规则
            val qualityRules = scanQualityService.getScanQuality(planId)
            //判断方案是否需要质量检查
            val qualityCheck = qualityCheck(qualityRules)
            //方案有设置质量检查 且 扫描结束有扫描结果，检查质量规则是否通过
            val qualityPass = if (qualityCheck && scanResultOverview != null) {
                scanQualityService.checkScanQualityRedLine(planId, scanResultOverview)
            } else {
                null
            }
            //方案没设置质量检查，只保存扫描状态
            val metadata = if (qualityPass == null) {
                mapOf(METADATA_KEY_SCAN_STATUS to ScanPlanConverter.convertToScanStatus(status).name)
            } else {
                mapOf(
                    METADATA_KEY_SCAN_STATUS to ScanPlanConverter.convertToScanStatus(status).name,
                    //方案设置质量检查，保存质量红线规则(通过true / 不通过false)
                    QUALITY_RED_LINE to qualityPass
                )
            }
            val request = MetadataSaveRequest(
                projectId = projectId,
                repoName = repoName,
                fullPath = fullPath,
                metadata = metadata,
                readOnly = true
            )
            logger.info("saveMetadata request:${request.toJsonString()}")
            metadataClient.saveMetadata(request)
            logger.info("update project[$projectId] repo[$repoName] fullPath[$fullPath] scanStatus[$status] success")
        }
    }

    fun qualityCheck(qualityRules: ScanQualityResponse): Boolean = with(qualityRules) {
        return critical != null || high != null || medium != null || low != null
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SubtaskStatusChangedEventListener::class.java)
        const val METADATA_KEY_SCAN_STATUS = "scanStatus"
    }
}
