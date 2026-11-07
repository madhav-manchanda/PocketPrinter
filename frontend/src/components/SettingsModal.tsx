import React, { useState } from 'react';
import { api } from '../api';
import { Device, HealthResponse } from '../types';

interface SettingsModalProps {
  device: Device | null;
  health: HealthResponse | null;
  latency: number | null;
  wsConnected: boolean;
  onClose: () => void;
  onRefresh: () => void;
  addToast: (msg: string, type: 'success' | 'error' | 'warning' | 'info') => void;
}

export const SettingsModal: React.FC<SettingsModalProps> = ({
  device,
  health,
  latency,
  wsConnected,
  onClose,
  onRefresh,
  addToast,
}) => {
  const [vpsUrl, setVpsUrl] = useState(api.getBaseUrl());
  const [isPinging, setIsPinging] = useState(false);
  const [pingResult, setPingResult] = useState<string | null>(null);

  const handleSaveUrl = (e: React.FormEvent) => {
    e.preventDefault();
    if (!vpsUrl.trim()) return;
    api.setBaseUrl(vpsUrl);
    addToast(`VPS endpoint updated to ${vpsUrl}`, 'success');
    onRefresh();
  };

  const handleTestHealth = async () => {
    setIsPinging(true);
    setPingResult('Testing backend readiness...');
    try {
      const start = performance.now();
      const h = await api.checkHealth();
      const r = await api.checkReadiness();
      const ms = Math.round(performance.now() - start);
      setPingResult(`OK (${ms}ms) · ${h.service} v${h.version} [${r.status}]`);
    } catch (err: any) {
      setPingResult(`FAILED: ${err.message || 'Host unreachable'}`);
    } finally {
      setIsPinging(false);
    }
  };

  return (
    <div className="drawer-backdrop" onClick={onClose}>
      <div
        className="card"
        style={{
          width: '100%',
          maxWidth: '520px',
          margin: 'auto',
          backgroundColor: 'var(--bg-surface)',
          padding: '24px',
          borderRadius: 'var(--radius-lg)',
          boxShadow: 'var(--shadow-lg)',
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="card-header" style={{ marginBottom: '16px' }}>
          <h3 style={{ fontSize: '16px', fontWeight: 700 }}>Console & Network Settings</h3>
          <button type="button" className="btn-icon" onClick={onClose}>
            ✕
          </button>
        </div>

        <form onSubmit={handleSaveUrl} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
          <div className="form-group">
            <label>PocketPrint VPS Server URL</label>
            <div style={{ display: 'flex', gap: '8px' }}>
              <input
                type="url"
                value={vpsUrl}
                onChange={(e) => setVpsUrl(e.target.value)}
                placeholder="https://vps.example.com or http://localhost:8000"
                required
              />
              <button type="submit" className="btn btn-secondary">
                Apply
              </button>
            </div>
            <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
              Supports localhost or Cloudflare Quick Tunnel endpoints.
            </span>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: '4px' }}>
            <button
              type="button"
              className="btn btn-secondary btn-sm"
              onClick={handleTestHealth}
              disabled={isPinging}
            >
              {isPinging ? 'Pinging...' : 'Test Connection Latency'}
            </button>
            {pingResult && (
              <span style={{ fontSize: '12px', fontFamily: 'var(--font-mono)' }}>
                {pingResult}
              </span>
            )}
          </div>

          <hr style={{ border: 'none', borderTop: '1px solid var(--border-subtle)', margin: '4px 0' }} />

          <div>
            <div className="card-title" style={{ marginBottom: '8px' }}>
              Diagnostic Bridge Details
            </div>
            <div className="code-block" style={{ display: 'grid', gridTemplateColumns: '130px 1fr', gap: '6px' }}>
              <span style={{ color: 'var(--text-dim)' }}>Backend State:</span>
              <span style={{ color: health ? 'var(--success)' : 'var(--danger)' }}>
                {health ? `HEALTHY (${latency || 0}ms)` : 'UNREACHABLE'}
              </span>

              <span style={{ color: 'var(--text-dim)' }}>Transport Mode:</span>
              <span>{wsConnected ? 'WebSocket Live Stream' : 'Adaptive Polling (5s)'}</span>

              <span style={{ color: 'var(--text-dim)' }}>Node Hardware:</span>
              <span>{device?.printerModel || 'HP Ink Tank 310 series'}</span>

              <span style={{ color: 'var(--text-dim)' }}>USB VID:PID:</span>
              <span>{device?.vidPid || '03F0:1053'}</span>

              <span style={{ color: 'var(--text-dim)' }}>Client Token:</span>
              <span>
                {api.getToken() ? `${api.getToken().slice(0, 16)}...` : 'Unauthenticated'}
              </span>
            </div>
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '8px' }}>
            <button type="button" className="btn btn-primary" onClick={onClose}>
              Done
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
