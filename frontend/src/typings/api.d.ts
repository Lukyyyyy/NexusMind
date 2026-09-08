/**
 * Namespace Api
 *
 * All backend api type
 */
declare namespace Api {
  namespace Common {
    /** common params of paginating */
    interface PaginatingCommonParams {
      /** current page number */
      page?: number;
      number: number;
      /** page size */
      size?: number;
      /** total count */
      totalElements: number;
    }

    /** common params of paginating query list data */
    interface PaginatingQueryRecord<T = any> extends PaginatingCommonParams {
      data: T[];
      content: T[];
    }

    /** common search params of table */
    type CommonSearchParams = Pick<Common.PaginatingCommonParams, 'page' | 'size'>;
  }

  /**
   * namespace Auth
   *
   * backend api module: "auth"
   */
  namespace Auth {
    interface LoginToken {
      token: string;
      refreshToken: string;
    }

    interface UserInfo {
      id: number;
      username: string;
      displayName: string;
      role: 'USER' | 'ADMIN' | 'SUPER_ADMIN';
      orgTags: string[];
      primaryOrg: string;
      email?: string | null;
      emailVerified?: boolean;
    }
  }

  /**
   * namespace Route
   *
   * backend api module: "route"
   */
  namespace Route {
    type ElegantConstRoute = import('@elegant-router/types').ElegantConstRoute;

    interface MenuRoute extends ElegantConstRoute {
      id: string;
    }

    interface UserRoute {
      routes: MenuRoute[];
      home: import('@elegant-router/types').LastLevelRouteKey;
    }
  }

  namespace OrgTag {
    interface Item {
      tagId: string;
      name: string;
      description: string;
      parentTag: string | null;
      joinable?: boolean;
      archivedAt?: string | null;
      archiveReason?: string | null;
      createdAt?: string | null;
      children?: Item[];
    }

    type List = Common.PaginatingQueryRecord<Item>;

    type Details = Pick<Item, 'tagId' | 'name' | 'description'>;
    type Mine = {
      orgTags: string[];
      primaryOrg: string;
      orgTagDetails: Details[];
    };
  }

  namespace Organization {
    type Membership = 'DIRECT' | 'INHERITED' | 'PENDING' | 'AVAILABLE';
    interface Item {
      tagId: string;
      name: string;
      path: string;
      description: string;
      membership: Membership;
      system: boolean;
      archived: boolean;
      joinable: boolean;
      primary: boolean;
      joinedAt: string | null;
    }
    interface Overview {
      mine: Item[];
      discover: Item[];
      discoverTotal: number;
      primaryOrg: string;
    }
    interface JoinRequest {
      id: number;
      userId: number;
      username: string;
      displayName: string;
      orgTag: string;
      organization: string;
      reason: string;
      status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | 'ARCHIVED' | 'REMOVED_BY_ADMIN';
      decisionReason: string | null;
      handledBy: string | null;
      createdAt: string;
      handledAt: string | null;
    }
    interface RequestPage extends Common.PaginatingCommonParams {
      content: JoinRequest[];
      pending?: number;
    }
  }

  namespace Notification {
    interface Item {
      id: number;
      type: string;
      title: string;
      content: string;
      link: string | null;
      read: boolean;
      createdAt: string;
    }
    interface List extends Common.PaginatingCommonParams {
      content: Item[];
      unread: number;
    }
  }

  namespace User {
    type SearchParams = CommonType.RecordNullable<
      Common.CommonSearchParams & {
        keyword: string;
        orgTag: string;
        status: number;
        sortField: 'createTime' | 'lastLoginTime';
        sortOrder: 'asc' | 'desc';
      }
    >;

    type Item = {
      userId: string;
      username: string;
      displayName: string;
      email: string;
      status: number;
      orgTags: Pick<OrgTag.Item, 'tagId' | 'name'>[];
      primaryOrg: string;
      createTime: string;
      lastLoginTime: string;
      role: 'USER' | 'ADMIN' | 'SUPER_ADMIN';
      emailVerified: boolean;
    };

