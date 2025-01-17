<template>
    <div class="plan-container" v-bkloading="{ isLoading }">
        <div class="ml20 mr20 mt10 flex-between-center">
            <bk-button icon="plus" theme="primary" @click="$router.push({ name: 'createPlan' })">{{ $t('create') }}</bk-button>
            <div class="flex-align-center">
                <bk-input
                    class="w250"
                    v-model.trim="planInput"
                    clearable
                    :placeholder="$t('planPlaceHolder')"
                    right-icon="bk-icon icon-search"
                    @enter="handlerPaginationChange()"
                    @clear="handlerPaginationChange()">
                </bk-input>
                <bk-select
                    class="ml10 w250"
                    v-model="lastExecutionStatus"
                    :placeholder="$t('lastExecutionStatus')"
                    @change="handlerPaginationChange()">
                    <bk-option v-for="(label, key) in asyncPlanStatusEnum" :key="key" :id="key" :name="$t(`asyncPlanStatusEnum.${key}`)"></bk-option>
                </bk-select>
                <bk-select
                    class="ml10 w250"
                    v-model="showEnabled"
                    :placeholder="$t('planStatus')"
                    @change="handlerPaginationChange()">
                    <bk-option id="true" :name="$t('activePlan')"></bk-option>
                    <bk-option id="false" :name="$t('discontinuedPlan')"></bk-option>
                </bk-select>
            </div>
        </div>
        <bk-table
            class="mt10 plan-table"
            height="calc(100% - 100px)"
            :data="planList"
            :outer-border="false"
            :row-border="false"
            row-key="userId"
            size="small">
            <template #empty>
                <empty-data :is-loading="isLoading" :search="Boolean(planInput || lastExecutionStatus || showEnabled)"></empty-data>
            </template>
            <bk-table-column :label="$t('planName')" show-overflow-tooltip>
                <template #default="{ row }">
                    <span class="hover-btn" @click="showPlanDetailHandler(row)">{{row.name}}</span>
                </template>
            </bk-table-column>
            <bk-table-column :label="$t('syncType')" width="80">
                <template #default="{ row }">
                    {{ { 'REPOSITORY': $t('synchronizeRepository'), 'PACKAGE': $t('synchronizePackage'), 'PATH': $t('synchronizePath') }[row.replicaObjectType] }}
                </template>
            </bk-table-column>
            <bk-table-column :label="$t('targetNode')" show-overflow-tooltip>
                <template #default="{ row }">{{ row.remoteClusters.map(v => v.name).join('、') }}</template>
            </bk-table-column>
            <bk-table-column :label="$t('synchronizationPolicy')" width="80">
                <template #default="{ row }">{{ getExecutionStrategy(row) }}</template>
            </bk-table-column>
            <bk-table-column :label="$t('lastExecutionTime')" prop="LAST_EXECUTION_TIME" width="150" :render-header="renderHeader">
                <template #default="{ row }">{{formatDate(row.lastExecutionTime)}}</template>
            </bk-table-column>
            <bk-table-column :label="$t('lastExecutionStatus')" width="100">
                <template #default="{ row }">
                    <span class="repo-tag" :class="row.lastExecutionStatus">{{row.lastExecutionStatus ? $t(`asyncPlanStatusEnum.${row.lastExecutionStatus}`) : $t('notExecuted')}}</span>
                </template>
            </bk-table-column>
            <bk-table-column :label="$t('nextExecutionTime')" prop="NEXT_EXECUTION_TIME" width="150" :render-header="renderHeader">
                <template #default="{ row }">{{formatDate(row.nextExecutionTime)}}</template>
            </bk-table-column>
            <bk-table-column :label="$t('creator')" width="90" show-overflow-tooltip>
                <template #default="{ row }">{{userList[row.createdBy] ? userList[row.createdBy].name : row.createdBy}}</template>
            </bk-table-column>
            <bk-table-column :label="$t('createdDate')" prop="CREATED_TIME" width="150" :render-header="renderHeader">
                <template #default="{ row }">{{formatDate(row.createdDate)}}</template>
            </bk-table-column>
            <bk-table-column :label="$t('enablePlan')" width="70">
                <template #default="{ row }">
                    <bk-switcher class="m5" v-model="row.enabled" size="small" theme="primary" @change="changeEnabledHandler(row)"></bk-switcher>
                </template>
            </bk-table-column>
            <bk-table-column :label="$t('execute')" width="60">
                <template #default="{ row }">
                    <i class="devops-icon icon-play3 hover-btn inline-block"
                        :class="{ 'disabled': row.lastExecutionStatus === 'RUNNING' || row.replicaType === 'REAL_TIME' }"
                        @click.stop="executePlanHandler(row)">
                    </i>
                </template>
            </bk-table-column>
            <bk-table-column :label="$t('operation')" width="70">
                <template #default="{ row }">
                    <operation-list
                        :list="[
                            { label: $t('edit'), clickEvent: () => editPlanHandler(row), disabled: Boolean(row.lastExecutionStatus) || row.replicaType === 'REAL_TIME' },
                            { label: $t('copy'), clickEvent: () => copyPlanHandler(row) },
                            { label: $t('delete'), clickEvent: () => deletePlanHandler(row) },
                            { label: $t('log'), clickEvent: () => showPlanLogHandler(row) }
                        ]"></operation-list>
                </template>
            </bk-table-column>
        </bk-table>
        <bk-pagination
            class="p10"
            size="small"
            align="right"
            show-total-count
            @change="current => handlerPaginationChange({ current })"
            @limit-change="limit => handlerPaginationChange({ limit })"
            :current.sync="pagination.current"
            :limit="pagination.limit"
            :count="pagination.count"
            :limit-list="pagination.limitList">
        </bk-pagination>
        <plan-log v-model="planLog.show" :plan-data="planLog.planData"></plan-log>
        <plan-copy-dialog v-bind="planCopy" @cancel="planCopy.show = false" @refresh="handlerPaginationChange()"></plan-copy-dialog>
    </div>
