import assert from 'node:assert/strict';
import { getSafeIframeUrl } from '../src/views/_builtin/iframe-page/safe-url';

assert.equal(getSafeIframeUrl('https://example.com/docs?q=1#intro'), 'https://example.com/docs?q=1#intro');
assert.equal(getSafeIframeUrl('http://localhost:3000'), 'http://localhost:3000/');

for (const value of [
  'javascript:alert(1)',
  ' JaVaScRiPt:alert(1)',
  'java\nscript:alert(1)',
  'data:text/html,<script>alert(1)</script>',
  'blob:https://example.com/id',
  '//example.com',
  '/relative/path',
  'javascript%3Aalert(1)'
]) {
  assert.equal(getSafeIframeUrl(value), null, `unsafe iframe URL accepted: ${value}`);
}