    type List = Common.PaginatingQueryRecord<Item>;
  }

  namespace ModelConfig {
    type OwnerType = 'SYSTEM' | 'USER';
    type ModelType = 'LLM' | 'EMBEDDING' | 'RERANK';

    interface Item {
      id: number;
      ownerType: OwnerType;
      ownerUserId: number | null;
      modelType: ModelType;
      name: string;
      provider: string | null;
      baseUrl: string;
      apiKey: string;
      modelName: string;
      enabled: boolean;
      defaultModel: boolean;
      temperature: number | null;
      topP: number | null;
      maxTokens: number | null;
      maxToolCalls: number | null;
      dimension: number | null;
      batchSize: number | null;
      maxConcurrency: number | null;
      instruct: string | null;
      topN: number | null;
      fps: number | null;
    }

    interface Overview {
      configs: Item[];
      selectedLlmConfigId: number | null;
      selectedEmbeddingConfigId: number | null;
      selectedGraphExtractionConfigId: number | null;
      selectedRerankConfigId: number | null;
      rerankWindowMin: number;
      rerankWindowMax: number;
      admin: boolean;
    }

    interface Request {
      ownerType: OwnerType;
      modelType: ModelType;
      name: string;
      provider: string | null;
      baseUrl: string;
      apiKey: string;
      modelName: string;
      enabled: boolean;
      defaultModel: boolean;
      temperature: number | null;
      topP: number | null;
      maxTokens: number | null;
      maxToolCalls: number | null;
      dimension: number | null;
      batchSize: number | null;
      maxConcurrency: number | null;
      instruct: string | null;
      topN: number | null;
      fps: number | null;
    }

    interface PreferenceRequest {
      llmConfigId: number | null;
      embeddingConfigId: number | null;
      graphExtractionConfigId: number | null;
      rerankConfigId: number | null;
    }

    interface Preference {
      llmConfigId: number | null;
      embeddingConfigId: number | null;
      graphExtractionConfigId: number | null;
      rerankConfigId: number | null;
    }
  }

  namespace Observability {
    interface TimeRangeParams {
      from: string;
      to: string;
    }

    interface TraceListParams extends TimeRangeParams {
      level?: string | null;
      traceName?: string | null;
      cursor?: string | null;
      limit?: number;
    }

    interface ModelSummary {
      model: string;
      count: number;
      totalTokens: number;
      totalCost: number;
    }

    interface TrendPoint {
      time: string;
      count: number;
      errorCount: number;
      totalTokens: number;
    }

    interface Overview {
      enabled: boolean;
      message: string | null;
      totalTraces: number;
      totalObservations: number;
      errorCount: number;
      avgLatencyMs: number;
      totalTokens: number;
      totalCost: number;
      byModel: ModelSummary[];
      trend: TrendPoint[];
    }

    interface TraceItem {
      traceId: string;
      traceName: string;
      startTime: string;
      endTime: string;
      durationMs: number;
      level: string;
      observationCount: number;
      modelNames: string[];
      totalTokens: number;
      totalCost: number;
    }

    interface TraceList {
      enabled: boolean;
      message: string | null;
      items: TraceItem[];
      nextCursor: string | null;
    }

    interface Observation {
      id: string;
      traceId: string;
      parentObservationId: string | null;
      type: string;
      name: string;
      level: string;
      statusMessage: string | null;
      startTime: string;
      endTime: string | null;
      durationMs: number | null;
      modelName: string | null;
      totalTokens: number | null;
      totalCost: number | null;
      traceName: string | null;
      sessionId: string | null;
      metadata: Record<string, unknown>;
      input: string | null;
      output: string | null;
    }

    interface TraceDetail {
      enabled: boolean;
      message: string | null;
      traceId: string;
      traceName: string | null;
      sessionId: string | null;
      observations: Observation[];
    }
  }

  namespace KnowledgeBase {
    interface SearchParams {
      query: string;
      topK: number;
    }

