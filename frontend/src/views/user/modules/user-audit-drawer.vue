<script setup lang="ts">
import dayjs from 'dayjs';
const props = defineProps<{ user: Api.User.Item | null }>();
const visible = defineModel<boolean>('visible', { default: false });
const loading = ref(false);
const events = ref<Array<{ id: number; actor: string; action: string; targetOrgTag: string | null; reason: string | null; ipAddress: string; createdAt: string }>>([]);
const actionText: Record<string, string> = {
  ORG_JOIN_APPLIED: '提交入组申请', ORG_JOIN_WITHDRAWN: '撤回入组申请', ORG_JOIN_APPROVED: '批准入组申请',
  ORG_JOIN_REJECTED: '拒绝入组申请', ORG_EXITED: '主动退出组织', ORG_MEMBERSHIP_ASSIGNED: '管理员调整组织',
  SUPER_ADMIN_PROMOTED: '提升为超级管理员', SUPER_ADMIN_DEMOTED: '降级为管理员',
  ACCOUNT_DISABLED: '禁用账户', ACCOUNT_ENABLED: '启用账户',
  SENSITIVE_REAUTH_RATE_LIMITED: '敏感操作密码验证已限流'
};
async function load() {
  if (!props.user) return;
  loading.value = true;
  const { data, error } = await request<{ content: typeof events.value }>({ url: '/admin/organization-management/audit', params: { userId: props.user.userId, page: 1, size: 100 } });
  if (!error) events.value = data.content;
  loading.value = false;
}
watch(visible, value => { if (value) load(); });
</script>

<template>
  <NDrawer v-model:show="visible" :width="560" placement="right">
    <NDrawerContent :title="`${user?.displayName || user?.username || ''}（${user?.username || ''}）· 操作记录`" closable>
      <NSpin :show="loading">
        <NTimeline v-if="events.length">
          <NTimelineItem v-for="item in events" :key="item.id" type="info" :title="actionText[item.action] || item.action" :time="dayjs(item.createdAt).format('YYYY-MM-DD HH:mm:ss')">
            <div class="text-12px text-gray-500">操作者：{{ item.actor }}<span v-if="item.targetOrgTag"> · 组织：{{ item.targetOrgTag }}</span></div>
            <div v-if="item.reason" class="mt-4px text-13px">{{ item.reason }}</div>
          </NTimelineItem>
        </NTimeline>
        <NEmpty v-else description="暂无操作记录" class="py-50px" />
      </NSpin>
    </NDrawerContent>
  </NDrawer>
</template>
