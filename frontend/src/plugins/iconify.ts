import { addIcon } from '@iconify/vue/offline';
import localIconifyIcons from 'virtual:nexusmind-iconify';

/** Register the Iconify icons embedded by the Vite build. */
export function setupIconifyOffline() {
  for (const [name, icon] of Object.entries(localIconifyIcons)) {
    addIcon(name, icon);
  }
}
