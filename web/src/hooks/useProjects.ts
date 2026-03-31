/** Projects hook — loads card definitions once at startup. */

import { useState, useEffect } from 'react';
import type { ProjectDef } from '../api/types';
import * as api from '../api/client';

export function useProjects() {
  const [projects, setProjects] = useState<ProjectDef[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.getProjects()
      .then(data => { if (!cancelled) setProjects(data); })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  /** Lookup a project by ID. */
  const byId = (id: string): ProjectDef | undefined =>
    projects.find(p => p.id === id);

  return { projects, loading, error, byId };
}
