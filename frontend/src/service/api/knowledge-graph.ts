import { request } from '../request';

export function fetchDocumentGraph(fileMd5: string) {
  return request<Api.KnowledgeGraph.DocumentGraph>({
    url: `/knowledge-graph/documents/${encodeURIComponent(fileMd5)}`
  });
}

export function updateGraphCandidate(fileMd5: string, candidateId: number, data: Api.KnowledgeGraph.CandidateUpdate) {
  return request<Api.KnowledgeGraph.Candidate>({
    url: `/knowledge-graph/documents/${encodeURIComponent(fileMd5)}/candidates/${candidateId}`,
    method: 'put',
    data
  });
}

export function publishDocumentGraph(fileMd5: string) {
  return request<Api.KnowledgeGraph.DocumentGraph>({
    url: `/knowledge-graph/documents/${encodeURIComponent(fileMd5)}/publish`,
    method: 'post'
  });
}

export function setDocumentGraphEnabled(fileMd5: string, enabled: boolean) {
  return request<Api.KnowledgeGraph.DocumentGraph>({
    url: `/knowledge-graph/documents/${encodeURIComponent(fileMd5)}/enabled`,
    method: 'put',
    data: { enabled }
  });
}

export function rebuildDocumentGraph(fileMd5: string) {
  return request<Api.KnowledgeGraph.DocumentGraph>({
    url: `/knowledge-graph/documents/${encodeURIComponent(fileMd5)}/rebuild`,
    method: 'post'
  });
}
