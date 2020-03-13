package com.tencent.bkrepo.auth.service.local

import com.tencent.bkrepo.auth.message.AuthMessageCode
import com.tencent.bkrepo.auth.model.TRole
import com.tencent.bkrepo.auth.pojo.CreateRoleRequest
import com.tencent.bkrepo.auth.pojo.Role
import com.tencent.bkrepo.auth.pojo.enums.RoleType
import com.tencent.bkrepo.auth.repository.RoleRepository
import com.tencent.bkrepo.auth.service.RoleService
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(prefix = "auth", name = ["realm"], havingValue = "local")
class RoleServiceImpl @Autowired constructor(
    private val roleRepository: RoleRepository
) : RoleService {

    override fun createRole(request: CreateRoleRequest): String? {
        var role: TRole?
        if (request.type == RoleType.REPO) {
            role = roleRepository.findOneByRoleIdAndProjectIdAndRepoName(
                request.roleId,
                request.projectId,
                request.repoName!!
            )
        } else {
            role = roleRepository.findOneByRoleIdAndProjectId(request.roleId, request.projectId)
        }

        if (role != null) {
            logger.warn("create role [${request.roleId} , ${request.projectId} ]  is exist.")
            throw ErrorCodeException(AuthMessageCode.AUTH_DUP_RID)
        }

        val result = roleRepository.insert(
            TRole(
                roleId = request.roleId,
                type = request.type,
                name = request.name,
                projectId = request.projectId,
                repoName = request.repoName,
                admin = request.admin
            )
        )
        return result.id
    }

    override fun detail(id: String): Role? {
        val result = roleRepository.findOneById(id) ?: return null
        return transfer(result)
    }

    override fun detail(rid: String, projectId: String): Role? {
        val result = roleRepository.findOneByRoleIdAndProjectId(rid, projectId) ?: return null
        return transfer(result)
    }

    override fun listRoleByProject(type: RoleType?, projectId: String?, repoName: String?): List<Role> {
        if (type == null && projectId == null) {
            return roleRepository.findAll().map { transfer(it) }
        } else if (type != null && projectId == null) {
            return roleRepository.findByType(type).map { transfer(it) }
        } else if (type == null && projectId != null) {
            return roleRepository.findByProjectId(projectId).map { transfer(it) }
        } else if (type != null && projectId != null) {
            return roleRepository.findByTypeAndProjectId(type, projectId).map { transfer(it) }
        } else if (projectId != null && repoName != null) {
            roleRepository.findByRepoNameAndProjectId(repoName, projectId).map { transfer(it) }
        }
        return emptyList()
    }

    override fun deleteRoleByid(id: String): Boolean {
        val role = roleRepository.findOneById(id)
        if (role == null) {
            logger.warn("delete role [$id ] not exist.")
            throw ErrorCodeException(AuthMessageCode.AUTH_ROLE_NOT_EXIST)
        }

        roleRepository.deleteById(id)
        return true
    }

    private fun transfer(tRole: TRole): Role {
        return Role(
            id = tRole.id,
            roleId = tRole.roleId,
            type = tRole.type,
            name = tRole.name,
            projectId = tRole.projectId,
            admin = tRole.admin
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RoleServiceImpl::class.java)
    }
}
