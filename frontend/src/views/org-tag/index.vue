<script setup lang="tsx">
import type { DropdownOption } from 'naive-ui';
import { NButton, NDropdown, NInput, NTag } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import OrgTagOperateDialog from './modules/org-tag-operate-dialog.vue';
import ApprovalList from './modules/approval-list.vue';
import AuditList from './modules/audit-list.vue';
import SmtpSettings from './modules/smtp-settings.vue';

const authStore = useAuthStore();
const route = useRoute();
const activeTab = ref(String(route.query.tab || 'organizations'));
const organizationKeyword = ref('');
const expandedRowKeys = ref<string[]>([]);

const { columns, columnChecks, data, loading, getData } = useTable({
  apiFn: fetchGetOrgTagList,
  columns: () => [
    {
      key: 'name',
      title: '组织名称',
      width: 300,
      render: row => (
        <div class="organization-name">
          <span class="organization-name__icon"><SvgIcon icon="solar:buildings-2-linear" /></span>
          <span class="min-w-0">
            <strong>{row.name}</strong>
            <small>{row.tagId}</small>
          </span>
        </div>
      ),
      ellipsis: {
        tooltip: true
      }
    },
    {
      key: 'description',
      title: '描述',
      minWidth: 200,
      ellipsis: {
        tooltip: true
      }
    },
    {
      key: 'archivedAt',
      title: '状态',
      width: 128,
      render: row => <NTag bordered={false} type={row.archivedAt ? 'default' : row.joinable ? 'success' : 'warning'}>{row.archivedAt ? '已归档' : row.joinable ? '允许申请' : '仅管理员分配'}</NTag>
    },
    {
      key: 'operate',
      title: '操作',
      width: 224,
      render: row => (
        <div class="row-actions">
          {['default', 'admin'].includes(row.tagId) ? <NTag size="small" bordered={false}>系统保护</NTag> : <>
            <NButton type="primary" secondary size="small" onClick={() => edit(row)}>编辑</NButton>
            <NButton secondary size="small" onClick={() => addChild(row)}>新增下级</NButton>
            <NDropdown trigger="click" options={getMoreOptions(row)} onSelect={key => handleMoreAction(String(key), row)}>
              <NButton quaternary size="small">更多</NButton>
            </NDropdown>
          </>}
        </div>
      )
    }
  ]
});

const displayedData = computed(() => {
  const keyword = organizationKeyword.value.trim().toLocaleLowerCase();
  if (!keyword) return data.value;

  const filter = (items: Api.OrgTag.Item[]): Api.OrgTag.Item[] => items.flatMap(item => {
    const children = filter(item.children || []);
    const matched = item.tagId.toLocaleLowerCase().includes(keyword) || item.name.toLocaleLowerCase().includes(keyword);
    return matched || children.length
      ? [{ ...item, ...(children.length ? { children } : { children: undefined }) }]
      : [];
  });
  return filter(data.value);
});

const {
  dialogVisible,
  operateType,
  editingData,
  handleAdd,
  handleAddChild,
  handleEdit,
  onDeleted
  // closeDrawer
} = useTableOperate<Api.OrgTag.Item>(getData);

function addChild(row: Api.OrgTag.Item) {
  handleAddChild(row);
}

/** the editing row data */
function edit(row: Api.OrgTag.Item) {
  handleEdit(row);
}

function getRowProps(row: Api.OrgTag.Item) {
  if (!row.children?.length) return {};

  return {
    class: 'organization-row--expandable',
    onClick(event: MouseEvent) {
      if ((event.target as HTMLElement).closest('.n-data-table-expand-trigger, .row-actions')) return;
      expandedRowKeys.value = expandedRowKeys.value.includes(row.tagId)
        ? expandedRowKeys.value.filter(key => key !== row.tagId)
        : [...expandedRowKeys.value, row.tagId];
    }
  };
}

async function handleDelete(tagId: string) {
  const { error } = await request({ url: `/admin/org-tags/${tagId}`, method: 'DELETE' });
  if (!error) {
    onDeleted();
  }
}

function getMoreOptions(row: Api.OrgTag.Item): DropdownOption[] {
  return [
    ...(!row.archivedAt ? [{ label: row.joinable ? '关闭申请' : '开放申请', key: 'joinable' }] : []),
    { label: row.archivedAt ? '恢复组织' : '归档组织', key: 'archive' },
    { type: 'divider', key: 'divider' },
    { label: '删除组织', key: 'delete' }
  ];
}

function handleMoreAction(key: string, row: Api.OrgTag.Item) {
  if (key === 'joinable') toggleJoinable(row);
  else if (key === 'archive') archive(row);
  else if (key === 'delete') confirmDelete(row);
}

function confirmDelete(row: Api.OrgTag.Item) {
  window.$dialog?.warning({
    title: '删除组织',
    content: `仅空组织可以删除，确认删除「${row.name}」吗？`,
    positiveText: '确认删除',
    negativeText: '取消',
    positiveButtonProps: { type: 'error' },
    onPositiveClick: () => handleDelete(row.tagId)
  });
}

function archive(row: Api.OrgTag.Item) {
  let reason = '';
  window.$dialog?.warning({
    title: row.archivedAt ? '恢复组织' : '归档组织',
    content: () => <NInput type="textarea" maxlength={200} showCount placeholder="请输入原因" onUpdateValue={value => { reason = value; }} />,
    positiveText: row.archivedAt ? '确认恢复' : '确认归档',
    negativeText: '取消',
    async onPositiveClick() {
      if (!reason.trim()) { window.$message?.warning('请输入原因'); return false; }
      const action = row.archivedAt ? 'restore' : 'archive';
      const { error } = await request({ url: `/admin/organization-management/organizations/${row.tagId}/${action}`, method: 'POST', data: { reason: reason.trim() } });
      if (!error) { window.$message?.success(row.archivedAt ? '组织已恢复' : '组织已归档'); await getData(); }
      return !error;
    }
  });
}