    interface SearchResult {
      fileMd5: string;
      chunkId: number;
      textContent: string;
      score: number;
      fileName: string;
    }

    interface UploadState {
      tasks: UploadTask[];
      activeUploads: Set<string>; // 当前正在上传的任务ID
    }

    interface Form {
      orgTag: string | null;
      orgTagName: string | null;
      isPublic: boolean;
      parseEngine: 'AUTO' | 'TIKA' | 'MINERU';
      chunkSize: number;
      graphEnabled: boolean;
      graphPromptTemplateId: number | null;
      graphBatchChars: number;
      fileList: import('naive-ui').UploadFileInfo[];
    }

    interface UploadTask {
      id?: number;
      file: File;
      chunk: Blob | null;
      fileMd5: string;
      chunkIndex: number;
      totalSize: number;
      fileName: string;
      userId?: string;
      orgTag: string | null;
      orgTagName?: string | null;
      public: boolean;
      isPublic: boolean;
      parseEngine: 'AUTO' | 'TIKA' | 'MINERU';
      actualParseEngine?: 'AUTO' | 'TIKA' | 'MINERU' | null;
      chunkSize?: number;
      actualChunkSize?: number | null;
      graphEnabled?: boolean;
      graphPromptTemplateId?: number | null;
      graphBatchChars?: number;
      graphStatus?: 'DISABLED' | 'QUEUED' | 'EXTRACTING' | 'PENDING_REVIEW' | 'PUBLISHED' | 'FAILED';
      graphError?: string | null;
      processingStage?: 'QUEUED' | 'PARSING' | 'CHUNKING' | 'VECTORIZING' | 'INDEXING' | 'COMPLETED' | 'FAILED';
      processingState?: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
      processingMessage?: string | null;
      processingError?: string | null;
      parsedChunkCount?: number;
      vectorizedCount?: number;
      esDocumentCount?: number;
      processingDurationMillis?: number | null;
      processingAccumulatedDurationMillis?: number | null;
      processingStartedAt?: string | null;
      processingUpdatedAt?: string | null;
      processingCompletedAt?: string | null;
      serverTime?: string | null;
      uploaderName?: string;
      uploadedChunks: number[];
      progress: number;
      status: UploadStatus;
      createdAt?: string;
      mergedAt?: string;
      uploadGeneration?: number; // 上传代次，拒绝删除前的迟到请求
      requestIds?: string[]; // 请求ID，用于取消上传
    }
    type List = Common.PaginatingQueryRecord<UploadTask>;

    type Merge = Pick<UploadTask, 'fileMd5' | 'fileName'>;

      interface Progress {
        uploaded: number[];
        progress: number;
        totalChunks: number;
      }

      interface ProcessingStatus {
        fileMd5: string;
        uploadStatus: number;
        dbChunkCount: number;
        esDocumentCount: number;
        processed: boolean;
        processingStage?: UploadTask['processingStage'];
        processingState?: UploadTask['processingState'];
        processingMessage?: string | null;
        processingError?: string | null;
        parseEngine?: UploadTask['parseEngine'];
        actualParseEngine?: UploadTask['actualParseEngine'];
        actualChunkSize?: number | null;
        parsedChunkCount?: number;
        vectorizedCount?: number;
        processingDurationMillis?: number | null;
        processingAccumulatedDurationMillis?: number | null;
        processingStartedAt?: string | null;
        processingUpdatedAt?: string | null;
        processingCompletedAt?: string | null;
        serverTime?: string | null;
      }

      interface Result {
        objectUrl: string;
        fileSize: number;
      }

    interface DocumentChunk {
      fileMd5: string;
      fileName?: string;
      chunkId: number;
      contentPreview: string;
      content?: string;
      contentLength: number;
      byteSize: number;
      configuredChunkSize: number;
      actualChunkSize?: number | null;
      contentFormat?: 'PLAIN_TEXT' | 'MARKDOWN';
      actualParseEngine?: UploadTask['actualParseEngine'];
      modelVersion?: string | null;
    }

