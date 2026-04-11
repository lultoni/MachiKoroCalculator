import { useState, useCallback } from 'react';
import { useSession } from './hooks/useSession';
import { useSettings } from './hooks/useSettings';
import { useProjects } from './hooks/useProjects';
import { useHover } from './hooks/useHover';
import { useLocale } from './i18n/useLocale';
import { usePlayerVsAi } from './hooks/usePlayerVsAi';
import { SetupScreen } from './components/SetupScreen';
import type { PvAiSetupConfig } from './components/SetupScreen';
import { GameScreen } from './components/GameScreen';
import { H2hOverview } from './components/H2hOverview';
import type { CreateSessionRequest } from './api/types';

type AppView = 'game' | 'h2h';

export default function App() {
  const session = useSession();
  const { settings, update: updateSettings } = useSettings();
  const projects = useProjects();
  const hover = useHover();
  const pvai = usePlayerVsAi();
  const { t } = useLocale();
  const [view, setView] = useState<AppView>('game');

  const handleStart = useCallback(async (req: CreateSessionRequest, pvaiConfig?: PvAiSetupConfig) => {
    await session.create(req);
    if (pvaiConfig) {
      await pvai.startPvAi(pvaiConfig.engineId, pvaiConfig.aiPlayerIndex, pvaiConfig.minThinkTimeMs);
    }
  }, [session, pvai]);

  // Loading project data
  if (projects.loading) {
    return (
      <div className="min-h-screen bg-machi-bg flex items-center justify-center">
        <p className="text-machi-text-dim text-lg animate-pulse">{t('app.title')}</p>
      </div>
    );
  }

  // H2H view
  if (view === 'h2h') {
    return <H2hOverview onBack={() => setView('game')} projects={projects} language={settings.language} />;
  }

  // No active session → setup screen
  if (!session.session) {
    return (
      <SetupScreen
        onStart={handleStart}
        onLoad={session.load}
        onFromSnapshot={session.fromSnapshot}
        loading={session.loading}
        error={session.error}
        onH2h={() => setView('h2h')}
      />
    );
  }

  // Active session → game screen
  return (
    <GameScreen
      session={session}
      settings={settings}
      updateSettings={updateSettings}
      projects={projects}
      hover={hover}
      pvai={pvai}
    />
  );
}
