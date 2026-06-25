import { useWebSocket } from '@vueuse/core';

export const useChatStore = defineStore(SetupStoreId.Chat, () => {
  const input = ref<Api.Chat.Input>({ message: '' });
  const sessions = ref<Api.Chat.Session[]>([]);
  const activeSessionId = ref<number | null>(null);
  const messages = ref<Api.Chat.Message[]>([]);
  const loading = ref(false);
  const sessionLoading = ref(false);

  const store = useAuthStore();

  const {
    status: wsStatus,
    data: wsData,
    send: wsSend,
    open: wsOpen,
    close: wsClose
  } = useWebSocket(`/proxy-ws/chat/${store.token}`, {
    autoReconnect: true
  });

  const scrollToBottom = ref<null | (() => void)>(null);

  const activeSession = computed(() => sessions.value.find(item => item.id === activeSessionId.value) || null);

  async function loadSessions() {
    sessionLoading.value = true;
    const { error, data } = await request<Api.Chat.Session[]>({ url: 'chat/sessions' });
    if (!error) {
      sessions.value = data || [];
      if (!activeSessionId.value && sessions.value.length) {
        activeSessionId.value = sessions.value[0].id;
      }
    }
    sessionLoading.value = false;
  }

  async function createSession() {
    const { error, data } = await request<Api.Chat.Session>({
      url: 'chat/sessions',
      method: 'post'
    });
    if (error) return null;
    sessions.value = [data, ...sessions.value.filter(item => item.id !== data.id)];
    activeSessionId.value = data.id;
    messages.value = [];
    return data;
  }

  async function loadMessages(sessionId = activeSessionId.value) {
    if (!sessionId) {
      messages.value = [];
      return;
    }
    loading.value = true;
    const { error, data } = await request<Api.Chat.Message[]>({ url: `chat/sessions/${sessionId}/messages` });
    if (!error && activeSessionId.value === sessionId) {
      messages.value = data || [];
    }
    loading.value = false;
  }

  async function selectSession(sessionId: number) {
    if (activeSessionId.value === sessionId) return;
    activeSessionId.value = sessionId;
    await loadMessages(sessionId);
  }

  async function renameSession(sessionId: number, title: string) {
    const { error, data } = await request<Api.Chat.Session>({
      url: `chat/sessions/${sessionId}`,
      method: 'patch',
      data: { title } satisfies Api.Chat.SessionUpdate
    });
    if (error) return false;
    sessions.value = sessions.value.map(item => (item.id === sessionId ? data : item));
    return true;
  }

  async function deleteSession(sessionId: number) {
    const { error } = await request({
      url: `chat/sessions/${sessionId}`,
      method: 'delete'
    });
    if (error) return false;
    sessions.value = sessions.value.filter(item => item.id !== sessionId);
    if (activeSessionId.value === sessionId) {
      activeSessionId.value = sessions.value[0]?.id || null;
      await loadMessages(activeSessionId.value);
    }
    return true;
  }

  async function ensureActiveSession() {
    if (activeSessionId.value) return activeSessionId.value;
    const created = await createSession();
    return created?.id || null;
  }

  async function refreshActiveSessionMessages() {
    await loadMessages(activeSessionId.value);
    await loadSessions();
  }

  return {
    input,
    sessions,
    activeSessionId,
    activeSession,
    messages,
    loading,
    sessionLoading,
    wsStatus,
    wsData,
    wsSend,
    wsOpen,
    wsClose,
    scrollToBottom,
    loadSessions,
    createSession,
    selectSession,
    loadMessages,
    renameSession,
    deleteSession,
    ensureActiveSession,
    refreshActiveSessionMessages
  };
});
