<script setup lang="ts">
defineOptions({
  name: 'ChatList'
});

const chatStore = useChatStore();
const appStore = useAppStore();
const { sessions, archivedSessions, activeSessionId, sessionLoading, generatingSessionId } = storeToRefs(chatStore);

const editingId = ref<number | null>(null);
const editingTitle = ref('');
const listView = ref<'history' | 'archived'>('history');
const visibleSessions = computed(() => listView.value === 'history' ? sessions.value : archivedSessions.value);
const collapsedStorageKey = 'nexusmind-chat-panel-collapsed';
const collapsed = ref(
  appStore.isMobile || (typeof window !== 'undefined' && window.localStorage.getItem(collapsedStorageKey) === 'true')
);

function formatSessionTime(timestamp: string) {
  const date = dayjs(timestamp);
  if (!date.isValid()) return '';
  if (date.isSame(dayjs(), 'day')) return date.format('HH:mm');
  if (date.isSame(dayjs().subtract(1, 'day'), 'day')) return '昨天';
  return date.format('MM/DD');
}

function setTitleScrollDuration(event: MouseEvent) {
  const title = (event.currentTarget as HTMLElement).querySelector<HTMLElement>('.chat-list__session-title');
  if (!title) return;
  title.style.setProperty('--title-scroll-duration', `${Math.max(0, title.scrollWidth - title.clientWidth) / 40}s`);
}

watch(
  () => appStore.isMobile,
  isMobile => {
    if (isMobile) collapsed.value = true;
  },
  { immediate: true }
);

watch(() => chatStore.activeSession, session => {
  if (session && !session.archivedAt && sessions.value.some(item => item.id === session.id)) {
    listView.value = 'history';
  }
});

function toggleCollapsed() {
  collapsed.value = !collapsed.value;
  window.localStorage.setItem(collapsedStorageKey, String(collapsed.value));
  editingId.value = null;
}

async function handleCreate() {
  listView.value = 'history';
  await chatStore.createSession();
}

async function handleSelect(sessionId: number) {
  if (editingId.value) return;
  await chatStore.selectSession(sessionId);
}

function startRename(session: Api.Chat.Session) {
  editingId.value = session.id;
  editingTitle.value = session.title;
}

async function submitRename(sessionId: number) {
  const title = editingTitle.value.trim();
  if (!title) {
    window.$message?.error('会话标题不能为空');
    return;
  }
  const ok = await chatStore.renameSession(sessionId, title);
  if (ok) {
    editingId.value = null;
    editingTitle.value = '';
  }
}

async function handleDelete(sessionId: number) {
  window.$dialog?.warning({
    title: '删除会话',
    content: '删除后该会话将不再显示。',
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await chatStore.deleteSession(sessionId);
    }
  });
}

async function handleArchive(sessionId: number) {
  if (generatingSessionId.value === sessionId) return;
  if (await chatStore.setArchived(sessionId, true)) window.$message?.success('会话已归档');
}

async function handleRestore(sessionId: number) {
  if (!await chatStore.setArchived(sessionId, false)) return;
  listView.value = 'history';
  await chatStore.selectSession(sessionId);
  window.$message?.success('会话已恢复');
}

function handleAction(action: string, session: Api.Chat.Session) {
  if (action === 'rename') startRename(session);
  else if (action === 'archive') void handleArchive(session.id);
  else if (action === 'delete') void handleDelete(session.id);
}
</script>

