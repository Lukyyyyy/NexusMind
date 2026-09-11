<script setup lang="ts">
import type { NScrollbar } from 'naive-ui';
import { VueMarkdownItProvider } from 'vue-markdown-shiki';
import { fetchModelConfigOverview } from '@/service/api';
import ChatMessage from './chat-message.vue';
import ScopeSelector from './scope-selector.vue';

const chatStore = useChatStore();
const { input, messages, activeSession, loading, generatingSessionId, wsStatus, wsData } = storeToRefs(chatStore);
const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();
const inputDockRef = ref<HTMLElement>();
const inputDockHeight = ref(112);
const stopping = ref(false);
let inputDockResizeObserver: ResizeObserver | null = null;
let scrollFrame: number | null = null;

function finishThinking(message?: Api.Chat.Message) {
  if (!message?.thinkingStartedAt || message.thinkingDurationMs != null) return;
  message.thinkingDurationMs = Math.max(0, Date.now() - message.thinkingStartedAt);
}

const latestMessage = computed(() => {
  return messages.value[messages.value.length - 1] ?? {};
});

const displayMessages = computed(() => {
  const firstConversationMessage = messages.value.findIndex(item => item.role === 'user' || item.role === 'assistant');
  return messages.value.filter(
    (item, index) => item.role !== 'model' || (firstConversationMessage >= 0 && index > firstConversationMessage)
  );
});

const isSending = computed(() => {
  return (
    stopping.value
    || generatingSessionId.value === activeSession.value?.id
    || (latestMessage.value?.role === 'assistant' && ['loading', 'pending'].includes(latestMessage.value?.status || ''))
  );
});

const sendable = computed(
  () => stopping.value || (!input.value.message.trim() && !isSending.value) || ['CLOSED', 'CONNECTING'].includes(wsStatus.value)
);

const inputRef = ref<HTMLTextAreaElement>();
const inputMinHeight = 24;
const inputMaxHeight = 200;
const modelOverview = ref<Api.ModelConfig.Overview | null>(null);
const switchingModel = ref(false);

const llmConfigs = computed(() =>
  (modelOverview.value?.configs || []).filter(config => config.modelType === 'LLM' && config.enabled)
);
const currentModel = computed(() =>
  llmConfigs.value.find(config => config.id === (activeSession.value?.modelConfigId ?? modelOverview.value?.selectedLlmConfigId))
);
const currentModelName = computed(() => currentModel.value?.modelName || activeSession.value?.modelName || '选择模型');
const modelOptions = computed(() =>
  llmConfigs.value.map(config => ({
    label: config.modelName,
    key: config.id
  }))
);

const suggestions = [
  '知识库中有哪些文档？',
  '帮我总结知识库中的核心内容。',
  '我能访问哪些知识库文档？'
];
let websocketOpened = false;

function useSuggestion(suggestion: string) {
  input.value.message = suggestion;
  nextTick(() => inputRef.value?.focus());
}

async function loadModels() {
  const { data, error } = await fetchModelConfigOverview();
  if (!error) modelOverview.value = data;
}

async function switchModel(modelId: number) {
  if (modelId === activeSession.value?.modelConfigId || switchingModel.value || isSending.value) return;

  switchingModel.value = true;
  const session = await chatStore.switchSessionModel(modelId);
  if (session && modelOverview.value) {
    modelOverview.value.selectedLlmConfigId = modelId;
    window.$message?.success(`已切换至 ${session.modelName || '新模型'}`);
  }
  switchingModel.value = false;
}

const scrollbarContentStyle = computed(() => ({
  padding: `28px 28px ${inputDockHeight.value + 30}px`
}));

function updateInputDockHeight() {
  nextTick(() => {
    const dock = inputDockRef.value;
    if (!dock) return;
    inputDockHeight.value = Math.ceil(dock.getBoundingClientRect().height);
  });
}

