---
name: PocketPrinter Precision Console
colors:
  surface: '#0f131c'
  surface-dim: '#0f131c'
  surface-bright: '#353942'
  surface-container-lowest: '#0a0e16'
  surface-container-low: '#181c24'
  surface-container: '#1c2028'
  surface-container-high: '#262a33'
  surface-container-highest: '#31353e'
  on-surface: '#dfe2ee'
  on-surface-variant: '#c2c6d6'
  inverse-surface: '#dfe2ee'
  inverse-on-surface: '#2c3039'
  outline: '#8c909f'
  outline-variant: '#424754'
  surface-tint: '#adc6ff'
  primary: '#adc6ff'
  on-primary: '#002e6a'
  primary-container: '#4d8eff'
  on-primary-container: '#00285d'
  inverse-primary: '#005ac2'
  secondary: '#a4c9ff'
  on-secondary: '#00315d'
  secondary-container: '#0267b8'
  on-secondary-container: '#d6e5ff'
  tertiary: '#4edea3'
  on-tertiary: '#003824'
  tertiary-container: '#00a572'
  on-tertiary-container: '#00311f'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#d8e2ff'
  primary-fixed-dim: '#adc6ff'
  on-primary-fixed: '#001a42'
  on-primary-fixed-variant: '#004395'
  secondary-fixed: '#d4e3ff'
  secondary-fixed-dim: '#a4c9ff'
  on-secondary-fixed: '#001c39'
  on-secondary-fixed-variant: '#004883'
  tertiary-fixed: '#6ffbbe'
  tertiary-fixed-dim: '#4edea3'
  on-tertiary-fixed: '#002113'
  on-tertiary-fixed-variant: '#005236'
  background: '#0f131c'
  on-background: '#dfe2ee'
  surface-variant: '#31353e'
typography:
  headline-xl:
    fontFamily: Inter
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 38px
    letterSpacing: -0.025em
  headline-xl-mobile:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 30px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Inter
    fontSize: 22px
    fontWeight: '600'
    lineHeight: 28px
    letterSpacing: -0.02em
  headline-md:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
    letterSpacing: -0.015em
  body-lg:
    fontFamily: Inter
    fontSize: 15px
    fontWeight: '400'
    lineHeight: 22px
    letterSpacing: -0.01em
  body-md:
    fontFamily: Inter
    fontSize: 13px
    fontWeight: '400'
    lineHeight: 18px
    letterSpacing: -0.005em
  body-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
    letterSpacing: 0em
  mono-code:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
    letterSpacing: -0.01em
  label-md:
    fontFamily: Inter
    fontSize: 11px
    fontWeight: '500'
    lineHeight: 14px
    letterSpacing: 0.04em
  label-xs:
    fontFamily: Inter
    fontSize: 10px
    fontWeight: '600'
    lineHeight: 12px
    letterSpacing: 0.06em
rounded:
  sm: 0.125rem
  DEFAULT: 0.25rem
  md: 0.375rem
  lg: 0.5rem
  xl: 0.75rem
  full: 9999px
spacing:
  gutter: 1rem
  gutter-mobile: 0.75rem
  margin: 1.5rem
  margin-mobile: 1rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 0.75rem
  space-lg: 1rem
  space-xl: 1.5rem
---

## Brand & Style

This design system delivers an industrial, high-precision dark utility interface built for low-latency hardware orchestration, direct USB print diagnostics, and stream monitoring. The aesthetic converges technical rigor with understated luxury: utilitarian, confident, and strictly functional. It rejects decorative trends like glossy neomorphism, ambient colored glows, and blurred glass panels in favor of structural clarity, high information density, and physical machine feedback.

### Emotional Signature
- **Mechanical Trust:** Feels as responsive and deterministic as a direct hardware bus connection.
- **Instrument Precision:** Evokes calibrated aerospace avionics and high-end rackmount server consoles.
- **Silent Competence:** Dark-adapted, calm visual footprint designed for operational environments, point-of-sale setups, and field debugging without visual fatigue.

### Core Philosophy
- **Physical Integrity:** Surfaces do not float ambiguously; they snap into precise 1px micro-bordered planes.
- **Information over Ornamentation:** Visual real estate is dedicated to status telemetry, queue metrics, and packet flow rather than empty space or decorative graphics.
- **Tactile Economy:** Every state transition corresponds to mechanical action—latches, toggles, relays, and hardware interrupts.

## Colors

The color palette is engineered for high-contrast legible data display under variable lighting, emphasizing dark-adapted usability and strict status communication.

