package com.tencent.bkrepo.rpm.job

import com.tencent.bkrepo.common.artifact.hash.sha1
import com.tencent.bkrepo.common.artifact.pojo.configuration.local.repository.RpmLocalConfiguration
import com.tencent.bkrepo.common.artifact.resolve.file.ArtifactFileFactory
import com.tencent.bkrepo.common.artifact.stream.Range
import com.tencent.bkrepo.common.artifact.stream.closeQuietly
import com.tencent.bkrepo.common.storage.core.StorageService
import com.tencent.bkrepo.repository.api.NodeClient
import com.tencent.bkrepo.repository.api.RepositoryClient
import com.tencent.bkrepo.repository.pojo.node.NodeInfo
import com.tencent.bkrepo.repository.pojo.node.service.NodeCreateRequest
import com.tencent.bkrepo.repository.pojo.repo.RepositoryInfo
import com.tencent.bkrepo.rpm.REPODATA
import com.tencent.bkrepo.rpm.artifact.SurplusNodeCleaner
import com.tencent.bkrepo.rpm.util.GZipUtils.gZip
import com.tencent.bkrepo.rpm.util.GZipUtils.unGzipInputStream
import com.tencent.bkrepo.rpm.util.XmlStrUtils
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

@Component
class FileListJob {

    @Autowired
    private lateinit var repositoryClient: RepositoryClient

    @Autowired
    private lateinit var nodeClient: NodeClient

    @Autowired
    private lateinit var storageService: StorageService

    @Autowired
    private lateinit var surplusNodeCleaner: SurplusNodeCleaner

    @Scheduled(cron = "0 0/15 * * * ?")
    @SchedulerLock(name = "FileListJob", lockAtMostFor = "PT30M")
    fun insertFileList() {
        val repoList = repositoryClient.pageByType(0, 100, "RPM").data?.records

        repoList?.let {
            for (repo in repoList) {
                val rpmConfiguration = repo.configuration as RpmLocalConfiguration
                val repodataDepth = rpmConfiguration.repodataDepth ?: 0
                val targetSet = mutableSetOf<String>()
                findRepoDataByRepo(repo, "/", repodataDepth, targetSet)
                for (repoDataPath in targetSet) {
                    updateFileListsXml(repo, repoDataPath)
                }
            }
        }
    }

    fun findRepoDataByRepo(
        repositoryInfo: RepositoryInfo,
        path: String,
        repodataDepth: Int,
        repoDataSet: MutableSet<String>
    ) {
        with(repositoryInfo) {
            val nodeList = nodeClient.list(projectId, name, path).data ?: return
            if (repodataDepth == 0) {
                for (node in nodeList.filter { it.folder }.filter { it.name == REPODATA }) {
                    repoDataSet.add(node.fullPath)
                }
            } else {
                for (node in nodeList.filter { it.folder }) {
                    findRepoDataByRepo(repositoryInfo, node.fullPath, repodataDepth.dec(), repoDataSet)
                }
            }
        }
    }

    private fun updateFileListsXml(
        repo: RepositoryInfo,
        repodataPath: String
    ) {
        val target = "-filelists.xml.gz"
        with(repo) {
            // repodata下'-filelists.xml.gz'最新节点。
            val nodeList = nodeClient.list(
                projectId, name,
                repodataPath,
                includeFolder = false, deep = false
            ).data
            val targetNodelist = nodeList?.filter {
                it.name.endsWith(target)
            }?.sortedByDescending {
                it.createdDate
            }

            if (!targetNodelist.isNullOrEmpty()) {
                val latestNode = targetNodelist[0]
                val oldFileLists = storageService.load(
                    latestNode.sha256!!,
                    Range.full(latestNode.size),
                    null
                ) ?: return
                // 从临时目录中遍历索引
                val page = nodeClient.page(
                    projectId, name, 0, 500,
                    "$repodataPath/temp/",
                    includeFolder = false,
                    includeMetadata = true
                ).data ?: return

                var newFileLists: File = oldFileLists.unGzipInputStream()
                try {
                    val tempFileListsNode = page.records
                    val calculatedList = mutableListOf<NodeInfo>()
                    for (tempFile in tempFileListsNode) {
                        val inputStream = storageService.load(
                            tempFile.sha256!!,
                            Range.full(tempFile.size),
                            null
                        ) ?: return
                        newFileLists = if ((tempFile.metadata?.get("repeat")) == "FULLPATH") {
                            XmlStrUtils.updateFileLists(
                                "filelists", newFileLists,
                                tempFile.fullPath,
                                inputStream,
                                tempFile.metadata!!
                            )
                        } else {
                            XmlStrUtils.insertFileLists(
                                "filelists", newFileLists,
                                inputStream,
                                false
                            )
                        }
                        calculatedList.add(tempFile)
                        storeFileListNode(repo, newFileLists, repodataPath)
                        surplusNodeCleaner.deleteTempXml(calculatedList)
                    }
                } finally {
                    newFileLists.delete()
                }
            } else {
                // first upload
                storeFileListXmlNode(repo, repodataPath)
                updateFileListsXml(repo, repodataPath)
            }
            // 删除多余索引节点
            GlobalScope.launch {
                targetNodelist?.let { surplusNodeCleaner.deleteSurplusNode(it) }
            }.start()
        }
    }