function resizeInput() {
  nextTick(() => {
    const textarea = inputRef.value;
    if (!textarea) {
      updateInputDockHeight();
      return;
    }

    textarea.style.height = 'auto';
    const nextHeight = Math.min(textarea.scrollHeight, inputMaxHeight);
    textarea.style.height = `${Math.max(inputMinHeight, nextHeight)}px`;
    textarea.style.overflowY = textarea.scrollHeight > inputMaxHeight ? 'auto' : 'hidden';
    updateInputDockHeight();
  });
}

watch(wsData, val => {
  if (!val) return;
  const data = JSON.parse(val);
  if (data.type === 'title_updated') {
    if (typeof data.sessionId === 'number' && typeof data.title === 'string') {
      chatStore.applySessionTitle(data.sessionId, data.title);
    }
    return;
  }
  if (data.type === 'stop') return;
  if (data.type === 'agent_step') {
    const assistant = messages.value[messages.value.length - 1];
    if (!assistant || assistant.role !== 'assistant' || assistant.status === 'cancelled') return;
    const trace = Array.isArray(assistant.agentTrace) ? assistant.agentTrace : [];
    const index = trace.findIndex(step => step.stepId === data.stepId);
    const nextStep = data as Api.Chat.AgentStep;
    assistant.agentTrace = index >= 0
      ? trace.map((step, stepIndex) => (stepIndex === index ? nextStep : step))
      : [...trace, nextStep];
    assistant.status = 'loading';
    scrollToBottom();
    return;
  }

  const assistant = messages.value[messages.value.length - 1];

  if (data.type === 'content_replaced') {
    if (assistant?.role === 'assistant') assistant.content = data.content;
    return;
  }

  if (data.type === 'completion' && ['finished', 'cancelled'].includes(data.status)) {
    if (assistant?.role === 'assistant' && assistant.status !== 'error') {
      finishThinking(assistant);
      assistant.status = data.status;
      if (Array.isArray(assistant.agentTrace)) {
        assistant.agentTrace = assistant.agentTrace.map(step =>
          step.status === 'running' ? { ...step, status: 'completed' as const } : step
        );
      }
    }
    chatStore.refreshActiveSessionMessages();
  } else if (data.error) {
    if (assistant) {
      finishThinking(assistant);
      assistant.status = 'error';
    }
    window.$message?.error(data.error);
  } else if (data.chunk) {
    if (!assistant || assistant.status === 'cancelled') return;
    finishThinking(assistant);
    assistant.status = 'loading';
    assistant.content += data.chunk;
  }
  scrollToBottom();
}, { flush: 'sync' });

watch(wsStatus, status => {
  if (status !== 'OPEN') return;
  if (websocketOpened) chatStore.loadSessions();
  websocketOpened = true;
});

watch(() => [...messages.value], scrollToBottom);

function scrollToBottom() {
  if (!displayMessages.value.length) return;
  if (scrollFrame != null) return;
  scrollFrame = requestAnimationFrame(() => {
    scrollbarRef.value?.scrollBy({
      top: 999999999999,
      behavior: 'auto'
    });
    scrollFrame = null;
  });
}

const handleSend = async () => {
  if (stopping.value) return;

  //  判断是否正在发送, 如果发送中，则停止ai继续响应
  if (isSending.value) {
    stopping.value = true;
    const sessionId = activeSession.value?.id;
    const assistant = messages.value[messages.value.length - 1];
    const previousStatus = assistant?.status;
    if (assistant?.role === 'assistant') {
      finishThinking(assistant);
      assistant.status = 'cancelled';
      if (Array.isArray(assistant.agentTrace)) {
        assistant.agentTrace = assistant.agentTrace.map(step =>
          step.status === 'running' ? { ...step, status: 'completed' as const } : step
        );
      }
    }
    generatingSessionId.value = null;

    // 停止指令沿已认证的 WebSocket 会话发送，不再请求全局令牌。
    if (wsStatus.value !== 'OPEN') {
      const ticket = await chatStore.ensureWsTicket();
      if (ticket) chatStore.wsOpen();
    }
    chatStore.wsSend(JSON.stringify({ type: 'stop' }));
    stopping.value = false;
    return;
  }

  const sessionId = await chatStore.ensureActiveSession();
  if (!sessionId) return;

  const ticket = await chatStore.ensureWsTicket();
  if (!ticket) {
    window.$message?.error('聊天连接获取失败，请重新登录');
    return;
  }
  if (wsStatus.value !== 'OPEN') {
    chatStore.wsOpen();
  }

  const content = input.value.message.trim();
  if (!content) return;
  if (activeSession.value?.id === sessionId && activeSession.value.title === '新会话') {
    chatStore.applySessionTitle(sessionId, Array.from(content.replace(/\s+/g, ' ')).slice(0, 120).join(''));
  }

  const timestamp = new Date().toISOString();

  messages.value.push({
    content,
    role: 'user',
    status: 'finished',
    timestamp
  });
  messages.value.push({
    content: '',
    role: 'assistant',
    status: 'pending',
    agentTrace: [],
    timestamp,
    thinkingStartedAt: Date.now()
  });
  chatStore.startGeneration(sessionId);
  chatStore.wsSend(
    JSON.stringify({
      type: 'message',
      sessionId,
      content
    } satisfies Api.Chat.SendPayload)
  );
  input.value.message = '';
  resizeInput();
};
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
    resizeInput();
  });
};

