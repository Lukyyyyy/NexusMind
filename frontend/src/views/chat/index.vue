<script setup lang="ts">
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';

const chatStore = useChatStore();

onMounted(async () => {
  await chatStore.loadSessions();
  if (chatStore.activeSessionId) {
    await chatStore.loadMessages(chatStore.activeSessionId);
  }
  const ticket = await chatStore.ensureWsTicket();
  if (ticket) chatStore.wsOpen();
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
