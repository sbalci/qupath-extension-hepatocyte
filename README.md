# QuPath Hepatocyte Extension (`qupath-extension-hepatocyte`)

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Build](https://github.com/sbalci/qupath-extension-hepatocyte/actions/workflows/build.yml/badge.svg)](https://github.com/sbalci/qupath-extension-hepatocyte/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/sbalci/qupath-extension-hepatocyte)](https://github.com/sbalci/qupath-extension-hepatocyte/releases/latest)

A **[QuPath 0.6+](https://qupath.github.io/)** extension that brings the **[hepatocyte-app](https://github.com/sbalci/hepatocyte-app)** deep-learning liver H&E cell segmentation workflow into QuPath as a permanent, easy-to-use **`Extensions → Hepatocyte`** menu.

> **⚠️ Research and education use only.** Model outputs are morphological measurements and deep-learning predictions, not clinical grades (NAS / SAF / fibrosis stage) or diagnostic decisions. Always verify predictions visually.

---

## What It Does

Once installed, a permanent **`Extensions → Hepatocyte`** menu appears in QuPath:

```text
Extensions
└── Hepatocyte
    ├── — Hepatocyte Segmentation —
    ├── ─────────────────────────────────────────────────────────
    ├── 1. Import slides from folder…
    ├── 2. Run inference on selected annotations…
    ├── 3. Run inference on whole project (unlabelled / tissue)…
    ├── ─────────────────────────────────────────────────────────
    ├── 4. Auto-detect tissue (current slide)
    ├── 5. Auto-detect tissue (whole project)…
    ├── ─────────────────────────────────────────────────────────
    ├── Settings…
    ├── Environment check…
    ├── ─────────────────────────────────────────────────────────
    └── About / Help…
```

| Menu Item | What It Does |
|---|---|
| **1. Import slides from folder…** | Select a folder; recursively finds all supported whole-slide images (`.svs`, `.tif`, `.tiff`, `.ndpi`, `.mrxs`, `.scn`, `.bif`), adds new slides to the open QuPath project, and sets their image type to **Brightfield (H&E)**. Safe to re-run (skips slides already in the project). |
| **2. Run inference on selected annotations…** | Opens a single-window wizard that segments your drawn/selected region(s) (Rectangle, Polygon, Wand, or Freehand), imports individual cell polygons back into QuPath, writes per-class cell counts to each parent annotation, and saves a multi-sheet Excel report under `<project>/reports/<slide>/`. |
| **3. Run inference on whole project…** | Batch-processes every slide in the open project that has unlabelled or `Tissue` annotations. Skips annotations that were already processed (`Hepatocyte: total` present). |
| **4. Auto-detect tissue (current slide)** | Runs QuPath's built-in tissue detector on the active slide with an MPP-aware minimum area (at least one model patch) while preserving any existing annotations. |
| **5. Auto-detect tissue (whole project)…** | Runs MPP-aware tissue detection across all slides in the open project in one batch. |
| **Settings…** | One-time configuration window with a **"📁 Select hepatocyte-app folder (auto-fill paths)…"** button, live `✅ Found` / `⚠ Missing` indicators, and advanced parameters. |
| **Environment check…** | Verifies at a glance that QuPath, Python, the bridge script, and the model weights are ready. |

### Morphological Classes Produced

Touching cells of the same class are separated into **individual cell polygons** using distance-transform Watershed segmentation:

| Class Name in QuPath | Color | Description |
|---|---|---|
| **Balloon dystrophy** | Red | Ballooning degeneration of hepatocytes |
| **Mesenchymal** | Green | Mesenchymal / stromal / sinusoidal cells |
| **Non-nuclear** | Yellow | Cytoplasmic / non-nuclear hepatocyte profiles |
| **Normal** | Blue | Relatively normal hepatocytes |
| **Steatosis** | Purple | Steatotic (fat-droplet containing) hepatocytes |

After inference finishes:
- Every predicted cell appears inside your annotation as an editable, color-coded QuPath polygon.
- Each parent annotation receives `Hepatocyte: balloon_dystrophy`, `Hepatocyte: mesenchymal_cells`, `Hepatocyte: non_nuclear`, `Hepatocyte: relatively_normal`, `Hepatocyte: steatosis`, and `Hepatocyte: total` count measurements in QuPath's **Annotations / Measurements** panel.
- When a QuPath project is open, an Excel workbook (`Summary`, `Per ROI`, and `Metadata` sheets) is saved automatically under `<project>/reports/<slide_name>/`.

---

## Step-by-Step Guide for End Users (Pathologists)

### Step 1 — Prerequisites (One-Time Setup on Your Computer)

Because the deep-learning model runs in Python outside QuPath, you need three things on your computer:

1. **QuPath 0.6.0 or newer** — download from [qupath.github.io](https://qupath.github.io/).
2. **A local copy of [`hepatocyte-app`](https://github.com/sbalci/hepatocyte-app)** with its Python virtual environment installed:
   ```bash
   git clone https://github.com/sbalci/hepatocyte-app.git
   cd hepatocyte-app
   python -m venv venv
   venv\Scripts\python.exe -m pip install --upgrade pip
   venv\Scripts\python.exe -m pip install -r requirements.txt
   ```
   *(Have an NVIDIA GPU? Install the CUDA PyTorch wheel for ~10–30× faster inference: `venv\Scripts\python.exe -m pip install torch==2.7.0 --index-url https://download.pytorch.org/whl/cu126`)*
3. **The TorchScript model checkpoint (`baseline-1-jit.pth`, ~180 MB)** — 🔒 available on request from the maintainer (`drserdarbalci@gmail.com`). Place it inside `hepatocyte-app/weights/baseline-1-jit.pth`.

---

### Step 2 — Install the Extension in QuPath

1. Download **`qupath-extension-hepatocyte-0.1.0.jar`** from [GitHub Releases](https://github.com/sbalci/qupath-extension-hepatocyte/releases/latest) (or build it from source).
2. **Drag and drop** the `.jar` file directly onto the open QuPath main window.
3. **Restart QuPath.** You will now see **`Extensions → Hepatocyte`** in the top menu bar.

*(Alternative: in QuPath, open `Extensions → Manage extensions → Manage extension catalogs`, add `https://raw.githubusercontent.com/sbalci/qupath-extension-hepatocyte/main/catalog.json`, and click Install.)*

---

### Step 3 — Point the Extension at Your `hepatocyte-app` Folder (One Click)

1. In QuPath, click **`Extensions → Hepatocyte → Settings…`**.
2. Click **`📁 Select hepatocyte-app folder (auto-fill paths)…`** and choose your `hepatocyte-app` folder.
3. Verify that all three rows (`Python`, `Bridge script`, and `Model weights`) show **`✅ Found`** and the green banner says **`✅ Ready`**.
4. Click **Close**. Your settings are saved permanently across QuPath restarts.

*(Don't have `git` installed? Open `Extensions → Hepatocyte → 2. Run inference on selected annotations…` and click **`⬇ Download bridge from GitHub`** to download the Python bridge script automatically.)*

---

### Step 4 — Daily Workflow in QuPath

#### Option A: Analyze Specific Regions on a Slide (Interactive)
1. Open a QuPath project (`File → Project → Create project…` or `Open project…`) and import slides via **`Extensions → Hepatocyte → 1. Import slides from folder…`** (or open any local `.svs` / `.tiff` slide directly via `File → Open…`).
2. Draw one or more regions of interest using QuPath's **Rectangle**, **Polygon**, **Brush**, or **Wand** tool (at least `512 × 512` pixels).
   - *Note:* Non-rectangular shapes (Wand / Polygon) are clipped accurately to the drawn boundary — predictions only appear inside your shape.
   - *Note:* Both **40×** (`~0.25 µm/px`) and **20×** (`~0.50 µm/px`) scans are supported automatically via MPP-aware patch scaling.
3. Click **`Extensions → Hepatocyte → 2. Run inference on selected annotations…`**.
4. Click **`Segment ▶`** in the wizard. Within a few seconds (on GPU), classified cell polygons appear inside your annotation and counts are added to the **Annotations** table.

#### Option B: Unattended Whole-Cohort Batch Run
1. **`Extensions → Hepatocyte → 1. Import slides from folder…`** — select your cohort's slide folder.
2. **`Extensions → Hepatocyte → 5. Auto-detect tissue (whole project)…`** — detects tissue regions (≥ 1 model patch) on every slide and labels them `Tissue`.
3. **`Extensions → Hepatocyte → 3. Run inference on whole project (unlabelled / tissue)…`** — runs the segmentation model on every slide's tissue regions, saves polygons + measurements into each project entry, and writes per-slide Excel reports under `<project>/reports/<slide>/`.

---

## Troubleshooting

| Symptom | Cause & Fix |
|---|---|
| **Menu items 2 or 4 are greyed out** | No slide is currently open in the viewer. Double-click a slide in the **Project** panel on the left (or use `File → Open…`). |
| **"The slide is not a local file"** | The Python bridge opens the slide directly from disk using OpenSlide. Remote slides (OMERO / HTTP) cannot be read by local Python — open the slide from a local drive. |
| **"No candidate area annotation to segment, or all are too small"** | Your annotation bounding box is smaller than `512 × 512` pixels (one model patch). Draw a larger region or run **4. Auto-detect tissue (current slide)**. |
| **Inference is very slow (minutes per region)** | Python is running on CPU instead of GPU. Open `2. Run inference… → Configure… → ✓ Check Environment` and check if `CUDA: True` is printed. If `CUDA: False` on an NVIDIA machine, install the CUDA PyTorch wheel in your `venv`. |
| **Settings paths show `⚠ Missing`** | Open `Extensions → Hepatocyte → Settings…` and click **`📁 Select hepatocyte-app folder (auto-fill paths)…`** to select your `hepatocyte-app` directory. |

---

## Building from Source (For Developers)

Requires **JDK 21**. QuPath 0.6.0 APIs are resolved from the SciJava Maven repository at build time.

```bash
./gradlew clean build
# Output JAR: build/libs/qupath-extension-hepatocyte-0.1.0.jar
```

On Windows PowerShell:
```powershell
.\gradlew.bat clean build
```

### Releasing a New Version

Keep version strings synchronized across:
- `build.gradle.kts` (`version`)
- `src/main/java/io/github/sbalci/qupath/hepatocyte/HepatocyteExtension.java` (`EXTENSION_VERSION`)
- `catalog.json` (`releases[0].name` and `main_url` JAR filename)
- `CITATION.cff` (`version`)

Pushing a `v*` tag triggers `.github/workflows/release.yml` to build the JAR and attach it to a GitHub Release.

---

## Citation & License

Licensed under [Apache-2.0](LICENSE). If you use this extension in academic work, please cite [`CITATION.cff`](CITATION.cff) along with the underlying [hepatocyte-app](https://github.com/sbalci/hepatocyte-app) repository and [QuPath](https://doi.org/10.1038/s41598-017-17204-5).