// ctrl + enter 换行
// enter 发送
const handShortcut = (e: KeyboardEvent) => {
  if (e.isComposing) return;

  if (e.key === 'Enter') {
    e.preventDefault();

    if (!e.shiftKey && !e.ctrlKey) {
      handleSend();
    } else insertNewline();
  }
};

onMounted(() => {
  loadModels();
  chatStore.scrollToBottom = scrollToBottom;
  resizeInput();
  inputDockResizeObserver = new ResizeObserver(updateInputDockHeight);
  if (inputDockRef.value) inputDockResizeObserver.observe(inputDockRef.value);
  updateInputDockHeight();
});

watch(() => input.value.message, resizeInput);

onUnmounted(() => {
  inputDockResizeObserver?.disconnect();
  inputDockResizeObserver = null;
  if (scrollFrame != null) cancelAnimationFrame(scrollFrame);
});
</script>

<template>
  <main class="chat-workspace relative min-w-0 flex flex-1 flex-col">
    <div class="chat-toolbar">
      <div class="min-w-0">
        <NText strong class="block truncate text-15px">{{ activeSession?.title || '新会话' }}</NText>
      </div>
      <div class="chat-connection" :class="`is-${wsStatus.toLowerCase()}`">
        <icon-eos-icons:loading v-if="wsStatus === 'CONNECTING'" class="color-yellow" />
        <icon-fluent:plug-connected-checkmark-20-filled v-else-if="wsStatus === 'OPEN'" class="color-green" />
        <icon-tabler:plug-connected-x v-else class="color-red" />
        <NText class="text-12px">{{ wsStatus === 'OPEN' ? '已连接' : wsStatus === 'CONNECTING' ? '连接中' : '未连接' }}</NText>
      </div>
    </div>

    <NScrollbar
      ref="scrollbarRef"
      class="min-h-0 flex-1"
      :content-style="scrollbarContentStyle"
    >
      <NSpin :show="loading">
        <VueMarkdownItProvider>
          <template v-for="(item, index) in displayMessages" :key="item.id || `${item.role}-${index}`">
            <div v-if="item.role === 'model'" class="chat-model-change">
              <span>已切换至 {{ item.content }}</span>
            </div>
            <ChatMessage v-else :msg="item" />
          </template>
        </VueMarkdownItProvider>
        <section v-if="!displayMessages.length && !loading" class="chat-empty">
          <div class="chat-empty__icon"><icon-solar:chat-round-line-duotone /></div>
          <h1>你好，欢迎使用知枢 NexusMind</h1>
          <p></p>
