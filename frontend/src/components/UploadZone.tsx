import React, { useMemo, useRef, useState } from 'react';
import {
  AdvancedOptions,
  ColorMode,
  MarginPreset,
  Margins,
  OptimizationTarget,
  Orientation,
  PaperSize,
  PaperType,
  PrintQuality,
  Scaling,
  UploadOptions,
} from '../types';

interface UploadZoneProps {
  onUpload: (file: File, options: UploadOptions, onProgress: (percent: number) => void) => Promise<any>;
  isPrinterOnline: boolean;
  addToast: (msg: string, type: 'success' | 'error' | 'warning' | 'info') => void;
}

const SUPPORTED_EXTENSIONS = [
  'pdf',
  'png',
  'jpg',
  'jpeg',
  'bmp',
  'webp',
  'tif',
  'tiff',
  'txt',
  'csv',
];

type TabId = 'layout' | 'quality' | 'margins' | 'color' | 'paper' | 'advanced';

const PAPER_DIMENSIONS: Record<string, { mm: [number, number]; in: [number, number]; label: string }> = {
  A4: { mm: [210, 297], in: [8.27, 11.69], label: 'A4 (210 × 297 mm)' },
  A5: { mm: [148, 210], in: [5.83, 8.27], label: 'A5 (148 × 210 mm)' },
  A3: { mm: [297, 420], in: [11.69, 16.54], label: 'A3 (297 × 420 mm)' },
  LETTER: { mm: [215.9, 279.4], in: [8.5, 11.0], label: 'Letter (8.5 × 11.0 in)' },
  LEGAL: { mm: [215.9, 355.6], in: [8.5, 14.0], label: 'Legal (8.5 × 14.0 in)' },
  CUSTOM: { mm: [210, 297], in: [8.27, 11.69], label: 'Custom Dimensions' },
};