    interface DocumentChunkPage {
      fileMd5: string;
      fileName: string;
      configuredChunkSize: number;
      actualChunkSize?: number | null;
      contentFormat?: 'PLAIN_TEXT' | 'MARKDOWN';
      actualParseEngine?: UploadTask['actualParseEngine'];
      totalChunks: number;
      page: number;
      size: number;
      totalPages: number;
      chunks: DocumentChunk[];
    }

    interface DocumentChunkSummary {
      fileMd5: string;
      fileName: string;
      totalChunks: number;
    }
  }

  namespace KnowledgeGraph {
    type Status = 'DISABLED' | 'QUEUED' | 'EXTRACTING' | 'PENDING_REVIEW' | 'PUBLISHED' | 'FAILED';
    type CandidateStatus = 'PENDING' | 'PUBLISHED' | 'REJECTED';

    interface Candidate {
      id: number;
      subjectName: string;
      subjectMentionName?: string;
      subjectType: string;
      predicate: string;
      objectName: string;
      objectMentionName?: string;
      objectType: string;
      evidenceChunkId: number;
      evidenceText: string;
      confidence: number;
      valueScore: number;
      selected: boolean;
      status: CandidateStatus;
    }

    interface DocumentGraph {
      fileMd5: string;
      enabled: boolean;
      status: Status;
      error: string | null;
      templateId: number | null;
      templateName: string;
      candidates: Candidate[];
      nodes: GraphNode[];
      edges: GraphEdge[];
      neo4jEnabled: boolean;
      batchChars: number;
      chunkSize: number;
      progress?: {
        stage: string;
        dictionary: { total: number; ended: number; succeeded: number; failed: number; retrying: number };
        relations: { total: number; ended: number; succeeded: number; failed: number; retrying: number };
        unresolved: number;
        canRetry: boolean;
        failures: { stage: string; batch: number; ranges: string[]; reason: string }[];
      } | null;
    }

    interface GraphNode {
      id: string;
      name: string;
      type: string;
      degree: number;
      componentId?: string;
      communityId?: string;
      importance?: number;
    }

    interface GraphEdge {
      id: string;
      source: string;
      target: string;
      predicate: string;
      confidence: number;
      evidenceChunkId: number;
      evidenceText: string;
      status: CandidateStatus;
      disputed?: boolean;
      relationKind?: 'ASSERTED' | 'DOCUMENT_PROVENANCE' | 'INFERRED';
      crossDocument?: boolean;
      documentCount?: number;
    }

    interface CandidateUpdate {
      selected?: boolean;
      subjectName?: string;
      subjectType?: string;
      predicate?: string;
      objectName?: string;
      objectType?: string;
    }

    interface OrganizationOption {
      scopeId: string;
      tagId: string;
      name: string;
      scopeType: 'PUBLIC' | 'INTERNAL' | 'PRIVATE';
      documentCount: number;
      publishedDocumentCount: number;
    }

    interface OrganizationGraphQuery {
      query?: string;
      entityType?: string;
      fileIds?: number[];
      limit?: number;
    }

    interface OrganizationGraph {
      scopeId: string;
      orgTag: string;
      orgName: string;
      scopeType: 'PUBLIC' | 'INTERNAL' | 'PRIVATE';
      nodes: GraphNode[];
      edges: OrganizationGraphEdge[];
      communities: OrganizationCommunity[];
      entityTypes: string[];
      documents: OrganizationDocument[];
      stats: OrganizationGraphStats;
      truncated: boolean;
      neo4jEnabled: boolean;
      batchChars: number;
      chunkSize: number;
      progress?: {
        stage: string;
        dictionary: { total: number; ended: number; succeeded: number; failed: number; retrying: number };
        relations: { total: number; ended: number; succeeded: number; failed: number; retrying: number };
        unresolved: number;
        canRetry: boolean;
        failures: { stage: string; batch: number; ranges: string[]; reason: string }[];
      } | null;
    }