<!--          <p>从知识库中检索信息，获得有依据的回答</p>-->
          <div class="chat-empty__suggestions">
            <button v-for="suggestion in suggestions" :key="suggestion" type="button" @click="useSuggestion(suggestion)">
              <span>{{ suggestion }}</span>
              <icon-material-symbols:arrow-forward-rounded />
            </button>
          </div>
        </section>
      </NSpin>
    </NScrollbar>

    <div
      ref="inputDockRef"
      class="chat-input-dock pointer-events-none absolute inset-x-0 bottom-0 z-10 flex flex-col items-center px-4 pb-3 pt-4"
    >
      <div class="chat-input pointer-events-auto">
        <textarea
          ref="inputRef"
          v-model="input.message"
          rows="1"
          placeholder="有问题，尽管问"
          class="chat-input__textarea"
          @keydown="handShortcut"
          @input="resizeInput"
        />
        <div class="chat-input__toolbar">
          <div class="chat-input__tools"><ScopeSelector /></div>
          <div class="chat-input__actions">
            <NDropdown
              trigger="click"
              placement="top-end"
              :options="modelOptions"
              :disabled="switchingModel || isSending || !modelOptions.length"
              @select="switchModel"
            >
              <button
                type="button"
                class="chat-input__model"
                :disabled="switchingModel || isSending || !modelOptions.length"
                :title="currentModelName"
                :aria-label="`当前模型：${currentModelName}，点击切换`"
              >
                <span>{{ currentModelName }}</span>
                <icon-material-symbols:keyboard-arrow-down-rounded />
              </button>
            </NDropdown>
            <NButton
              :disabled="sendable"
              strong
              circle
              type="primary"
              class="chat-input__send"
              :aria-label="isSending ? '停止生成' : '发送消息'"
              @click="handleSend"
            >
              <icon-material-symbols:stop-rounded v-if="isSending" />
              <icon-material-symbols:arrow-upward-rounded v-else class="text-22px" />
            </NButton>
          </div>
        </div>
      </div>
      <p class="pointer-events-none mt-3 text-center text-13px color-#9ca3af dark:color-#6b7280">
        NexusMind 也可能会犯错。请核查重要信息。
      </p>
    </div>
  </main>
</template>

<style scoped lang="scss">
.chat-model-change {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 18px 0;
  color: #9ca3af;
  font-size: 13px;

  &::before,
  &::after {
    height: 1px;
    flex: 1;
    background: #e5e7eb;
    content: '';
  }
}

:global(.dark) .chat-model-change {
  color: #6b7280;

  &::before,
  &::after {
    background: #30343b;
  }
}