export const UploadZone: React.FC<UploadZoneProps> = ({ onUpload, isPrinterOnline, addToast }) => {
  // File state
  const [file, setFile] = useState<File | null>(null);
  const [isDragging, setIsDragging] = useState(false);
  const [isUploading, setIsUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Active Tab
  const [activeTab, setActiveTab] = useState<TabId>('layout');

  // Preset
  const [preset, setPreset] = useState<string>('everyday');

  // 1. Layout Tab State
  const [orientation, setOrientation] = useState<Orientation>('PORTRAIT');
  const [pagesPerSheet, setPagesPerSheet] = useState<number>(1);
  const [copies, setCopies] = useState<number>(1);
  const [collate, setCollate] = useState<boolean>(true);
  const [pageRangeMode, setPageRangeMode] = useState<'all' | 'custom'>('all');
  const [pageRange, setPageRange] = useState<string>('');
  const [scaling, setScaling] = useState<Scaling>('FIT_TO_PAGE');
  const [customScalePercent, setCustomScalePercent] = useState<number>(100);
  const [reverseOrder, setReverseOrder] = useState<boolean>(false);

  // 2. Quality Tab State
  const [quality, setQuality] = useState<PrintQuality>('NORMAL');
  const [dpi, setDpi] = useState<number>(300);
  const [optimization, setOptimization] = useState<OptimizationTarget>('mixed');

  // 3. Margins Tab State
  const [marginPreset, setMarginPreset] = useState<MarginPreset>('normal');
  const [marginUnit, setMarginUnit] = useState<'mm' | 'in'>('mm');
  const [margins, setMargins] = useState<Margins>({
    top: 4,
    bottom: 4,
    left: 4,
    right: 4,
    unit: 'mm',
  });
  const [lockEqualMargins, setLockEqualMargins] = useState<boolean>(true);
  const [centerHorizontal, setCenterHorizontal] = useState<boolean>(true);
  const [centerVertical, setCenterVertical] = useState<boolean>(true);

  // 4. Color Tab State
  const [colorMode, setColorMode] = useState<ColorMode>('COLOR_RGB');
  const [colorIntensity, setColorIntensity] = useState<number>(100);
  const [imageColorOpt, setImageColorOpt] = useState<boolean>(true);
  const [blackOnly, setBlackOnly] = useState<boolean>(false);

  // 5. Paper Tab State
  const [paperSize, setPaperSize] = useState<PaperSize>('A4');
  const [paperType, setPaperType] = useState<PaperType>('plain');
  const [paperSource, setPaperSource] = useState<string>('AUTO_TRAY_1');

  // 6. Advanced Tab State
  const [skipBlankPages, setSkipBlankPages] = useState<boolean>(true);
  const [backgroundGraphics, setBackgroundGraphics] = useState<boolean>(true);
  const [imageInterpolation, setImageInterpolation] = useState<boolean>(true);
  const [duplex, setDuplex] = useState<'NONE' | 'LONG_EDGE' | 'SHORT_EDGE'>('NONE');
  const [renderingMode, setRenderingMode] = useState<'pure_raster' | 'raster_hybrid'>('raster_hybrid');

  // File validator
  const validateAndSetFile = (candidate: File) => {
    const ext = candidate.name.split('.').pop()?.toLowerCase() || '';
    if (!SUPPORTED_EXTENSIONS.includes(ext)) {
      addToast(
        `Unsupported file type .${ext}. Supported: PDF, PNG, JPG, BMP, WebP, TIFF, TXT, CSV.`,
        'warning'
      );
      return;
    }
    if (candidate.size > 50 * 1024 * 1024) {
      addToast('File exceeds the 50MB maximum upload limit', 'error');
      return;
    }
    setFile(candidate);
  };

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(true);
  };

  const handleDragLeave = () => {
    setIsDragging(false);
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      validateAndSetFile(e.dataTransfer.files[0]);
    }
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files.length > 0) {
      validateAndSetFile(e.target.files[0]);
    }
  };

  // Presets handler
  const applyPreset = (presetName: string) => {
    setPreset(presetName);
    if (presetName === 'everyday') {
      setColorMode('COLOR_RGB');
      setQuality('NORMAL');
      setDpi(300);
      setPaperSize('A4');
      setPaperType('plain');
      setMargins({ top: 4, bottom: 4, left: 4, right: 4, unit: 'mm' });
      setMarginPreset('normal');
      setPagesPerSheet(1);
      setOrientation('PORTRAIT');
      setScaling('FIT_TO_PAGE');
      setCollate(true);
      setOptimization('mixed');
    } else if (presetName === 'high_quality') {
      setColorMode('COLOR_RGB');
      setQuality('HIGH');
      setDpi(600);
      setPaperSize('A4');
      setPaperType('glossy');
      setMargins({ top: 3, bottom: 3, left: 3, right: 3, unit: 'mm' });
      setMarginPreset('narrow');
      setPagesPerSheet(1);
      setScaling('FIT_TO_PRINTABLE');
      setOptimization('fine_detail');
    } else if (presetName === 'draft_saver') {
      setColorMode('MONOCHROME_1BIT');
      setQuality('DRAFT');
      setDpi(300);
      setPaperSize('A4');
      setPaperType('plain');
      setMargins({ top: 5, bottom: 5, left: 5, right: 5, unit: 'mm' });
      setMarginPreset('normal');
      setPagesPerSheet(1);
      setOptimization('economy');
      setBlackOnly(true);
    } else if (presetName === 'photo') {
      setColorMode('COLOR_RGB');
      setQuality('BEST');
      setDpi(600);
      setPaperType('photo');
      setMargins({ top: 0, bottom: 0, left: 0, right: 0, unit: 'mm' });
      setMarginPreset('borderless');
      setOptimization('images');
    }
  };

  // Margin Preset application
  const applyMarginPreset = (presetKey: MarginPreset) => {
    setMarginPreset(presetKey);
    const unit = marginUnit;
    const factor = unit === 'in' ? 1 / 25.4 : 1;
    if (presetKey === 'normal') {
      const v = parseFloat((4 * factor).toFixed(2));
      setMargins({ top: v, bottom: v, left: v, right: v, unit });
    } else if (presetKey === 'narrow') {
      const v = parseFloat((2 * factor).toFixed(2));
      setMargins({ top: v, bottom: v, left: v, right: v, unit });
    } else if (presetKey === 'moderate') {
      const v = parseFloat((6 * factor).toFixed(2));
      setMargins({ top: v, bottom: v, left: v, right: v, unit });
    } else if (presetKey === 'wide') {
      const v = parseFloat((12 * factor).toFixed(2));
      setMargins({ top: v, bottom: v, left: v, right: v, unit });
    } else if (presetKey === 'borderless') {
      setMargins({ top: 0, bottom: 0, left: 0, right: 0, unit });
    }
  };

  const handleMarginChange = (side: keyof Omit<Margins, 'unit'>, value: number) => {
    setMarginPreset('custom');
    if (lockEqualMargins) {
      setMargins({ top: value, bottom: value, left: value, right: value, unit: marginUnit });
    } else {
      setMargins((prev) => ({ ...prev, [side]: value }));
    }
  };

  // Tradeoff calculations
  const speedPercentage = useMemo(() => {
    let score = 70;
    if (quality === 'DRAFT') score += 25;
    if (quality === 'HIGH') score -= 25;
    if (quality === 'BEST') score -= 40;
    if (dpi === 600) score -= 20;
    if (dpi === 150) score += 15;
    if (colorMode === 'MONOCHROME_1BIT') score += 15;
    return Math.max(15, Math.min(100, score));
  }, [quality, dpi, colorMode]);

  const qualityPercentage = useMemo(() => {
    let score = 65;
    if (quality === 'DRAFT') score -= 30;
    if (quality === 'HIGH') score += 20;
    if (quality === 'BEST') score += 35;
    if (dpi === 600) score += 25;
    if (dpi === 150) score -= 25;
    if (colorMode === 'COLOR_RGB') score += 10;
    return Math.max(20, Math.min(100, score));
  }, [quality, dpi, colorMode]);

  // Submit Handler
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file) {
      addToast('Please select or drop a document to print', 'warning');
      return;
    }

    setIsUploading(true);
    setUploadProgress(0);

    const advOptions: AdvancedOptions = {
      skipBlankPages,
      backgroundGraphics,
      imageInterpolation,
      reverseOrder,
      duplex,
      renderingMode,
      centerHorizontal,
      centerVertical,
      colorIntensity,
      blackOnly,
    };

    const options: UploadOptions = {
      colorMode,
      paperSize,
      orientation,
      copies: Math.min(Math.max(copies, 1), 99),
      scaling,
      pageRange: pageRangeMode === 'custom' && pageRange.trim() ? pageRange.trim() : undefined,
      dpi,
      quality,
      margins,
      collate,
      pagesPerSheet,
      printOptions: advOptions,
    };

    try {
      await onUpload(file, options, (percent) => {
        setUploadProgress(percent);
      });
      // Reset after success
      setFile(null);
      setUploadProgress(0);
      if (fileInputRef.current) fileInputRef.current.value = '';
    } catch {
      // Handled in parent
    } finally {
      setIsUploading(false);
    }
  };

  const formatFileSize = (bytes: number) => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
  };

  // Preview Aspect Ratio calculation
  const previewRatio = useMemo(() => {
    const dim = PAPER_DIMENSIONS[paperSize] || PAPER_DIMENSIONS.A4;
    const [w, h] = dim.mm;
    return orientation === 'PORTRAIT' ? w / h : h / w;
  }, [paperSize, orientation]);

  const previewBoxStyle = useMemo(() => {
    const maxHeight = 190;
    const maxWidth = 230;
    let width = maxHeight * previewRatio;
    let height = maxHeight;
    if (width > maxWidth) {
      width = maxWidth;
      height = width / previewRatio;
    }
    return {
      width: `${Math.round(width)}px`,
      height: `${Math.round(height)}px`,
    };
  }, [previewRatio]);

  // Preview margins in percentage of sheet
  const marginPercentage = useMemo(() => {
    const dim = PAPER_DIMENSIONS[paperSize] || PAPER_DIMENSIONS.A4;
    const [wMm, hMm] = orientation === 'PORTRAIT' ? dim.mm : [dim.mm[1], dim.mm[0]];
    const factor = margins.unit === 'in' ? 25.4 : 1;
    const topMm = margins.top * factor;
    const bottomMm = margins.bottom * factor;
    const leftMm = margins.left * factor;
    const rightMm = margins.right * factor;

    const topPct = Math.min(40, Math.max(0, (topMm / hMm) * 100));
    const bottomPct = Math.min(40, Math.max(0, (bottomMm / hMm) * 100));
    const leftPct = Math.min(40, Math.max(0, (leftMm / wMm) * 100));
    const rightPct = Math.min(40, Math.max(0, (rightMm / wMm) * 100));

    return {
      top: `${topPct}%`,
      bottom: `${bottomPct}%`,
      left: `${leftPct}%`,
      right: `${rightPct}%`,
    };
  }, [paperSize, orientation, margins]);

  return (
    <div className="card upload-card dispatcher-studio">
      {/* Dispatcher Studio Header */}
      <div className="card-header dispatcher-header-actions">
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <span className="card-title">Document Dispatcher</span>
          {!isPrinterOnline && (
            <span className="badge badge-warning" title="Android agent will queue job until USB printer is ready">
              BUFFERING (PRINTER OFFLINE)
            </span>
          )}
        </div>

        {/* Presets Toolbar */}
        <div className="preset-selector-wrap">
          <label style={{ fontSize: '11px', color: 'var(--text-muted)' }}>Preset:</label>
          <select
            className="preset-select"
            value={preset}
            onChange={(e) => applyPreset(e.target.value)}
            disabled={isUploading}
          >
            <option value="everyday">Everyday Documents (Standard)</option>
            <option value="high_quality">High Quality Presentation</option>
            <option value="draft_saver">Draft / Ink Saver</option>
            <option value="photo">Photo / Edge-to-Edge</option>
            <option value="custom">Custom Configuration</option>
          </select>
          <button
            type="button"
            className="btn btn-secondary btn-sm"
            onClick={() => applyPreset('everyday')}
            title="Reset settings to defaults"
            disabled={isUploading}
          >
            Reset
          </button>
        </div>
      </div>

      <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
        {/* Dropzone / File Picker */}
        <input
          type="file"
          ref={fileInputRef}
          onChange={handleFileChange}
          style={{ display: 'none' }}
          accept=".pdf,.png,.jpg,.jpeg,.bmp,.webp,.tif,.tiff,.txt,.csv"
        />

        {!file ? (
          <div
            className={`dropzone ${isDragging ? 'active' : ''}`}
            onDragOver={handleDragOver}
            onDragLeave={handleDragLeave}
            onDrop={handleDrop}
            onClick={() => fileInputRef.current?.click()}
          >
            <svg className="dropzone-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
              <polyline points="17 8 12 3 7 8" />
              <line x1="12" y1="3" x2="12" y2="15" />
            </svg>
            <span className="dropzone-label">Drop file here or browse files</span>
            <span className="dropzone-hint">
              PDF, PNG, JPG, WebP, TIFF, TXT, CSV (max 50 MB)
            </span>
          </div>
        ) : (
          <div className="file-pill">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
              <polyline points="14 2 14 8 20 8" />
            </svg>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {file.name}
              </div>
              <div style={{ color: 'var(--text-dim)', fontSize: '11px' }}>
                {formatFileSize(file.size)}
              </div>
            </div>
            {!isUploading && (
              <button
                type="button"
                className="file-pill-remove"
                style={{ background: 'transparent', border: 'none' }}
                onClick={() => {
                  setFile(null);
                  if (fileInputRef.current) fileInputRef.current.value = '';
                }}
                title="Remove file"
              >
                ✕
              </button>
            )}
          </div>
        )}

        {/* Upload Progress Bar */}
        {isUploading && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '11px', color: 'var(--text-muted)' }}>
              <span>Uploading to VPS buffer...</span>
              <span>{uploadProgress}%</span>
            </div>
            <div className="progress-track">
              <div className="progress-fill" style={{ width: `${uploadProgress}%` }} />
            </div>
          </div>
        )}

        {/* Navigation Tabs */}
        <div className="tab-nav">
          <button
            type="button"
            className={`tab-nav-btn ${activeTab === 'layout' ? 'active' : ''}`}
            onClick={() => setActiveTab('layout')}
          >
            1. Layout
          </button>
          <button
            type="button"
            className={`tab-nav-btn ${activeTab === 'quality' ? 'active' : ''}`}
            onClick={() => setActiveTab('quality')}
          >
            2. Quality
          </button>
          <button
            type="button"
            className={`tab-nav-btn ${activeTab === 'margins' ? 'active' : ''}`}
            onClick={() => setActiveTab('margins')}
          >
            3. Margins
          </button>
          <button
            type="button"
            className={`tab-nav-btn ${activeTab === 'color' ? 'active' : ''}`}
            onClick={() => setActiveTab('color')}
          >
            4. Color
          </button>
          <button
            type="button"
            className={`tab-nav-btn ${activeTab === 'paper' ? 'active' : ''}`}
            onClick={() => setActiveTab('paper')}
          >
            5. Paper
          </button>
          <button
            type="button"
            className={`tab-nav-btn ${activeTab === 'advanced' ? 'active' : ''}`}
            onClick={() => setActiveTab('advanced')}
          >
            6. Advanced
          </button>
        </div>

        {/* Main Grid: Left = Tab Settings, Right = Live Preview */}
        <div className="dispatcher-main-grid">
          {/* TAB 1: LAYOUT */}
          {activeTab === 'layout' && (
            <div className="tab-pane">
              <div className="form-row">
                <div className="form-group">
                  <label>Orientation</label>
                  <div className="option-cards-grid" style={{ gridTemplateColumns: '1fr 1fr' }}>
                    <button
                      type="button"
                      className={`option-card-btn ${orientation === 'PORTRAIT' ? 'selected' : ''}`}
                      onClick={() => setOrientation('PORTRAIT')}
                    >
                      <span className="option-card-title">Portrait</span>
                      <span className="option-card-sub">Vertical sheet</span>
                    </button>
                    <button
                      type="button"
                      className={`option-card-btn ${orientation === 'LANDSCAPE' ? 'selected' : ''}`}
                      onClick={() => setOrientation('LANDSCAPE')}
                    >
                      <span className="option-card-title">Landscape</span>
                      <span className="option-card-sub">Horizontal sheet</span>
                    </button>
                  </div>
                </div>

                <div className="form-group">
                  <label>Pages Per Sheet (N-Up)</label>
                  <select
                    value={pagesPerSheet}
                    onChange={(e) => setPagesPerSheet(parseInt(e.target.value, 10))}
                    disabled={isUploading}
                  >
                    <option value={1}>1 page per sheet (Standard)</option>
                    <option value={2}>2 pages per sheet (Side by side)</option>
                    <option value={4}>4 pages per sheet (2 × 2 grid)</option>
                    <option value={6}>6 pages per sheet (2 × 3 grid)</option>
                    <option value={9}>9 pages per sheet (3 × 3 grid)</option>
                    <option value={16}>16 pages per sheet (4 × 4 grid)</option>
                  </select>
                  <span className="field-desc">Renders multiple pages on a single physical page.</span>
                </div>
              </div>

              <div className="form-row">
                <div className="form-group">
                  <label>Copies</label>
                  <input
                    type="number"
                    min="1"
                    max="99"
                    value={copies}
                    onChange={(e) => setCopies(parseInt(e.target.value, 10) || 1)}
                    disabled={isUploading}
                  />
                </div>

                <div className="form-group">
                  <label>Collate Pages</label>
                  <div style={{ display: 'flex', alignItems: 'center', height: '38px' }}>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '8px', cursor: 'pointer', margin: 0 }}>
                      <input
                        type="checkbox"
                        checked={collate}
                        onChange={(e) => setCollate(e.target.checked)}
                        disabled={isUploading}
                        style={{ width: '16px', height: '16px', accentColor: 'var(--primary)' }}
                      />
                      <span>Collated (1,2,3... 1,2,3...)</span>
                    </label>
                  </div>
                </div>
              </div>

              <div className="form-row">
                <div className="form-group">
                  <label>Page Range</label>
                  <div style={{ display: 'flex', gap: '8px', marginBottom: '6px' }}>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px', cursor: 'pointer' }}>
                      <input
                        type="radio"
                        name="pageRangeMode"
                        checked={pageRangeMode === 'all'}
                        onChange={() => setPageRangeMode('all')}
                        disabled={isUploading}
                      />
                      All Pages
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px', cursor: 'pointer' }}>
                      <input
                        type="radio"
                        name="pageRangeMode"
                        checked={pageRangeMode === 'custom'}
                        onChange={() => setPageRangeMode('custom')}
                        disabled={isUploading}
                      />
                      Custom Range
                    </label>
                  </div>
                  {pageRangeMode === 'custom' && (
                    <input
                      type="text"
                      placeholder="e.g. 1-3, 5, 8-10"
                      value={pageRange}
                      onChange={(e) => setPageRange(e.target.value)}
                      disabled={isUploading}
                    />
                  )}
                </div>

                <div className="form-group">
                  <label>Scaling</label>
                  <select
                    value={scaling}
                    onChange={(e) => setScaling(e.target.value as Scaling)}
                    disabled={isUploading}
                  >
                    <option value="FIT_TO_PAGE">Fit to Page (Default)</option>
                    <option value="FIT_TO_PRINTABLE">Fit to Printable Area</option>
                    <option value="ACTUAL_SIZE">Actual Size (100%)</option>
                    <option value="CUSTOM">Custom Percentage</option>
                  </select>
                  {scaling === 'CUSTOM' && (
                    <input
                      type="number"
                      min="25"
                      max="400"
                      value={customScalePercent}
                      onChange={(e) => setCustomScalePercent(parseInt(e.target.value, 10) || 100)}
                      placeholder="Scale %"
                      style={{ marginTop: '6px' }}
                      disabled={isUploading}
                    />
                  )}
                </div>
              </div>

              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <input
                  type="checkbox"
                  id="reverseOrder"
                  checked={reverseOrder}
                  onChange={(e) => setReverseOrder(e.target.checked)}
                  disabled={isUploading}
                  style={{ width: '16px', height: '16px', accentColor: 'var(--primary)' }}
                />
                <label htmlFor="reverseOrder" style={{ cursor: 'pointer', margin: 0 }}>
                  Reverse page order (Print last page first)
                </label>
              </div>
            </div>
          )}

          {/* TAB 2: QUALITY */}
          {activeTab === 'quality' && (
            <div className="tab-pane">
              <div className="form-group">
                <label>Print Quality Level</label>
                <div className="option-cards-grid">
                  <button
                    type="button"
                    className={`option-card-btn ${quality === 'DRAFT' ? 'selected' : ''}`}
                    onClick={() => setQuality('DRAFT')}
                  >
                    <span className="option-card-title">Draft</span>
                    <span className="option-card-sub">Fast, ink-saver mode</span>
                  </button>
                  <button
                    type="button"
                    className={`option-card-btn ${quality === 'NORMAL' ? 'selected' : ''}`}
                    onClick={() => setQuality('NORMAL')}
                  >
                    <span className="option-card-title">Normal</span>
                    <span className="option-card-sub">Balanced output</span>
                  </button>
                  <button
                    type="button"
                    className={`option-card-btn ${quality === 'HIGH' ? 'selected' : ''}`}
                    onClick={() => setQuality('HIGH')}
                  >
                    <span className="option-card-title">High</span>
                    <span className="option-card-sub">Dense text & graphics</span>
                  </button>
                  <button
                    type="button"
                    className={`option-card-btn ${quality === 'BEST' ? 'selected' : ''}`}
                    onClick={() => setQuality('BEST')}
                  >
                    <span className="option-card-title">Best</span>
                    <span className="option-card-sub">Maximum ink density</span>
                  </button>
                </div>
              </div>

              <div className="form-row">
                <div className="form-group">
                  <label>Resolution (DPI)</label>
                  <select
                    value={dpi}
                    onChange={(e) => setDpi(parseInt(e.target.value, 10))}
                    disabled={isUploading}
                  >
                    <option value={150}>150 DPI (Fast preview draft)</option>
                    <option value={300}>300 DPI (Standard document raster)</option>
                    <option value={600}>600 DPI (Fine photographic detail)</option>
                  </select>
                  <span className="field-desc">Native resolution negotiated with printer raster engine.</span>
                </div>

                <div className="form-group">
                  <label>Optimization Target</label>
                  <select
                    value={optimization}
                    onChange={(e) => setOptimization(e.target.value as OptimizationTarget)}
                    disabled={isUploading}
                  >
                    <option value="text">Sharp Black Text</option>
                    <option value="images">Photographic Images</option>
                    <option value="mixed">Mixed Document (Text & Graphics)</option>
                    <option value="economy">Economy Ink Saver</option>
                    <option value="fine_detail">Fine Vector Detail</option>
                  </select>
                  <span className="field-desc">Applies targeted dithering & sharpness filters.</span>
                </div>
              </div>

              {/* Trade-off meter */}
              <div className="tradeoff-meter">
                <div style={{ fontSize: '11px', fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase' }}>
                  Hardware Performance Trade-off
                </div>
                <div className="tradeoff-row">
                  <span style={{ width: '80px', color: 'var(--text-main)' }}>Print Speed</span>
                  <div className="tradeoff-bar-track">
                    <div className="tradeoff-bar-fill fill-speed" style={{ width: `${speedPercentage}%` }} />
                  </div>
                  <span style={{ width: '40px', textAlign: 'right', fontWeight: 600 }}>{speedPercentage}%</span>
                </div>
                <div className="tradeoff-row">
                  <span style={{ width: '80px', color: 'var(--text-main)' }}>Output Density</span>
                  <div className="tradeoff-bar-track">
                    <div className="tradeoff-bar-fill fill-quality" style={{ width: `${qualityPercentage}%` }} />
                  </div>
                  <span style={{ width: '40px', textAlign: 'right', fontWeight: 600 }}>{qualityPercentage}%</span>
                </div>
              </div>
            </div>
          )}

          {/* TAB 3: MARGINS */}
          {activeTab === 'margins' && (
            <div className="tab-pane">
              <div className="form-group">
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <label>Margin Presets</label>
                  <div className="margin-unit-toggle">
                    <button
                      type="button"
                      className={`margin-unit-btn ${marginUnit === 'mm' ? 'active' : ''}`}
                      onClick={() => setMarginUnit('mm')}
                    >
                      mm
                    </button>
                    <button
                      type="button"
                      className={`margin-unit-btn ${marginUnit === 'in' ? 'active' : ''}`}
                      onClick={() => setMarginUnit('in')}
                    >
                      inch
                    </button>
                  </div>
                </div>

                <div className="option-cards-grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(90px, 1fr))' }}>
                  <button
                    type="button"
                    className={`option-card-btn ${marginPreset === 'normal' ? 'selected' : ''}`}
                    onClick={() => applyMarginPreset('normal')}
                  >
                    <span className="option-card-title">Normal</span>
                    <span className="option-card-sub">4 mm</span>
                  </button>
                  <button
                    type="button"
                    className={`option-card-btn ${marginPreset === 'narrow' ? 'selected' : ''}`}
                    onClick={() => applyMarginPreset('narrow')}
                  >
                    <span className="option-card-title">Narrow</span>
                    <span className="option-card-sub">2 mm</span>
                  </button>
                  <button
                    type="button"
                    className={`option-card-btn ${marginPreset === 'moderate' ? 'selected' : ''}`}
                    onClick={() => applyMarginPreset('moderate')}
                  >
                    <span className="option-card-title">Moderate</span>
                    <span className="option-card-sub">6 mm</span>
                  </button>
                  <button
                    type="button"
                    className={`option-card-btn ${marginPreset === 'wide' ? 'selected' : ''}`}
                    onClick={() => applyMarginPreset('wide')}
                  >
                    <span className="option-card-title">Wide</span>
                    <span className="option-card-sub">12 mm</span>
                  </button>
                  <button
                    type="button"
                    className={`option-card-btn ${marginPreset === 'borderless' ? 'selected' : ''}`}
                    onClick={() => applyMarginPreset('borderless')}
                  >
                    <span className="option-card-title">Borderless</span>
                    <span className="option-card-sub">0 mm</span>
                  </button>
                </div>
              </div>

              {/* Custom Margins Input Box */}
              <div className="margin-control-wrap">
                <div className="margin-input-group">
                  <label>Top ({marginUnit})</label>
                  <input
                    type="number"
                    step="0.5"
                    min="0"
                    max="50"
                    value={margins.top}
                    onChange={(e) => handleMarginChange('top', parseFloat(e.target.value) || 0)}
                    disabled={isUploading}
                  />
                </div>
                <div className="margin-input-group">
                  <label>Bottom ({marginUnit})</label>
                  <input
                    type="number"
                    step="0.5"
                    min="0"
                    max="50"
                    value={margins.bottom}
                    onChange={(e) => handleMarginChange('bottom', parseFloat(e.target.value) || 0)}
                    disabled={isUploading}
                  />
                </div>
                <div className="margin-input-group">
                  <label>Left ({marginUnit})</label>
                  <input
                    type="number"
                    step="0.5"
                    min="0"
                    max="50"
                    value={margins.left}
                    onChange={(e) => handleMarginChange('left', parseFloat(e.target.value) || 0)}
                    disabled={isUploading}
                  />
                </div>
                <div className="margin-input-group">
                  <label>Right ({marginUnit})</label>
                  <input
                    type="number"
                    step="0.5"
                    min="0"
                    max="50"
                    value={margins.right}
                    onChange={(e) => handleMarginChange('right', parseFloat(e.target.value) || 0)}
                    disabled={isUploading}
                  />
                </div>
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: '8px', cursor: 'pointer', margin: 0 }}>
                  <input
                    type="checkbox"
                    checked={lockEqualMargins}
                    onChange={(e) => setLockEqualMargins(e.target.checked)}
                    style={{ width: '16px', height: '16px', accentColor: 'var(--primary)' }}
                  />
                  <span>Lock equal margins on all sides</span>
                </label>
                <div style={{ display: 'flex', gap: '16px' }}>
                  <label style={{ display: 'flex', alignItems: 'center', gap: '6px', cursor: 'pointer', margin: 0, fontSize: '12px' }}>
                    <input
                      type="checkbox"
                      checked={centerHorizontal}
                      onChange={(e) => setCenterHorizontal(e.target.checked)}
                      style={{ accentColor: 'var(--primary)' }}
                    />
                    Center Horizontally
                  </label>
                  <label style={{ display: 'flex', alignItems: 'center', gap: '6px', cursor: 'pointer', margin: 0, fontSize: '12px' }}>
                    <input
                      type="checkbox"
                      checked={centerVertical}
                      onChange={(e) => setCenterVertical(e.target.checked)}
                      style={{ accentColor: 'var(--primary)' }}
                    />
                    Center Vertically
                  </label>
                </div>
              </div>
            </div>
          )}

          {/* TAB 4: COLOR */}
          {activeTab === 'color' && (
            <div className="tab-pane">
              <div className="form-group">
                <label>Color Reproduction Mode</label>
                <div className="option-cards-grid">
                  <button
                    type="button"
                    className={`option-card-btn ${colorMode === 'COLOR_RGB' ? 'selected' : ''}`}
                    onClick={() => {
                      setColorMode('COLOR_RGB');
                      setBlackOnly(false);
                    }}
                  >
                    <span className="option-card-title">Full Color (RGB)</span>
                    <span className="option-card-sub">24-bit photo & graphic color</span>
                  </button>
                  <button
                    type="button"
                    className={`option-card-btn ${colorMode === 'GRAYSCALE_8BIT' ? 'selected' : ''}`}
                    onClick={() => setColorMode('GRAYSCALE_8BIT')}
                  >
                    <span className="option-card-title">Grayscale</span>
                    <span className="option-card-sub">8-bit continuous shades</span>
                  </button>
                  <button
                    type="button"
                    className={`option-card-btn ${colorMode === 'MONOCHROME_1BIT' ? 'selected' : ''}`}
                    onClick={() => setColorMode('MONOCHROME_1BIT')}
                  >
                    <span className="option-card-title">Black & White</span>
                    <span className="option-card-sub">1-bit crisp monochrome</span>
                  </button>
                  <button
                    type="button"
                    className={`option-card-btn ${colorMode === 'AUTO' ? 'selected' : ''}`}
                    onClick={() => setColorMode('AUTO')}
                  >
                    <span className="option-card-title">Auto Detect</span>
                    <span className="option-card-sub">Auto color / mono scan</span>
                  </button>
                </div>
              </div>

              <div className="form-group">
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <label>Color Intensity / Saturation</label>
                  <span style={{ fontSize: '11px', fontWeight: 600 }}>{colorIntensity}%</span>
                </div>
                <input
                  type="range"
                  min="50"
                  max="150"
                  value={colorIntensity}
                  onChange={(e) => setColorIntensity(parseInt(e.target.value, 10))}
                  disabled={colorMode === 'MONOCHROME_1BIT' || isUploading}
                />
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: '8px', cursor: 'pointer', margin: 0 }}>
                  <input
                    type="checkbox"
                    checked={imageColorOpt}
                    onChange={(e) => setImageColorOpt(e.target.checked)}
                    disabled={isUploading}
                    style={{ width: '16px', height: '16px', accentColor: 'var(--primary)' }}
                  />
                  <span>Image Color Optimization (Auto contrast and vivid tones)</span>
                </label>
                <label style={{ display: 'flex', alignItems: 'center', gap: '8px', cursor: 'pointer', margin: 0 }}>
                  <input
                    type="checkbox"
                    checked={blackOnly}
                    onChange={(e) => {
                      setBlackOnly(e.target.checked);
                      if (e.target.checked) setColorMode('MONOCHROME_1BIT');
                    }}
                    disabled={isUploading}
                    style={{ width: '16px', height: '16px', accentColor: 'var(--primary)' }}
                  />
                  <span>Black-Only Printing (Uses K-cartridge only to preserve color ink)</span>
                </label>
              </div>
            </div>
          )}

          {/* TAB 5: PAPER */}
          {activeTab === 'paper' && (
            <div className="tab-pane">
              <div className="form-row">
                <div className="form-group">
                  <label>Paper Size</label>
                  <select
                    value={paperSize}
                    onChange={(e) => setPaperSize(e.target.value as PaperSize)}
                    disabled={isUploading}
                  >
                    <option value="A4">A4 (210 × 297 mm / 8.27 × 11.69 in)</option>
                    <option value="LETTER">Letter (215.9 × 279.4 mm / 8.5 × 11 in)</option>
                    <option value="LEGAL">Legal (215.9 × 355.6 mm / 8.5 × 14 in)</option>
                    <option value="A5">A5 (148 × 210 mm / 5.83 × 8.27 in)</option>
                    <option value="A3">A3 (297 × 420 mm / 11.69 × 16.54 in)</option>
                  </select>
                  <span className="field-desc">
                    Physical dimensions: {PAPER_DIMENSIONS[paperSize]?.label || 'Standard Media'}
                  </span>
                </div>

                <div className="form-group">
                  <label>Paper Type / Media</label>
                  <select
                    value={paperType}
                    onChange={(e) => setPaperType(e.target.value as PaperType)}
                    disabled={isUploading}
                  >
                    <option value="plain">Plain Paper (75 - 90 g/m²)</option>
                    <option value="photo">HP Glossy Photo Paper (200+ g/m²)</option>
                    <option value="matte">Matte Brochure / Presentation Paper</option>
                    <option value="glossy">Glossy Brochure Paper</option>
                  </select>
                  <span className="field-desc">Adjusts head travel velocity and ink drying delays.</span>
                </div>
              </div>

              <div className="form-row">
                <div className="form-group">
                  <label>Paper Source / Tray</label>
                  <select
                    value={paperSource}
                    onChange={(e) => setPaperSource(e.target.value)}
                    disabled={isUploading}
                  >
                    <option value="AUTO_TRAY_1">Auto Select / Main Input Tray</option>
                    <option value="MANUAL_FEED">Manual Sheet Feeder (If fitted)</option>
                  </select>
                </div>

                <div className="form-group">
                  <label>Media Weight Profile</label>
                  <input
                    type="text"
                    value="Standard Media (75-105 gsm)"
                    disabled
                    style={{ background: 'var(--bg-surface-alt)', opacity: 0.8 }}
                  />
                </div>
              </div>
            </div>
          )}

          {/* TAB 6: ADVANCED */}
          {activeTab === 'advanced' && (
            <div className="tab-pane">
              <div className="form-group">
                <label>Duplex (Two-Sided Printing)</label>
                <div className="option-cards-grid" style={{ gridTemplateColumns: 'repeat(3, 1fr)' }}>
                  <button
                    type="button"
                    className={`option-card-btn ${duplex === 'NONE' ? 'selected' : ''}`}
                    onClick={() => setDuplex('NONE')}
                  >
                    <span className="option-card-title">Simplex (Off)</span>
                    <span className="option-card-sub">One-sided sheet</span>
                  </button>
                  <button
                    type="button"
                    className={`option-card-btn ${duplex === 'LONG_EDGE' ? 'selected' : ''}`}
                    onClick={() => setDuplex('LONG_EDGE')}
                  >
                    <span className="option-card-title">Booklet</span>
                    <span className="option-card-sub">Flip on long edge</span>
                  </button>
                  <button
                    type="button"
                    className={`option-card-btn ${duplex === 'SHORT_EDGE' ? 'selected' : ''}`}
                    onClick={() => setDuplex('SHORT_EDGE')}
                  >
                    <span className="option-card-title">Tablet</span>
                    <span className="option-card-sub">Flip on short edge</span>
                  </button>
                </div>
                <span className="field-desc">
                  <span className="compat-badge">Hardware Note</span> Most desktop USB inkjets require manual re-feed for duplex.
                </span>
              </div>

              <div className="form-row">
                <div className="form-group">
                  <label>Raster Pipeline Mode</label>
                  <select
                    value={renderingMode}
                    onChange={(e) => setRenderingMode(e.target.value as any)}
                    disabled={isUploading}
                  >
                    <option value="raster_hybrid">Hybrid Vector / Raster Engine</option>
                    <option value="pure_raster">Pure Bitmap Rasterizer</option>
                  </select>
                  <span className="field-desc">PCL3GUI raster transmission protocol pipeline.</span>
                </div>

                <div className="form-group">
                  <label>Image Interpolation</label>
                  <select
                    value={imageInterpolation ? 'bicubic' : 'nearest'}
                    onChange={(e) => setImageInterpolation(e.target.value === 'bicubic')}
                    disabled={isUploading}
                  >
                    <option value="bicubic">Bicubic / High Smoothing</option>
                    <option value="nearest">Nearest Neighbor (Crisp Pixels)</option>
                  </select>
                </div>
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: '8px', cursor: 'pointer', margin: 0 }}>
                  <input
                    type="checkbox"
                    checked={skipBlankPages}
                    onChange={(e) => setSkipBlankPages(e.target.checked)}
                    disabled={isUploading}
                    style={{ width: '16px', height: '16px', accentColor: 'var(--primary)' }}
                  />
                  <span>Skip blank document pages automatically</span>
                </label>
                <label style={{ display: 'flex', alignItems: 'center', gap: '8px', cursor: 'pointer', margin: 0 }}>
                  <input
                    type="checkbox"
                    checked={backgroundGraphics}
                    onChange={(e) => setBackgroundGraphics(e.target.checked)}
                    disabled={isUploading}
                    style={{ width: '16px', height: '16px', accentColor: 'var(--primary)' }}
                  />
                  <span>Render background images & full fills</span>
                </label>
              </div>
            </div>
          )}

          {/* LIVE DOCUMENT PREVIEW PANEL (Sticky on Right) */}
          <div className="preview-card">
            <div className="preview-title-bar">
              <span>Live Layout Preview</span>
              <span style={{ fontSize: '10px', color: 'var(--primary)', fontWeight: 700 }}>
                {pagesPerSheet}-UP • {orientation.slice(0, 4)}
              </span>
            </div>

            <div className="preview-canvas-box">
              <div className="preview-paper" style={previewBoxStyle}>
                {/* Visual Margin Boundaries */}
                <div
                  className="preview-margin-boundary"
                  style={{
                    top: marginPercentage.top,
                    bottom: marginPercentage.bottom,
                    left: marginPercentage.left,
                    right: marginPercentage.right,
                  }}
                >
                  {/* Inner N-up grid */}
                  <div className="preview-inner-content">
                    {pagesPerSheet === 1 ? (
                      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '100%', gap: '4px' }}>
                        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#64748b" strokeWidth="1.5">
                          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                          <polyline points="14 2 14 8 20 8" />
                          <line x1="16" y1="13" x2="8" y2="13" />
                          <line x1="16" y1="17" x2="8" y2="17" />
                          <polyline points="10 9 9 9 8 9" />
                        </svg>
                        <span style={{ fontSize: '8.5px', color: '#64748b', fontWeight: 600 }}>Page 1</span>
                      </div>
                    ) : (
                      <div
                        className="preview-grid-nup"
                        style={{
                          gridTemplateColumns:
                            pagesPerSheet === 2
                              ? '1fr 1fr'
                              : pagesPerSheet === 4
                              ? '1fr 1fr'
                              : pagesPerSheet === 6
                              ? 'repeat(3, 1fr)'
                              : pagesPerSheet === 9
                              ? 'repeat(3, 1fr)'
                              : 'repeat(4, 1fr)',
                        }}
                      >
                        {Array.from({ length: pagesPerSheet }).map((_, idx) => (
                          <div key={idx} className="preview-nup-cell" />
                        ))}
                      </div>
                    )}
                  </div>
                </div>
              </div>
            </div>

            <div className="preview-meta-details">
              <div>
                <strong>{paperSize}</strong> • {orientation}
              </div>
              <div>
                Margins: {margins.top} {margins.unit} ({marginPreset})
              </div>
              <div style={{ color: 'var(--text-dim)', marginTop: '2px' }}>
                {file ? `${file.name.slice(0, 20)}...` : 'Ready for upload'}
              </div>
            </div>
          </div>
        </div>

        {/* Dispatcher Summary Footer Bar */}
        <div className="dispatcher-summary-bar">
          <div className="summary-tags-wrap">
            <span className="summary-chip blue">{paperSize}</span>
            <span className="summary-chip">{orientation}</span>
            <span className="summary-chip green">{pagesPerSheet}-UP</span>
            <span className="summary-chip">{quality} ({dpi} DPI)</span>
            <span className="summary-chip">
              {colorMode === 'COLOR_RGB' ? 'RGB Color' : colorMode === 'MONOCHROME_1BIT' ? 'B&W' : 'Grayscale'}
            </span>
            <span className="summary-chip amber">{copies} {copies === 1 ? 'Copy' : 'Copies'}</span>
            {collate && copies > 1 && <span className="summary-chip">Collated</span>}
          </div>

          <button
            type="submit"
            className="btn btn-primary"
            disabled={!file || isUploading}
            style={{ minWidth: '160px', padding: '10px 20px', fontSize: '13.5px', fontWeight: 700 }}
          >
            {isUploading ? `Queueing (${uploadProgress}%)` : 'Queue Print Job'}
          </button>
        </div>
      </form>
    </div>
  );
};
