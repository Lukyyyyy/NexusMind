<script setup lang="ts">
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';

const chatStore = useChatStore();

onMounted(async () => {
  await chatStore.loadSessions();
  if (chatStore.activeSessionId) {
    await chatStore.loadMessages(chatStore.activeSessionId);
  }
});
</script>

<template>
  <div class="h-full min-h-0 flex overflow-hidden bg-#f6f7fb dark:bg-#101014">
    <ChatList />
    <InputBox />
  </div>
</template>

<style scoped></style>
