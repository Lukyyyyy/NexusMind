<script setup lang="ts">
import { VueMarkdownIt } from 'vue-markdown-shiki';
import DocumentMarkdown from '@/components/custom/document-markdown.vue';
defineOptions({ name: 'ChatMessage' });

const props = defineProps<{ msg: Api.Chat.Message }>();

const weekDayLabels = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

const messageTime = computed(() => {
  if (!props.msg.timestamp) return '';

  const timestamp = dayjs(props.msg.timestamp);
  if (!timestamp.isValid()) return '';

  const today = dayjs().startOf('day');
  const messageDay = timestamp.startOf('day');
  const time = timestamp.format('HH:mm');

  if (messageDay.isSame(today)) return time;
  if (messageDay.isSame(today.subtract(1, 'day'))) return `昨天 ${time}`;

  // 周一作为一周的开始；更早的消息（包括上周）显示具体日期。
  const daysSinceMonday = (today.day() + 6) % 7;
  const currentWeekStart = today.subtract(daysSinceMonday, 'day');
  if (!messageDay.isBefore(currentWeekStart) && !messageDay.isAfter(today)) {
    return `${weekDayLabels[timestamp.day()]} ${time}`;
  }

  return timestamp.format('MM-DD HH:mm');
});

function handleCopy(content: string) {
  navigator.clipboard.writeText(content);
  window.$message?.success('已复制');
}

const traceExpanded = ref(false);
const traceSteps = computed<Api.Chat.AgentStep[]>(() => {
  if (Array.isArray(props.msg.agentTrace)) return props.msg.agentTrace;
  if (typeof props.msg.agentTrace === 'string') {
    try {
      return JSON.parse(props.msg.agentTrace) as Api.Chat.AgentStep[];
    } catch {
      return [];
    }
  }
  return [];
});
const traceRunning = computed(() => traceSteps.value.some(step => step.status === 'running'));
const thinkingElapsedMs = ref(0);
let thinkingTimer: ReturnType<typeof setInterval> | null = null;

const thinkingRunning = computed(
  () => props.msg.thinkingStartedAt != null && props.msg.thinkingDurationMs == null && !props.msg.content
);
const thinkingDuration = computed(() => props.msg.thinkingDurationMs ?? thinkingElapsedMs.value);
const traceActive = computed(() => traceRunning.value || thinkingRunning.value);

function formatDuration(durationMs: number) {
  const seconds = durationMs / 1000;
  return `${Math.max(0, Math.ceil(seconds))} 秒`;
}

const thinkingSummary = computed(() => {
  if (props.msg.thinkingStartedAt == null && props.msg.thinkingDurationMs == null) return '';
  return thinkingRunning.value
    ? `已用时 ${formatDuration(thinkingDuration.value)}`
    : `耗时 ${formatDuration(thinkingDuration.value)}`;
});

function stopThinkingTimer() {
  if (thinkingTimer == null) return;
  clearInterval(thinkingTimer);
  thinkingTimer = null;
}

function syncThinkingTimer() {
  stopThinkingTimer();
  if (!thinkingRunning.value || !props.msg.thinkingStartedAt) return;
  const updateElapsed = () => {
    thinkingElapsedMs.value = Math.max(0, Date.now() - (props.msg.thinkingStartedAt || Date.now()));
  };
  updateElapsed();
  thinkingTimer = setInterval(updateElapsed, 1000);
}

watch(thinkingRunning, syncThinkingTimer, { immediate: true });
onUnmounted(stopThinkingTimer);

const traceSummary = computed(() => {
  if (thinkingRunning.value && !traceSteps.value.length) return '正在思考';
  if (traceRunning.value) return traceSteps.value.at(-1)?.title || '正在处理';
  const toolCount = traceSteps.value.filter(step => step.category === 'tool').length;
  return toolCount > 0 ? `已完成 · 调用了 ${toolCount} 次工具` : '已完成思考与整理';
});