<template>
  <aside
    class="chat-list relative h-full shrink-0 b-r b-#e5e7eb bg-white dark:b-#2b2b31 dark:bg-#18181c"
    :class="{ 'is-collapsed': collapsed }"
  >
    <NTooltip placement="right">
      <template #trigger>
        <button type="button" class="chat-list__toggle" @click="toggleCollapsed">
          <icon-material-symbols:keyboard-arrow-right v-if="collapsed" />
          <icon-material-symbols:keyboard-arrow-left v-else />
        </button>
      </template>
      {{ collapsed ? '展开会话列表' : '折叠会话列表' }}
    </NTooltip>

    <div v-if="!collapsed" class="chat-list__header">
      <NButton type="primary" block class="chat-list__create" @click="handleCreate">
        <template #icon><icon-material-symbols:add-rounded /></template>
        新建会话
      </NButton>
      <div class="chat-list__tabs" role="tablist" aria-label="会话列表">
        <button
          type="button"
          role="tab"
          :aria-selected="listView === 'history'"
          :class="{ 'is-selected': listView === 'history' }"
          @click="listView = 'history'; editingId = null"
        >
          历史会话 <span>{{ sessions.length }}</span>
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="listView === 'archived'"
          :class="{ 'is-selected': listView === 'archived' }"
          @click="listView = 'archived'; editingId = null"
        >
          已归档 <span>{{ archivedSessions.length }}</span>
        </button>
      </div>
    </div>

    <NSpin v-if="!collapsed" :show="sessionLoading" class="chat-list__spin" content-class="chat-list__spin-content">
      <NScrollbar class="chat-list__scroll">
        <div class="chat-list__sessions">
          <div
            v-for="session in visibleSessions"
            :key="session.id"
            class="chat-list__session group"
            :class="{ 'is-active': activeSessionId === session.id }"
            role="button"
            tabindex="0"
            :aria-label="session.title"
            @click="handleSelect(session.id)"
            @keydown.enter.self.prevent="handleSelect(session.id)"
            @keydown.space.self.prevent="handleSelect(session.id)"
            @mouseenter="setTitleScrollDuration"
          >
            <div v-if="editingId === session.id" class="flex items-center gap-1">
              <NInput
                v-model:value="editingTitle"
                size="small"
                autofocus
                @keydown.enter.stop="submitRename(session.id)"
                @keydown.esc.stop="editingId = null"
              />
              <NButton size="tiny" quaternary circle @click.stop="submitRename(session.id)">
                <template #icon>
                  <icon-material-symbols:check />
                </template>
              </NButton>
            </div>
            <div v-else class="chat-list__session-row">
              <icon-solar:chat-round-line-duotone class="chat-list__session-icon" />
              <span class="chat-list__session-title" :title="session.title">
                <NText class="chat-list__session-title-text">{{ session.title }}</NText>
              </span>
              <time>{{ formatSessionTime(session.lastConversationAt || session.updatedAt) }}</time>
              <NTooltip v-if="listView === 'archived'" placement="top">
                <template #trigger>
                  <NButton size="tiny" quaternary circle aria-label="恢复会话" @click.stop="handleRestore(session.id)">
                    <template #icon><icon-material-symbols:unarchive-outline /></template>
                  </NButton>
                </template>
                恢复会话
              </NTooltip>
              <NDropdown
                v-else
                trigger="click"
                :options="[
                  { label: '重命名', key: 'rename' },
                  { label: '归档', key: 'archive', disabled: generatingSessionId === session.id },
                  { label: '删除', key: 'delete' }
                ]"
                @select="key => handleAction(String(key), session)"
              >
                <NButton size="tiny" quaternary circle aria-label="会话操作" @click.stop>
                  <template #icon><icon-material-symbols:more-horiz /></template>
                </NButton>
              </NDropdown>
            </div>
          </div>
          <NEmpty
            v-if="!visibleSessions.length"
            :description="listView === 'archived' ? '暂无已归档会话' : '暂无会话'"
            class="mt-20"
          />
        </div>
      </NScrollbar>
    </NSpin>
  </aside>
</template>

<style scoped lang="scss">
.chat-list {
  display: flex;
  flex-direction: column;
  width: 264px;
  border-color: #e4e9f0;
  background: #fff;
  padding: 14px 12px 12px;
  transition:
    width 180ms ease,
    padding 180ms ease;
  overflow: visible;
}

.chat-list.is-collapsed {
  width: 0;
  padding-right: 0;
  padding-left: 0;
}

.chat-list__spin {
  flex: 1 1 auto;
  min-height: 0;
}

.chat-list__spin :deep(.n-spin-content) {
  height: 100%;
}