    private fun storeFileListXmlNode(
        repo: RepositoryInfo,
        repodataPath: String
    ) {
        val target = "-filelists.xml.gz"
        val xmlStr = "<?xml version=\"1.0\"?>\n" +
            "<metadata xmlns=\"http://linux.duke.edu/metadata/filelists\" packages=\"0\">\n" +
            "</metadata>"
        val indexType = "filelists"
        ByteArrayInputStream((xmlStr.toByteArray())).use { xmlInputStream ->
            // 保存节点同时保存节点信息到元数据方便repomd更新。
            val xmlFileSize = xmlStr.toByteArray().size

            val xmlGZFile = xmlStr.toByteArray().gZip(indexType)
            val xmlFileSha1 = xmlInputStream.sha1()
            try {
                val xmlGZFileSha1 = FileInputStream(xmlGZFile).sha1()

                // 先保存primary-xml.gz文件
                val xmlGZArtifact = ArtifactFileFactory.build(FileInputStream(xmlGZFile))
                val fullPath = "$repodataPath/$xmlGZFileSha1$target"
                val metadata = mutableMapOf(
                    "indexType" to indexType,
                    "checksum" to xmlGZFileSha1,
                    "size" to (xmlGZArtifact.getSize().toString()),
                    "timestamp" to System.currentTimeMillis().toString(),
                    "openChecksum" to xmlFileSha1,
                    "openSize" to (xmlFileSize.toString())
                )

                val xmlPrimaryNode = NodeCreateRequest(
                    repo.projectId,
                    repo.name,
                    fullPath,
                    false,
                    0L,
                    true,
                    xmlGZArtifact.getSize(),
                    xmlGZArtifact.getFileSha256(),
                    xmlGZArtifact.getFileMd5(),
                    metadata
                )
                storageService.store(xmlPrimaryNode.sha256!!, xmlGZArtifact, null)
                with(xmlPrimaryNode) { logger.info("Success to store $projectId/$repoName/$fullPath") }
                nodeClient.create(xmlPrimaryNode)
                logger.info("Success to insert $xmlPrimaryNode")
            } finally {
                xmlGZFile.delete()
            }
        }
    }

    private fun storeFileListNode(
        repo: RepositoryInfo,
        xmlFile: File,
        repodataPath: String
    ) {
        val indexType = "filelists"
        val target = "-filelists.xml.gz"
        // 保存节点同时保存节点信息到元数据方便repomd更新。
        val xmlInputStream = FileInputStream(xmlFile)
        val xmlFileSize = xmlFile.length()

        val xmlGZFile = xmlInputStream.gZip(indexType)
        val xmlFileSha1 = xmlInputStream.sha1()
        try {
            val xmlGZFileSha1 = FileInputStream(xmlGZFile).sha1()

            val xmlGZArtifact = ArtifactFileFactory.build(FileInputStream(xmlGZFile))
            val fullPath = "$repodataPath/$xmlGZFileSha1$target"
            val metadata = mutableMapOf(
                "indexType" to indexType,
                "checksum" to xmlGZFileSha1,
                "size" to (xmlGZArtifact.getSize().toString()),
                "timestamp" to System.currentTimeMillis().toString(),
                "openChecksum" to xmlFileSha1,
                "openSize" to (xmlFileSize.toString())
            )

            val xmlPrimaryNode = NodeCreateRequest(
                repo.projectId,
                repo.name,
                fullPath,
                false,
                0L,
                true,
                xmlGZArtifact.getSize(),
                xmlGZArtifact.getFileSha256(),
                xmlGZArtifact.getFileMd5(),
                metadata
            )
            storageService.store(xmlPrimaryNode.sha256!!, xmlGZArtifact, null)
            with(xmlPrimaryNode) { logger.info("Success to store $projectId/$repoName/$fullPath") }
            nodeClient.create(xmlPrimaryNode)
            logger.info("Success to insert $xmlPrimaryNode")
        } finally {
            xmlGZFile.delete()
            xmlInputStream.closeQuietly()
        }
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(FileListJob::class.java)
    }
}