watch(
  () => [props.msg.status, traceSteps.value.length] as const,
  ([status, length], previous) => {
    if (!length) return;
    if (status === 'finished' || status === 'cancelled' || status === 'error') {
      traceExpanded.value = false;
    } else if (!previous || previous[1] === 0) {
      traceExpanded.value = true;
    }
  },
  { immediate: true }
);

function formatInput(input?: Record<string, string | number> | null) {
  if (!input) return [];
  const labels: Record<string, string> = { query: '查询', sourceId: '来源', before: '前文', after: '后文' };
  return Object.entries(input)
    .filter(([key]) => key !== 'topK' && key !== 'limit')
    .map(([key, value]) => ({ label: labels[key] || key, value }));
}

const sourceNames = reactive<Record<string, string>>({});
const loadingSourceNames = new Set<string>();
const sourceDialogVisible = ref(false);
const sourceLoading = ref(false);
const selectedSource = ref<Api.KnowledgeBase.DocumentChunk | null>(null);
const isSelectedSourceMarkdown = computed(() => selectedSource.value?.contentFormat === 'MARKDOWN');

const sourceTokenPattern = /kb:([a-f\d]{32,64})(?::(\d+(?:\s*[、,，]\s*\d+)*))?/gi;
const wrappedSourcePattern = /[（(]\s*(?:来源|编号)\s*[：:]\s*(kb:[a-f\d]{32,64}(?::\d+(?:\s*[、,，]\s*\d+)*)?)\s*[）)]/gi;
const bracketedSourcePattern = /\[\s*(kb:[a-f\d]{32,64}(?::\d+(?:\s*[、,，]\s*\d+)*)?)\s*\]/gi;

function escapeHtml(value: string) {
  return value.replace(/[&<>"']/g, character => {
    const entities: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
    return entities[character];
  });
}

function renderSourceReferences(fileMd5: string, chunkIds?: string) {
  const fileName = escapeHtml(sourceNames[fileMd5] || '文档');
  if (!chunkIds) return '';
  return chunkIds
    .split(/\s*[、,，]\s*/)
    .map(chunkId =>
      `<a href="#" class="source-reference" data-file-md5="${fileMd5}" data-chunk-id="${chunkId}">${fileName} · 分片 ${chunkId}</a>`
    )
    .join(' ');
}

