import { useSession } from './hooks/useSession';
import { useSettings } from './hooks/useSettings';
import { useProjects } from './hooks/useProjects';
import { useHover } from './hooks/useHover';
import { useLocale } from './i18n/useLocale';
import { SetupScreen } from './components/SetupScreen';
import { GameScreen } from './components/GameScreen';

export default function App() {
  const session = useSession();
  const { settings, update: updateSettings } = useSettings();
  const projects = useProjects();
  const hover = useHover();
  const { t } = useLocale();

  // Loading project data
  if (projects.loading) {
    return (
      <div className="min-h-screen bg-machi-bg flex items-center justify-center">
        <p className="text-machi-text-dim text-lg animate-pulse">{t('app.title')}</p>
      </div>
    );
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
