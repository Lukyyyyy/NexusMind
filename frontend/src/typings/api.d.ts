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
      role: 'USER' | 'ADMIN';
      orgTags: string[];
      primaryOrg: string;
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

  namespace User {
    type SearchParams = CommonType.RecordNullable<
      Common.CommonSearchParams & {
        keyword: string;
        orgTag: string;
        status: number;
      }
    >;

    type Item = {
      userId: string;
      username: string;
      email: string;
      status: number;
      orgTags: Pick<OrgTag.Item, 'tagId' | 'name'>[];
      primaryOrg: string;
      createTime: string;
      lastLoginTime: string;
    };

    type List = Common.PaginatingQueryRecord<Item>;
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
      input: null;
      output: null;
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
      userId: string;
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
      fileList: import('naive-ui').UploadFileInfo[];
    }

    interface UploadTask {
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
      processingStage?: 'QUEUED' | 'PARSING' | 'CHUNKING' | 'VECTORIZING' | 'INDEXING' | 'COMPLETED' | 'FAILED';
      processingState?: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
      processingMessage?: string | null;
      processingError?: string | null;
      parsedChunkCount?: number;
      vectorizedCount?: number;
      esDocumentCount?: number;
      processingDurationMillis?: number | null;
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
  }

  namespace Chat {
    interface Input {
      message: string;
      conversationId?: string;
    }

    interface Output {
      chunk: string;
      type?: 'completion' | 'stop';
      status?: 'finished';
      sessionId?: number;
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
      role: 'user' | 'assistant';
      content: string;
      status?: 'pending' | 'loading' | 'finished' | 'error';
      timestamp?: string;
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
