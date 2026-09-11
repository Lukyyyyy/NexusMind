import { useWebSocket } from '@vueuse/core';

export const useChatStore = defineStore(SetupStoreId.Chat, () => {
  const input = ref<Api.Chat.Input>({ message: '' });
  const sessions = ref<Api.Chat.Session[]>([]);
  const draftSession = ref<Api.Chat.Session | null>(null);
  const activeSessionId = ref<number | null>(null);
  const messages = ref<Api.Chat.Message[]>([]);
  const loading = ref(false);
  const sessionLoading = ref(false);
  const generatingSessionId = ref<number | null>(null);
  let sessionsRequestId = 0;

  const wsTicket = ref('');

  const {
    status: wsStatus,
    data: wsData,
    send: wsSend,
    open: wsOpen,
    close: wsClose
  } = useWebSocket(computed(() => (wsTicket.value ? `/proxy-ws/chat/${wsTicket.value}` : undefined)), {
    // 票据一次性消费，禁止用同一票据自动重连；重连前必须换发新票据。
    autoConnect: false,
    autoReconnect: false
  });

  watch(wsStatus, status => {
    // 连接关闭后票据即失效，下次发送前重新换发。
    if (status === 'CLOSED') wsTicket.value = '';
  });

  async function ensureWsTicket() {
    if (wsTicket.value) return wsTicket.value;
    const { error, data } = await request<Api.Chat.WsTicket>({ url: 'chat/ws-ticket' });
    if (error || !data?.ticket) return '';
    wsTicket.value = data.ticket;
    return wsTicket.value;
  }

  const scrollToBottom = ref<null | (() => void)>(null);

  watch(wsData, value => {
    if (!value || generatingSessionId.value == null) return;
    try {
      const event = JSON.parse(value);
      if (event.type === 'completion' || event.error) generatingSessionId.value = null;
    } catch {
      // Ignore non-JSON websocket payloads; the message view handles display errors.
    }
  });

  const activeSession = computed(() =>
    draftSession.value?.id === activeSessionId.value
      ? draftSession.value
      : sessions.value.find(item => item.id === activeSessionId.value) || null
  );

  function normalizeMessage(message: Api.Chat.Message): Api.Chat.Message {
    if (typeof message.agentTrace !== 'string') return message;
    try {
      return { ...message, agentTrace: JSON.parse(message.agentTrace) as Api.Chat.AgentStep[] };
    } catch {
      return { ...message, agentTrace: [] };
    }
  }

  async function loadSessions(showLoading = true) {
    const requestId = ++sessionsRequestId;
    if (showLoading) sessionLoading.value = true;
    try {
      const { error, data } = await request<Api.Chat.Session[]>({ url: 'chat/sessions' });
      if (!error && requestId === sessionsRequestId) {
        const loaded = data || [];
        const draft = draftSession.value;
        const draftPersisted = draft && loaded.some(item => item.id === draft.id);
        const draftVisible = draft != null && draft.title !== '新会话' && sessions.value.some(item => item.id === draft.id);
        sessions.value = draft && draftVisible && !draftPersisted
          ? [draft, ...loaded.filter(item => item.id !== draft.id)]
          : loaded;
        if (draftPersisted) {
          draftSession.value = null;
        }
      }
    } finally {
      if (requestId === sessionsRequestId) sessionLoading.value = false;
    }
  }

  async function createSession(scope?: Api.Chat.ScopeSelection) {
    const { error, data } = await request<Api.Chat.Session>({
      url: 'chat/sessions',
      method: 'post',
      data: scope
    });
    if (error) return null;
    draftSession.value = data;
    activeSessionId.value = data.id;
    messages.value = [];
    return data;
  }

  async function applyScope(scope: Api.Chat.ScopeSelection) {
    const started = messages.value.some(message => message.role === 'user' || message.role === 'assistant');
    if (!activeSessionId.value || started) {
      return createSession(scope);
    }
    const { error, data } = await request<Api.Chat.Session>({
      url: `chat/sessions/${activeSessionId.value}/scope`,
      method: 'patch',
      data: scope
    });
    if (error) return null;
    if (draftSession.value?.id === data.id) draftSession.value = data;
    else sessions.value = sessions.value.map(item => (item.id === data.id ? data : item));
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
      messages.value = (data || []).map(normalizeMessage);
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
    if (draftSession.value?.id === sessionId) draftSession.value = data;
    sessions.value = sessions.value.map(item => (item.id === sessionId ? data : item));
    return true;
  }

  async function switchSessionModel(modelConfigId: number) {
    const sessionId = await ensureActiveSession();
    if (!sessionId) return null;
    const previousModelId = activeSession.value?.modelConfigId;
    const started = messages.value.some(message => message.role === 'user' || message.role === 'assistant');
    const { error, data } = await request<Api.Chat.Session>({
      url: `chat/sessions/${sessionId}/model`,
      method: 'patch',
      data: { modelConfigId }
    });
    if (error) return null;
    if (draftSession.value?.id === sessionId) draftSession.value = data;
    sessions.value = sessions.value.map(item => (item.id === sessionId ? data : item));
    if (started && previousModelId !== data.modelConfigId && data.modelName) {
      messages.value.push({
        role: 'model',
        content: data.modelName,
        status: 'finished',
        timestamp: new Date().toISOString()
      });
    }
    return data;
  }

  function startGeneration(sessionId: number) {
    generatingSessionId.value = sessionId;
  }

  function applySessionTitle(sessionId: number, title: string) {
    if (draftSession.value?.id === sessionId) {
      draftSession.value = { ...draftSession.value, title };
      sessions.value = [draftSession.value, ...sessions.value.filter(item => item.id !== sessionId)];
      return;
    }
    sessions.value = sessions.value.map(item => (item.id === sessionId ? { ...item, title } : item));
  }

  async function deleteSession(sessionId: number) {
    const { error } = await request({
      url: `chat/sessions/${sessionId}`,
      method: 'delete'
    });
    if (error) return false;
    if (draftSession.value?.id === sessionId) draftSession.value = null;
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
    const sessionId = activeSessionId.value;
    const timingByAssistantIndex = messages.value
      .filter(message => message.role === 'assistant')
      .map(message => ({
        thinkingStartedAt: message.thinkingStartedAt,
        thinkingDurationMs: message.thinkingDurationMs
      }));
    await loadMessages(sessionId);
    if (activeSessionId.value !== sessionId) return;
    let assistantIndex = 0;
    messages.value = messages.value.map(message => {
      if (message.role !== 'assistant') return message;
      const timing = timingByAssistantIndex[assistantIndex++];
      if (!timing) return message;
      return {
        ...message,
        thinkingStartedAt: timing.thinkingStartedAt,
        thinkingDurationMs: message.thinkingDurationMs ?? timing.thinkingDurationMs
      };
    });
    await loadSessions(false);
  }

  return {
    input,
    sessions,
    activeSessionId,
    activeSession,
    messages,
    loading,
    sessionLoading,
    generatingSessionId,
    wsStatus,
    wsData,
    wsSend,
    wsOpen,
    wsClose,
    scrollToBottom,
    loadSessions,
    createSession,
    applyScope,
    selectSession,
    loadMessages,
    renameSession,
    switchSessionModel,
    startGeneration,
    applySessionTitle,
    deleteSession,
    ensureActiveSession,
    ensureWsTicket,
    refreshActiveSessionMessages
  };
});