function processSourceReferences(text: string) {
  const replaceToken = (_match: string, fileMd5: string, chunkIds?: string) =>
    chunkIds ? `[ ${renderSourceReferences(fileMd5.toLowerCase(), chunkIds)} ]` : '';
  return text
    .replace(/(`+)(kb:[a-f\d]{32,64}(?::\d+(?:\s*[、,，]\s*\d+)*)?)\1/gi, '$2')
    .replace(/\[\s*sourceId\s*[:：]\s*(\[\s*kb:[a-f\d]{32,64}(?::\d+(?:\s*[、,，]\s*\d+)*)?\s*\])\s*\]/gi, '$1')
    .replace(wrappedSourcePattern, '$1')
    .replace(bracketedSourcePattern, '$1')
    .replace(sourceTokenPattern, replaceToken);
}

function collectSourceFileMd5s(text: string) {
  return [...text.matchAll(sourceTokenPattern)].filter(match => match[2]).map(match => match[1].toLowerCase());
}

async function resolveSourceName(fileMd5: string) {
  if (sourceNames[fileMd5] || loadingSourceNames.has(fileMd5)) return;
  loadingSourceNames.add(fileMd5);
  const { error, data } = await request<Api.KnowledgeBase.DocumentChunkSummary>({
    url: `/documents/${fileMd5}/chunks/summary`
  });
  if (!error && data?.fileName) sourceNames[fileMd5] = data.fileName;
  loadingSourceNames.delete(fileMd5);
}

watch(
  () => props.msg.content,
  text => {
    if (props.msg.role !== 'assistant') return;
    new Set(collectSourceFileMd5s(text || '')).forEach(resolveSourceName);
  },
  { immediate: true }
);

const content = computed(() => {
  const rawContent = props.msg.content ?? '';

  // 只对助手消息处理来源链接
  if (props.msg.role === 'assistant') {
    return processSourceReferences(rawContent);
  }

  return rawContent;
});

// 处理内容点击事件（事件委托）
async function handleContentClick(event: MouseEvent) {
  const target = event.target as HTMLElement;

  const sourceButton = target.closest<HTMLElement>('.source-reference');
  const fileMd5 = sourceButton?.dataset.fileMd5;
  const chunkId = Number(sourceButton?.dataset.chunkId);
  if (!fileMd5 || !Number.isInteger(chunkId)) return;
  event.preventDefault();

  selectedSource.value = {
    fileMd5,
    fileName: sourceNames[fileMd5] || '文档',
    chunkId,
    contentPreview: '',
    contentLength: 0,
    byteSize: 0,
    configuredChunkSize: 0
  };
  sourceDialogVisible.value = true;
  sourceLoading.value = true;
  const { error, data } = await request<Api.KnowledgeBase.DocumentChunk>({
    url: `/documents/${fileMd5}/chunks/${chunkId}`
  });
  if (!error) {
    selectedSource.value = data;
    if (data.fileName) sourceNames[fileMd5] = data.fileName;
  }
  sourceLoading.value = false;
}
</script>

<template>
  <div class="chat-message" :class="msg.role === 'user' ? 'chat-message--user' : 'chat-message--assistant'">
    <div v-if="msg.showScope && msg.scope" class="chat-message__scope">
      <span><icon-solar:layers-minimalistic-linear />{{ msg.scope.label }}</span>
      <NTooltip v-if="msg.scope.details?.length" trigger="hover">
        <template #trigger><button type="button">查看范围</button></template>
        <div class="max-w-320px">
          <div v-for="detail in msg.scope.details" :key="detail" class="truncate">{{ detail }}</div>
        </div>
      </NTooltip>
    </div>
    <div class="chat-message__inner">
      <template v-if="msg.role === 'user'">
        <div class="chat-message__user-row">
          <div class="chat-message__user-stack">
            <div class="chat-message__user-bubble">
              <NText tag="div" class="chat-message__text whitespace-pre-wrap text-4">{{ content }}</NText>
            </div>
            <div class="chat-message__actions justify-end">
              <time v-if="messageTime" class="chat-message__time" :datetime="msg.timestamp">{{ messageTime }}</time>
              <NButton quaternary size="tiny" aria-label="复制消息" @click="handleCopy(msg.content)">
                <template #icon><icon-mynaui:copy /></template>
              </NButton>
            </div>
          </div>
        </div>
      </template>

      <template v-else>
        <div class="chat-message__assistant-row">
          <div class="chat-message__assistant-avatar" aria-hidden="true">
            <SystemLogo />
          </div>
          <div class="chat-message__assistant-body">
            <div v-if="traceSteps.length || thinkingSummary" class="agent-trace" :class="{ 'is-running': traceActive }">
              <button
                type="button"
                class="agent-trace__summary"
                :aria-expanded="traceExpanded"
                @click="traceExpanded = !traceExpanded"
              >
                <span class="agent-trace__summary-main">
                  <icon-eos-icons:three-dots-loading v-if="traceActive" class="agent-trace__spinner" />
                  <icon-solar:magic-stick-3-linear v-else />
                  <span>{{ traceSummary }}</span>
                  <span v-if="thinkingSummary" class="agent-trace__thinking-time">· {{ thinkingSummary }}</span>
                </span>
                <icon-material-symbols:keyboard-arrow-down-rounded
                  class="agent-trace__arrow"
                  :class="{ 'is-expanded': traceExpanded }"
                />
              </button>
              <div v-show="traceExpanded" class="agent-trace__body">
                <div v-for="step in traceSteps" :key="step.stepId" class="agent-step">
                  <div class="agent-step__rail">
                    <span class="agent-step__dot" :class="`is-${step.status}`">
                      <icon-eos-icons:loading v-if="step.status === 'running'" />
                      <icon-material-symbols:close-rounded v-else-if="step.status === 'error'" />
                      <icon-material-symbols:check-rounded v-else />
                    </span>
                    <span class="agent-step__line"></span>
                  </div>
                  <div class="agent-step__content">
                    <div class="agent-step__heading">
                      <span>{{ step.title }}</span>
                      <span v-if="step.durationMs != null" class="agent-step__duration">{{ step.durationMs }} ms</span>
                    </div>
                    <p>{{ step.detail }}</p>
                    <div v-if="formatInput(step.input).length" class="agent-step__input">
                      <span v-for="item in formatInput(step.input)" :key="item.label">
                        <b>{{ item.label }}</b>{{ item.value }}
                      </span>
                    </div>
                  </div>
                </div>
                <div v-if="traceActive && !traceRunning" class="agent-step">
                  <div class="agent-step__rail">
                    <span class="agent-step__dot is-running">
                      <icon-eos-icons:loading />
                    </span>
                    <span class="agent-step__line"></span>
                  </div>
                  <div class="agent-step__content">
                    <div class="agent-step__heading"><span>正在分析检索结果</span></div>
                    <p>判断是否需要继续检索，并规划下一步</p>
                  </div>
                </div>
              </div>
            </div>
            <div class="chat-message__assistant-content">
              <NText v-if="msg.status === 'pending'">
                <icon-eos-icons:three-dots-loading class="text-8" />
              </NText>
              <NText v-else-if="msg.status === 'error'" class="italic">服务器繁忙，请稍后再试</NText>
              <NText v-else tag="div" class="chat-message__markdown text-4" @click="handleContentClick">
                <VueMarkdownIt :content="content" />
              </NText>
              <NText v-if="msg.status === 'cancelled'" depth="3" class="mt-2 block text-13px">已停止生成</NText>
            </div>
            <div class="chat-message__actions justify-start">
              <NButton quaternary size="tiny" aria-label="复制消息" @click="handleCopy(msg.content)">
                <template #icon><icon-mynaui:copy /></template>
              </NButton>
              <time v-if="messageTime" class="chat-message__time" :datetime="msg.timestamp">{{ messageTime }}</time>
            </div>
          </div>
        </div>
      </template>
    </div>
    <NModal
      v-model:show="sourceDialogVisible"
      preset="card"
      class="source-dialog"
      :title="`${selectedSource?.fileName || '文档'} · 分片 ${selectedSource?.chunkId ?? ''}`"
      :style="{ width: 'min(680px, 92vw)' }"
    >
      <NSpin :show="sourceLoading">
        <div class="source-dialog__content">
          <DocumentMarkdown
            v-if="selectedSource && isSelectedSourceMarkdown"
            class="markdown-body source-dialog__markdown"
            :content="selectedSource.content || ''"
            :file-md5="selectedSource.fileMd5"
          />
          <pre v-else class="source-dialog__plain-text">{{ selectedSource?.content || '' }}</pre>
        </div>
      </NSpin>
    </NModal>
  </div>
</template>

<style scoped lang="scss">
:deep(.source-reference) {
  display: inline;
  border-radius: 5px;
  background: #eef3ff;
  padding: 1px 6px;
  box-decoration-break: clone;
  color: #245bdb;
  cursor: pointer;
  font: inherit;
  font-size: 0.86em;
  line-height: 1.55;
  overflow-wrap: anywhere;
  text-decoration: none;
  -webkit-box-decoration-break: clone;
  white-space: normal;
  transition: background 0.16s ease;

  &:hover {
    background: #dfe9ff;
  }
}

:global(.dark) :deep(.source-reference) {
  background: #26334f;
  color: #a9c2ff;
}

.source-dialog__content {
  max-height: min(62vh, 620px);
  min-height: 80px;
  overflow-y: auto;
  color: #334155;
  font-size: 14px;
  line-height: 1.75;
  word-break: break-word;
}

.source-dialog__plain-text {
  margin: 0;
  font: inherit;
  white-space: pre-wrap;
}

.source-dialog__markdown {
  background: transparent;
  color: inherit;
}

:global(.dark) .source-dialog__content {
  color: #e2e8f0;
}

.chat-message {
  margin-bottom: 22px;
}

.chat-message__inner {
  max-width: 860px;
  margin: 0 auto;
}

.chat-message__user-row {
  display: flex;
  justify-content: flex-end;
}

.chat-message__user-stack {
  display: flex;
  max-width: min(72%, 640px);
  align-items: flex-end;
  flex-direction: column;
}

.chat-message__user-bubble {
  max-width: 100%;
  border: 1px solid #d8e3fb;
  border-radius: 14px 14px 4px;
  background: #f1f5ff;
  padding: 10px 14px;
  color: #233438;
  line-height: 1.6;
}

:global(.dark) .chat-message__user-bubble {
  background: #26262b;
}

.chat-message__assistant-content {
  max-width: 790px;
  color: #1f2937;
}

.chat-message__assistant-row {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  align-items: flex-start;
  gap: 12px;
}

.chat-message__assistant-avatar {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border: 1px solid #d6e1fb;
  border-radius: 9px;
  background: #f2f6ff;
  color: #245bdb;
  font-size: 21px;
}

.chat-message__assistant-body {
  min-width: 0;
  padding-top: 4px;
}

.agent-trace {
  max-width: 790px;
  margin-bottom: 14px;
  overflow: hidden;
  border: 1px solid #dce4f2;
  border-radius: 12px;
  background: #f8faff;
}

.agent-trace.is-running {
  border-color: #c8d8fb;
  box-shadow: 0 0 0 2px rgb(36 91 219 / 5%);
}

.agent-trace__summary {
  display: flex;
  width: 100%;
  min-height: 42px;
  align-items: center;
  justify-content: space-between;
  border: 0;
  background: transparent;
  padding: 9px 12px;
  color: #526176;
  cursor: pointer;
  font: inherit;
  text-align: left;
}

.agent-trace__summary:hover {
  background: rgb(36 91 219 / 4%);
}

.agent-trace__summary-main {
  display: inline-flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
}

.agent-trace__spinner {
  color: #245bdb;
}

.agent-trace__thinking-time {
  color: #718096;
  font-weight: 500;
  white-space: nowrap;
}

.agent-trace__arrow {
  flex-shrink: 0;
  font-size: 20px;
  transition: transform 160ms ease;
}

.agent-trace__arrow.is-expanded {
  transform: rotate(180deg);
}

.agent-trace__body {
  border-top: 1px solid #e3e9f4;
  padding: 12px 14px 5px;
}

.agent-step {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr);
  gap: 9px;
}

.agent-step__rail {
  display: flex;
  align-items: center;
  flex-direction: column;
}

.agent-step__dot {
  display: grid;
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  place-items: center;
  border-radius: 50%;
  background: #e4eaf5;
  color: #65748a;
  font-size: 12px;
}

.agent-step__dot.is-running {
  background: #e8f0ff;
  color: #245bdb;
}

.agent-step__dot.is-error {
  background: #fff0f0;
  color: #d14343;
}

.agent-step__line {
  width: 1px;
  min-height: 18px;
  flex: 1;
  background: #dfe5ef;
}

.agent-step:last-child .agent-step__line {
  visibility: hidden;
}

.agent-step__content {
  min-width: 0;
  padding: 0 0 14px;
}

.agent-step__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: #334155;
  font-size: 13px;
  font-weight: 600;
  line-height: 18px;
}

.agent-step__duration {
  color: #94a3b8;
  font-size: 11px;
  font-weight: 400;
}

.agent-step__content p {
  margin: 4px 0 0;
  color: #718096;
  font-size: 12px;
  line-height: 1.55;
}

.agent-step__input {
  display: flex;
  margin-top: 7px;
  flex-wrap: wrap;
  gap: 6px;
}

.agent-step__input span {
  max-width: 100%;
  overflow: hidden;
  border: 1px solid #dfe6f2;
  border-radius: 6px;
  background: #fff;
  padding: 3px 7px;
  color: #65748a;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-step__input b {
  margin-right: 5px;
  color: #40516a;
  font-weight: 600;
}

:global(.dark) .agent-trace {
  border-color: #323d4f;
  background: #1c232d;
}

:global(.dark) .agent-trace__body {
  border-color: #303a49;
}

:global(.dark) .agent-trace__summary,
:global(.dark) .agent-step__heading {
  color: #d7deea;
}

:global(.dark) .agent-step__input span {
  border-color: #374253;
  background: #171d25;
  color: #a8b3c4;
}

:global(.dark) .agent-step__input b {
  color: #d1d9e6;
}

:global(.dark) .chat-message__assistant-content {
  color: #f1f5f9;
}

.chat-message__actions {
  display: flex;
  min-height: 24px;
  align-items: center;
  margin-top: 4px;
  gap: 4px;
  opacity: 0;
  pointer-events: none;
  visibility: hidden;
  transition:
    opacity 160ms ease,
    visibility 160ms ease;
}

.chat-message__user-stack:hover .chat-message__actions,
.chat-message__user-stack:focus-within .chat-message__actions,
.chat-message__assistant-body:hover .chat-message__actions,
.chat-message__assistant-body:focus-within .chat-message__actions {
  opacity: 1;
  pointer-events: auto;
  visibility: visible;
}

.chat-message__time {
  color: #8a94a6;
  font-size: 12px;
  line-height: 24px;
  white-space: nowrap;
}

.chat-message__text,
.chat-message__markdown {
  color: inherit;
  line-height: 1.5;
}

.chat-message__scope {
  display: flex;
  max-width: 860px;
  align-items: center;
  justify-content: space-between;
  margin: 0 auto 16px;
  border-bottom: 1px solid #edf0f4;
  padding: 0 2px 9px;
  color: #7b8794;
  font-size: 12px;
}

.chat-message__scope span,
.chat-message__scope button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.chat-message__scope button { background: transparent; color: #245bdb; }

:global(.dark) .chat-message__scope { border-color: #2d3138; color: #929baa; }

.chat-message__markdown {
  :deep(.vp-doc) {
    width: auto;
    min-width: 0;
    color: inherit;
    font-size: inherit;
    line-height: 1.75;
    overflow-wrap: anywhere;
  }

  :deep(.vp-doc > :first-child) {
    margin-top: 0;
  }

  :deep(.vp-doc > :last-child) {
    margin-bottom: 0;
  }

  :deep(.vp-doc p) {
    margin: 0 0 1em;
    font-size: inherit;
    line-height: 1.75;
  }

  :deep(.vp-doc ul),
  :deep(.vp-doc ol) {
    margin: 0.75em 0 1em;
    padding-left: 1.5em;
  }

  :deep(.vp-doc li) {
    margin: 0.35em 0;
    line-height: 1.75;
  }

  :deep(.vp-doc h1),
  :deep(.vp-doc h2),
  :deep(.vp-doc h3),
  :deep(.vp-doc h4),
  :deep(.vp-doc h5),
  :deep(.vp-doc h6) {
    margin: 0.8em 0 0.4em;
    border: 0;
    padding: 0;
    font-size: 1em;
    line-height: 1.5;
  }

  :deep(.vp-doc a) {
    color: #245bdb;
    font-weight: inherit;
    overflow-wrap: anywhere;
    word-break: break-word;
    text-underline-offset: 3px;
  }

  :deep(.vp-doc img) {
    max-width: 100%;
    height: auto;
  }

  :deep(.vp-doc table) {
    display: block;
    max-width: 100%;
    overflow-x: auto;
  }

  :deep(.vp-doc strong) {
    font-weight: 600;
  }
}

@media (max-width: 640px) {
  .chat-message {
    margin-bottom: 18px;
  }

  .chat-message__inner {
    max-width: 100%;
  }

  .chat-message__user-stack {
    max-width: 86%;
  }

  .chat-message__assistant-row {
    grid-template-columns: 28px minmax(0, 1fr);
    gap: 9px;
  }

  .chat-message__assistant-avatar {
    width: 28px;
    height: 28px;
    border-radius: 8px;
    font-size: 18px;
  }
}
</style>