.chat-list__scroll {
  height: 100%;
}

.chat-list__header {
  flex-shrink: 0;
  margin-bottom: 8px;
  padding: 0 2px;
}

.chat-list__create {
  --n-height: 40px !important;
  --n-border-radius: 9px !important;
  font-size: 14px;
}

.chat-list__tabs {
  display: flex;
  gap: 3px;
  margin-top: 14px;
  border-bottom: 1px solid #e9edf3;
}

.chat-list__tabs button {
  display: flex;
  min-width: 0;
  flex: 1;
  align-items: center;
  justify-content: center;
  gap: 5px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  padding: 10px 0 9px;
  color: #7b8798;
  font-size: 12px;
  cursor: pointer;
}

.chat-list__tabs button.is-selected {
  border-bottom-color: #356ae6;
  color: #245bdb;
  font-weight: 600;
}

.chat-list__tabs span {
  color: #98a2b3;
  font-variant-numeric: tabular-nums;
}

.chat-list__sessions { display: flex; flex-direction: column; gap: 4px; }

.chat-list__session {
  width: 100%;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  padding: 10px;
  text-align: left;
  cursor: pointer;
  transition: 150ms ease;
}

.chat-list__session:hover { background: #f4f6f9; }
.chat-list__session:focus-visible { outline: 2px solid #356ae6; outline-offset: 2px; }
.chat-list__session.is-active { border-color: #dbe5ff; background: #eef3ff; }
.chat-list__session-row { display: flex; min-width: 0; align-items: center; gap: 8px; }
.chat-list__session-icon { flex: 0 0 auto; color: #8a96a8; font-size: 17px; }
.chat-list__session-title { min-width: 0; flex: 1; overflow: hidden; container-type: inline-size; }
.chat-list__session-title-text {
  display: inline-block;
  min-width: max-content;
  font-size: 14px;
  white-space: nowrap;
  transition: transform 120ms ease-out;
}
.chat-list__session:hover .chat-list__session-title-text {
  transform: translateX(min(0px, calc(100cqw - 100%)));
  transition: transform var(--title-scroll-duration) linear 100ms;
}
.chat-list__session.is-active .chat-list__session-icon { color: #356ae6; }
.chat-list__session time { flex: 0 0 auto; color: #98a2b3; font-size: 11px; font-variant-numeric: tabular-nums; }
.chat-list__session .n-button { flex: 0 0 auto; }

.chat-list__toggle {
  position: absolute;
  top: 50%;
  right: -17px;
  z-index: 10;
  display: flex;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  border: 1px solid #e5e7eb;
  border-radius: 50%;
  background: #fff;
  color: #60636f;
  font-size: 20px;
  line-height: 1;
  box-shadow: 0 3px 10px rgb(15 23 42 / 6%);
  transform: translateY(-50%);
  transition:
    color 160ms ease,
    border-color 160ms ease,
    box-shadow 160ms ease;
}

.chat-list__toggle:hover {
  border-color: rgb(var(--primary-color));
  color: rgb(var(--primary-color));
  box-shadow: 0 4px 12px rgb(15 23 42 / 9%);
}

:global(.dark) .chat-list__toggle {
  border-color: #2b2b31;
  background: #18181c;
  color: #c9ccd6;
}

:global(.dark) .chat-list__session:hover { background: #24272d; }
:global(.dark) .chat-list__session.is-active { border-color: #3c527f; background: #202b40; }
:global(.dark) .chat-list__tabs { border-bottom-color: #303843; }

@media (prefers-reduced-motion: reduce) {
  .chat-list__session-title-text { transition: none; }
}

.chat-list.is-collapsed .chat-list__toggle {
  right: -28px;
  border-radius: 0 8px 8px 0;
}

@media (max-width: 760px) {
  .chat-list:not(.is-collapsed) {
    position: absolute;
    inset: 0 auto 0 0;
    z-index: 20;
    box-shadow: 10px 0 30px rgb(15 23 42 / 12%);
  }
}
</style>
