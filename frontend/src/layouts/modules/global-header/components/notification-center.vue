<script setup lang="ts">
import dayjs from 'dayjs';
import { handleAccountDisabled } from '@/service/request';
defineOptions({ name: 'NotificationCenter' });

const router = useRouter();
const authStore = useAuthStore();
const visible = ref(false);
const loading = ref(false);
const notifications = ref<Api.Notification.Item[]>([]);
const unread = ref(0);
let socket: WebSocket | null = null;
let reconnectTimer: number | undefined;

async function loadNotifications() {
  if (!authStore.isLogin) return;
  loading.value = true;
  const { data, error } = await request<Api.Notification.List>({ url: '/notifications', params: { page: 1, size: 20 } });
  if (!error) {
    notifications.value = data.content;
    unread.value = data.unread;
  }
  loading.value = false;
}

async function connect() {
  if (!authStore.isLogin) return;
  const { data, error } = await request<{ ticket: string }>({ url: '/notifications/socket-ticket', method: 'POST' });
  if (error) return;
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  socket = new WebSocket(`${protocol}//${window.location.host}/proxy-ws/notifications/${data.ticket}`);
  socket.onmessage = event => {
    try {
      const payload = JSON.parse(event.data) as { event: string; data?: { type?: string; content?: string } };
      if (payload.event === 'account_disabled' || payload.data?.type === 'ACCOUNT_DISABLED') {
        handleAccountDisabled(payload.data?.content);
        return;
      }
      if (payload.event === 'notification' || payload.event.startsWith('read')) loadNotifications();
    } catch {
      loadNotifications();
    }
  };
  socket.onclose = () => {
    window.clearTimeout(reconnectTimer);
    reconnectTimer = window.setTimeout(connect, 3000);
  };
}

async function openItem(item: Api.Notification.Item) {
  if (!item.read) await request({ url: `/notifications/${item.id}/read`, method: 'POST' });
  visible.value = false;
  if (item.link) await router.push(item.link);
  await loadNotifications();
}

async function markAllRead() {
  const { error } = await request({ url: '/notifications/read-all', method: 'POST' });
  if (!error) await loadNotifications();
}

watch(visible, value => {
  if (value) loadNotifications();
});
onMounted(() => {
  loadNotifications();
  connect();
});
onBeforeUnmount(() => {
  window.clearTimeout(reconnectTimer);
  socket?.close();
});
</script>

<template>
  <NPopover v-model:show="visible" trigger="click" placement="bottom-end" :show-arrow="true" :width="360" raw>
    <template #trigger>
      <button type="button" class="notification-trigger" aria-label="通知">
        <NBadge :value="unread" :max="99" :offset="[-3, 4]">
          <SvgIcon icon="solar:bell-linear" />
        </NBadge>
      </button>
    </template>
    <div class="notification-panel">
      <div class="notification-head">
        <span class="text-15px font-600">通知</span>
        <NButton text type="primary" size="small" :disabled="!unread" @click="markAllRead">全部已读</NButton>
      </div>
      <NSpin :show="loading">
        <NScrollbar class="max-h-420px">
          <button
            v-for="item in notifications"
            :key="item.id"
            type="button"
            class="notification-item"
            :class="{ unread: !item.read }"
            @click="openItem(item)"
          >
            <span class="notification-dot" :class="{ muted: item.read }"></span>
            <span class="min-w-0 flex-1 text-left">
              <span class="block truncate text-13px font-500">{{ item.title }}</span>
              <span class="mt-4px block line-clamp-2 text-12px text-gray-500">{{ item.content }}</span>
              <span class="mt-6px block text-11px text-gray-400">{{ dayjs(item.createdAt).format('MM-DD HH:mm') }}</span>
            </span>
            <SvgIcon icon="solar:alt-arrow-right-linear" class="mt-2px text-gray-400" />
          </button>
          <NEmpty v-if="!notifications.length" description="暂无通知" class="py-42px" />
        </NScrollbar>
      </NSpin>
    </div>
  </NPopover>
</template>

<style scoped>
.notification-trigger { display: grid; width: 36px; height: 36px; place-items: center; border: 0; border-radius: 5px; background: transparent; color: #4b5565; cursor: pointer; font-size: 21px; }
.notification-trigger:hover { background: #eef3ff; color: #245bdb; }
.notification-panel { overflow: hidden; border: 1px solid #e5e9f0; border-radius: 8px; background: var(--n-color, #fff); box-shadow: 0 12px 32px rgba(31, 45, 61, 0.13); }
.notification-head { display: flex; height: 50px; align-items: center; justify-content: space-between; padding: 0 16px; border-bottom: 1px solid #edf0f5; }
.notification-item { display: flex; width: 100%; gap: 10px; padding: 14px 16px; border: 0; border-bottom: 1px solid #f0f2f6; background: transparent; color: inherit; cursor: pointer; }
.notification-item:hover, .notification-item.unread { background: #f6f9ff; }
.notification-dot { width: 7px; height: 7px; flex: 0 0 auto; margin-top: 5px; border-radius: 50%; background: #2f6feb; }
.notification-dot.muted { background: #d0d5dd; }
</style>
