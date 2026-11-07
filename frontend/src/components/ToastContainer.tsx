import React from 'react';
import { ToastMessage } from '../types';

interface ToastContainerProps {
  toasts: ToastMessage[];
  onDismiss: (id: string) => void;
}

export const ToastContainer: React.FC<ToastContainerProps> = ({ toasts, onDismiss }) => {
  if (toasts.length === 0) return null;

  return (
    <div className="toast-container">
      {toasts.map((t) => (
        <div key={t.id} className={`toast toast-${t.type}`}>
          <div style={{ flex: 1, minWidth: 0 }}>
            {t.title && <div style={{ fontWeight: 600, marginBottom: '2px' }}>{t.title}</div>}
            <div style={{ wordBreak: 'break-word' }}>{t.message}</div>
          </div>
          <button
            type="button"
            onClick={() => onDismiss(t.id)}
            style={{
              background: 'none',
              border: 'none',
              color: 'var(--text-muted)',
              cursor: 'pointer',
              padding: 0,
              fontSize: '12px',
            }}
          >
            ✕
          </button>
        </div>
      ))}
    </div>
  );
};