</template>
<script>
    import OperationList from '@repository/components/OperationList'
    import planLog from './planLog'
    import planCopyDialog from './planCopyDialog'
    import { mapState, mapActions } from 'vuex'
    import { formatDate } from '@repository/utils'
    import { asyncPlanStatusEnum } from '@repository/store/publicEnum'
    export default {
        name: 'plan',
        components: { planLog, planCopyDialog, OperationList },
        data () {
            return {
                asyncPlanStatusEnum,
                isLoading: false,
                showEnabled: undefined,
                lastExecutionStatus: '',
                planInput: '',
                sortType: 'CREATED_TIME',
                planList: [],
                pagination: {
                    count: 0,
                    current: 1,
                    limit: 20,
                    limitList: [10, 20, 40]
                },
                planLog: {
                    show: false,
                    planData: {}
                },
                planCopy: {
                    show: false,
                    name: '',
                    planKey: '',
                    description: ''
                }
            }
        },
        computed: {
            ...mapState(['userList'])
        },
        created () {
            this.handlerPaginationChange()
        },
        methods: {
            formatDate,
            ...mapActions([
                'getPlanList',
                'changeEnabled',
                'executePlan',
                'deletePlan'
            ]),
            getExecutionStrategy ({ replicaType, setting: { executionStrategy } }) {
                return replicaType === 'REAL_TIME'
                    ? this.$t('realTimeSync')
                    : {
                        IMMEDIATELY: this.$t('executeImmediately'),
                        SPECIFIED_TIME: this.$t('designatedTime'),
                        CRON_EXPRESSION: this.$t('timedExecution')
                    }[executionStrategy]
            },
            renderHeader (h, { column }) {
                return h('div', {
                    class: {
                        'flex-align-center hover-btn': true,
                        'selected-header': this.sortType === column.property
                    },
                    on: {
                        click: () => {
                            this.sortType = column.property
                            this.handlerPaginationChange()
                        }
                    }
                }, [
                    h('span', column.label),
                    h('i', {
                        class: 'ml5 devops-icon icon-down-shape'
                    })
                ])
            },
            handlerPaginationChange ({ current = 1, limit = this.pagination.limit } = {}) {
                this.pagination.current = current
                this.pagination.limit = limit
                this.getPlanListHandler()
            },
            getPlanListHandler () {
                this.isLoading = true
                return this.getPlanList({
                    projectId: this.$route.params.projectId,
                    name: this.planInput || undefined,
                    enabled: this.showEnabled || undefined,
                    lastExecutionStatus: this.lastExecutionStatus || undefined,
                    sortType: this.sortType,
                    current: this.pagination.current,
                    limit: this.pagination.limit
                }).then(({ records, totalRecords }) => {
                    this.planList = records
                    this.pagination.count = totalRecords
                }).finally(() => {
                    this.isLoading = false
                })
            },
            executePlanHandler ({ key, name, lastExecutionStatus, replicaType }) {
                if (lastExecutionStatus === 'RUNNING' || replicaType === 'REAL_TIME') return
                this.$confirm({
                    theme: 'warning',
                    message: this.$t('planConfirmExecuteMsg', [name]),
                    confirmFn: () => {
                        return this.executePlan({
                            key
                        }).then(() => {
                            this.getPlanListHandler()
                            this.$bkMessage({
                                theme: 'success',
                                message: this.$t('executePlan') + this.$t('space') + this.$t('success')
                            })
                        })
                    }
                })
            },
            editPlanHandler ({ name, key, lastExecutionStatus, replicaType }) {
                if (lastExecutionStatus || replicaType === 'REAL_TIME') return
                this.$router.push({
                    name: 'editPlan',
                    params: {
                        ...this.$route.params,
                        planId: key
                    },
                    query: {
                        planName: name
                    }
                })
            },
            copyPlanHandler ({ name, key, description }) {
                this.planCopy = {
                    show: true,
                    name,
                    planKey: key,
                    description
                }
            },
            deletePlanHandler ({ key, name }) {
                this.$confirm({
                    theme: 'danger',
                    message: this.$t('planConfirmDeleteMsg', [name]),
                    confirmFn: () => {
                        return this.deletePlan({
                            key
                        }).then(() => {
                            this.handlerPaginationChange()
                            this.$bkMessage({
                                theme: 'success',
                                message: this.$t('deletePlan') + this.$t('space') + this.$t('success')
                            })
                        })
                    }
                })
            },
            changeEnabledHandler ({ key, enabled }) {
                this.changeEnabled({
                    key
                }).then(res => {
                    this.$bkMessage({
                        theme: 'success',
                        message: `${enabled ? this.$t('enablePlanSuccess') : this.$t('stopPlanSuccess')}`
                    })
                }).finally(() => {
                    this.getPlanListHandler()
                })
            },
            showPlanDetailHandler ({ key }) {
                this.$router.push({
                    name: 'planDetail',
                    params: {
                        ...this.$route.params,
                        planId: key
                    }
                })
            },
            showPlanLogHandler (row) {
                this.planLog.show = true
                this.planLog.planData = row
            }
        }
    }
</script>
<style lang="scss" scoped>
.plan-container {
    height: 100%;
    overflow: hidden;
    background-color: white;
    .plan-table {
        ::v-deep .selected-header {
            color: var(--primaryColor);
        }
    }
}
</style>
