import { useState, useEffect } from 'react';
import * as api from '../api/client';
import type { ParamDef, EngineParamSchema } from '../api/types';

/** Module-level cache — fetched once per page load. */
let cachedSchema: EngineParamSchema | null = null;
let fetchPromise: Promise<EngineParamSchema> | null = null;

function fetchSchema(): Promise<EngineParamSchema> {
  if (cachedSchema) return Promise.resolve(cachedSchema);
  if (!fetchPromise) {
    fetchPromise = api.getEngineParams().then(schema => {
      cachedSchema = schema;
      return schema;
    });
  }
  return fetchPromise;
}

/** Group params by category for display. Returns [categoryName, params[]] pairs. */
export function groupByCategory(params: ParamDef[]): [string, ParamDef[]][] {
  const map = new Map<string, ParamDef[]>();
  for (const p of params) {
    const cat = p.category ?? 'Other';
    const list = map.get(cat);
    if (list) list.push(p);
    else map.set(cat, [p]);
  }
  return Array.from(map.entries());
}

/**
 * Hook to fetch and provide engine parameter schemas.
 *
 * Fetches once from {@code GET /api/engine-params}, caches at module level.
 * Returns standard + engine-specific params for a given engine class.
 */
export function useEngineParams() {
  const [schema, setSchema] = useState<EngineParamSchema | null>(cachedSchema);

  useEffect(() => {
    if (cachedSchema) {
      setSchema(cachedSchema);
      return;
    }
    fetchSchema().then(setSchema).catch(() => {});
  }, []);

  const getForClass = (cls: string): ParamDef[] => {
    if (!schema) return [];
    return [...schema.standard, ...(schema.engines[cls] ?? [])];
  };

  const engineClassIds = schema ? Object.keys(schema.engines) : [];

  return { schema, getForClass, engineClassIds, loading: !schema };
}
