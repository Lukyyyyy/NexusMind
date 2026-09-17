import { request } from '../request';

/** IM 渠道接入：每用户独立微信机器人（ClawBot/iLink 直连）。 */

export type ImTokenState = 'NOT_CONFIGURED' | 'WAITING_LOGIN' | 'ACTIVE' | 'EXPIRED' | 'DISABLED';

export interface ImClawbotStatus {
  configured: boolean;
  tokenState: ImTokenState;
  botId: string;
}

export interface ImLoginStartResult {
  /** 轮询凭据（传给 login/poll）。 */
  qrcode: string;
  /**
   * 二维码内容：iLink 返回的 liteapp 页面 URL。页面用该字符串在本地渲染二维码图片
   * （该 URL 是 HTML 页面而非图片，后端无法直接代理成图片，故前端本地绘制）。
   */
  qrcodeContent: string;
  expireSeconds: number;
}

export interface ImLoginPollResult {
  status: 'wait' | 'scaned' | 'scanned' | 'confirmed' | 'expired' | string;
  botId?: string;
}

export function fetchImClawbotStatus() {
  return request<ImClawbotStatus>({ url: '/im/clawbot/status' });
}

export function startImClawbotLogin() {
  return request<ImLoginStartResult>({ url: '/im/clawbot/login/start', method: 'post' });
}

export function pollImClawbotLogin(qrcode: string) {
  return request<ImLoginPollResult>({ url: '/im/clawbot/login/poll', method: 'post', data: { qrcode } });
}
