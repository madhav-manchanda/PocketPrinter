import React, { useMemo, useState } from 'react';
import './main.css';
import './stitch.css';
import { ConnectScreen } from './components/ConnectScreen';
import { JobDetailDrawer } from './components/JobDetailDrawer';
import { QueueTable } from './components/QueueTable';
import { SettingsModal } from './components/SettingsModal';
import { StatusOverview } from './components/StatusOverview';
import { ToastContainer } from './components/ToastContainer';
import { UploadZone } from './components/UploadZone';
import { usePocketPrint } from './usePocketPrint';
import { api } from './api';

type View = 'overview' | 'workspace' | 'queue' | 'diagnostics' | 'console';

const navItems: { id: View; label: string; icon: string }[] = [
  { id: 'overview', label: 'Overview', icon: 'grid_view' },
  { id: 'workspace', label: 'Print Workspace', icon: 'edit_document' },
  { id: 'queue', label: 'Print Queue', icon: 'reorder' },
  { id: 'diagnostics', label: 'Diagnostics', icon: 'monitor_heart' },
  { id: 'console', label: 'Precision Console', icon: 'terminal' },
];

export default function App() {
  const {
    isAuthenticated, device, jobs, health, latency, wsConnected, isLoading, toasts,
    selectedJob, setSelectedJob, connectWithPassword, disconnectSession, uploadAndPrint,
    cancelJob, retryJob, reconcileJob, inspectJob, refreshAll, addToast, removeToast,
  } = usePocketPrint();
  const [view, setView] = useState<View>(() => {
    const path = window.location.pathname.replace(/^\/+|\/+$/g, '');
    return (navItems.find((item) => item.id === path)?.id || 'overview') as View;
  });
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [apiReachable, setApiReachable] = useState<boolean | null>(null);

  const counts = useMemo(() => ({
    active: jobs.filter(j => ['UPLOADED','QUEUED','ASSIGNED','DOWNLOADING','READY','PRINTING','TRANSMISSION_COMPLETED','CANCEL_REQUESTED','RETRY_PENDING'].includes(j.status)).length,
    completed: jobs.filter(j => j.status === 'COMPLETED').length,
    failed: jobs.filter(j => ['FAILED','CANCELLED','UNCERTAIN','NEEDS_RECONCILIATION'].includes(j.status)).length,
  }), [jobs]);

  const navigate = (next: View) => {
    setView(next);
    const path = next === 'overview' ? '/' : `/${next}`;
    window.history.pushState({}, '', path);
  };

  React.useEffect(() => {
    const onPop = () => {
      const path = window.location.pathname.replace(/^\/+|\/+$/g, '');
      setView((navItems.find(item => item.id === path)?.id || 'overview') as View);
    };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);

  const testApi = async () => {
    try { await api.checkHealth(); await api.checkReadiness(); setApiReachable(true); }
    catch { setApiReachable(false); }
  };

  if (!isAuthenticated) return <div className="app-shell"><ConnectScreen onConnect={connectWithPassword} isLoading={isLoading} /><ToastContainer toasts={toasts} onDismiss={removeToast} /></div>;

  const pageTitle = navItems.find(item => item.id === view)?.label || 'Overview';
  return (
    <div className="pp-shell">
      <aside className="pp-sidebar">
        <button className="pp-brand" onClick={() => navigate('overview')} aria-label="PocketPrinter home">
          <span className="pp-brand-mark"><span className="material-symbols-outlined">print</span></span>
          <span>PocketPrinter<small>PRECISION CONSOLE</small></span>
        </button>
        <div className="pp-device-chip"><i className={device?.isOnline ? 'is-online' : ''} /><span>{device?.name || 'No printer paired'}</span><b>{device?.isOnline ? 'READY' : 'OFFLINE'}</b></div>
        <div className="pp-nav-label">WORKSPACE</div>
        <nav className="pp-nav">{navItems.map(item => <button key={item.id} className={view === item.id ? 'active' : ''} onClick={() => navigate(item.id)}><span className="material-symbols-outlined">{item.icon}</span>{item.label}{item.id === 'queue' && counts.active > 0 && <em>{counts.active}</em>}</button>)}</nav>
        <div className="pp-sidebar-bottom">
          <div className="pp-agent-state"><span className={wsConnected ? 'pulse-dot' : 'pulse-dot muted'} /> <span>ANDROID RELAY</span><b>{wsConnected ? 'CONNECTED' : 'WAITING'}</b></div>
          <button className="pp-side-action" onClick={() => setSettingsOpen(true)}><span className="material-symbols-outlined">settings</span>Settings</button>
          <button className="pp-side-action" onClick={disconnectSession}><span className="material-symbols-outlined">logout</span>Disconnect</button>
          <small className="pp-version">POCKETPRINTER CLOUD · API {health?.apiVersion ?? '—'}</small>
        </div>
      </aside>

      <div className="pp-main">
        <header className="pp-topbar">
          <div><div className="pp-breadcrumb">POCKETPRINTER <span>/</span> {pageTitle.toUpperCase()}</div><h1>{pageTitle}</h1></div>
          <div className="pp-top-actions"><div className="pp-api-indicator"><i className={health ? 'is-online' : ''}/><span>{health ? 'API OPERATIONAL' : 'API UNREACHABLE'}</span><code>{latency == null ? '—' : latency + ' ms'}</code></div><button className="pp-icon-button" onClick={refreshAll} disabled={isLoading} title="Refresh"><span className="material-symbols-outlined">refresh</span></button><button className="pp-icon-button" onClick={() => setSettingsOpen(true)} title="Settings"><span className="material-symbols-outlined">tune</span></button></div>
        </header>

        <main className="pp-content">
          {view === 'overview' && <>
            <div className="pp-welcome"><div><div className="pp-eyebrow">At a glance</div><h2>Your printer, ready when you are.</h2><p>See the connection, check recent jobs, and send the next document.</p></div><button className="pp-primary-button" onClick={() => navigate('workspace')}><span className="material-symbols-outlined">add</span> New print job</button></div>
            <div className="pp-metric-grid">
              <Metric label="Printer status" value={device?.isOnline ? 'Online' : 'Offline'} detail={device?.printerModel || 'Awaiting printer details'} icon="print" tone={device?.isOnline ? 'good' : 'warn'}/>
              <Metric label="Android relay" value={wsConnected ? 'Connected' : 'Disconnected'} detail={device?.lastHeartbeat ? 'Last heartbeat ' + new Date(device.lastHeartbeat).toLocaleTimeString() : 'No recent heartbeat'} icon="devices" tone={wsConnected ? 'good' : 'neutral'}/>
              <Metric label="Active jobs" value={String(counts.active).padStart(2,'0')} detail="Jobs currently in the pipeline" icon="sync" tone="blue"/>
              <Metric label="Job history" value={String(jobs.length).padStart(2,'0')} detail={`${counts.completed} completed · ${counts.failed} needs attention`} icon="history" tone="neutral"/>
            </div>
            <section className="pp-section-heading"><div><div className="pp-eyebrow">Recent work</div><h2>Print activity</h2></div><button className="pp-text-button" onClick={() => navigate('queue')}>View all jobs <span className="material-symbols-outlined">arrow_forward</span></button></section>
            <div className="pp-overview-grid"><section className="pp-panel"><PanelTitle title="Printer & relay status" subtitle="Live hardware connection details"/><StatusOverview device={device} jobs={jobs}/></section><section className="pp-panel pp-quick-panel"><PanelTitle title="Quick actions" subtitle="Start a print or inspect the system"/><button onClick={() => navigate('workspace')}><span className="material-symbols-outlined">upload_file</span><span><b>Print a document</b><small>Upload a file and configure output</small></span><span className="material-symbols-outlined trailing">chevron_right</span></button><button onClick={() => navigate('queue')}><span className="material-symbols-outlined">format_list_bulleted</span><span><b>Review print queue</b><small>{counts.active} active · {counts.failed} attention needed</small></span><span className="material-symbols-outlined trailing">chevron_right</span></button><button onClick={() => navigate('diagnostics')}><span className="material-symbols-outlined">health_and_safety</span><span><b>Run diagnostics</b><small>Check API and relay connectivity</small></span><span className="material-symbols-outlined trailing">chevron_right</span></button></section></div>
          </>}
          {view === 'workspace' && <><PageIntro eyebrow="New print job" title="Print a document" description="Choose a file, set the page options, and send it to your printer."/><div className="pp-workspace"><UploadZone onUpload={uploadAndPrint} isPrinterOnline={device?.isOnline ?? false} addToast={addToast}/><aside className="pp-workspace-aside"><div className="pp-panel"><PanelTitle title="Destination" subtitle="Current print endpoint"/><div className="pp-destination"><span className="material-symbols-outlined">print</span><div><b>{device?.name || 'Printer not connected'}</b><small>{device?.printerModel || 'Connect a printer to begin'}</small></div><i className={device?.isOnline ? 'is-online' : ''}/></div><div className="pp-info-row"><span>Connection</span><b>{device?.isOnline ? 'Online' : 'Offline'}</b></div><div className="pp-info-row"><span>Relay channel</span><b>{wsConnected ? 'Active' : 'Waiting'}</b></div></div><div className="pp-note"><span className="material-symbols-outlined">info</span><p>Print options are submitted through the existing PocketPrinter API. Availability depends on the connected printer agent.</p></div></aside></div></>}
          {view === 'queue' && <><PageIntro eyebrow="Job history" title="Print queue" description="Check progress, review job details, and resolve interrupted print jobs."/><div className="pp-queue-panel"><div className="pp-queue-summary"><Metric label="ALL JOBS" value={String(jobs.length)} detail="Recorded jobs" icon="description" tone="neutral"/><Metric label="IN FLIGHT" value={String(counts.active)} detail="Queued or transmitting" icon="sync" tone="blue"/><Metric label="COMPLETED" value={String(counts.completed)} detail="Finished jobs" icon="task_alt" tone="good"/><Metric label="ATTENTION" value={String(counts.failed)} detail="Failed or uncertain" icon="warning" tone="warn"/></div><QueueTable jobs={jobs} onInspect={inspectJob} onCancel={cancelJob} onRetry={retryJob} onReconcile={reconcileJob}/></div></>}
          {view === 'diagnostics' && <><PageIntro eyebrow="Connection checks" title="Diagnostics" description="Check whether the cloud service and Android relay are responding."/><div className="pp-diagnostic-grid"><div className="pp-panel"><PanelTitle title="Cloud API" subtitle="FastAPI service health"/><DiagnosticRow label="Base URL" value={api.getBaseUrl()}/><DiagnosticRow label="Health endpoint" value={health ? 'HEALTHY' : 'UNREACHABLE'} good={!!health}/><DiagnosticRow label="Readiness" value={health ? 'Service responding' : 'Not verified'} good={!!health}/><DiagnosticRow label="Round-trip latency" value={latency == null ? 'Not measured' : latency + ' ms'}/><button className="pp-primary-button pp-test-button" onClick={testApi}>Test API connection</button>{apiReachable !== null && <p className={apiReachable ? 'pp-test-result good' : 'pp-test-result bad'}>{apiReachable ? 'Health and readiness checks passed.' : 'Could not reach the API or readiness check failed.'}</p>}</div><div className="pp-panel"><PanelTitle title="Printer & Android relay" subtitle="Reported device state"/><DiagnosticRow label="Printer" value={device?.name || 'Not paired'}/><DiagnosticRow label="Printer model" value={device?.printerModel || 'Not reported'}/><DiagnosticRow label="USB VID / PID" value={device?.vidPid || 'Not reported'}/><DiagnosticRow label="Device status" value={device?.status || 'UNKNOWN'} good={!!device?.isOnline}/><DiagnosticRow label="WebSocket" value={wsConnected ? 'CONNECTED' : 'DISCONNECTED'} good={wsConnected}/><DiagnosticRow label="Last heartbeat" value={device?.lastHeartbeat ? new Date(device.lastHeartbeat).toLocaleString() : 'Not reported'}/></div></div></>}
          {view === 'console' && <><PageIntro eyebrow="Service details" title="System status" description="A concise view of the API, device and current job activity."/><div className="pp-panel pp-console"><div className="pp-console-head"><span><i className={health ? 'is-online' : ''}/> SYSTEM SNAPSHOT</span><code>{new Date().toLocaleString()}</code></div><div className="pp-console-grid"><DiagnosticRow label="SERVICE" value={health?.service || 'Unavailable'}/><DiagnosticRow label="VERSION" value={health?.version || '—'}/><DiagnosticRow label="API VERSION" value={String(health?.apiVersion ?? '—')}/><DiagnosticRow label="API LATENCY" value={latency == null ? '—' : latency + ' ms'}/><DiagnosticRow label="DEVICE ID" value={device?.id || '—'}/><DiagnosticRow label="DEVICE STATUS" value={device?.status || 'UNKNOWN'}/><DiagnosticRow label="WEBSOCKET" value={wsConnected ? 'CONNECTED' : 'DISCONNECTED'} good={wsConnected}/><DiagnosticRow label="JOBS IN MEMORY" value={String(jobs.length)}/></div><div className="pp-console-footer">Telemetry shown here is sourced from live API responses and client connection state. Unsupported hardware measurements are intentionally not fabricated.</div></div></>}
        </main>
        <footer className="pp-footer"><span>POCKETPRINTER · PRECISION PRINT MANAGEMENT</span><span>API {health?.version || '—'} <i/> {health ? 'SERVICE AVAILABLE' : 'SERVICE UNAVAILABLE'}</span></footer>
      </div>
      {selectedJob && <JobDetailDrawer job={selectedJob} onClose={() => setSelectedJob(null)} onRetry={retryJob} onCancel={cancelJob} onReconcile={reconcileJob}/>}
      {settingsOpen && <SettingsModal device={device} health={health} latency={latency} wsConnected={wsConnected} onClose={() => setSettingsOpen(false)} onRefresh={refreshAll} addToast={addToast}/>}
      <ToastContainer toasts={toasts} onDismiss={removeToast}/>
    </div>
  );
}

function Metric({label,value,detail,icon,tone}:{label:string;value:string;detail:string;icon:string;tone:string}) {
  return <article className="pp-metric"><div className="pp-metric-top"><span>{label}</span><span className="material-symbols-outlined">{icon}</span></div><strong className={tone}>{value}</strong><small>{detail}</small></article>;
}
function PanelTitle({title,subtitle}:{title:string;subtitle:string}) { return <div className="pp-panel-title"><div><h3>{title}</h3><p>{subtitle}</p></div></div>; }
function PageIntro({eyebrow,title,description}:{eyebrow:string;title:string;description:string}) { return <div className="pp-page-intro"><div className="pp-eyebrow">{eyebrow}</div><h2>{title}</h2><p>{description}</p></div>; }
function DiagnosticRow({label,value,good}:{label:string;value:string;good?:boolean}) { return <div className="pp-diagnostic-row"><span>{label}</span><b className={good === undefined ? '' : good ? 'good' : 'bad'}>{value}</b></div>; }