async function toggleJoinable(row: Api.OrgTag.Item) {
  const { error } = await request({ url: `/admin/organization-management/organizations/${row.tagId}/joinable`, method: 'PUT', data: { joinable: !row.joinable } });
  if (!error) { window.$message?.success(row.joinable ? '已关闭用户申请' : '已开放用户申请'); await getData(); }
}
</script>

<template>
  <div class="management-page flex-col-stretch gap-16px overflow-hidden <sm:overflow-auto">
    <NCard :bordered="false" size="small" content-style="padding: 0;" class="card-wrapper management-shell">
      <template #header>
        <div class="page-heading">
          <div class="page-heading__icon"><SvgIcon icon="solar:buildings-2-linear" /></div>
          <div>
            <h1>组织管理</h1>
            <p>统一维护组织结构、成员准入与系统级服务</p>
          </div>
        </div>
      </template>
      <NTabs v-model:value="activeTab" type="line" animated class="page-tabs">
        <NTabPane name="organizations" tab="组织列表">
          <div class="table-toolbar">
            <div>
              <strong>组织结构</strong>
              <p>维护组织层级、开放状态和生命周期</p>
            </div>
            <div class="table-toolbar__actions">
              <NInput
                v-model:value="organizationKeyword"
                clearable
                placeholder="按标签ID或标签名称搜索"
                class="organization-search"
              />
              <TableHeaderOperation v-model:columns="columnChecks" :loading="loading" @add="handleAdd" @refresh="getData" />
            </div>
          </div>
          <NDataTable
            v-model:expanded-row-keys="expandedRowKeys"
            remote
            :columns="columns"
            :data="displayedData"
            :row-props="getRowProps"
            size="small"
            :scroll-x="962"
            :loading="loading"
            :pagination="false"
            :row-key="item => item.tagId"
            striped
            class="organization-table"
          />
        </NTabPane>
        <NTabPane name="applications" tab="入组审批"><ApprovalList /></NTabPane>
        <NTabPane name="audit" tab="审计记录"><AuditList /></NTabPane>
        <NTabPane v-if="authStore.isSuperAdmin" name="smtp" tab="邮件服务"><SmtpSettings /></NTabPane>
      </NTabs>
      <OrgTagOperateDialog
        v-model:visible="dialogVisible"
        :operate-type="operateType"
        :row-data="editingData!"
        :data="data"
        @submitted="getData"
      />
    </NCard>
  </div>
</template>

<style scoped lang="scss">
.management-page { min-height: 100%; }
.page-heading { display: flex; align-items: center; gap: 12px; }
.page-heading__icon { display: grid; width: 40px; height: 40px; flex: 0 0 auto; place-items: center; border-radius: 6px; background: var(--nexus-primary-soft); color: var(--nexus-primary); font-size: 20px; }
.page-heading h1 { margin: 0; color: var(--nexus-text); font-size: 17px; font-weight: 650; line-height: 24px; }
.page-heading p { margin: 3px 0 0; color: var(--nexus-text-secondary); font-size: 12px; font-weight: 400; }
.page-tabs :deep(.n-tabs-nav) { padding: 0 18px; border-bottom: 1px solid var(--nexus-border); }
.page-tabs :deep(.n-tab-pane) { padding: 18px; }
.table-toolbar { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 16px; }
.table-toolbar strong { color: var(--nexus-text); font-size: 14px; font-weight: 600; }
.table-toolbar p { margin: 4px 0 0; color: var(--nexus-text-secondary); font-size: 12px; }
.table-toolbar__actions { display: flex; align-items: center; gap: 12px; }
.organization-search { width: 240px; }
.organization-table { overflow: hidden; border: 1px solid var(--nexus-border); border-radius: 5px; }
.organization-table :deep(.n-data-table-expand-trigger) { vertical-align: middle; }
.organization-table :deep(.n-ellipsis:has(.organization-name)) { vertical-align: middle; }
.organization-table :deep(.organization-row--expandable) { cursor: pointer; }
.management-shell :deep(.organization-name) { display: inline-flex; min-width: 0; align-items: center; gap: 10px; padding: 4px 0; vertical-align: middle; }
.management-shell :deep(.organization-name__icon) { display: grid; width: 32px; height: 32px; flex: 0 0 auto; place-items: center; border-radius: 5px; background: var(--nexus-primary-soft); color: var(--nexus-primary); font-size: 16px; }
.management-shell :deep(.organization-name strong), .management-shell :deep(.organization-name small) { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.management-shell :deep(.organization-name strong) { color: var(--nexus-text); font-size: 14px; font-weight: 600; }
.management-shell :deep(.organization-name small) { margin-top: 2px; color: var(--nexus-text-secondary); font-size: 11px; }
.management-shell :deep(.row-actions) { display: flex; align-items: center; gap: 6px; }
@media (max-width: 640px) {
  .page-heading__icon { display: none; }
  .page-tabs :deep(.n-tabs-nav) { padding: 0 14px; }
  .page-tabs :deep(.n-tab-pane) { padding: 14px; }
  .table-toolbar { align-items: flex-start; flex-direction: column; }
  .table-toolbar__actions { width: 100%; align-items: stretch; flex-direction: column; }
  .organization-search { width: 100%; }
}
</style>