### Surface and Structure Architecture
- **Base Canvas (`#0B0F17`):** The primary root layer, an ultra-deep charcoal/navy void that provides maximum contrast.
- **Surface Elevation 1 (`#111827`):** Foundational container surface for consoles, sidebars, and grid panels.
- **Surface Elevation 2 (`#1E293B`):** Interactive card layers, table rows, and nested instrument housings.
- **Surface Elevation 3 (`#334155`):** Hover states, active pressed states, and segmented control wells.
- **Structural Micro-Borders (`rgba(51, 65, 85, 0.8)` / `border-slate-700/80`):** Crisp 1px geometric framing that defines bounds without ambient diffuse shadows.

### Accent and Action
- **Primary Electric Blue (`#3B82F6`):** Reserved for direct active actions, selected telemetry nodes, and system focus indices.
- **Secondary Ice Blue (`#60A5FA`):** Used for micro-interactive highlights, active state glyphs, and high-priority secondary readouts.

### Restrained Semantic Status
Status indicators mimic physical calibrated LEDs. They are never rendered as saturated washes or gradient fills; they exist strictly as crisp indicator dots, border accents, and high-legibility text badges:
- **Operational / Ready (`#34D399` / emerald-400):** Continuous USB connection established, buffer ready, agent heartbeat intact.
- **Active / Processing (`#FBBF24` / amber-400):** Packet transmission in flight, thermal head heating, spooling buffer actively emptying.
- **Error / Fault (`#F87171` / rose-400):** Out of paper, head overheated, USB disconnect, protocol CRC failure.
- **Offline / Stale (`#64748B` / slate-500):** Agent detached, hardware bus powered down.

### Typography & Content Shades
- **Crisp Primary (`#F8FAFC`):** Primary data readouts, active status headers, critical metrics.
- **Muted Subtext (`#94A3B8`):** Field labels, serial numbers, terminal logs, secondary units.
- **Deep De-emphasized (`#475569`):** Inactive parameters, timestamp dividers, structural brackets.

## Typography

Typography relies on a dual-engine architecture: **Inter** for all interface structures, headlines, and data grids; **JetBrains Mono** strictly for technical readouts, device descriptors (VID/PID), hexadecimal packet hex dumps, and baud rate counters.

### Typesetting Rules
- **Tracking & Proportion:** Inter uses negative letter-spacing (`-0.01em` to `-0.025em`) across body and display weights to ensure compact density and horizontal stability. Technical labels utilize positive tracking (`0.04em` to `0.06em`) with full uppercase formatting for rapid visual classification.
- **Tabular Numerics:** All dynamic counters, queue times, and byte tallies must enforce `font-feature-settings: "tnum" 1` to prevent jitter during streaming telemetry.
- **Hierarchy Enforcers:** Never use font size alone to convey hierarchy. Pair subtle size steps with distinct weight variations (`600` vs `400`) and precise luminance dropping (`#F8FAFC` to `#94A3B8`).

## Layout & Spacing

The layout is built upon a rigid, non-negotiable **4px/8px incremental base rhythm**. High information density is achieved not through crowding, but through systematic alignment, structured data striping, and zero arbitrary padding.

### Grid & Canvas Framework
- **Desktop (1024px+):** A 12-column fixed or fluid console layout. Structural panels (e.g., Device Selector, Terminal Buffer, Spool Inspector) are docked with 1px hairline divider borders instead of expansive empty space.
- **Tablet / Split View (768px - 1023px):** Two-pane layout with collapsible drawer topology. The main status panel and queue list share equal vertical viewport space.
- **Mobile (Handheld Android Agent, < 768px):** Single-column stacked telemetry deck with fixed top connection breadcrumbs and sticky physical action triggers at the thumb reach zone.

### Spacing Application
- `space-xs (4px)`: Tight alignment for status dots paired with text, micro-badge internal padding, and nested parameter labels.
- `space-sm (8px)`: Default gap between form elements, inline button groups, and data key-value pairs.
- `space-md (12px)`: Internal component padding for compact list items, telemetry cells, and toolbar clusters.
- `space-lg (16px)`: Standard card inner padding and section separators.
- `space-xl (24px)`: Primary macro container gutters and boundary margins.

## Elevation & Depth

Visual hierarchy is constructed entirely through **tonal surface stacking** and **precise 1px micro-borders**, bypassing diffuse drop shadows or blurred translucency.

