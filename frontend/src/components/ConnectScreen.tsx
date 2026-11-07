import React, { useState } from 'react';
import { api } from '../api';

interface ConnectScreenProps {
  onConnect: (password: string, vpsUrl?: string) => Promise<any>;
  isLoading: boolean;
}

export const ConnectScreen: React.FC<ConnectScreenProps> = ({ onConnect, isLoading }) => {
  const [vpsUrl, setVpsUrl] = useState(api.getBaseUrl());
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!password.trim()) {
      setError('Please enter the printer password configured on your Android device.');
      return;
    }

    try {
      await onConnect(password.trim(), vpsUrl.trim());
    } catch (err: any) {
      setError(err.message || 'Authentication failed. Check your printer password.');
    }
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '24px 20px',
      }}
    >
      <div
        className="card"
        style={{
          width: '100%',
          maxWidth: '440px',
          padding: '32px 28px',
          boxShadow: 'var(--shadow-lg)',
          borderRadius: 'var(--radius-lg)',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '20px' }}>
          <div className="brand-monogram">PP</div>
          <div>
            <div className="brand-name">PocketPrint Console</div>
            <div className="brand-tag">Direct VPS Hardware Bridge</div>
          </div>
        </div>

        <h2 style={{ fontSize: '20px', fontWeight: 800, letterSpacing: '-0.02em', marginBottom: '6px' }}>
          Connect to Printer
        </h2>
        <p style={{ fontSize: '13px', color: 'var(--text-muted)', marginBottom: '24px' }}>
          No accounts or credentials required. Connect directly using the printer password set on your Android device.
        </p>

        {error && (
          <div
            className="toast-error"
            style={{
              padding: '10px 14px',
              borderRadius: 'var(--radius-md)',
              fontSize: '12px',
              marginBottom: '16px',
              backgroundColor: 'var(--danger-surface)',
              color: 'var(--danger-text)',
            }}
          >
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
          <div className="form-group">
            <label>VPS Server Endpoint</label>
            <input
              type="url"
              value={vpsUrl}
              onChange={(e) => setVpsUrl(e.target.value)}
              placeholder="https://vps.example.com or http://localhost:8000"
              required
              disabled={isLoading}
            />
          </div>

          <div className="form-group">
            <label>Printer Password</label>
            <div style={{ position: 'relative' }}>
              <input
                type={showPassword ? 'text' : 'password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="••••••••••••"
                required
                disabled={isLoading}
                style={{ paddingRight: '40px' }}
              />
              <button
                type="button"
                onClick={() => setShowPassword((v) => !v)}
                style={{
                  position: 'absolute',
                  right: '10px',
                  top: '50%',
                  transform: 'translateY(-50%)',
                  background: 'none',
                  border: 'none',
                  color: 'var(--text-muted)',
                  cursor: 'pointer',
                  padding: 0,
                  fontSize: '12px',
                }}
              >
                {showPassword ? 'Hide' : 'Show'}
              </button>
            </div>
            <span style={{ fontSize: '11px', color: 'var(--text-dim)' }}>
              Matches the password stored in your Android agent's encrypted Keystore.
            </span>
          </div>

          <button
            type="submit"
            className="btn btn-primary"
            style={{ width: '100%', marginTop: '8px', padding: '10px' }}
            disabled={isLoading}
          >
            {isLoading ? 'Verifying with VPS...' : 'Connect to Printer'}
          </button>
        </form>
      </div>
    </div>
  );
};
