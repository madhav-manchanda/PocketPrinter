import React from 'react';

interface HeaderProps {
  device?: {
    isConnected: boolean;
    name: string | null;
    printerModel: string | null;
    vidPid: string | null;
  };
}

export const Header: React.FC<HeaderProps> = ({ device }) => {
  const location = window.location.pathname;

  const navigate = (path: string) => {
    window.history.pushState({}, '', path);
    renderApp();
  };

  const isActive = (path: string) =>
    location === path || (location.startsWith(path) && location.length > path.length);

  return (
    <header className="fixed top-0 left-72 right-0 h-16 bg-surface/80 backdrop-blur-xl z-40 flex items-center justify-between px-6 py-3 shadow-[0_1px_8px_rgba(0,0,0,0.04)]">
      <div className="flex items-center gap-3">
        <img
          alt="PocketPrinter Precision Logo"
          className="h-8 w-auto object-contain"
          src="https://lh3.googleusercontent.com/aida/AEtjO1WBw1qyWkM9RPc1YHfwVdS4WFGzOu1HLJikxdhEyzKqnB6MJ3jXovk72iyByvY1btoGswdLQeJ6-7nGay99XTN6UP5hLh_RTedXZ4Egt1JhI2Q-wg1jHqlpDS86qk4lN00qx9LVT0P0BOD4lNOgPPBPuzmVX5GJ0aoTOJ78GVFWmcirRlh-iB3DGzOk8f5kLJmWy2PzLXo7S7ZUUgWLGwOht9Gk9O-08GmY0GlAtCpR"
        />
        <span className="font-headline-md text-headline-md text-on-surface tracking-tight">PocketPrinter</span>
      </div>

      <nav className="hidden md:flex items-center gap-8">
        <a
          className={`flex items-center gap-2 px-3 py-1 rounded transition-colors ${isActive('/overview') ? 'bg-surface-container-high text-primary font-medium' : 'text-on-surface-variant hover:bg-surface-container'} ${location === '/overview' ? 'bg-surface-container-high text-primary font-medium' : ''}`}
          data-path="overview"
          href="/overview"
        >
          <span className="material-symbols-outlined text-[18px]">grid_view</span>
          <span className="font-body-md text-body-md">Overview</span>
        </a>
        <a
          className={`flex items-center gap-2 px-3 py-1 rounded transition-colors ${isActive('/print-workspace') ? 'bg-surface-container-high text-primary font-medium' : 'text-on-surface-variant hover:bg-surface-container'} ${location === '/print-workspace' ? 'bg-surface-container-high text-primary font-medium' : ''}`}
          data-path="print-workspace"
          href="/print-workspace"
        >
          <span className="material-symbols-outlined text-[18px]">edit_document</span>
          <span className="font-body-md text-body-md">Print Workspace</span>
        </a>
        <a
          className={`flex items-center gap-2 px-3 py-1 rounded transition-colors ${isActive('/print-queue') ? 'bg-surface-container-high text-primary font-medium' : 'text-on-surface-variant hover:bg-surface-container'} ${location === '/print-queue' ? 'bg-surface-container-high text-primary font-medium' : ''}`}
          data-path="print-queue"
          href="/print-queue"
        >
          <span className="material-symbols-outlined text-[18px]">reorder</span>
          <span className="font-body-md text-body-md">Print Queue</span>
        </a>
        <a
          className={`flex items-center gap-2 px-3 py-1 rounded transition-colors ${isActive('/history') ? 'bg-surface-container-high text-primary font-medium' : 'text-on-surface-variant hover:bg-surface-container'} ${location === '/history' ? 'bg-surface-container-high text-primary font-medium' : ''}`}
          data-path="history"
          href="/history"
        >
          <span className="material-symbols-outlined text-[18px]">history</span>
          <span className="font-body-md text-body-md">History</span>
        </a>
        <a
          className={`flex items-center gap-2 px-3 py-1 rounded transition-colors ${isActive('/printer-agent') ? 'bg-surface-container-high text-primary font-medium' : 'text-on-surface-variant hover:bg-surface-container'} ${location === '/printer-agent' ? 'bg-surface-container-high text-primary font-medium' : ''}`}
          data-path="printer-agent"
          href="/printer-agent"
        >
          <span className="material-symbols-outlined text-[18px]">print</span>
          <span className="font-body-md text-body-md">Printer & Agent</span>
        </a>
        <a
          className={`flex items-center gap-2 px-3 py-1 rounded transition-colors ${isActive('/diagnostics') ? 'bg-surface-container-high text-primary font-medium' : 'text-on-surface-variant hover:bg-surface-container'} ${location === '/diagnostics' ? 'bg-surface-container-high text-primary font-medium' : ''}`}
          data-path="diagnostics"
          href="/diagnostics"
        >
          <span className="material-symbols-outlined text-[18px]">terminal</span>
          <span className="font-body-md text-body-md">Diagnostics</span>
        </a>
        <a
          className={`flex items-center gap-2 px-3 py-1 rounded transition-colors ${isActive('/settings') ? 'bg-surface-container-high text-primary font-medium' : 'text-on-surface-variant hover:bg-surface-container'} ${location === '/settings' ? 'bg-surface-container-high text-primary font-medium' : ''}`}
          data-path="settings"
          href="/settings"
        >
          <span className="material-symbols-outlined text-[18px]">tune</span>
          <span className="font-body-md text-body-md">Settings</span>
        </a>
      </nav>

      {/* User / Device Info */}
      <div className="flex items-center gap-3">
        {device?.isConnected && (
          <div className="flex items-center gap-2 bg-surface-container-low rounded-lg p-2">
            <span className="w-2 h-2 rounded-full bg-tertiary"></span>
            <span className="font-label-xs text-label-xs text-tertiary">
              {device?.name || 'Agent Connected'}
            </span>
          </div>
        )}

        {/* Theme toggle */}
        <button
          className="bg-surface-container-low rounded-lg p-2 hover:bg-surface-container text-on-surface focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-2"
          aria-label="Toggle dark mode"
        >
          <span className="material-symbols-outlined text-[16px]">
            {document.documentElement.classList.contains('dark') ? 'light_mode' : 'dark_mode'}
          </span>
        </button>

        {/* Profile */}
        <div className="w-8 h-8 rounded-full bg-primary flex items-center justify-center">
          <span className="material-symbols-outlined text-on-primary text-[18px]">person</span>
        </div>
      </div>
    </header>
  );
}