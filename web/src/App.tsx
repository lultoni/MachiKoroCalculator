import { useState } from 'react';
import { useSession } from './hooks/useSession';
import { useSettings } from './hooks/useSettings';
import { useProjects } from './hooks/useProjects';
import { useHover } from './hooks/useHover';
import { useLocale } from './i18n/useLocale';
import { SetupScreen } from './components/SetupScreen';
import { GameScreen } from './components/GameScreen';
import { H2hOverview } from './components/H2hOverview';

type AppView = 'game' | 'h2h';

export default function App() {
  const session = useSession();
  const { settings, update: updateSettings } = useSettings();
  const projects = useProjects();
  const hover = useHover();
  const { t } = useLocale();
  const [view, setView] = useState<AppView>('game');

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
        onStart={session.create}
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
    />
  );
}
