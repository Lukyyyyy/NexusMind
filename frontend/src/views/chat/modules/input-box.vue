<script setup lang="ts">
import type { NScrollbar } from 'naive-ui';
import { VueMarkdownItProvider } from 'vue-markdown-shiki';
import ChatMessage from './chat-message.vue';

const chatStore = useChatStore();
const { input, messages, activeSession, loading, wsStatus, wsData } = storeToRefs(chatStore);
const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();

const latestMessage = computed(() => {
  return messages.value[messages.value.length - 1] ?? {};
});

const isSending = computed(() => {
  return (
    latestMessage.value?.role === 'assistant' && ['loading', 'pending'].includes(latestMessage.value?.status || '')
  );
});

const sendable = computed(
  () => (!input.value.message && !isSending.value) || ['CLOSED', 'CONNECTING'].includes(wsStatus.value)
);

watch(wsData, val => {
  if (!val) return;
  const data = JSON.parse(val);
  if (data.type === 'title_updated') {
    chatStore.loadSessions();
    return;
  }

  const assistant = messages.value[messages.value.length - 1];

  if (data.type === 'completion' && data.status === 'finished') {
    if (assistant?.role === 'assistant' && assistant.status !== 'error') {
      assistant.status = 'finished';
    }
    chatStore.refreshActiveSessionMessages();
  } else if (data.error) {
    if (assistant) assistant.status = 'error';
  } else if (data.chunk) {
    if (!assistant) return;
    assistant.status = 'loading';
    assistant.content += data.chunk;
  }
  scrollToBottom();
});

watch(() => [...messages.value], scrollToBottom);

function scrollToBottom() {
  setTimeout(() => {
    scrollbarRef.value?.scrollBy({
      top: 999999999999,
      behavior: 'auto'
    });
  }, 80);
}

const handleSend = async () => {
  //  判断是否正在发送, 如果发送中，则停止ai继续响应
  if (isSending.value) {
    const { error, data } = await request<Api.Chat.Token>({ url: 'chat/websocket-token', baseURL: 'proxy-api' });
    if (error) return;

    chatStore.wsSend(JSON.stringify({ type: 'stop', _internal_cmd_token: data.cmdToken }));

    messages.value[messages.value.length - 1].status = 'finished';
    if (!latestMessage.value.content) messages.value.pop();
    return;
  }

  const sessionId = await chatStore.ensureActiveSession();
  if (!sessionId) return;

  const content = input.value.message;
  messages.value.push({
    content,
    role: 'user',
    status: 'finished',
    timestamp: new Date().toISOString()
  });
  messages.value.push({
    content: '',
    role: 'assistant',
    status: 'pending'
  });
  chatStore.wsSend(
    JSON.stringify({
      type: 'message',
      sessionId,
      content
    } satisfies Api.Chat.SendPayload)
  );
  input.value.message = '';
};

const inputRef = ref<HTMLTextAreaElement>();
// 手动插入换行符（确保所有浏览器兼容）
const insertNewline = () => {
  const textarea = inputRef.value;
  if (!textarea) return;
  const start = textarea.selectionStart;
  const end = textarea.selectionEnd;

  // 在光标位置插入换行符
  input.value.message = `${input.value.message.substring(0, start)}\n${input.value.message.substring(end)}`;

  // 更新光标位置（在插入的换行符之后）
  nextTick(() => {
    textarea.selectionStart = start + 1;
    textarea.selectionEnd = start + 1;
    textarea.focus(); // 确保保持焦点
  });
};

// ctrl + enter 换行
// enter 发送
const handShortcut = (e: KeyboardEvent) => {
  if (e.key === 'Enter') {
    e.preventDefault();

    if (!e.shiftKey && !e.ctrlKey) {
      handleSend();
    } else insertNewline();
  }
};

onMounted(() => {
  chatStore.scrollToBottom = scrollToBottom;
});
</script>

<template>
  <main class="relative min-w-0 flex flex-1 flex-col">
    <div class="flex h-52px shrink-0 items-center justify-between b-b b-#e5e7eb bg-white px-5 dark:b-#2b2b31 dark:bg-#18181c">
      <NText strong class="truncate">{{ activeSession?.title || '新会话' }}</NText>
      <div class="flex items-center text-18px color-gray-500">
        <NText class="text-14px">连接状态：</NText>
        <icon-eos-icons:loading v-if="wsStatus === 'CONNECTING'" class="color-yellow" />
        <icon-fluent:plug-connected-checkmark-20-filled v-else-if="wsStatus === 'OPEN'" class="color-green" />
        <icon-tabler:plug-connected-x v-else class="color-red" />
      </div>
    </div>

    <NScrollbar ref="scrollbarRef" class="min-h-0 flex-1 px-6 pb-24 pt-5">
      <NSpin :show="loading">
        <VueMarkdownItProvider>
          <ChatMessage v-for="(item, index) in messages" :key="item.id || index" :msg="item" />
        </VueMarkdownItProvider>
        <NEmpty v-if="!messages.length" description="暂无消息" class="mt-30" />
      </NSpin>
    </NScrollbar>

    <div
      class="pointer-events-none absolute inset-x-0 bottom-0 z-10 flex flex-col items-center bg-transparent px-4 pb-3"
    >
      <div
        class="pointer-events-auto flex w-full max-w-920px items-center gap-3 rounded-24px bg-white px-5 py-3 ring-1 ring-#e5e7eb dark:bg-#18181c dark:ring-#2b2b31"
      >
        <textarea
          ref="inputRef"
          v-model.trim="input.message"
          rows="1"
          placeholder="有问题，尽管问"
          class="h-24px min-h-24px flex-1 cursor-text resize-none overflow-y-auto bg-transparent p-0 text-16px leading-24px color-#333 caret-[rgb(var(--primary-color))] outline-none placeholder:color-#9ca3af dark:color-#f1f1f1 dark:placeholder:color-#6b7280"
          @keydown="handShortcut"
        />
        <NButton
          :disabled="sendable"
          strong
          circle
          type="primary"
          class="shrink-0"
          @click="handleSend"
        >
          <template #icon>
            <icon-material-symbols:stop-rounded v-if="isSending" />
            <icon-guidance:send v-else />
          </template>
        </NButton>
      </div>
      <p class="pointer-events-none mt-3 text-center text-13px color-#9ca3af dark:color-#6b7280">
        NexusMind 也可能会犯错。请核查重要信息。
      </p>
    </div>
  </main>
</template>

<style scoped></style>
