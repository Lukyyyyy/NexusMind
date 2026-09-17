<script setup lang="tsx">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { NAlert, NButton, NCard, NDescriptions, NDescriptionsItem, NModal, NSpin, NTag, NText } from 'naive-ui';
import QRCode from 'qrcode';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { useAuthStore } from '@/store/modules/auth';
import {
  fetchImClawbotStatus,
  pollImClawbotLogin,
  startImClawbotLogin
} from '@/service/api';
import type { ImClawbotStatus, ImLoginStartResult } from '@/service/api';

defineOptions({ name: 'ImChannel' });

const authStore = useAuthStore();
const isSuperAdmin = computed(() => authStore.isSuperAdmin);

const loading = ref(false);
const status = ref<ImClawbotStatus | null>(null);

const loginVisible = ref(false);
const loginLoading = ref(false);
const loginSession = ref<ImLoginStartResult | null>(null);
const qrCanvas = ref<HTMLCanvasElement | null>(null);
let pollTimer: ReturnType<typeof setTimeout> | null = null;
let pollStopFlag = false;

const tokenStateMeta: Record<string, { label: string; type: 'success' | 'warning' | 'error' | 'default' }> = {
  ACTIVE: { label: '已连接', type: 'success' },
  WAITING_LOGIN: { label: '待扫码登录', type: 'warning' },
  EXPIRED: { label: '登录已过期', type: 'error' },
  NOT_CONFIGURED: { label: '未配置', type: 'default' },
  DISABLED: { label: '已停用', type: 'error' }
};

async function loadData() {
  loading.value = true;
  const statusRes = await fetchImClawbotStatus();
  if (!statusRes.error) status.value = statusRes.data;
  loading.value = false;
}

function stopPolling() {
  pollStopFlag = true;
  if (pollTimer) {
    clearTimeout(pollTimer);
    pollTimer = null;
  }
}

async function openLogin() {
  stopPolling();
  loginLoading.value = true;
  const { data, error } = await startImClawbotLogin();
  loginLoading.value = false;
  if (error || !data) {
    window.$message?.error('获取登录二维码失败，请稍后重试');
    return;
  }
  loginSession.value = data;
  loginVisible.value = true;
  pollStopFlag = false;
  await renderQr();
  schedulePoll(2000);
}

/** iLink 返回的是 liteapp 页面 URL（HTML 非图片），在本地 canvas 渲染二维码供微信扫码。 */
async function renderQr() {
  await nextTick();
  const content = loginSession.value?.qrcodeContent;
  if (!content || !qrCanvas.value) return;
  try {
    await QRCode.toCanvas(qrCanvas.value, content, { width: 220, margin: 2 });
  } catch {
    window.$message?.error('二维码渲染失败，请点击刷新重试');
  }
}

function schedulePoll(delayMs: number) {
  if (pollStopFlag) return;
  pollTimer = setTimeout(runPoll, delayMs);
}

async function runPoll() {
  if (!loginSession.value || pollStopFlag) return;
  const { data, error } = await pollImClawbotLogin(loginSession.value.qrcode);
  if (pollStopFlag) return;
  if (error || !data) {
    schedulePoll(4000);
    return;
  }
  if (data.status === 'expired') {
    stopPolling();
    window.$message?.warning('二维码已过期，正在刷新');
    await openLogin();
    return;
  }
  if (data.status === 'confirmed') {
    stopPolling();
    loginVisible.value = false;
    status.value = {
      configured: true,
      tokenState: 'ACTIVE',
      botId: data.botId || status.value?.botId || ''
    };
    window.$message?.success('微信机器人已连接，发消息即可开始问答');
    return;
  }
  schedulePoll(2000);
}

function closeLogin() {
  stopPolling();
  loginVisible.value = false;
}

onMounted(loadData);
onBeforeUnmount(stopPolling);
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-y-auto p-16px">
    <div class="page-heading">
      <div class="page-heading__icon">
        <SvgIcon icon="ant-design:comment-outlined" class="text-20px" />
      </div>
      <div>
        <h1>IM 渠道接入</h1>
        <p>用你自己的微信小号作为知识库问答机器人：扫码连接后，直接给它发消息提问</p>
      </div>
    </div>

    <NSpin :show="loading">
      <div class="flex-col-stretch gap-16px">
        <NCard title="微信 ClawBot" :bordered="false" size="small" class="card-wrapper">
          <template #header-extra>
            <NTag
              v-if="isSuperAdmin && status"
              :type="tokenStateMeta[status.tokenState]?.type || 'default'"
              size="small"
            >
              {{ tokenStateMeta[status.tokenState]?.label || status.tokenState }}
            </NTag>
          </template>
          <div class="flex-col-stretch gap-12px">
            <NDescriptions v-if="isSuperAdmin && status" :column="2" size="small" label-placement="left">
              <NDescriptionsItem label="机器人 ID">
                <NText :depth="3">{{ status.botId || '—' }}</NText>
              </NDescriptionsItem>
              <NDescriptionsItem label="收发方式">
                <NText :depth="3">iLink 直连（长轮询收消息）</NText>
              </NDescriptionsItem>
            </NDescriptions>

            <NAlert v-if="isSuperAdmin && status?.tokenState === 'EXPIRED'" type="warning" :show-icon="true">
              登录已过期，请重新扫码连接；过期前的对话历史不受影响。
            </NAlert>

            <div class="flex items-center gap-12px">
              <NButton type="primary" :loading="loginLoading" @click="openLogin">
                {{ status?.tokenState === 'ACTIVE' ? '重新扫码连接' : '扫码连接微信' }}
              </NButton>
              <NText depth="3" class="text-12px">扫码确认后即刻上线</NText>
            </div>
          </div>
        </NCard>
      </div>
    </NSpin>

    <NModal
      v-model:show="loginVisible"
      preset="card"
      title="扫码连接微信"
      class="w-420px"
      :mask-closable="false"
      @after-leave="closeLogin"
    >
      <div class="flex-col-stretch items-center gap-12px py-8px">
        <div class="qrcode-box">
          <canvas ref="qrCanvas" width="220" height="220" />
        </div>
        <NText depth="3" class="text-center text-13px">
          打开微信扫一扫，并在手机上确认登录。二维码约 {{ loginSession?.expireSeconds || 120 }} 秒后过期，过期后会自动刷新。
        </NText>
        <NText depth="3" class="text-center text-12px">
          页面会自动检测扫码结果，确认后本页面自动关闭。
        </NText>
        <NButton size="small" quaternary :loading="loginLoading" @click="openLogin">刷新二维码</NButton>
      </div>
    </NModal>
  </div>
</template>

<style scoped>
.page-heading { display: flex; align-items: center; gap: 12px; }
.page-heading__icon { display: grid; width: 40px; height: 40px; flex: 0 0 auto; place-items: center; border-radius: 6px; background: var(--nexus-primary-soft); color: var(--nexus-primary); font-size: 20px; }
.page-heading h1 { margin: 0; color: var(--nexus-text); font-size: 17px; font-weight: 650; line-height: 24px; }
.page-heading p { margin: 3px 0 0; color: var(--nexus-text-secondary); font-size: 12px; font-weight: 400; }
.qrcode-box { display: grid; place-items: center; width: 240px; height: 240px; border-radius: 8px; background: #fff; border: 1px solid var(--nexus-border); overflow: hidden; }
.qrcode-box canvas { width: 220px; height: 220px; }
</style>