### Depth Ladder
- **Level 0 (Canvas Base):** `#0B0F17`. The root viewport on which all instrumentation mounts.
- **Level 1 (Structural Shells):** `#111827`, bounded by `1px solid rgba(51, 65, 85, 0.6)`. Houses device lists, side navigation, and continuous terminal logs.
- **Level 2 (Panels & Groupings):** `#1E293B`, framed with `1px solid rgba(71, 85, 105, 0.8)`. Contains interactive job cards, hardware metric indicators, and configurable parameter sets.
- **Level 3 (Modal Dialogs & Command Overlays):** `#1E293B` raised above a pure black backdrop (`rgba(0, 0, 0, 0.75)`). Bounded by a high-contrast top hairline border: `1px solid #3B82F6` or `1px solid rgba(148, 163, 184, 0.3)`.

### Border Discipline
Shadows are strictly functional: a subtle `0 1px 2px rgba(0, 0, 0, 0.5)` is permitted only on elevated floating dropdown menus to separate overlapping text layers. All other separation is produced by high-definition 1px lines using the `slate-700/80` or `slate-800` register.

## Shapes

The design system maintains an intentional, engineered silhouette using a **Soft / Industrial corner language (Level 1)**. Rounded corners are strictly restrained to prevent a consumer-toy appearance, reflecting the milled chassis of industrial hardware.

### Corner Radius Standards
- **Micro-Controls (Chips, Tags, Checkboxes, Toggle Knobs):** `2px` to `4px`. Crisp, blocky, and mechanically definite.
- **Standard Controls (Buttons, Text Inputs, Terminal Cells):** `4px` (`rounded-sm`).
- **Cards & Enclosures:** `6px` to `8px` (`rounded-md`).
- **Pill Shapes:** Strictly forbidden, except for circular LED hardware status blips (`w-2 h-2 rounded-full`).

## Components

### Buttons & Hardware Triggers
- **Primary Execute ("Print Test Page", "Flush Queue"):** Background `#3B82F6`, hover `#2563EB`, text `#FFFFFF`, radius `4px`, padding `6px 12px`. Tight tracking, font weight `500`. Micro-interaction: `transform: scale(0.99)` and immediate border snap on mousedown.
- **Secondary / Peripheral Action:** Background `#1E293B`, border `1px solid rgba(71, 85, 105, 0.8)`, text `#F8FAFC`, hover `#334155`.
- **Destructive ("Abort Job", "Reset USB Bus"):** Background `transparent`, border `1px solid rgba(248, 113, 113, 0.4)`, text `#F87171`, hover background `rgba(248, 113, 113, 0.1)`.

### Status Indicators & Badges
- **Hardware Telemetry Dot:** `8px × 8px` dimension with an inner circular glow strictly constrained to a `0 0 4px` radius matching the semantic color.
- **Status Chip:** Solid background with low alpha (`rgba(52, 211, 153, 0.1)`), paired with a 1px border (`rgba(52, 211, 153, 0.3)`) and uppercase monospace label (`text-emerald-400 font-mono text-[10px] tracking-wider`).

### Data Lists & Stream Logs
- **Terminal & Spool Logs:** Rendered on `#0B0F17` backing. Monospace font (`JetBrains Mono`), 12px size, alternating line contrast or 1px hairline horizontal rules. Timestamps in `#64748B`, payload lengths in `#38BDF8`, errors inline highlighted in `#F87171`.
- **Key-Value Telemetry Rows:** Divided by hairline `border-b border-slate-800/80`. Keys left-aligned in `#94A3B8`, values right-aligned in `#F8FAFC` using tabular numbers.

### Inputs & Selectors
- **Input Fields (Baud Rate, Endpoint Address):** Height `32px` or `36px`, background `#0F172A`, border `1px solid #334155`, text `#F8FAFC`, placeholder `#475569`. Focus state switches border to `#3B82F6` with zero fuzzy focus rings—just a crisp 1px electric frame.
- **Toggle Switches (Mechanical Style):** Bounded rectangle track (`#1E293B` off, `#3B82F6` on) with a sharp rectangular sliding thumb (`#F8FAFC`, radius `2px`).

### Cards & Container Panels
- **Printer Enclosure Card:** Structural header with USB vendor and product ID (`04B8:0E15`), an inline status indicator at top right, and an active queue count footer. Framed in `1px solid rgba(51, 65, 85, 0.8)` over `#111827`. Hover state initiates an instantaneous border tint shift to `rgba(96, 165, 250, 0.5)` with no layout shift or lifting shadow.