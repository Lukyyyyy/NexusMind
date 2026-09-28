<script setup lang="ts">
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';

const chatStore = useChatStore();
let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
let unmounted = false;

async function connectChat() {
  if (chatStore.wsStatus !== 'CLOSED') return;
  const ticket = await chatStore.ensureWsTicket();
  if (unmounted) return;
  if (ticket && chatStore.wsStatus === 'CLOSED') chatStore.wsOpen();
  else if (!ticket) scheduleReconnect();
}

function scheduleReconnect() {
  if (reconnectTimer) return;
  // ponytail: 固定间隔重连；大规模同时断线造成压力时再改为退避。
  reconnectTimer = setTimeout(() => {
    reconnectTimer = undefined;
    void connectChat();
  }, 3000);
}

watch(() => chatStore.wsStatus, status => {
  if (status === 'CLOSED' && !unmounted) scheduleReconnect();
  else if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = undefined;
  }
});

onMounted(async () => {
  void connectChat();
  await chatStore.loadSessions();
  if (chatStore.activeSessionId) {
    await chatStore.loadMessages(chatStore.activeSessionId);
  }
});

onUnmounted(() => {
  unmounted = true;
  if (reconnectTimer) clearTimeout(reconnectTimer);
});
</script>

<template>
  <div class="chat-page h-full min-h-0 flex overflow-hidden">
    <ChatList />
    <InputBox />
  </div>
</template>

<style scoped>
.chat-page {
  background: #fff;
}

:global(.dark) .chat-page {
  background: #10151a;
}
</style>