    interface OrganizationCommunity {
      id: string;
      componentId: string;
      label: string;
      nodeCount: number;
      relationCount: number;
      documentCount: number;
    }

    interface OrganizationGraphEdge {
      id: string;
      source: string;
      target: string;
      predicate: string;
      confidence: number;
      evidenceChunkId: number;
      evidenceText: string;
      fileUploadId: number;
      fileMd5: string;
      fileName: string;
      supportCount: number;
      documentCount: number;
      disputed: boolean;
      relationKind: 'ASSERTED';
      crossDocument: boolean;
      evidences: OrganizationGraphEvidence[];
    }

    interface OrganizationGraphEvidence {
      claimId: number;
      fileUploadId: number;
      fileMd5: string;
      fileName: string;
      chunkId: number;
      evidenceText: string;
      confidence: number;
    }

    interface OrganizationDocument {
      id: number;
      fileMd5: string;
      fileName: string;
    }

    interface OrganizationGraphStats {
      entityCount: number;
      relationCount: number;
      documentCount: number;
      disputedRelationCount: number;
      crossDocumentRelationCount: number;
    }
  }

  namespace GraphPromptTemplate {
    interface Item {
      id: number;
      name: string;
      documentType: string;
      description: string | null;
      instructions: string;
      enabled: boolean;
      defaultTemplate: boolean;
      editable: boolean;
    }
    interface Request {
      name: string;
      documentType: string;
      description?: string | null;
      instructions: string;
      enabled: boolean;
      defaultTemplate: boolean;
    }
  }

  namespace Chat {
    type ScopeType = 'ALL' | 'PRIVATE' | 'ORGANIZATION' | 'DOCUMENTS';

    interface ScopeSelection {
      type: ScopeType;
      orgTag?: string | null;
      documentIds?: number[];
    }

    interface ScopeView {
      type: ScopeType;
      label: string;
      orgTag?: string | null;
      documentIds: number[];
      details: string[];
    }

    interface ScopeOptions {
      privateAvailable: boolean;
      organizations: Array<{ tagId: string; name: string; documentCount: number }>;
      documents: Array<{ id: number; fileMd5: string; fileName: string; orgTag?: string | null }>;
    }
    interface AgentStep {
      type: 'agent_step';
      stepId: string;
      category: 'thinking' | 'tool' | 'answer';
      status: 'running' | 'completed' | 'error';
      title: string;
      detail: string;
      tool?: string | null;
      input?: Record<string, string | number> | null;
      resultCount?: number | null;
      durationMs?: number | null;
    }

    interface Input {
      message: string;
      conversationId?: string;
    }

    interface Output {
      chunk: string;
      content?: string;
      type?: 'completion' | 'stop' | 'content_replaced' | 'title_updated';
      status?: 'finished' | 'cancelled';
      sessionId?: number;
      title?: string;
      error?: string;
    }

    interface Conversation {
      conversationId: string;
    }

    interface Session {
      id: number;
      title: string;
      titleGenerated: boolean;
      createdAt: string;
      updatedAt: string;
      scope: ScopeView;
      modelConfigId: number | null;
      modelName: string | null;
    }

    interface SendPayload {
      type: 'message';
      sessionId: number;
      content: string;
    }

    interface SessionUpdate {
      title: string;
    }

    interface Message {
      id?: number;
      role: 'user' | 'assistant' | 'model';
      content: string;
      status?: 'pending' | 'loading' | 'finished' | 'cancelled' | 'error';
      timestamp?: string;
      agentTrace?: AgentStep[] | string | null;
      /** 客户端发出本轮请求的时间，用于实时展示思考耗时 */
      thinkingStartedAt?: number;
      /** 首个回答内容到达时冻结的思考耗时 */
      thinkingDurationMs?: number;
      sessionId?: number;
      scope?: ScopeView;
      showScope?: boolean;
    }

    interface Token {
      cmdToken: string;
    }
  }

  namespace Document {
    interface DownloadResponse {
      fileName: string;
      downloadUrl: string;
      fileSize: number;
    }
  }
}
