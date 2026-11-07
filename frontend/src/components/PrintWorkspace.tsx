import React, { useState, useEffect } from 'react';
import { api } from '../api';
import { Device, HealthResponse, Job, UploadOptions } from '../types';

interface PrintWorkspaceProps {
  device: Device | null;
  onBack: () => void;
}

export const PrintWorkspace: React.FC<PrintWorkspaceProps> = ({
  device,
  onBack,
}) => {
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [printOptions, setPrintOptions] = useState<UploadOptions>({
    colorMode: 'MONOCHROME_1BIT',
    paperSize: 'A4',
    orientation: 'PORTRAIT',
    copies: 1,
    scaling: 'FIT_TO_PAGE',
    quality: 'NORMAL',
    margins: { top: 4, bottom: 4, left: 4, right: 4, unit: 'mm' },
    pagesPerSheet: 1,
    dpi: undefined,
    quality: 'NORMAL' as 'DRAFT' | 'NORMAL' | 'HIGH' | 'BEST',
  });
  const [showPreview, setShowPreview] = useState(false);
  const [previewImage, setPreviewImage] = useState<string | null>(null);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setSelectedFile(file);
    // For demo purposes, create a placeholder preview
    setPreviewImage(`/placeholder-preview-${file.name}`);
    setShowPreview(true);
  };

  const handlePrint = async () => {
    if (!selectedFile) return;
    setUploadProgress(0);
    try {
      const result = await api.createPrintJob(
        selectedFile,
        printOptions,
        (percent) => setUploadProgress(percent)
      );
      setUploadProgress(100);
      setSelectedFile(null);
      setShowPreview(false);
      // In a real app, we'd navigate to job details or show a success toast
      // addToast(`Document queued: ${result.jobId}`, 'success');
    } catch (err: any) {
      console.error('Print job failed:', err);
      // addToast(err.message || 'Print job failed', 'error');
    }
  };

  return (
    <div className="min-h-screen bg-surface px-4 py-6">
      <div className="max-w-2xl mx-auto">
        {/* Back button */}
        <div className="mb-4 flex items-center justify-between">
          <button
            onClick={onBack}
            className="bg-surface-container-high hover:bg-surface-bright text-on-surface px-3 py-1 rounded text-sm transition-colors"
          >
            ← Back to Queue
          </button>
          <h1 className="font-headline-md text-headline-md text-on-surface">
            Print Workspace
          </h1>
        </div>

        {/* File Selection */}
        <div className="mb-4 p-4 bg-surface-container-low rounded-lg">
          <label className="font-label-xs text-label-xs text-on-surface-variant uppercase tracking-wider">
            Document File
          </label>
          <div className="flex items-center gap-2 mt-1">
            {selectedFile ? (
              <>
                <span className="font-mono-code text-mono-code text-on-surface">
                  {selectedFile.name}
                </span>
                <span className="font-label-xs text-label-xs text-on-surface-variant">
                  {selectedFile.size > 1024
                    ? `${(selectedFile.size / 1024).toFixed(1)} KB`
                    : `${selectedFile.size} bytes`}
                </span>
              </>
            ) : (
              <div>
                <input
                  type="file"
                  accept=".pdf,.png,.jpg,.jpeg,.txt,.escpos"
                  className="bg-surface-container-low border border-surface-border rounded-lg p-2 cursor-pointer w-full"
                  onChange={handleFileChange}
                />
                <span className="font-label-xs text-label-xs text-secondary cursor-pointer">
                  Browse PDF, images, text, or ESC/POS data
                </span>
              </>
            )}
          </div>
        </div>

        {/* Print Configuration */}
        <div className="bg-surface-container-low rounded-lg p-4 mb-4">
          <h2 className="font-label-xs text-label-xs text-on-surface-variant uppercase tracking-wider mb-2">
            Print Configuration
          </h2>
          <div className="grid grid-cols-2 gap-3">
            {/* Paper Size */}
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">Paper Size</span>
              <div className="grid grid-cols-3 gap-1">
                <button
                  className="flex-1 bg-surface-container-high text-primary font-medium text-label-sm py-1.5 rounded">
                  58mm Roll
                </button>
                <button className="text-on-surface-variant hover:text-on-surface font-normal py-1.5 rounded">
                  80mm Roll
                </button>
                <button className="text-on-surface-variant hover:text-on-surface font-normal py-1.5 rounded">
                  Custom (48mm)
                </button>
              </div>
            </div>

            {/* Orientation */}
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">Orientation</span>
              <div className="flex bg-surface-container-lowest rounded-lg py-1">
                <button class="flex-1 text-on-surface font-medium rounded">Portrait</button>
                <button className="text-on-surface-variant hover:text-on-surface font-normal rounded">Landscape</button>
                <button className="text-on-surface-variant hover:text-on-surface font-normal rounded">Auto</button>
              </div>
            </div>

            {/* Copies */}
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">Copies</span>
              <div className="flex items-center bg-surface-container-lowest rounded-lg p-1">
                <button
                  className="w-7 h-7 rounded bg-surface-container-high hover:bg-surface-bright flex items-center justify-center text-on-surface font-bold transition-colors"
                  id="copyDec"
                >
                  -
                </button>
                <span className="font-mono-code text-mono-code text-on-surface font-medium" id="copyVal">1</span>
                <button
                  className="w-7 h-7 rounded bg-surface-container-high hover:bg-surface-bright flex items-center justify-center text-on-surface font-bold transition-colors"
                  id="copyInc"
                >
                  +
                </button>
              </div>
            </div>

            {/* Quality */}
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">Quality</span>
              <div className="grid grid-cols-3 gap-1">
                <button
                  className="bg-surface-container p-space-xs rounded text-left flex flex-col">
                  <span class="font-label-xs text-label-xs text-on-surface-variant">Draft</span>
                  <span className="font-mono-code text-mono-code text-on-surface text-[10px]">180 DPI</span>
                </button>
                <button
                  className="bg-secondary-container/40 p-space-xs rounded text-left flex flex-col">
                  <span class="font-label-xs text-label-xs text-secondary font-medium">Native</span>
                  <span className="font-mono-code text-mono-code text-primary text-[10px]">203 DPI</span>
                </button>
                <button
                  className="bg-surface-container p-space-xs rounded text-left flex flex-col">
                  <span class="font-label-xs text-label-xs text-on-surface-variant">Floyd-Stein</span>
                  <span className="font-mono-code text-mono-code text-on-surface text-[10px]">High Contrast</span>
                </button>
              </div>
            </div>

            {/* Margins */}
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">
                Hardware Margins (mm)
              </span>
              <div className="grid grid-cols-4 gap-1">
                <div className="bg-surface-container-lowest rounded p-1">
                  <span className="font-label-xs text-label-xs text-on-surface-variant">Top</span>
                  <input
                    className="bg-transparent font-mono-code text-mono-code text-on-surface outline-none w-full"
                    type="text"
                    value="0.0"
                  />
                </div>
                <div className="bg-surface-container-lowest rounded p-1">
                  <span className="font-label-xs text-label-xs text-on-surface-variant">Bottom</span>
                  <input
                    className="bg-transparent font-mono-code text-mono-code text-on-surface outline-none w-full"
                    type="text"
                    value="2.0"
                  />
                </div>
                <div className="bg-surface-container-lowest rounded p-1">
                  <span className="font-label-xs text-label-xs text-on-surface-variant">Left</span>
                  <input
                    className="bg-transparent font-mono-code text-mono-code text-on-surface outline-none w-full"
                    type="text"
                    value="0.0"
                  />
                </div>
                <div className="bg-surface-container-lowest rounded p-1">
                  <span className="font-label-xs text-label-xs text-on-surface-variant">Right</span>
                  <input
                    className="bg-transparent font-mono-code text-mono-code text-on-surface outline-none w-full"
                    type="text"
                    value="0.0"
                  />
                </div>
              </div>
            </div>

            {/* Quality/Dithering */}
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">
                Dithering & Raster Quality
              </span>
              <div className="flex flex-col gap-2 bg-surface-container-lowest rounded-lg p-2">
                <div class="flex flex-col gap-1">
                  <div class="flex items-center justify-between">
                    <span class="font-label-xs text-label-xs text-on-surface-variant">Draft</span>
                    <span className="font-mono-code text-mono-code text-on-surface text-[10px]">180 DPI</span>
                  </div>
                  <div class="bg-surface-container p-space-xs rounded text-left flex flex-col">
                    <span class="font-label-xs text-label-xs text-secondary">Native</span>
                    <span className="font-mono-code text-mono-code text-primary text-[10px]">203 DPI</span>
                  </div>
                  <div class="bg-surface-container p-space-xs rounded text-left flex flex-col">
                    <span class="font-label-xs text-label-xs text-on-surface-variant">Floyd-Stein</span>
                    <span className="font-mono-code text-mono-code text-on-surface text-[10px]">High Contrast</span>
                  </div>
                </div>
                <div className="pt-1 flex items-center justify-between">
                  <span class="font-label-xs text-label-xs text-on-surface-variant">Black/White Threshold</span>
                  <span className="font-mono-code text-mono-code text-tertiary" id="thresholdDisplay">128 / 255</span>
                </div>
                <input
                  className="w-full h-1 bg-surface-container-highest rounded appearance-none cursor-pointer accent-primary"
                  type="range"
                  min="0"
                  max="255"
                  value="128"
                  id="threshSlider"
                />
              </div>
            </div>

            {/* Margins */}
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">
                Hardware Margins (mm)
              </span>
              <div className="grid grid-cols-4 gap-1">
                <div className="bg-surface-container-lowest rounded p-1">
                  <span className="font-label-xs text-label-xs text-on-surface-variant">Top</span>
                  <input
                    className="bg-transparent font-mono-code text-mono-code text-on-surface outline-none w-full"
                    type="text"
                    value="0.0"
                  />
                </div>
                <div className="bg-surface-container-lowest rounded p-1">
                  <span className="font-label-xs text-label-xs text-on-surface-variant">Bottom</span>
                  <input
                    className="bg-transparent font-mono-code text-mono-code text-on-surface outline-none w-full"
                    type="text"
                    value="2.0"
                  />
                </div>
                <div className="bg-surface-container-lowest rounded p-1">
                  <span className="font-label-xs text-label-xs text-on-surface-variant">Left</span>
                  <input
                    className="bg-transparent font-mono-code text-mono-code text-on-surface outline-none w-full"
                    type="text"
                    value="0.0"
                  />
                </div>
                <div className="bg-surface-container-lowest rounded p-1">
                  <span className="font-label-xs text-label-xs text-on-surface-variant">Right</span>
                  <input
                    className="bg-transparent font-mono-code text-mono-code text-on-surface outline-none w-full"
                    type="text"
                    value="0.0"
                  />
                </div>
              </div>
            </div>

            {/* Quality */}
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">
                Dithering & Raster Quality
              </span>
              <div className="flex flex-col gap-2 bg-surface-container-lowest p-space-sm rounded-lg">
                <div class="flex flex-col gap-1">
                  <div class="flex items-center justify-between">
                    <span class="font-label-xs text-label-xs text-on-surface-variant">Draft</span>
                    <span className="font-mono-code text-mono-code text-on-surface text-[10px]">180 DPI</span>
                  </div>
                  <div class="bg-surface-container p-space-xs rounded text-left flex flex-col">
                    <span class="font-label-xs text-label-xs text-secondary">Native</span>
                    <span className="font-mono-code text-mono-code text-primary text-[10px]">203 DPI</span>
                  </div>
                  <div class="bg-surface-container p-space-xs rounded text-left flex flex-col">
                    <span class="font-label-xs text-label-xs text-on-surface-variant">Floyd-Stein</span>
                    <span className="font-mono-code text-mono-code text-on-surface text-[10px]">High Contrast</span>
                  </div>
                </div>
                <div className="pt-1 flex items-center justify-between">
                  <span class="font-label-xs text-label-xs text-on-surface-variant">Black/White Threshold</span>
                  <span className="font-mono-code text-mono-code text-tertiary" id="thresholdDisplay">128 / 255</span>
                </div>
                <input
                  className="w-full h-1 bg-surface-container-highest rounded appearance-none cursor-pointer accent-primary"
                  type="range"
                  min="0"
                  max="255"
                  value="128"
                  id="threshSlider"
                />
              </div>
            </div>

            {/* Margins */}
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">
                Hardware Margins (mm)
              </span>
              <div className="grid grid-cols-4 gap-1">
                <div className="bg-surface-container-lowest rounded p-1">
                  <span className="font-label-xs text-label-xs text-on-surface-variant">Top</span>
                  <input
                    className="bg-transparent font-mono-code text-mono-code text-on-surface outline-none w-full"
                    type="text"
                    value="0.0"
                  />
                </div>
                <div className="bg-surface-container-lowest rounded p-1">
                  <span className="font-label-xs text-label-xs text-on-surface-variant">Bottom</span>
                  <input
                    className="bg-transparent font-mono-code text-mono-code text-on-surface outline-none w-full"
                    type="text"
                    value="2.0"
                  />
                </div>
                <div className="bg-surface-container-lowest rounded p-1">
                  <span className="font-label-xs text-label-xs text-on-surface-variant">Left</span>
                  <input
                    className="bg-transparent font-mono-code text-mono-code text-on-surface outline-none w-full"
                    type="text"
                    value="0.0"
                  />
                </div>
                <div className="bg-surface-container-lowest rounded p-1">
                  <span className="font-label-xs text-label-xs text-on-surface-variant">Right</span>
                  <input
                    className="bg-transparent font-mono-code text-mono-code text-on-surface outline-none w-full"
                    type="text"
                    value="0.0"
                  />
                </div>
              </div>
            </div>

            {/* Post-Job Sequences */}
            <div>
              <span className="font-label-xs text-label-xs text-on-surface-variant uppercase block">
                Post-Job Sequences
              </div>
              <div className="flex flex-col gap-2 bg-surface-container-lowest p-space-sm rounded-lg">
                <label className="flex items-center gap-space-sm cursor-pointer select-none">
                  <input
                    checked=""
                    className="w-3.5 h-3.5 rounded bg-surface-container text-primary accent-primary"
                    type="checkbox"
                  />
                  <span className="font-body-sm text-body-sm text-on-surface">
                    Feed 3 blank lines before cut (ESC d 3)
                  </span>
                </label>
                <label className="flex items-center gap-space-sm cursor-pointer select-none">
                  <input
                    checked=""
                    className="w-3.5 h-3.5 rounded bg-surface-container text-primary accent-primary"
                    type="checkbox"
                  />
                  <span className="font-body-sm text-body-sm text-on-surface">
                    Execute Full Cut at end of spool (GS V 0)
                  </span>
                </label>
                <label className="flex items-center gap-space-sm cursor-pointer select-none">
                  <input
                    className="w-3.5 h-3.5 rounded bg-surface-container text-primary accent-primary"
                    type="checkbox"
                  />
                  <span className="font-body-sm text-body-sm text-on-surface">
                    Invert colors (Thermal Reverse Print mode)
                  </span>
                </label>
              </div>
            </div>
          </div>
        </div>

        {/* Sticky Action Bar */}
        <div className="sticky bottom-0 bg-surface-container-high/95 backdrop-blur-md p-4 rounded-xl shadow-md flex flex-col sm:flex-row justify-between items-center gap-2">
          <div className="flex items-center gap-2">
            <span className="font-mono-code text-mono-code text-on-surface">
              {selectedFile?.name ? selectedFile.name.substring(0, 20) + '...' : 'No file selected'}
            </span>
          </div>
          <button
            className="flex-1 bg-primary text-on-primary font-body-md text-body-md font-semibold py-2.5 px-space-md rounded-lg flex items-center justify-center gap-space-sm hover:brightness-110 active:scale-[0.99] transition-all shadow-md"
            onClick={handlePrint}
            disabled={!selectedFile}
          >
            <span className="material-symbols-outlined text-[20px]">
              print
            </span>
            <span>Send to PocketPrinter</span>
          </button>
        </div>
      </div>
    </div>
  );
}