import { readFileSync } from 'node:fs';

const source = readFileSync(new URL('../src/views/observability/index.vue', import.meta.url), 'utf8');

const requiredSnippets = [
  'trace-table',
  'const appStore = useAppStore();',
  'flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto',
  'sm:flex-1-hidden card-wrapper',
  ':flex-height="!appStore.isMobile"',
  'class="sm:h-full trace-table"'
];

const missing = requiredSnippets.filter(snippet => !source.includes(snippet));

if (missing.length) {
  console.error(`Observability Trace layout is missing constraints: ${missing.join(', ')}`);
  process.exit(1);
}
