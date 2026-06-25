<script setup lang="ts">
defineOptions({
  name: 'ChatList'
});

const chatStore = useChatStore();
const { sessions, activeSessionId, sessionLoading } = storeToRefs(chatStore);

const editingId = ref<number | null>(null);
const editingTitle = ref('');

async function handleCreate() {
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
</script>

<template>
  <aside class="h-full w-280px shrink-0 b-r b-#e5e7eb bg-white p-3 dark:b-#2b2b31 dark:bg-#18181c">
    <div class="mb-3 flex items-center justify-between gap-2">
      <NText strong>会话</NText>
      <NButton size="small" type="primary" circle @click="handleCreate">
        <template #icon>
          <icon-material-symbols:add />
        </template>
      </NButton>
    </div>
    <NSpin :show="sessionLoading">
      <NScrollbar class="h-[calc(100vh-150px)]">
        <div class="flex-col gap-2">
          <button
            v-for="session in sessions"
            :key="session.id"
            type="button"
            class="group w-full rounded-6px px-3 py-2 text-left transition-colors"
            :class="
              activeSessionId === session.id
                ? 'bg-primary/12 color-[rgb(var(--primary-color))]'
                : 'hover:bg-#f1f3f7 dark:hover:bg-#24242a'
            "
            @click="handleSelect(session.id)"
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
            <div v-else class="flex items-center gap-2">
              <NText class="min-w-0 flex-1 truncate text-14px">{{ session.title }}</NText>
              <NButton size="tiny" quaternary circle class="opacity-0 group-hover:opacity-100" @click.stop="startRename(session)">
                <template #icon>
                  <icon-material-symbols:edit-outline />
                </template>
              </NButton>
              <NButton size="tiny" quaternary circle class="opacity-0 group-hover:opacity-100" @click.stop="handleDelete(session.id)">
                <template #icon>
                  <icon-material-symbols:delete-outline />
                </template>
              </NButton>
            </div>
          </button>
          <NEmpty v-if="!sessions.length" description="暂无会话" class="mt-20" />
        </div>
      </NScrollbar>
    </NSpin>
  </aside>
</template>

<style scoped lang="scss"></style>
