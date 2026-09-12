declare module 'virtual:nexusmind-iconify' {
  type LocalIconifyIcon = Parameters<typeof import('@iconify/vue/offline').addIcon>[1];

  const icons: Record<string, LocalIconifyIcon>;

  export default icons;
}
