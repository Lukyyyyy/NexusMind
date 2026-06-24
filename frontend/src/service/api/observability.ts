import { request } from '../request';

export function fetchLangfuseOverview(params: Api.Observability.TimeRangeParams) {
  return request<Api.Observability.Overview>({
    url: '/observability/langfuse/overview',
    params
  });
}

export function fetchLangfuseTraces(params: Api.Observability.TraceListParams) {
  return request<Api.Observability.TraceList>({
    url: '/observability/langfuse/traces',
    params
  });
}

export function fetchLangfuseTraceDetail(traceId: string, params: Api.Observability.TimeRangeParams) {
  return request<Api.Observability.TraceDetail>({
    url: `/observability/langfuse/traces/${traceId}`,
    params
  });
}
