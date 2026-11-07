import React, { useState } from 'react';
import { api } from '../api';
import { HealthResponse } from '../types';

interface DiagnosticsProps {
  device: Device | null;
  health: HealthResponse | null;
}

export const Diagnostics: React.FC<DiagnosticsProps> = ({
  device,
  health,
}) => {
  const [selectedTab, setSelectedTab] = useState<'network' | 'printer' | 'agent' | 'logs'>('network');

  const tabs = [
    { key: 'network', label: 'Network' },
    { key: 'printer', label: 'Printer' },
    { key: 'agent', label: 'Agent' },
    { key: 'logs', label: 'Logs' },
  ];

  const getStatusColor = (status: string) => {
    if (status === 'CONNECTED' || status === 'HEALTHY') return 'text-green-400';
    if (status === 'DISCONNECTED' || status === 'DEGRADED') return 'text-yellow-400';
    return 'text-red-400';
  };

  const statusLabel = (status: string) => {
    if (status === 'CONNECTED' || status === 'HEALTHY') return 'Connected';
    if (status === 'DISCONNECTED' || status === 'DEGRADED') return 'Disconnected';
    return 'Offline';
  };

  const getSignalColor = (signal: number) => {
    if (signal > 70) return 'text-green-400';
    if (signal > 40) return 'text-yellow-400';
    return 'text-red-400';
  };

  return (
    <div className="min-h-screen bg-surface px-4 py-6">
      <div className="max-w-4xl mx-auto">
        <header className="mb-6">
          <h1 className="font-headline-md text-headline-md text-on-surface">
            Diagnostics
          </h1>
          <p className="text-on-surface-variant text-sm mt-1">
            System health and connection status monitoring
          </p>
        </header>

        {/* Tabs */}
        <div className="mb-4">
          <div className="grid grid-cols-4 gap-1 bg-surface-container-low rounded-lg p-1">
            {tabs.map((tab) => (
              <button
                key={tab.key}
                className={`flex-1 text-on-surface-variant text-label-xs rounded py-1.5 px-2 transition-colors ${selectedTab === tab.key ? 'text-primary font-medium bg-surface-container-high' : ''}`}
                onClick={() => setSelectedTab(tab.key)}
              >
                {tab.label}
              </button>
            ))}
          </div>
        </div>

        {/* Network Tab */}
        <div className="bg-surface-container-low rounded-lg p-4 mb-4">
          <h2 className="font-label-xs text-label-xs text-on-surface-variant uppercase tracking-wider mb-3">
            Network Status
          </h9>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">Connection</span>
              <div className="flex items-center gap-2">
                <span className={`w-2 h-2 rounded-full ${getStatusColor(health?.connectionStatus || 'DISCONNECTED')}`}></span>
                <span className="font-medium text-on-surface">
                  {statusLabel(health?.connectionStatus || 'DISCONNECTED')}
                </span>
              </div>
            </div>
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">Latency</span>
              <div className="flex items-center gap-2">
                <span className="font-mono-code text-mono-code text-on-surface text-[10px]">{health?.latency?.toString() || 'N/A'}ms</span>
                <span className={`w-3 h-1 rounded-full ${getSignalColor(health?.latency?.realTime ?? 0 || 0)}" style={{ width: `${health?.latency?.realTime ?? 0 || 0}%` }}></span>
              </div>
            </div>
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">Download Speed</span>
              <div className="flex items-center gap-2">
                <span className="font-mono-code text-mono-code text-on-surface text-[10px]">12.4 MB/s</span>
                <span className={`w-3 h-1 rounded-full ${getSignalColor(85)}" style={{ width: '85%' }}></span>
              </div>
            </div>
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">Upload Speed</span>
              <div className="flex items-center gap-2">
                <span className="font-mono-code text-mono-code text-on-surface text-[10px]">2.1 MB/s</span>
                <span className={`w-3 h-1 rounded-full ${getSignalColor(15)}" style={{ width: '15%' }}></span>
              </div>
            </div>
          </div>
        </div>

        {/* Printer Tab */}
        <div className="bg-surface-container-low rounded-lg p-4 mb-4">
          <h2 className="font-label-xs text-label-xs text-on-surface-variant uppercase tracking-wider mb-3">
            Printer Status
          </h2>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">Temperature</span>
              <div className="flex items-center gap-2">
                <span className="font-mono-code text-mono-code text-on-surface text-[10px]">28°C</span>
                <span className={`w-3 h-1 rounded-full ${getSignalColor(30)}" style={{ width: '30%' }}></span>
                <span className="font-label-xs text-label-xs text-on-surface-variant">Normal operating</span>
              </div>
            </div>
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">Head Temp</span>
              <div className="flex items-center gap-2">
                <span className="font-mono-code text-mono-code text-on-surface text-[10px]">Normal</span>
                <span className={`w-3 h-1 rounded-full ${getSignalColor(25)}" style={{ width: '25%' }}></span>
              </div>
            </div>
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">Paper Level</span>
              <div className="flex items-center gap-2">
                <span className="w-3 h-1 rounded-full bg-green-500/50 opacity-80" style={{ width: '74%' }}></span>
                <span className="font-label-xs text-label-xs text-on-surface-variant">74% remaining</span>
              </div>
            </div>
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">Voltage</span>
              <div className="flex items-center gap-2">
                <span className="font-mono-code text-mono-code text-on-surface text-[10px]">5.2V</span>
                <span className={`w-3 h-1 rounded-full ${getSignalColor(51)}" style={{ width: '51%' }}></span>
                <span className="font-label-xs text-label-xs text-on-surface-variant">Good</span>
              </div>
            </div>
          </div>
          <div className="mt-3 pt-3 border-t border-surface-container-highest">
            <div className="flex items-center justify-between">
              <span className="font-label-xs text-label-xs text-on-surface-variant">Status Details</span>
              <span className="font-mono-code text-mono-code text-tertiary">Ready & Idle</span>
            </div>
          </div>
        </div>

        {/* Agent Tab */}
        <div className="bg-surface-container-low rounded-lg p-4 mb-4">
          <h2 className="font-label-xs text-label-xs text-on-surface-variant uppercase tracking-wider mb-3">
            Android Agent
          </h2>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">Heartbeat</span>
              <div className="flex items-center gap-2">
                <span className={`w-2 h-2 rounded-full ${getStatusColor(health?.agentStatus || 'DISCONNECTED')}`}></span>
                <span className="font-medium text-on-surface">
                  {statusLabel(health?.agentStatus || 'DISCONNECTED')}
                </span>
              </div>
            </div>
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">Protocol</span>
              <div className="flex items-center gap-2">
                <span className="font-mono-code text-mono-code text-on-surface text-[10px]">FastAPI WS-v2</span>
              </div>
            </div>
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">Battery</span>
              <div className="flex items-center gap-2">
                <span className="font-mono-code text-mono-code text-on-surface text-[10px]">94%</span>
                <span className={`w-3 h-1 rounded-full ${getSignalColor(94)}" style={{ width: '94%' }}></span>
              </div>
            </div>
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">Uptime</span>
              <div className="flex items-center gap-2">
                <span className="font-mono-code text-mono-code text-on-surface text-[10px]">5h 23m</span>
              </div>
            </div>
          </div>
          <div className="mt-3 pt-3 border-t border-surface-container-highest">
            <div className="flex items-center justify-between">
              <span className="font-label-xs text-label-xs text-on-surface-variant">Last Sync</span>
              <span className="font-mono-code text-mono-code text-tertiary">32s ago</span>
            </div>
          </div>
        </div>

        {/* Logs Tab */}
        <div className="bg-surface-container-low rounded-lg p-4 h-[400px] overflow-y-auto">
          <h2 className="font-label-xs text-label-xs text-on-surface-variant uppercase tracking-wider mb-3">
            Recent Logs
          </h2>
          <div className="h-full overflow-y-auto">
            <div className="space-y-2 max-h-[300px] overflow-y-auto">
              {/* Demo log entries */}
              <div className="p-3 bg-surface-container-highest rounded text-xs font-mono-code text-tertiary">
                [2024-01-15 10:30:15] Backend health check: OK
              </div>
              <div className="p-3 bg-surface-container-highest rounded text-xs font-mono-code text-tertiary">
                [2024-01-15 10:30:10] Agent heartbeat received: 42ms latency
              </div>
              <div className="p-3 bg-surface-container-highest rounded text-xs font-mono-code text-tertiary">
                [2024-01-15 10:30:05] Print job #42 completed successfully
              </div>
              <div className="p-3 bg-surface-container-highest rounded text-xs font-mono-code text-tertiary">
                [2024-01-15 10:30:01] Connection re-established after reconnect
              </div>
              <div className="p-3 bg-surface-container-highest rounded text-xs font-mono-code text-tertiary">
                [2024-01-15 10:29:58] Temperature check: 28°C normal
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}