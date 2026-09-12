import path from 'node:path';
import { readFile, readdir } from 'node:fs/promises';
import { lookupCollection } from '@iconify/json';
import { getIconData } from '@iconify/utils';
import type { Plugin } from 'vite';

const virtualModuleId = 'virtual:nexusmind-iconify';
const resolvedVirtualModuleId = `\0${virtualModuleId}`;
const sourceExtensions = new Set(['.js', '.jsx', '.ts', '.tsx', '.vue']);
const iconNamePattern = /(?<![\w-])([a-z][a-z0-9-]*):([a-z0-9][a-z0-9-]*)\b/g;

async function listSourceFiles(directory: string): Promise<string[]> {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = await Promise.all(
    entries.map(async entry => {
      const entryPath = path.join(directory, entry.name);

      if (entry.isDirectory()) return listSourceFiles(entryPath);
      if (entry.isFile() && sourceExtensions.has(path.extname(entry.name))) return [entryPath];

      return [];
    })
  );

  return files.flat();
}

async function collectIconNames(sourceDirectory: string) {
  const iconNames = new Set<string>();
  const files = await listSourceFiles(sourceDirectory);

  await Promise.all(
    files.map(async file => {
      const source = await readFile(file, 'utf8');

      for (const match of source.matchAll(iconNamePattern)) {
        iconNames.add(`${match[1]}:${match[2]}`);
      }
    })
  );

  return [...iconNames].sort();
}

function isMissingCollection(error: unknown) {
  return (error as NodeJS.ErrnoException)?.code === 'ENOENT';
}

async function createLocalIconModule(sourceDirectory: string) {
  const iconNames = await collectIconNames(sourceDirectory);
  const namesByPrefix = new Map<string, string[]>();

  for (const iconName of iconNames) {
    const separatorIndex = iconName.indexOf(':');
    const prefix = iconName.slice(0, separatorIndex);
    const name = iconName.slice(separatorIndex + 1);
    const names = namesByPrefix.get(prefix) ?? [];

    names.push(name);
    namesByPrefix.set(prefix, names);
  }

  const icons: Record<string, NonNullable<ReturnType<typeof getIconData>>> = {};
  let collectionCount = 0;

  for (const [prefix, names] of namesByPrefix) {
    let collection;

    try {
      collection = await lookupCollection(prefix);
    } catch (error) {
      // Source files also contain CSS variants such as `hover:bg-*`.
      // Prefixes without an Iconify collection are not icon references.
      if (isMissingCollection(error)) continue;
      throw error;
    }

    collectionCount += 1;

    for (const name of names) {
      const icon = getIconData(collection, name);

      if (!icon) {
        throw new Error(`[local-iconify] Unknown icon "${prefix}:${name}"`);
      }

      icons[`${prefix}:${name}`] = icon;
    }
  }

  return {
    code: `export default ${JSON.stringify(icons)};`,
    collectionCount,
    iconCount: Object.keys(icons).length
  };
}

/** Bundle only Iconify icons referenced by application source code. */
export function setupLocalIconify(): Plugin {
  let sourceDirectory = path.resolve(process.cwd(), 'src');
  let modulePromise: ReturnType<typeof createLocalIconModule> | undefined;

  return {
    name: 'nexusmind-local-iconify',
    enforce: 'pre',
    configResolved(config) {
      sourceDirectory = path.resolve(config.root, 'src');
    },
    resolveId(id) {
      if (id === virtualModuleId) return resolvedVirtualModuleId;
      return null;
    },
    async load(id) {
      if (id !== resolvedVirtualModuleId) return null;

      modulePromise ??= createLocalIconModule(sourceDirectory);
      const result = await modulePromise;

      this.info(`[local-iconify] Bundled ${result.iconCount} icons from ${result.collectionCount} collections`);

      return result.code;
    }
  };
}