.chat-input-dock {
  isolation: isolate;
  background: linear-gradient(to bottom, rgb(255 255 255 / 0%), #fff 28%, #fff 100%);

  &::before {
    content: '';
    position: absolute;
    top: 28px;
    bottom: 0;
    left: 50%;
    z-index: 0;
    width: min(calc(100% - 32px), 860px);
    border-radius: 18px 18px 0 0;
    background: transparent;
    transform: translateX(-50%);
  }
}

:global(.dark) .chat-input-dock::before {
  background: #18181c;
}

.chat-input,
.chat-input-dock > p {
  position: relative;
  z-index: 1;
}

.chat-input {
  display: flex;
  width: 100%;
  max-width: 820px;
  min-height: 108px;
  flex-direction: column;
  align-items: stretch;
  gap: 12px;
  border: 1px solid #dbe2ea;
  border-radius: 16px;
  background: #fff;
  padding: 16px 14px 12px 16px;
  box-shadow: 0 8px 24px rgb(15 23 42 / 7%);
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.chat-input:focus-within {
  border-color: #84a6f2;
  box-shadow: 0 0 0 3px rgb(36 91 219 / 10%), 0 12px 32px rgb(15 23 42 / 8%);
}

:global(.dark) .chat-input {
  background: #18181c;
  box-shadow: 0 0 0 1px #2b2b31;
}

.chat-input__textarea {
  width: 100%;
  min-height: 24px;
  max-height: 200px;
  flex: none;
  resize: none;
  overflow-y: hidden;
  border: 0;
  background: transparent;
  padding: 0;
  color: #333;
  font-size: 15px;
  line-height: 24px;
  outline: none;
  caret-color: rgb(var(--primary-color));
}

.chat-input__textarea::placeholder {
  color: #9ca3af;
}

:global(.dark) .chat-input__textarea {
  color: #f1f1f1;
}

:global(.dark) .chat-input__textarea::placeholder {
  color: #6b7280;
}

.chat-input__toolbar {
  display: flex;
  width: 100%;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
}

.chat-input__tools {
  min-width: 1px;
}

.chat-input__actions,
.chat-input__model {
  display: flex;
  align-items: center;
}

.chat-input__actions {
  min-width: 0;
  gap: 8px;
}

.chat-input__model {
  max-width: 220px;
  gap: 5px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  padding: 6px 7px;
  color: #4b5563;
  cursor: pointer;
}

.chat-input__model:hover {
  background: #f3f4f6;
  color: #245bdb;
}

.chat-input__model:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.chat-input__model span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:global(.dark) .chat-input__model {
  color: #c8d0db;
}

:global(.dark) .chat-input__model:hover {
  background: #252c35;
}

.chat-input__send {
  flex-shrink: 0;
  --n-width: 40px !important;
  --n-height: 40px !important;
  --n-border-radius: 999px !important;
  min-width: 40px;
  border-radius: 50% !important;
}

.chat-workspace {
  background: #fff;
}

.chat-toolbar {
  display: flex;
  min-height: 62px;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid #e7ebef;
  background: rgb(255 255 255 / 92%);
  padding: 10px 28px;
}

.chat-connection {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid #dce6e4;
  border-radius: 999px;
  background: #f6faf9;
  padding: 5px 9px;
  color: #64748b;
}

.chat-empty {
  display: flex;
  width: min(100%, 620px);
  min-height: calc(100vh - 350px);
  flex-direction: column;
  align-items: center;
  justify-content: center;
  margin: 0 auto;
  color: #162033;
  text-align: center;
}

.chat-empty__icon {
  display: grid;
  width: 46px;
  height: 46px;
  place-items: center;
  margin-bottom: 16px;
  border: 1px solid #cddafa;
  border-radius: 14px;
  background: #eef3ff;
  color: #356ae6;
  font-size: 26px;
}

.chat-empty h1 { margin: 0; font-size: 24px; font-weight: 700; letter-spacing: -0.02em; }
.chat-empty > p { margin: 9px 0 24px; color: #7b8798; font-size: 14px; }
.chat-empty__suggestions { display: grid; width: min(100%, 560px); gap: 9px; }
.chat-empty__suggestions button {
  display: flex;
  min-height: 48px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border: 1px solid #dfe5ed;
  border-radius: 11px;
  background: #fff;
  padding: 0 15px;
  color: #475467;
  font-size: 14px;
  text-align: left;
  transition: 160ms ease;
}
.chat-empty__suggestions button:hover {
  border-color: #a9bff2;
  color: #245bdb;
  box-shadow: 0 4px 12px rgb(15 23 42 / 5%);
}
.chat-empty__suggestions button svg { flex: 0 0 auto; color: #8b96a7; font-size: 18px; }

:global(.dark) .chat-workspace,
:global(.dark) .chat-toolbar {
  border-color: #2b3440;
  background: #181e25;
}

:global(.dark) .chat-input-dock {
  background: linear-gradient(to bottom, rgb(24 30 37 / 0%), #181e25 28%, #181e25 100%);
}

:global(.dark) .chat-empty { color: #edf0f5; }
:global(.dark) .chat-empty__icon { border-color: #3c527f; background: #202b40; }
:global(.dark) .chat-empty__suggestions button {
  border-color: #303843;
  background: #1d242c;
  color: #c8d0db;
}

@media (max-width: 640px) {
  .chat-toolbar {
    min-height: 56px;
    padding-right: 14px;
    padding-left: 48px;
  }

  .chat-connection {
    border: 0;
    background: transparent;
    padding-inline: 0;
  }

  .chat-input-dock {
    padding-inline: 10px;
  }

  .chat-input {
    min-height: 104px;
    border-radius: 14px;
  }

  .chat-input__model {
    max-width: 120px;
  }

  .chat-empty { min-height: calc(100vh - 330px); padding-inline: 8px; }
  .chat-empty h1 { font-size: 20px; }
}
</style>
